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
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索器：按 kbId 集合做向量检索（面试链路已解耦，当前唯一调用方为知识笔记 AI 问答）。
 * 每次检索落一条 kind=retrieval 的观测 span（token/成本恒为 0，不计入 LLM 调用统计）。
 */
@Component
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);
    /** 检索 span 的归因名（观测台 Agent 拆分中展示为知识库检索） */
    private static final String AGENT_RETRIEVER = "retriever";

    /**
     * 原始 cosine 相似度阈值（非归一化分数）：Spring AI ES store 将 similarityThreshold
     * 直接映射为 ES knn.similarity，对底层 cosine 生效。通用 embedding（如 DashScope）
     * 的"问题 vs 知识片段"cosine 通常落在 0.3~0.5，阈值取 0.5 会大量漏召回导致误拒答；
     * 无关问题（如闲聊/其他领域）cosine 一般 <0.2，0.3 仍可有效拒答。
     */
    private static final double MIN_SIMILARITY = 0.3;

    private final VectorStore vectorStore;
    private final LlmTraceObservationHandler traceHandler;

    public KnowledgeRetriever(VectorStore vectorStore, LlmTraceObservationHandler traceHandler) {
        this.vectorStore = vectorStore;
        this.traceHandler = traceHandler;
    }

    /** 检索命中片段：docId/title 供引用来源展示，excerpt 为原文摘录 */
    public record RetrievedChunk(Long docId, String title, String excerpt, double score) {}

    /**
     * 在指定知识库集合内检索与 query 最相关的片段。
     * 调用方必须自行保证 kbIds 只含当前登录用户拥有的库（用户隔离的信任边界在服务端）。
     * 集合为空 / 无结果 / 异常时返回空列表（调用方据此走拒答）。
     */
    public List<RetrievedChunk> searchByKbIds(List<Long> kbIds, String query, int topK) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        long startNanos = System.nanoTime();
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            // 注意：in(String, List<Object>) 无法接收 List<Long>（泛型不变），
            // 会退化匹配 varargs 重载把整个 List 当单个值，生成非法嵌套数组 terms 查询；
            // 必须 toArray() 展开为逐个值
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(MIN_SIMILARITY)
                    .filterExpression(b.in("kbId", kbIds.toArray()).build())
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            List<RetrievedChunk> chunks = results == null ? List.of() : results.stream()
                    .map(doc -> {
                        java.util.Map<String, Object> md = doc.getMetadata();
                        Long docId = md != null && md.get("docId") != null
                                ? Long.valueOf(String.valueOf(md.get("docId"))) : null;
                        String title = md != null && md.get("title") != null
                                ? String.valueOf(md.get("title")) : "未知";
                        String content = doc.getText();
                        if (content != null && content.length() > 500) {
                            content = content.substring(0, 500) + "...";
                        }
                        double score = doc.getScore() != null ? doc.getScore() : 0.0;
                        return new RetrievedChunk(docId, title, content, score);
                    })
                    .collect(Collectors.toList());

            log.info("知识库检索完成: kbIds={}, topK={}, 命中={}", kbIds, topK, chunks.size());
            recordSpan(query, kbIds, chunks.size(), startNanos, true, null);
            return chunks;
        } catch (Exception e) {
            log.error("知识库检索失败: kbIds={}", kbIds, e);
            recordSpan(query, kbIds, 0, startNanos, false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 落检索 span：任何异常仅记日志，绝不影响主链路（无结果也记录，便于观测检索命中率） */
    private void recordSpan(String query, List<Long> kbIds, int hitCount, long startNanos,
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
            trace.setPromptExcerpt("[kbIds=" + kbIds + ", topK] " + truncate(query, 2000));
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
