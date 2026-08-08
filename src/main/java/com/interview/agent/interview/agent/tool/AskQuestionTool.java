package com.interview.agent.interview.agent.tool;

import com.interview.agent.common.exception.InterviewTerminatedException;
import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.sse.SseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class AskQuestionTool {
    private static final Logger log = LoggerFactory.getLogger(AskQuestionTool.class);
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingQuestions = new ConcurrentHashMap<>();
    private final Set<String> terminatedSessions = ConcurrentHashMap.newKeySet();
    private final SseRegistry sseRegistry;
    private final InterviewSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public AskQuestionTool(SseRegistry sseRegistry, InterviewSessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sseRegistry = sseRegistry;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 出题并等待回答（阻塞），默认使用 "QUESTION" 事件名
     * @param sessionId 会话ID
     * @param question 题目内容
     * @return 候选人回答
     */
    public String askAndWait(String sessionId, String question) {
        return askAndWait(sessionId, question, "QUESTION", 0);
    }

    /**
     * 出题并等待回答（阻塞），可指定事件名
     * @param sessionId 会话ID
     * @param question 题目内容
     * @param eventName SSE 事件名（QUESTION / FOLLOW_UP 等）
     * @param questionNumber 题号（追问时为其所属题号）
     * @return 候选人回答
     */
    public String askAndWait(String sessionId, String question, String eventName, int questionNumber) {
        if (terminatedSessions.contains(sessionId)) {
            throw new InterviewTerminatedException("面试已终止: " + sessionId);
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingQuestions.put(sessionId, future);

        // 持久化当前题目：SSE 事件丢失时前端可轮询恢复（避免提交后卡死无下一题）
        try {
            sessionMapper.updateCurrentQuestion(sessionId, question);
        } catch (Exception e) {
            log.warn("持久化当前题目失败: sessionId={}", sessionId, e);
        }

        // SSE 推送题目（JSON：题号 + 题目 + 是否追问，前端据此展示“第N题”徽标）
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("questionNumber", questionNumber);
            payload.put("question", question);
            payload.put("isFollowUp", "FOLLOW_UP".equals(eventName));
            sseRegistry.sendEvent(sessionId, eventName, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("SSE 题目事件序列化失败，回退纯文本: sessionId={}", sessionId, e);
            sseRegistry.sendEvent(sessionId, eventName, question);
        }

        try {
            // 阻塞等待回答，最多30分钟
            String answer = future.get(30, TimeUnit.MINUTES);
            // 回答已收到，清除当前题目标记
            sessionMapper.updateCurrentQuestion(sessionId, null);
            return answer;
        } catch (Exception e) {
            log.warn("等待回答超时或被中断: sessionId={}", sessionId);
            pendingQuestions.remove(sessionId);
            sessionMapper.updateCurrentQuestion(sessionId, null);
            return "【超时未回答】";
        }
    }

    /**
     * 提交回答（唤醒等待）
     */
    public void submitAnswer(String sessionId, String answer) {
        CompletableFuture<String> future = pendingQuestions.remove(sessionId);
        if (future != null) {
            future.complete(answer);
        } else {
            log.warn("未找到等待中的问题: sessionId={}", sessionId);
        }
    }

    /**
     * 取消等待（并标记会话已终止）
     */
    public void cancel(String sessionId) {
        terminatedSessions.add(sessionId);
        CompletableFuture<String> future = pendingQuestions.remove(sessionId);
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * 重置会话终止标记（仅面试重新开始时使用）
     */
    public void resetTermination(String sessionId) {
        terminatedSessions.remove(sessionId);
    }

    /**
     * 推送思考中状态
     */
    public void sendThinking(String sessionId) {
        sseRegistry.sendEvent(sessionId, "THINKING", "思考中...");
    }
}
