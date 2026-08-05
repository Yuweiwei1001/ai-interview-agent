package com.interview.agent.interview.agent.tool;

import com.interview.agent.common.exception.InterviewTerminatedException;
import com.interview.agent.sse.SseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public AskQuestionTool(SseRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
    }

    /**
     * 出题并等待回答（阻塞），默认使用 "QUESTION" 事件名
     * @param sessionId 会话ID
     * @param question 题目内容
     * @return 候选人回答
     */
    public String askAndWait(String sessionId, String question) {
        return askAndWait(sessionId, question, "QUESTION");
    }

    /**
     * 出题并等待回答（阻塞），可指定事件名
     * @param sessionId 会话ID
     * @param question 题目内容
     * @param eventName SSE 事件名（QUESTION / FOLLOW_UP 等）
     * @return 候选人回答
     */
    public String askAndWait(String sessionId, String question, String eventName) {
        if (terminatedSessions.contains(sessionId)) {
            throw new InterviewTerminatedException("面试已终止: " + sessionId);
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingQuestions.put(sessionId, future);

        // SSE 推送题目
        sseRegistry.sendEvent(sessionId, eventName, question);

        try {
            // 阻塞等待回答，最多30分钟
            return future.get(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("等待回答超时或被中断: sessionId={}", sessionId);
            pendingQuestions.remove(sessionId);
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
