package com.interview.agent.eval.metrics;

import com.interview.agent.eval.EvalDatasetLoader.CalibrationSample;
import com.interview.agent.interview.agent.AnswerEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Judge 校准器：验证系统内评分器（AnswerEvaluator）与人工标注的一致性。
 * LLM judge 本身必须被验证——用人工标注的 golden QA 样本检查评分是否落入预期档位，
 * 一致率过低说明评分 prompt 需要调整（对齐业界 judge alignment 实践）。
 */
@Component
public class JudgeCalibrator {
    private static final Logger log = LoggerFactory.getLogger(JudgeCalibrator.class);
    private final AnswerEvaluator answerEvaluator;

    public JudgeCalibrator(AnswerEvaluator answerEvaluator) {
        this.answerEvaluator = answerEvaluator;
    }

    public record CalibrationDetail(int index, String expectedLevel, int expectedBucket,
                                    int actualScore, int actualBucket, boolean exactMatch,
                                    boolean relaxedMatch, String summary) {}

    public static class CalibrationResult {
        private int sampleCount;
        /** 精确一致率：评分落入人工标注的同一档位 */
        private double exactAgreementRate;
        /** 宽松一致率：评分档位与人工标注相差不超过 1 档 */
        private double relaxedAgreementRate;
        private List<CalibrationDetail> details = new ArrayList<>();

        public int getSampleCount() { return sampleCount; }
        public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
        public double getExactAgreementRate() { return exactAgreementRate; }
        public void setExactAgreementRate(double v) { this.exactAgreementRate = v; }
        public double getRelaxedAgreementRate() { return relaxedAgreementRate; }
        public void setRelaxedAgreementRate(double v) { this.relaxedAgreementRate = v; }
        public List<CalibrationDetail> getDetails() { return details; }
        public void setDetails(List<CalibrationDetail> details) { this.details = details; }
    }

    /** 档位定义与 AnswerEvaluator 评分标准一致：EXCELLENT 90-100 / GOOD 70-89 / AVERAGE 50-69 / FAIL 0-49 */
    private int bucketOf(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "EXCELLENT" -> 3;
            case "GOOD" -> 2;
            case "AVERAGE" -> 1;
            case "FAIL" -> 0;
            default -> -1;
        };
    }

    private int bucketOfScore(int score) {
        if (score >= 90) return 3;
        if (score >= 70) return 2;
        if (score >= 50) return 1;
        return 0;
    }

    public CalibrationResult calibrate(List<CalibrationSample> samples) {
        CalibrationResult result = new CalibrationResult();
        if (samples == null || samples.isEmpty()) {
            log.warn("judge 校准集为空，跳过校准");
            return result;
        }

        int exact = 0;
        int relaxed = 0;
        int valid = 0;
        for (int i = 0; i < samples.size(); i++) {
            CalibrationSample sample = samples.get(i);
            int expectedBucket = bucketOf(sample.expectedLevel());
            if (expectedBucket < 0) {
                log.warn("校准样本 expectedLevel 非法，跳过: index={}, level={}", i, sample.expectedLevel());
                continue;
            }
            AnswerEvaluator.EvaluationResult eval;
            try {
                eval = answerEvaluator.evaluate(sample.question(), sample.answer());
            } catch (Exception e) {
                log.warn("校准样本评分失败: index={}", i, e);
                continue;
            }
            valid++;
            int actualBucket = bucketOfScore(eval.score());
            boolean exactMatch = actualBucket == expectedBucket;
            boolean relaxedMatch = Math.abs(actualBucket - expectedBucket) <= 1;
            if (exactMatch) exact++;
            if (relaxedMatch) relaxed++;
            result.getDetails().add(new CalibrationDetail(i, sample.expectedLevel(), expectedBucket,
                    eval.score(), actualBucket, exactMatch, relaxedMatch, eval.summary()));
        }

        result.setSampleCount(valid);
        result.setExactAgreementRate(valid == 0 ? 0 : (double) exact / valid);
        result.setRelaxedAgreementRate(valid == 0 ? 0 : (double) relaxed / valid);
        return result;
    }
}
