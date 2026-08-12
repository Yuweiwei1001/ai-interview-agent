package com.interview.agent.eval;

import com.interview.agent.eval.metrics.JudgeCalibrator;
import com.interview.agent.eval.metrics.LlmJudgeEvaluator;
import com.interview.agent.eval.metrics.RuleMetrics;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测报告：一次评测运行的完整结果。
 * 结构：逐用例（规则指标 + judge 指标）+ judge 校准结果 + 汇总指标。
 */
public class EvalReport {
    private String runId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String evalUsername;
    private List<CaseResult> caseResults = new ArrayList<>();
    private JudgeCalibrator.CalibrationResult calibration;
    private Aggregate aggregate;

    public static class CaseResult {
        private String caseId;
        private String description;
        private String answerLevel;
        /** 轨迹引用（含 sessionId、终态、时间线、轮次） */
        private EvalTrace trace;
        private RuleMetrics ruleMetrics;
        private LlmJudgeEvaluator.JudgeMetrics judgeMetrics;
        /** 用例驱动层错误 */
        private String error;

        public String getCaseId() { return caseId; }
        public void setCaseId(String caseId) { this.caseId = caseId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAnswerLevel() { return answerLevel; }
        public void setAnswerLevel(String answerLevel) { this.answerLevel = answerLevel; }
        public EvalTrace getTrace() { return trace; }
        public void setTrace(EvalTrace trace) { this.trace = trace; }
        public RuleMetrics getRuleMetrics() { return ruleMetrics; }
        public void setRuleMetrics(RuleMetrics ruleMetrics) { this.ruleMetrics = ruleMetrics; }
        public LlmJudgeEvaluator.JudgeMetrics getJudgeMetrics() { return judgeMetrics; }
        public void setJudgeMetrics(LlmJudgeEvaluator.JudgeMetrics judgeMetrics) { this.judgeMetrics = judgeMetrics; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /** 汇总指标：跨用例聚合，用作版本间回归对比 */
    public static class Aggregate {
        private int totalCases;
        /** 完成率：completed 用例占比 */
        private double completionRate;
        /** 目标达成率（goalAchieved 占比，对齐 Ragas AgentGoalAccuracy） */
        private double goalAchievedRate;
        private double avgRoundAdherence;
        private double avgQuestionDuplicateRate;
        private double avgTopicCoverage;
        private int totalCodingOffTopic;
        private double avgDegradedRate;
        private double avgQuestionRelevance;
        private double avgFollowUpQuality;

        public int getTotalCases() { return totalCases; }
        public void setTotalCases(int totalCases) { this.totalCases = totalCases; }
        public double getCompletionRate() { return completionRate; }
        public void setCompletionRate(double completionRate) { this.completionRate = completionRate; }
        public double getGoalAchievedRate() { return goalAchievedRate; }
        public void setGoalAchievedRate(double goalAchievedRate) { this.goalAchievedRate = goalAchievedRate; }
        public double getAvgRoundAdherence() { return avgRoundAdherence; }
        public void setAvgRoundAdherence(double avgRoundAdherence) { this.avgRoundAdherence = avgRoundAdherence; }
        public double getAvgQuestionDuplicateRate() { return avgQuestionDuplicateRate; }
        public void setAvgQuestionDuplicateRate(double v) { this.avgQuestionDuplicateRate = v; }
        public double getAvgTopicCoverage() { return avgTopicCoverage; }
        public void setAvgTopicCoverage(double avgTopicCoverage) { this.avgTopicCoverage = avgTopicCoverage; }
        public int getTotalCodingOffTopic() { return totalCodingOffTopic; }
        public void setTotalCodingOffTopic(int totalCodingOffTopic) { this.totalCodingOffTopic = totalCodingOffTopic; }
        public double getAvgDegradedRate() { return avgDegradedRate; }
        public void setAvgDegradedRate(double avgDegradedRate) { this.avgDegradedRate = avgDegradedRate; }
        public double getAvgQuestionRelevance() { return avgQuestionRelevance; }
        public void setAvgQuestionRelevance(double avgQuestionRelevance) { this.avgQuestionRelevance = avgQuestionRelevance; }
        public double getAvgFollowUpQuality() { return avgFollowUpQuality; }
        public void setAvgFollowUpQuality(double avgFollowUpQuality) { this.avgFollowUpQuality = avgFollowUpQuality; }
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getEvalUsername() { return evalUsername; }
    public void setEvalUsername(String evalUsername) { this.evalUsername = evalUsername; }
    public List<CaseResult> getCaseResults() { return caseResults; }
    public void setCaseResults(List<CaseResult> caseResults) { this.caseResults = caseResults; }
    public JudgeCalibrator.CalibrationResult getCalibration() { return calibration; }
    public void setCalibration(JudgeCalibrator.CalibrationResult calibration) { this.calibration = calibration; }
    public Aggregate getAggregate() { return aggregate; }
    public void setAggregate(Aggregate aggregate) { this.aggregate = aggregate; }
}
