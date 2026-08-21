package com.interview.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.knowledge.KnowledgeBase;
import com.interview.agent.knowledge.KnowledgeBaseMapper;
import com.interview.agent.knowledge.KnowledgeRetriever;
import com.interview.agent.knowledge.KnowledgeRetriever.RetrievedChunk;
import com.interview.agent.observability.LlmTrace;
import com.interview.agent.observability.LlmTraceObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

/**
 * 知识笔记 AI 问答服务：基于当前用户全部知识库文档的 RAG 多轮对话。
 *
 * <p>安全边界（用户隔离）：
 * <ul>
 *   <li>userId 一律取自登录态（BaseContext），前端传入的 sessionId 仅作查找键，必须校验归属；</li>
 *   <li>检索的 kbId 集合由服务端按 userId 现查 DB，不信任任何前端传入的库 ID；</li>
 *   <li>多轮历史只从 DB 读取（belonging 已校验），绝不接收前端传入的对话上下文。</li>
 * </ul>
 *
 * <p>严格拒答（Grounded）：检索无命中时返回固定拒答文案，不调用 LLM，
 * 防止模型用自有知识编造答案。
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String REFUSAL_MESSAGE = "该问题超出了你当前知识库范围，我无法回答。可以在知识笔记中补充相关文档后再试。";
    private static final int TOP_K = 5;
    /** 多轮上下文注入的最大历史消息条数（近尾截取） */
    private static final int MAX_HISTORY = 10;
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeRetriever retriever;
    private final ChatClient chatClient;
    private final LlmTraceObservationHandler traceHandler;
    private final ObjectMapper objectMapper;

    /** chat 专用线程池：SSE 流式生成与落库均在此执行，Controller 立即返回 emitter */
    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "rag-chat");
        t.setDaemon(true);
        return t;
    });

    public ChatService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                       KnowledgeBaseMapper kbMapper, KnowledgeRetriever retriever,
                       ChatClient.Builder chatClientBuilder, LlmTraceObservationHandler traceHandler,
                       ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.kbMapper = kbMapper;
        this.retriever = retriever;
        this.chatClient = chatClientBuilder.build();
        this.traceHandler = traceHandler;
        this.objectMapper = objectMapper;
    }

    // ---------- 会话管理 ----------

    public ChatSession createSession(Long userId) {
        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setTitle("新对话");
        sessionMapper.insert(s);
        return s;
    }

    public List<ChatSession> listSessions(Long userId) {
        return sessionMapper.findByUserId(userId);
    }

    public List<ChatMessage> getMessages(Long sessionId, Long userId) {
        getOwnedSession(sessionId, userId);
        return messageMapper.findBySessionId(sessionId);
    }

    public void deleteSession(Long sessionId, Long userId) {
        getOwnedSession(sessionId, userId);
        messageMapper.deleteBySessionId(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    /** 归属校验：会话不存在或不属于当前用户均视为不存在（不泄漏他人会话是否存在） */
    private ChatSession getOwnedSession(Long sessionId, Long userId) {
        ChatSession s = sessionMapper.findById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BaseException(404, "会话不存在");
        }
        return s;
    }

    // ---------- 问答（SSE 流式） ----------

    public SseEmitter ask(Long sessionId, Long userId, String question) {
        getOwnedSession(sessionId, userId);
        if (question == null || question.isBlank()) {
            throw new BaseException("问题不能为空");
        }
        question = question.strip();
        if (question.length() > 4000) {
            question = question.substring(0, 4000);
        }
        final String q = question;

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> log.warn("chat SSE 超时: sessionId={}", sessionId));

        chatExecutor.execute(() -> runAsk(sessionId, userId, q, emitter));
        return emitter;
    }

    private void runAsk(Long sessionId, Long userId, String question, SseEmitter emitter) {
        try {
            // 1. 落用户消息；首问时以问题前缀更新会话标题
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(sessionId);
            userMsg.setRole("user");
            userMsg.setContent(question);
            messageMapper.insert(userMsg);
            if (messageMapper.countBySessionId(sessionId) <= 1) {
                String title = question.length() > 20 ? question.substring(0, 20) + "…" : question;
                sessionMapper.updateTitle(sessionId, title);
            }

            // 2. 服务端查询该用户拥有的知识库集合（用户隔离：不信任前端）
            List<Long> kbIds = kbMapper.findByUserId(userId).stream()
                    .map(KnowledgeBase::getId).toList();

            // 3. 跨库检索；无命中（含无库/无 ACTIVE 文档/异常）走固定拒答，不调 LLM
            List<RetrievedChunk> chunks = retriever.searchByKbIds(kbIds, question, TOP_K);
            if (chunks.isEmpty()) {
                saveAssistantMessage(sessionId, REFUSAL_MESSAGE, null);
                send(emitter, "refusal", REFUSAL_MESSAGE);
                send(emitter, "done", "");
                emitter.complete();
                return;
            }

            // 4. 组装 Prompt（知识片段 + 最近对话历史），LLM 流式生成
            String sourcesJson = toJson(chunks.stream()
                    .map(c -> Map.of("docId", c.docId() == null ? 0 : c.docId(),
                            "kbId", c.kbId() == null ? 0 : c.kbId(),
                            "title", c.title(),
                            "excerpt", c.excerpt() != null && c.excerpt().length() > 200
                                    ? c.excerpt().substring(0, 200) : c.excerpt()))
                    .toList());
            send(emitter, "sources", sourcesJson);

            List<ChatMessage> history = recentHistory(sessionId);
            String prompt = buildPrompt(question, history, chunks);

            long startNanos = System.nanoTime();
            StringBuilder answer = new StringBuilder();
            chatClient.prompt().user(prompt).stream().content()
                    .doOnNext(token -> {
                        if (token != null && !token.isEmpty()) {
                            answer.append(token);
                            send(emitter, "delta", token);
                        }
                    })
                    .doOnError(e -> {
                        log.error("chat LLM 流式生成失败: sessionId={}", sessionId, e);
                        recordLlmTrace(sessionId, prompt, answer.toString(), startNanos, false, e.getMessage());
                        String partial = answer.isEmpty() ? "回答生成失败，请稍后重试。" : answer.toString();
                        saveAssistantMessage(sessionId, partial, sourcesJson);
                        send(emitter, "error", "回答生成失败，请稍后重试");
                        safeComplete(emitter);
                    })
                    .doOnComplete(() -> {
                        recordLlmTrace(sessionId, prompt, answer.toString(), startNanos, true, null);
                        saveAssistantMessage(sessionId, answer.toString(), sourcesJson);
                        send(emitter, "done", "");
                        safeComplete(emitter);
                    })
                    .subscribe();
        } catch (Exception e) {
            log.error("chat ask 处理异常: sessionId={}", sessionId, e);
            send(emitter, "error", "处理失败，请稍后重试");
            safeComplete(emitter);
        }
    }

    /** 最近 N 条历史（剔除本轮刚插入的 user 消息，即倒数第一条） */
    private List<ChatMessage> recentHistory(Long sessionId) {
        List<ChatMessage> all = messageMapper.findBySessionId(sessionId);
        if (all.size() <= 1) {
            return List.of();
        }
        List<ChatMessage> prior = all.subList(0, all.size() - 1);
        return prior.subList(Math.max(0, prior.size() - MAX_HISTORY), prior.size());
    }

    private String buildPrompt(String question, List<ChatMessage> history, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是用户的知识笔记助手，基于用户自己沉淀的文档回答问题。\n\n");
        sb.append("知识片段（来自用户的知识笔记，是回答的唯一依据）：\n");
        for (RetrievedChunk c : chunks) {
            sb.append("【").append(c.title()).append("】\n").append(c.excerpt()).append("\n---\n");
        }
        sb.append("\n严格规则：\n");
        sb.append("1. 只能依据上面的知识片段回答；\n");
        sb.append("2. 片段中没有的信息，必须明确说\"知识库中没有相关内容\"，严禁用你自己的知识补全、推测或编造；\n");
        sb.append("3. 回答用 Markdown 组织，简洁清晰，可引用片段中的原文。\n");
        if (!history.isEmpty()) {
            sb.append("\n之前的对话记录（仅用于理解上下文）：\n");
            for (ChatMessage m : history) {
                String role = "assistant".equals(m.getRole()) ? "助手" : "用户";
                String brief = m.getContent();
                if (brief != null && brief.length() > 500) {
                    brief = brief.substring(0, 500) + "……";
                }
                sb.append("[").append(role).append("] ").append(brief).append("\n");
            }
        }
        sb.append("\n用户问题：").append(question);
        return sb.toString();
    }

    private void saveAssistantMessage(Long sessionId, String content, String sourcesJson) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole("assistant");
            msg.setContent(content != null ? content : "");
            msg.setSources(sourcesJson);
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存 assistant 消息失败（已忽略）: sessionId={}", sessionId, e);
        }
    }

    /** 手动落一条 LLM trace（流式回调线程的 ThreadLocal 无法被 ChatModel observation 捕获归因） */
    private void recordLlmTrace(Long sessionId, String prompt, String completion,
                                 long startNanos, boolean success, String errorMsg) {
        try {
            LlmTrace trace = new LlmTrace();
            trace.setKind("llm");
            trace.setAgent("chat");
            trace.setSessionId("chat-" + sessionId);
            trace.setDurationMs((System.nanoTime() - startNanos) / 1_000_000);
            trace.setPromptExcerpt(prompt != null && prompt.length() > 2000 ? prompt.substring(0, 2000) : prompt);
            trace.setCompletionExcerpt(completion != null && completion.length() > 2000 ? completion.substring(0, 2000) : completion);
            trace.setEstimatedCost(BigDecimal.ZERO);
            trace.setStatus(success ? "success" : "error");
            trace.setErrorMsg(errorMsg != null && errorMsg.length() > 480 ? errorMsg.substring(0, 480) : errorMsg);
            traceHandler.submit(trace);
        } catch (Exception e) {
            log.warn("chat trace 记录失败（已忽略）", e);
        }
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
        } catch (Exception e) {
            log.warn("chat SSE 发送失败（客户端可能已断开）: event={}", event);
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "[]";
        }
    }

    @PreDestroy
    public void shutdown() {
        chatExecutor.shutdownNow();
    }
}
