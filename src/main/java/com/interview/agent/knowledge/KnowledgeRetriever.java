package com.interview.agent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.ai.LightweightLlmClient;
import com.interview.agent.common.ai.LlmCallWrapper;
import com.interview.agent.observability.LlmTrace;
import com.interview.agent.observability.LlmTraceContext;
import com.interview.agent.observability.LlmTraceContextHolder;
import com.interview.agent.observability.LlmTraceObservationHandler;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索器：父子块检索（面试链路已解耦，当前唯一调用方为知识笔记 AI 问答）。
 * 先对小节 child 做向量检索，再按 docId 去重并加载整篇 parent 作为 LLM 上下文（Parent-Child 策略）。
 *
 * <p>支持可开关的增强检索管线（默认全部关闭，保持原有向量检索行为）：
 * 查询改写/多查询、HyDE、混合检索（BM25+向量，RRF 融合）、LLM 重排。开关见 application.yml 的 app.rag.*。
 *
 * <p>每次检索落一条 kind=retrieval 的观测 span（token/成本恒为 0，不计入 LLM 调用统计）。
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
    /**
     * 文档级分数余量：与检索最高分差距大于该值的弱相关文档将被剔除，避免引用来源混入无关文档。
     * 经验节奏待用真实命中分数校准（retrieval 测试后按需调整）。
     */
    private static final double SCORE_MARGIN = 0.10;
    /** parent（整篇文档）喂给 LLM 的内容上限 */
    private static final int PARENT_EXCERPT_LIMIT = 1500;
    /** ES 向量库索引名（Spring AI 默认索引） */
    private static final String ES_INDEX = "spring-ai-document-index";
    /** RRF 融合常量 k（经验值 60）*/
    private static final int RRF_K = 60;
    /** 增强检索：每个召回引擎取 chunk 数的倍率，供 RRF 融合后有足够余量 */
    private static final int FUSION_TOP_CHUNKS_MULT = 3;
    /** 增强检索：LLM 一次调用超时（秒），避免拉长问答延迟 */
    private static final int RAG_LLM_TIMEOUT_SECONDS = 20;

    // ==== 增强检索开关（application.yml -> app.rag.*，默认全部关闭） ====
    @Value("${app.rag.query-rewrite-enabled:false}")
    private boolean queryRewriteEnabled;
    @Value("${app.rag.hyde-enabled:false}")
    private boolean hydeEnabled;
    @Value("${app.rag.hybrid-enabled:false}")
    private boolean hybridEnabled;
    @Value("${app.rag.llm-rerank-enabled:false}")
    private boolean llmRerankEnabled;
    /** 增强检索用的 LLM 模型（默认 qwen3.7-flash，复用 LightweightLlmClient 多模态端点） */
    @Value("${app.rag.llm-model:qwen3.7-flash}")
    private String llmModel;

    private final VectorStore vectorStore;
    private final KnowledgeDocumentMapper docMapper;
    private final LlmTraceObservationHandler traceHandler;
    private final LightweightLlmClient llmClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeRetriever(VectorStore vectorStore, KnowledgeDocumentMapper docMapper,
                              LlmTraceObservationHandler traceHandler,
                              LightweightLlmClient llmClient, RestClient restClient) {
        this.vectorStore = vectorStore;
        this.docMapper = docMapper;
        this.traceHandler = traceHandler;
        this.llmClient = llmClient;
        this.restClient = restClient;
    }

    /** 检索命中片段：docId/title 供引用来源展示，excerpt 为原文摘录 */
    public record RetrievedChunk(Long docId, Long kbId, String title, String excerpt, double score) {}

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
            List<RetrievedChunk> chunks = collect(kbIds, query, topK);

            log.info("知识库检索完成: kbIds={}, topK={}, 命中={}, rewrite={}, hyde={}, hybrid={}, rerank={}",
                    kbIds, topK, chunks.size(), queryRewriteEnabled, hydeEnabled, hybridEnabled, llmRerankEnabled);
            recordSpan(query, kbIds, chunks.size(), startNanos, true, null);
            return chunks;
        } catch (Exception e) {
            log.error("知识库检索失败: kbIds={}", kbIds, e);
            recordSpan(query, kbIds, 0, startNanos, false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 检索编排：默认走"纯向量检索 → 父子块展开"；任一增强开关打开时，
     * 进入"多召回源 + RRF 融合 → 父子块展开 →（可选）LLM 重排"管线。
     */
    private List<RetrievedChunk> collect(List<Long> kbIds, String query, int topK) {
        boolean enhanced = queryRewriteEnabled || hydeEnabled || hybridEnabled;
        // 未开启任何增强：保持原有两条规则（向量阈值 + 文档级 score margin）的稳定行为
        if (!enhanced) {
            return toParentContext(vectorSearch(kbIds, query, topK), true);
        }

        List<List<Document>> lists = new ArrayList<>();
        lists.add(vectorSearch(kbIds, query, topK)); // 原始问题向量召回（基准）

        if (queryRewriteEnabled) {
            String rewrite = llmRewrite(query);
            if (rewrite != null && !rewrite.isBlank()) {
                lists.add(vectorSearch(kbIds, rewrite, topK));
                log.info("查询改写参与检索: {} -> {}", truncate(query, 120), truncate(rewrite, 120));
            }
        }
        if (hydeEnabled) {
            String hyde = llmHyde(query);
            if (hyde != null && !hyde.isBlank()) {
                lists.add(vectorSearch(kbIds, hyde, topK));
                log.info("HyDE 参与检索");
            }
        }
        if (hybridEnabled) {
            List<Document> bm25 = bm25Search(kbIds, query, topK * FUSION_TOP_CHUNKS_MULT);
            if (!bm25.isEmpty()) {
                lists.add(bm25);
                log.info("BM25 参与检索: {}", bm25.size());
            }
        }

        List<Document> merged = rrfFuse(lists, topK * FUSION_TOP_CHUNKS_MULT);
        // RRF 融合分数不再具有单一引擎的 cosine 可比性，故跳过 score margin，交给重排/截断收敛
        List<RetrievedChunk> chunks = toParentContext(merged, false);

        if (llmRerankEnabled) {
            chunks = llmRerank(query, chunks, topK);
        } else if (chunks.size() > topK) {
            chunks = new ArrayList<>(chunks.subList(0, topK));
        }
        return chunks;
    }

    /** 单次向量召回（kbId 过滤 + 相似度阈值） */
    private List<Document> vectorSearch(List<Long> kbIds, String query, int topK) {
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
        return vectorStore.similaritySearch(request);
    }

    /**
     * 父子块展开：向量检索命中的是精细的小节 child，这里先按 docId 去重（每篇文档只保留得分最高的小节），
     * 再加载该文档的整篇 parent 内容作为送 LLM 的上下文——既保留小节检索的语义聚焦，又提供完整上下文，避免截断漏答。
     *
     * <p>同时在文档级按分数余量过滤：只保留与所得最高分接近的文档，剔除语义相邻但明显无关的弱相关片段，
     * 避免引用来源混入无关文档（如 `redis缓存三大问题` 带出「分布式系统基础」「Java 并发」）。
     * 增强检索（RRF 融合）走 {@code applyMargin=false}，因融合分数不再具备单一引擎 cosine 可比性。
     */
    private List<RetrievedChunk> toParentContext(List<Document> results, boolean applyMargin) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        Map<Long, Document> bestByDoc = new LinkedHashMap<>();
        for (Document doc : results) {
            Map<String, Object> md = doc.getMetadata();
            Long docId = md != null && md.get("docId") != null
                    ? Long.valueOf(String.valueOf(md.get("docId"))) : null;
            if (docId == null) {
                continue;
            }
            Document prev = bestByDoc.get(docId);
            double score = doc.getScore() != null ? doc.getScore() : Double.NEGATIVE_INFINITY;
            double prevScore = (prev != null && prev.getScore() != null)
                    ? prev.getScore() : Double.NEGATIVE_INFINITY;
            if (prev == null || score > prevScore) {
                bestByDoc.put(docId, doc);
            }
        }

        List<RetrievedChunk> out = new ArrayList<>();
        double maxScore = Double.NEGATIVE_INFINITY;
        for (Document doc : bestByDoc.values()) {
            double score = doc.getScore() != null ? doc.getScore() : 0.0;
            maxScore = Math.max(maxScore, score);
        }
        for (Document doc : bestByDoc.values()) {
            Map<String, Object> md = doc.getMetadata();
            Long docId = Long.valueOf(String.valueOf(md.get("docId")));
            String title = md != null && md.get("title") != null ? String.valueOf(md.get("title")) : "未知";
            double score = doc.getScore() != null ? doc.getScore() : 0.0;
            // 分数余量过滤：与最高分差距超过阈值的弱相关文档直接剔除（仅常规向量路径）
            if (applyMargin && maxScore - score > SCORE_MARGIN) {
                log.debug("知识库剪枝弱相关文档: docId={}, title={}, score={}, maxScore={}",
                        docId, title, score, maxScore);
                continue;
            }

            KnowledgeDocument parent = loadParent(docId);
            String parentContent = parent != null ? parent.getContentMd() : null;
            String excerpt = parentContent != null ? parentContent : doc.getText();
            if (excerpt != null && excerpt.length() > PARENT_EXCERPT_LIMIT) {
                excerpt = excerpt.substring(0, PARENT_EXCERPT_LIMIT) + "...";
            }
            Long kbId = parent != null ? parent.getKnowledgeBaseId() : null;
            out.add(new RetrievedChunk(docId, kbId, title, excerpt, score));
        }
        return out;
    }

    /** 按 docId 从 DB 加载整篇 parent；文档已删除或读取失败时返回 null（调用方回退到小节原文） */
    private KnowledgeDocument loadParent(Long docId) {
        try {
            return docMapper.findById(docId);
        } catch (Exception e) {
            log.warn("加载 parent 文档失败，回退小节节选: docId={}", docId, e);
            return null;
        }
    }

    /**
     * BM25 关键词召回：直接对 ES 文本字段 content 做 match + kbId 过滤，覆盖向量角度的术语/专有名词召回盲区。
     * 失败（如 ES 抖动/字段不同）返回空列表，由 RRF 兜底，不阻断主链路。
     */
    private List<Document> bm25Search(List<Long> kbIds, String query, int size) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> bool = new LinkedHashMap<>();
            List<Object> must = new ArrayList<>();
            Map<String, Object> match = new LinkedHashMap<>();
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("match", Map.of("content", query));
            must.add(content);
            bool.put("must", must);
            Map<String, Object> filterMap = new LinkedHashMap<>();
            filterMap.put("terms", Map.of("metadata.kbId", kbIds));
            bool.put("filter", List.of(filterMap));
            body.put("query", Map.of("bool", bool));
            body.put("size", size);

            Request request = new Request("POST", "/" + ES_INDEX + "/_search");
            request.setJsonEntity(objectMapper.writeValueAsString(body));
            Response response = restClient.performRequest(request);
            JsonNode root = objectMapper.readTree(response.getEntity().getContent());
            JsonNode hits = root.path("hits").path("hits");

            List<Document> out = new ArrayList<>();
            if (!hits.isArray()) {
                return out;
            }
            for (JsonNode hit : hits) {
                String id = hit.path("_id").asText("");
                JsonNode src = hit.path("_source");
                String text = src.path("content").asText("");
                JsonNode md = src.path("metadata");
                Map<String, Object> meta = new HashMap<>();
                if (md.isObject()) {
                    meta.put("kbId", md.path("kbId").asLong(0));
                    meta.put("docId", md.path("docId").asLong(0));
                    meta.put("title", md.path("title").asText("未知"));
                }
                // 增强路径跳过 score margin 过滤，这里刻意不给 score（统一交给 RRF 的 rank 判定）
                out.add(new Document(id, text, meta));
            }
            return out;
        } catch (Exception e) {
            log.warn("BM25 召回失败（跳过该召回源）: kbIds={}", kbIds, e);
            return List.of();
        }
    }

    /**
     * RRF 融合：多个召回源各自给出按相关度排序的列表，对每个 chunk 按 rank 计分并求和，
     * 归一处理不同引擎/模型的分数单位不可比问题。融合后取 topN。
     */
    private List<Document> rrfFuse(List<List<Document>> lists, int topN) {
        Map<String, Double> fused = new HashMap<>();
        Map<String, Document> byId = new HashMap<>();
        for (List<Document> list : lists) {
            int rank = 1;
            for (Document doc : list) {
                String id = doc.getId();
                if (id == null || id.isBlank()) {
                    rank++;
                    continue;
                }
                fused.merge(id, 1.0 / (RRF_K + rank), Double::sum);
                byId.putIfAbsent(id, doc);
                rank++;
            }
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Document> out = new ArrayList<>();
        for (Map.Entry<String, Double> entry : entries) {
            if (out.size() >= topN) {
                break;
            }
            Document doc = byId.get(entry.getKey());
            if (doc != null) {
                out.add(doc);
            }
        }
        return out;
    }

    /** 查询改写：让 LLM 把问题改写为更适合向量检索的查询（经多模态 JSON 端返回） */
    private String llmRewrite(String query) {
        String prompt = "你是知识库查询改写器。把下面对用户的问题改写为更适合向量检索的中文查询，"
                + "保持原意，可补充同义词、做简短拆解，只输出改写结果，不要任何解释。\n"
                + "严格用 JSON 返回 {\"query\": \"改写后的查询\"}\n问题：" + query;
        String out = llmText("rag-query-rewrite", prompt);
        JsonNode root = parseJsonRoot(out);
        String q = root != null ? root.path("query").asText("") : "";
        return q.isBlank() ? null : q.trim();
    }

    /** HyDE：让 LLM 先生成一段"理想答案"文档，再以其做向量召回，弥合问题与文档的语义鸿沟 */
    private String llmHyde(String query) {
        String prompt = "你是知识库助手。根据以下问题，假设知识库里有对应内容，写一段简洁、全面的"
                + "参考答案正文（100~300 字，陈述已知事实，不要提及‘没有/不存在’之类）。\n"
                + "严格用 JSON 返回 {\"document\": \"参考答案正文\"}\n问题：" + query;
        String out = llmText("rag-hyde", prompt);
        JsonNode root = parseJsonRoot(out);
        String doc = root != null ? root.path("document").asText("") : "";
        return doc.isBlank() ? null : doc.trim();
    }

    /** LLM 重排：把 RRF 后的候选片段喂给 LLM 打 0-10 相关分并重排，取 topN；解析/调用失败则保持原序 */
    private List<RetrievedChunk> llmRerank(String query, List<RetrievedChunk> chunks, int topN) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("评估下面每个知识片段与问题的相关度，给出 0-10 整数相关分（0 最不相关，10 最相关）。\n")
                .append("严格用 JSON 返回 {\"scores\": [与片段数量一致的整数数组，按输入顺序逐位给分]}，不要解释。\n")
                .append("问题：").append(query).append("\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append(i + 1).append(". ").append(truncate(chunks.get(i).excerpt(), 300)).append("\n");
        }
        String out = llmText("rag-rerank", sb.toString());
        JsonNode root = parseJsonRoot(out);
        JsonNode scores = root != null ? root.path("scores") : null;
        if (scores == null || !scores.isArray()) {
            log.warn("LLM 重排解析失败，保持原序: candidates={}", chunks.size());
            return chunks.subList(0, Math.min(topN, chunks.size()));
        }
        Float[] ranked = new Float[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            JsonNode s = i < scores.size() ? scores.get(i) : null;
            ranked[i] = s != null ? (float) s.asDouble(0) : 0f;
        }
        // 以分数降序稳定重排（分数相同的保持原相对顺序）
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ranked.length; i++) {
            order.add(i);
        }
        order.sort((i, j) -> Float.compare(ranked[j], ranked[i]));

        List<RetrievedChunk> result = new ArrayList<>();
        for (Integer idx : order) {
            if (result.size() >= topN) {
                break;
            }
            result.add(chunks.get(idx));
        }
        log.info("LLM 重排完成: {} -> {}", chunks.size(), result.size());
        return result;
    }

    /** 增强检索的 LLM 调用统一封装：短超时、不重试，失败返回 null 让调用方跳过该阶段 */
    private String llmText(String agent, String prompt) {
        try {
            return LlmCallWrapper.callWithRetry(agent,
                    () -> llmClient.callText(llmModel, prompt, 0.1f),
                    () -> null, RAG_LLM_TIMEOUT_SECONDS, 0);
        } catch (Exception e) {
            log.warn("增强检索 LLM 调用失败（{}），跳过该阶段", agent, e);
            return null;
        }
    }

    /** 宽容解析 LLM 返回：仅截取首个 '{' 到末个 '}'，容错多余的前缀/后缀文字 */
    private JsonNode parseJsonRoot(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("增强检索 LLM 返回非 JSON，忽略: {}", truncate(raw, 200));
            return null;
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
