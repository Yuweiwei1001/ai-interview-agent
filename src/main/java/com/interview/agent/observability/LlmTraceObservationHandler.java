package com.interview.agent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ChatModel 层 Observation 处理器：每次 LLM 调用落一行 llm_trace。
 *
 * <p>设计要点：
 * <ul>
 *   <li>只在 onStop 记录一次（异常时 context.getError() 非空，标记 error）</li>
 *   <li>agent/sessionId 归因来自 {@link LlmTraceContextHolder}（调用前由 wrapper 恢复到执行线程）</li>
 *   <li>异步单线程写库 + 有界队列，观测链路任何异常都不影响面试主链路</li>
 *   <li>成本在写入时按模型单价计算（observability.cost.*，元/千token）</li>
 * </ul>
 */
@Component
public class LlmTraceObservationHandler implements ObservationHandler<ChatModelObservationContext> {
    private static final Logger log = LoggerFactory.getLogger(LlmTraceObservationHandler.class);

    private static final String KEY_START_NANOS = "llm-trace-start-nanos";
    private static final int QUEUE_CAPACITY = 1000;

    private final LlmTraceMapper traceMapper;
    private final ObservabilityProperties properties;
    private final BlockingQueue<LlmTrace> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService writer;

    public LlmTraceObservationHandler(LlmTraceMapper traceMapper, ObservabilityProperties properties) {
        this.traceMapper = traceMapper;
        this.properties = properties;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "llm-trace-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.writer.submit(this::writeLoop);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        // 泛型限定 ChatModel 层：不会与 ChatClient 层 observation 重复，也不会捕获 embedding
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put(KEY_START_NANOS, System.nanoTime());
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        try {
            LlmTrace trace = buildTrace(context);
            if (!queue.offer(trace)) {
                log.warn("llm_trace 队列已满，丢弃一条追踪记录: agent={}", trace.getAgent());
            }
        } catch (Exception e) {
            // 观测系统故障绝不能影响面试主链路
            log.warn("构建 llm_trace 记录失败（已忽略）", e);
        }
    }

    private LlmTrace buildTrace(ChatModelObservationContext context) {
        LlmTrace trace = new LlmTrace();

        LlmTraceContext holderCtx = LlmTraceContextHolder.current();
        if (holderCtx != null) {
            trace.setAgent(holderCtx.getAgent());
            trace.setSessionId(holderCtx.getSessionId());
        }

        if (context.getRequest() != null) {
            trace.setPromptExcerpt(excerpt(context.getRequest().getContents()));
            if (context.getRequest().getOptions() != null) {
                trace.setModel(context.getRequest().getOptions().getModel());
            }
        }

        ChatResponse response = context.getResponse();
        if (response != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                trace.setPromptTokens(safeInt(usage.getPromptTokens()));
                trace.setCompletionTokens(safeInt(usage.getCompletionTokens()));
                trace.setTotalTokens(safeInt(usage.getTotalTokens()));
            }
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                trace.setCompletionExcerpt(excerpt(response.getResult().getOutput().getText()));
            }
        }

        Long startNanos = context.get(KEY_START_NANOS);
        trace.setDurationMs(startNanos != null ? (System.nanoTime() - startNanos) / 1_000_000 : 0);

        Throwable error = context.getError();
        if (error != null) {
            trace.setStatus("error");
            trace.setErrorMsg(truncate(String.valueOf(error.getMessage()), 480));
        } else {
            trace.setStatus("success");
        }

        trace.setEstimatedCost(computeCost(trace.getPromptTokens(), trace.getCompletionTokens()));
        return trace;
    }

    private BigDecimal computeCost(int promptTokens, int completionTokens) {
        ObservabilityProperties.Cost cost = properties.getCost();
        double value = promptTokens / 1000.0 * cost.getInputPer1k()
                + completionTokens / 1000.0 * cost.getOutputPer1k();
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return truncate(text.strip(), properties.getPromptExcerptLength());
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private void writeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                LlmTrace trace = queue.take();
                traceMapper.insert(trace);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("llm_trace 写入失败（已忽略，不影响主链路）", e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdownNow();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 尽力落盘剩余记录
        LlmTrace remaining;
        while ((remaining = queue.poll()) != null) {
            try {
                traceMapper.insert(remaining);
            } catch (Exception ignored) {
                break;
            }
        }
    }
}
