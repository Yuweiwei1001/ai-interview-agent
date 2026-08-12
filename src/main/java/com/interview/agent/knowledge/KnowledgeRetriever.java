package com.interview.agent.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索器（非工具类版本）：面试出题/评估时确定性调用，检索结果格式化后注入 Prompt。
 * 输出格式对齐 ThinkVerse SearchKnowledgeTool：【标题】\n内容（截断500字），片段间 --- 分隔。
 */
@Component
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    private final VectorStore vectorStore;

    public KnowledgeRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 检索指定知识库中与 query 最相关的知识片段并格式化。
     * 无结果或异常时返回 null（调用方据此决定是否注入 Prompt）。
     */
    public String search(Long knowledgeBaseId, String query, int topK) {
        if (knowledgeBaseId == null || query == null || query.isBlank()) {
            return null;
        }
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
            return formatted;
        } catch (Exception e) {
            log.error("知识库检索失败: kbId={}, query='{}'", knowledgeBaseId, query, e);
            return null;
        }
    }
}
