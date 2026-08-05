package com.interview.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识点归一化器
 * 三阶段：①精确匹配（去空格标点小写）→ ②embedding 余弦（暂用精确匹配替代）→ ③新建
 */
@Component
public class KnowledgePointNormalizer {
    private static final Logger log = LoggerFactory.getLogger(KnowledgePointNormalizer.class);
    private static final double SIMILARITY_THRESHOLD = 0.85;

    /**
     * 归一化知识点：找到匹配的已有知识点或新建
     * @return 归一化后的 topic 名称
     */
    public String normalize(String rawTopic, List<String> existingTopics) {
        if (rawTopic == null || rawTopic.isBlank()) return "unknown";
        if (existingTopics == null || existingTopics.isEmpty()) return rawTopic;

        // 阶段1：精确匹配（去空格、标点、小写）
        String normalized = normalizeText(rawTopic);
        for (String existing : existingTopics) {
            if (normalizeText(existing).equals(normalized)) {
                log.debug("精确匹配: {} -> {}", rawTopic, existing);
                return existing;
            }
        }

        // 阶段2：相似度匹配（暂用精确匹配的简化版，后续可接入 embedding）
        for (String existing : existingTopics) {
            double similarity = calculateSimilarity(rawTopic, existing);
            if (similarity >= SIMILARITY_THRESHOLD) {
                log.debug("相似度匹配: {} ~ {} (similarity={})", rawTopic, existing, similarity);
                return existing;
            }
        }

        // 阶段3：新建
        log.debug("新建知识点: {}", rawTopic);
        return rawTopic;
    }

    /**
     * 批量归一化
     */
    public List<String> normalizeBatch(List<String> rawTopics, List<String> existingTopics) {
        if (rawTopics == null) return Collections.emptyList();
        return rawTopics.stream()
                .map(t -> normalize(t, existingTopics))
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replaceAll("[\\s,，。；:：、！!？?（）()【】\\[\\]《》{}「」『』\"'']", "")
                .trim();
    }

    /**
     * 简单文本相似度计算（基于公共子串）
     * 后续可替换为 embedding 余弦相似度
     */
    private double calculateSimilarity(String a, String b) {
        String normA = normalizeText(a);
        String normB = normalizeText(b);
        if (normA.isEmpty() && normB.isEmpty()) return 1.0;
        if (normA.isEmpty() || normB.isEmpty()) return 0.0;

        // 最长公共子串
        String longer = normA.length() >= normB.length() ? normA : normB;
        String shorter = normA.length() < normB.length() ? normA : normB;

        int maxLen = 0;
        for (int i = 0; i < shorter.length(); i++) {
            for (int j = i + 1; j <= shorter.length(); j++) {
                String sub = shorter.substring(i, j);
                if (longer.contains(sub) && sub.length() > maxLen) {
                    maxLen = sub.length();
                }
            }
        }

        return (double) maxLen / longer.length();
    }
}