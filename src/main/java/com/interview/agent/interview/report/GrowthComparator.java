package com.interview.agent.interview.report;

import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.interview.model.InterviewSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class GrowthComparator {
    private static final Logger log = LoggerFactory.getLogger(GrowthComparator.class);
    private final InterviewSessionMapper sessionMapper;

    public GrowthComparator(InterviewSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /**
     * 获取成长对比数据
     */
    public GrowthComparison compare(String currentSessionId) {
        InterviewSession current = sessionMapper.findById(currentSessionId);
        if (current == null || current.getOverallScore() == null) {
            return new GrowthComparison(null, null, null);
        }

        // 查找历史最近一场有分数的面试
        List<InterviewSession> history = sessionMapper.findByUserId(current.getUserId());
        InterviewSession previous = history.stream()
                .filter(s -> !s.getId().equals(currentSessionId))
                .filter(s -> s.getOverallScore() != null)
                .findFirst()
                .orElse(null);

        if (previous == null) {
            return new GrowthComparison(null, current.getOverallScore(), null);
        }

        BigDecimal improvement = current.getOverallScore().subtract(previous.getOverallScore());
        return new GrowthComparison(previous.getOverallScore(), current.getOverallScore(), improvement);
    }

    public static class GrowthComparison {
        private final BigDecimal previousScore;
        private final BigDecimal currentScore;
        private final BigDecimal improvement;

        public GrowthComparison(BigDecimal previousScore, BigDecimal currentScore, BigDecimal improvement) {
            this.previousScore = previousScore;
            this.currentScore = currentScore;
            this.improvement = improvement;
        }

        public BigDecimal getPreviousScore() { return previousScore; }
        public BigDecimal getCurrentScore() { return currentScore; }
        public BigDecimal getImprovement() { return improvement; }
        public boolean hasGrowth() { return previousScore != null && currentScore != null; }
    }
}