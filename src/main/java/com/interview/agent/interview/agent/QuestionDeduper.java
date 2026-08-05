package com.interview.agent.interview.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 题目去重器
 * 基于已问主题摘要，强制约束出题 prompt 避免重复
 */
@Component
public class QuestionDeduper {
    private static final Logger log = LoggerFactory.getLogger(QuestionDeduper.class);

    /**
     * 生成已问主题摘要，用于注入出题 prompt
     */
    public String buildAskedTopicsSummary(List<String> askedTopics) {
        if (askedTopics == null || askedTopics.isEmpty()) {
            return "暂无已考察主题";
        }
        return "已考察主题（请避免重复）：" + String.join("、", askedTopics);
    }

    /**
     * 检查新题目是否与已有题目重复（基于简单关键词匹配）
     */
    public boolean isDuplicate(String newQuestion, List<String> existingQuestions) {
        if (newQuestion == null || existingQuestions == null || existingQuestions.isEmpty()) {
            return false;
        }

        // 提取关键词
        Set<String> newKeywords = extractKeywords(newQuestion);

        for (String existing : existingQuestions) {
            Set<String> existingKeywords = extractKeywords(existing);
            // 计算 Jaccard 相似度
            double similarity = jaccardSimilarity(newKeywords, existingKeywords);
            if (similarity > 0.5) {
                log.debug("题目重复检测: similarity={}", similarity);
                return true;
            }
        }
        return false;
    }

    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        // 简单分词：按标点和空格分割，过滤短词
        return Arrays.stream(text.toLowerCase()
                .replaceAll("[，。！？、；：\"\"''（）【】《》\\[\\]\\(\\)\\{\\}]", " ")
                .split("\\s+"))
                .filter(w -> w.length() > 1)
                .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
