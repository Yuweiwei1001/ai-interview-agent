package com.interview.agent.common.ai;

import com.interview.agent.observability.LlmTraceContext;
import com.interview.agent.observability.LlmTraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * LLM 调用统一容错包装器
 * 支持超时控制、重试退避、降级兜底
 */
public class LlmCallWrapper {
    private static final Logger log = LoggerFactory.getLogger(LlmCallWrapper.class);

    // 默认超时时间（秒）
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    // 默认重试次数
    private static final int DEFAULT_RETRY_COUNT = 1;
    // 重试退避时间（秒）
    private static final long RETRY_BACKOFF_SECONDS = 1;

    private LlmCallWrapper() {}

    /**
     * 执行 LLM 调用，带超时和重试
     */
    public static <T> T callWithRetry(Callable<T> callable, Supplier<T> fallback) {
        return callWithRetry(null, callable, fallback, DEFAULT_TIMEOUT_SECONDS, DEFAULT_RETRY_COUNT);
    }

    /**
     * 执行 LLM 调用，带超时和重试（agent 归因，供 llm_trace 追踪）
     */
    public static <T> T callWithRetry(String agent, Callable<T> callable, Supplier<T> fallback) {
        return callWithRetry(agent, callable, fallback, DEFAULT_TIMEOUT_SECONDS, DEFAULT_RETRY_COUNT);
    }

    /**
     * 执行 LLM 调用，带自定义超时和重试次数
     */
    public static <T> T callWithRetry(Callable<T> callable, Supplier<T> fallback,
                                       long timeoutSeconds, int maxRetries) {
        return callWithRetry(null, callable, fallback, timeoutSeconds, maxRetries);
    }

    /**
     * 执行 LLM 调用，带自定义超时、重试次数与 agent 归因。
     *
     * <p>追踪上下文传播：LLM 实际调用发生在临时单线程 executor 上，
     * 因此在提交前捕获调用线程的上下文快照（含 sessionId），并叠加 agent 后在 callable 内恢复。
     */
    public static <T> T callWithRetry(String agent, Callable<T> callable, Supplier<T> fallback,
                                       long timeoutSeconds, int maxRetries) {
        LlmTraceContext snapshot = LlmTraceContextHolder.current();
        if (agent != null) {
            snapshot = snapshot == null ? new LlmTraceContext(agent, null) : snapshot.withAgent(agent);
        }
        final LlmTraceContext traceContext = snapshot;
        Callable<T> tracedCallable = () -> {
            LlmTraceContextHolder.set(traceContext);
            try {
                return callable.call();
            } finally {
                LlmTraceContextHolder.clear();
            }
        };

        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("LLM 调用重试: attempt={}/{}", attempt, maxRetries);
                    Thread.sleep(RETRY_BACKOFF_SECONDS * 1000);
                }

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<T> future = executor.submit(tracedCallable);
                    return future.get(timeoutSeconds, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
            } catch (TimeoutException e) {
                log.warn("LLM 调用超时: attempt={}/{}", attempt + 1, maxRetries);
                lastException = e;
            } catch (Exception e) {
                log.warn("LLM 调用异常: attempt={}/{}", attempt + 1, maxRetries, e);
                lastException = e;
            }
        }

        // 所有重试失败，执行降级
        log.error("LLM 调用全部失败，执行降级", lastException);
        try {
            return fallback.get();
        } catch (Exception e) {
            throw new RuntimeException("LLM 降级也失败", e);
        }
    }

    /**
     * 执行 LLM 调用，带超时和重试，返回 entity 类型
     */
    public static <T> T callEntity(Callable<T> callable, Supplier<T> fallback) {
        return callWithRetry(null, callable, fallback);
    }

    /**
     * 执行 LLM 调用，带超时和重试，返回 entity 类型（agent 归因）
     */
    public static <T> T callEntity(String agent, Callable<T> callable, Supplier<T> fallback) {
        return callWithRetry(agent, callable, fallback);
    }
}
