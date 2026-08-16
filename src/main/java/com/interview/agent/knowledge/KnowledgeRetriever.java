package com.interview.agent.knowledge;

import com.interview.agent.observability.LlmTrace;
import com.interview.agent.observability.LlmTraceContext;
import com.interview.agent.observability.LlmTraceContextHolder;
import com.interview.agent.observability.LlmTraceObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索器（非工具类版本）：面试出题/评估时确定性调用，检索结果格式化后注入 Prompt。
 * 输出格式对齐 ThinkVerse SearchKnowledgeTool：【标题】\n内容（截断500字），片段间 --- 分隔。
 * 每次检索落一条 kind=retrieval 的观测 span（token/成本恒为 0，不计入 LLM 调用统计）。
 */
@Component
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);
    /** 检索 span 的归因名（观测台 Agent 拆分中展示为知识库检索） */
    private static final String AGENT_RETRIEVER = "retriever";

    private final VectorStore vectorStore;
    private final LlmTraceObservationHandler traceHandler;

    public KnowledgeRetriever(VectorStore vectorStore, LlmTraceObservationHandler traceHandler) {
        this.vectorStore = vectorStore;
        this.traceHandler = traceHandler;
    }

    /**
     * 检索指定知识库中与 query 最相关的知识片段并格式化。
     * 无结果或异常时返回 null（调用方据此决定是否注入 Prompt）。
     */
    public String search(Long knowledgeBaseId, String query, int topK) {
        if (knowledgeBaseId == null || query == null || query.isBlank()) {
            return null;
        }
        long startNanos = System.nanoTime();
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.5)
                    .filterExpression("kbId == " + knowledgeBaseId)
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            if (results == null || results.isEmpty()) {
                log.info("知识库检索无结果: kbId={}, query='{}'", knowledgeBaseId, query);
                recordSpan(query, knowledgeBaseId, 0, startNanos, true, null);
                return null;
            }

            String formatted = results.stream()
                    .map(doc -> {
                        String title = doc.getMetadata() != null
                                ? String.valueOf(doc.getMetadata().getOrDefault("title", "未知"))
                                : "未知";
                        String content = doc.getText();
                        if (content != null && content.length() > 500) {
                            content = content.substring(0, 500) + "...";
                        }
                        return "【" + title + "】\n" + content;
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.info("知识库检索成功: kbId={}, query='{}', 结果数={}", knowledgeBaseId, query, results.size());
            recordSpan(query, knowledgeBaseId, results.size(), startNanos, true, null);
            return formatted;
        } catch (Exception e) {
            log.error("知识库检索失败: kbId={}, query='{}'", knowledgeBaseId, query, e);
            recordSpan(query, knowledgeBaseId, 0, startNanos, false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return null;
        }
    }

    /** 落检索 span：任何异常仅记日志，绝不影响检索主链路（无结果也记录，便于观测检索命中率） */
    private void recordSpan(String query, Long knowledgeBaseId, int hitCount, long startNanos,
                            boolean success, String errorMsg) {
        try {
            LlmTrace trace = new LlmTrace();
            trace.setKind("retrieval");
            trace.setAgent(AGENT_RETRIEVER);
            LlmTraceContext ctx = LlmTraceContextHolder.current();
            if (ctx != null) {
                trace.setSessionId(ctx.getSessionId());
                trace.setTraceId(ctx.getRoundTraceId());
            }
            trace.setPromptExcerpt("[kbId=" + knowledgeBaseId + ", topK] " + truncate(query, 2000));
            trace.setCompletionExcerpt("命中 " + hitCount + " 个片段");
            trace.setDurationMs((System.nanoTime() - startNanos) / 1_000_000);
            // 检索不消耗 LLM token，成本恒为 0（列 NOT NULL，不可省略）
            trace.setEstimatedCost(BigDecimal.ZERO);
            trace.setStatus(success ? "success" : "error");
            trace.setErrorMsg(errorMsg != null && errorMsg.length() > 480 ? errorMsg.substring(0, 480) : errorMsg);
            traceHandler.submit(trace);
        } catch (Exception e) {
            log.warn("检索 span 记录失败（已忽略）", e);
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
