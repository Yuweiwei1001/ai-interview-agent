package com.interview.agent.interview.agent.tool;

import com.interview.agent.common.exception.InterviewTerminatedException;
import com.interview.agent.common.exception.InterviewTimeoutException;
import com.interview.agent.interview.InterviewService;
import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.sse.SseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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
    /** @Lazy 打破与 InterviewService 的构造器循环依赖；仅超时收尾时回调 */
    private final InterviewService interviewService;
    /** 单题等待回答超时（分钟），可配置便于测试；默认 30 */
    private final long answerTimeoutMinutes;

    public AskQuestionTool(SseRegistry sseRegistry, InterviewSessionMapper sessionMapper, ObjectMapper objectMapper,
                           @Lazy InterviewService interviewService,
                           @Value("${interview.answer-timeout-minutes:30}") long answerTimeoutMinutes) {
        this.sseRegistry = sseRegistry;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
        this.interviewService = interviewService;
        this.answerTimeoutMinutes = answerTimeoutMinutes;
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

        // 先流式推送题目（打字机效果），再持久化当前题目，最后推送完整 QUESTION 落定。
        // 持久化放在流式之后：避免轮询在流式期间读到 currentQuestion 而重复推送完整题目
        streamQuestionTyping(sessionId, question);
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
            // 阻塞等待回答，默认最多 30 分钟（interview.answer-timeout-minutes 可配置）
            String answer = future.get(answerTimeoutMinutes, TimeUnit.MINUTES);
            // 回答已收到，清除当前题目标记
            sessionMapper.updateCurrentQuestion(sessionId, null);
            return answer;
        } catch (Exception e) {
            log.warn("等待回答超时或被中断: sessionId={}", sessionId);
            pendingQuestions.remove(sessionId);
            sessionMapper.updateCurrentQuestion(sessionId, null);
            // 面试已被手动结束：直接终止流程，不再把超时占位作为回答继续评估
            if (terminatedSessions.contains(sessionId)) {
                throw new InterviewTerminatedException("面试已终止: " + sessionId);
            }
            // 单题超时：不再用「【超时未回答】」占位继续评估（会产生 0 分隐藏轮次与迟到回答错配），
            // 而是终止面试并自动收尾（已完成轮次生成报告）；迟到回答因无等待中的 future 自然被拒
            sseRegistry.sendEvent(sessionId, "ANSWER_TIMEOUT", "单题等待超时，面试已结束");
            try {
                interviewService.endInterviewOnTimeout(sessionId);
            } catch (Exception ex) {
                log.warn("超时收尾失败（已忽略）: sessionId={}", sessionId, ex);
            }
            throw new InterviewTimeoutException("等待回答超时: " + sessionId);
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
     * 清除会话终止标记：图线程因终止异常退出时调用（防 terminatedSessions 无界增长），
     * 或面试重新开始前显式重置
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

    /** 流式输出题目：按标点/空格切块，每隔 30ms 推送一个 QUESTION_DELTA 块 */
    private void streamQuestionTyping(String sessionId, String question) {
        if (question == null || question.isBlank()) return;
        for (String chunk : chunkForTyping(question)) {
            try {
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("delta", chunk);
                String json = objectMapper.writeValueAsString(payload);
                sseRegistry.sendEvent(sessionId, "QUESTION_DELTA", json);
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("QUESTION_DELTA 推送失败", e);
            }
        }
    }

    /** 按 3-5 字符切块，在标点后优先断开，模拟自然打字节奏 */
    private java.util.List<String> chunkForTyping(String text) {
        java.util.List<String> chunks = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            boolean punctuation = "，。！？、；：,.!?;: \n".indexOf(c) >= 0;
            if (sb.length() >= 4 || (punctuation && sb.length() >= 1)) {
                chunks.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) chunks.add(sb.toString());
        return chunks;
    }
}
