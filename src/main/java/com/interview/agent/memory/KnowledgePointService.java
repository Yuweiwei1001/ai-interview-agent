package com.interview.agent.memory;

import com.interview.agent.common.context.BaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KnowledgePointService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgePointService.class);
    private final KnowledgePointMapper mapper;
    private final KnowledgePointNormalizer normalizer;

    public KnowledgePointService(KnowledgePointMapper mapper, KnowledgePointNormalizer normalizer) {
        this.mapper = mapper;
        this.normalizer = normalizer;
    }

    /**
     * 从评估结果中更新知识点（Mem0 风格 UPDATE 语义：新证据与历史加权融合，而非直接覆盖）。
     * 置信度 = 历史置信度按考察次数加上新分的移动平均，status 由融合后置信度判定，
     * 因此“上次薄弱、这次答好”会逐步修正旧结论。
     */
    public void updateFromEvaluation(List<String> knowledgePoints, Map<String, Object> evaluation) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) return;
        if (knowledgePoints == null || knowledgePoints.isEmpty()) return;

        // 获取已有知识点主题
        List<String> existingTopics = mapper.findByUserId(userId)
                .stream()
                .map(KnowledgePoint::getTopic)
                .collect(Collectors.toList());

        // 归一化
        List<String> normalizedTopics = normalizer.normalizeBatch(knowledgePoints, existingTopics);

        // 提取评估分数
        Object scoreObj = evaluation.get("score");
        int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 50;

        for (String topic : normalizedTopics) {
            KnowledgePoint point = new KnowledgePoint();
            point.setUserId(userId);
            point.setTopic(topic);

            // UPDATE 语义：已有记录则按历史考察次数加权融合置信度
            KnowledgePoint existing = mapper.findByUserIdAndTopic(userId, topic);
            int mergedConfidence;
            if (existing != null && existing.getAssessmentCount() != null && existing.getConfidence() != null) {
                int count = Math.max(1, existing.getAssessmentCount());
                int oldConfidence = existing.getConfidence().intValue();
                mergedConfidence = (int) Math.round((oldConfidence * (double) count + score) / (count + 1));
            } else {
                mergedConfidence = score;
            }

            point.setStatus(mergedConfidence >= 60 ? "mastered" : "weak");
            point.setConfidence(BigDecimal.valueOf(mergedConfidence));
            point.setLastAssessed(LocalDateTime.now());
            point.setAssessmentCount(1);
            point.setVerified(false);
            mapper.upsert(point);
            log.info("知识点更新: topic={}, score={}, mergedConfidence={}, status={}, sessionId 上下文 user={}",
                    topic, score, mergedConfidence, point.getStatus(), userId);
        }
    }

    /**
     * 获取薄弱知识点 TOP N
     */
    public List<KnowledgePoint> getWeakPoints(int limit) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) return List.of();
        return mapper.findWeakPoints(userId, limit);
    }

    /**
     * 获取最近未考察的知识点
     */
    public List<KnowledgePoint> getLeastAssessed(int limit) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) return List.of();
        return mapper.findLeastAssessed(userId, limit);
    }

    /**
     * 获取所有知识点摘要，用于计划生成
     */
    public String buildKnowledgeSummary() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) return "";

        List<KnowledgePoint> points = mapper.findByUserId(userId);
        if (points.isEmpty()) return "暂无历史知识点记录";

        StringBuilder sb = new StringBuilder("历史知识点记录：\n");
        sb.append("--- 薄弱点 ---\n");
        points.stream()
                .filter(p -> "weak".equals(p.getStatus()))
                .forEach(p -> sb.append("- ").append(p.getTopic())
                        .append("（信心度:").append(p.getConfidence())
                        .append("，考察次数:").append(p.getAssessmentCount())
                        .append("）\n"));
        sb.append("--- 掌握点 ---\n");
        points.stream()
                .filter(p -> "mastered".equals(p.getStatus()))
                .forEach(p -> sb.append("- ").append(p.getTopic())
                        .append("（信心度:").append(p.getConfidence())
                        .append("）\n"));
        return sb.toString();
    }
}