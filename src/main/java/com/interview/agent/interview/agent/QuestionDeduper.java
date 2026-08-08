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
        Set<String> keywords = new HashSet<>();
        String lower = text.toLowerCase();
        // 1. 英文单词 / 数字 / 驼峰词
        for (String token : lower.split("[^a-z0-9]+")) {
            if (token.length() > 1) {
                keywords.add(token);
            }
        }
        // 2. 中文连续片段按 2-gram 切分（有效捕捉中文主题相似度）
        for (String seg : lower.split("[^\\u4e00-\\u9fa5]+")) {
            if (seg.length() < 2) continue;
            for (int i = 0; i < seg.length() - 1; i++) {
                String gram = seg.substring(i, i + 2);
                // 过滤常见停用 2-gram（“题目”“请问”“解释”“说明”等无意义词元）
                if (!STOP_GRAMS.contains(gram)) {
                    keywords.add(gram);
                }
            }
        }
        return keywords;
    }

    /** 高频停用词元：不参与相似度计算，避免“请解释/请说明/你如何”等通用表述拉高相似度 */
    private static final Set<String> STOP_GRAMS = Set.of(
            "题目", "请问", "请结", "结合", "说明", "解释", "介绍", "描述", "谈谈", "阐述", "详细",
            "如何", "怎么", "为什么", "哪些", "什么", "一个", "以及", "请你", "给出", "要求",
            "候选", "回答", "问题", "面试", "技术", "设计", "系统", "项目", "简历", "实际", "真实",
            "过程", "情况", "场景", "方案", "实现", "原理", "底层", "核心", "关键", "主要", "具体",
            "相关", "方面", "维度", "分析", "考虑", "权衡", "策略", "机制", "架构", "流程", "思路"
    );

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
