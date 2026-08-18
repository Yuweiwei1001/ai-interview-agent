package com.interview.agent.observability;

/**
 * LLM 追踪上下文 ThreadLocal 持有者。
 *
 * <p>传播链路：
 * <ol>
 *   <li>面试图节点执行时设置 sessionId（节点运行在 StateGraph 异步线程池）</li>
 *   <li>{@code LlmCallWrapper.callWithRetry} 在提交共享 executor 前捕获快照，
 *       并在 callable 内恢复（LLM 实际调用发生在另一线程）</li>
 *   <li>{@code LlmTraceObservationHandler} 在 observation onStop/onError 时读取</li>
 * </ol>
 */
public final class LlmTraceContextHolder {
    private static final ThreadLocal<LlmTraceContext> HOLDER = new ThreadLocal<>();

    private LlmTraceContextHolder() {}

    public static void set(LlmTraceContext context) {
        if (context == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(context);
        }
    }

    public static LlmTraceContext current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 仅设置 sessionId（保留已有 agent，无上下文时新建） */
    public static void setSessionId(String sessionId) {
        LlmTraceContext current = HOLDER.get();
        if (current == null) {
            HOLDER.set(new LlmTraceContext(null, sessionId));
        } else {
            HOLDER.set(current.withSessionId(sessionId));
        }
    }

    /** 设置 sessionId + 轮次 traceId（保留已有 agent，无上下文时新建） */
    public static void setSessionAndRound(String sessionId, String roundTraceId) {
        LlmTraceContext current = HOLDER.get();
        if (current == null) {
            HOLDER.set(new LlmTraceContext(null, sessionId, roundTraceId));
        } else {
            HOLDER.set(current.withSessionId(sessionId).withRoundTraceId(roundTraceId));
        }
    }
}
