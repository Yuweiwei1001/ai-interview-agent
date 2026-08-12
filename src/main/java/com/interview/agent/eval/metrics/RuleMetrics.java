package com.interview.agent.eval.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则类（确定性）指标结果：快、便宜、可复现，适合回归门禁。
 * 指标命名对齐业界 Agent 评测框架（Ragas/DeepEval）的概念：
 * - goalAccuracy        → 计划完成度（轮次达成 + 完整走完 + 编程题恰好 1 道）
 * - topicAdherence      → 编程题是否跑题到系统设计（护栏有效性证明）
 * - duplication/coverage → 出题质量
 */
public class RuleMetrics {
    private String sessionId;
    private String finalStatus;
    private boolean completed;
    private boolean driverTimeout;

    /** 计划预估轮次（maxRounds） */
    private int planRounds;
    /** 实际主轮次数（不含追问轮） */
    private int actualMainRounds;
    /** 轮次达成率 = min(actual/plan, 1.0) */
    private double roundAdherence;
    /** 目标达成：状态 completed 且轮次达成率 >= 0.8 且编程题恰好 1 道 */
    private boolean goalAchieved;

    private int codingRoundCount;
    /** 编程题跑题数（命中系统设计关键词，应被护栏拦截） */
    private int codingOffTopicCount;

    private int followUpCount;
    private double followUpRate;

    /** 相似重复题目对数 */
    private int duplicateQuestionPairs;
    private double questionDuplicateRate;

    /** 计划主题覆盖率（计划主题在轮次主题/题目中的命中比例） */
    private double topicCoverageRatio;
    private List<String> uncoveredTopics = new ArrayList<>();

    /** 评估降级轮数（evaluation JSON 中 degraded=true） */
    private int degradedRoundCount;
    private double degradedRate;

    /** 超时未回答数 */
    private int timeoutAnswerCount;

    private double avgScore;
    private long durationMs;

    // getters/setters
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getFinalStatus() { return finalStatus; }
    public void setFinalStatus(String finalStatus) { this.finalStatus = finalStatus; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public boolean isDriverTimeout() { return driverTimeout; }
    public void setDriverTimeout(boolean driverTimeout) { this.driverTimeout = driverTimeout; }
    public int getPlanRounds() { return planRounds; }
    public void setPlanRounds(int planRounds) { this.planRounds = planRounds; }
    public int getActualMainRounds() { return actualMainRounds; }
    public void setActualMainRounds(int actualMainRounds) { this.actualMainRounds = actualMainRounds; }
    public double getRoundAdherence() { return roundAdherence; }
    public void setRoundAdherence(double roundAdherence) { this.roundAdherence = roundAdherence; }
    public boolean isGoalAchieved() { return goalAchieved; }
    public void setGoalAchieved(boolean goalAchieved) { this.goalAchieved = goalAchieved; }
    public int getCodingRoundCount() { return codingRoundCount; }
    public void setCodingRoundCount(int codingRoundCount) { this.codingRoundCount = codingRoundCount; }
    public int getCodingOffTopicCount() { return codingOffTopicCount; }
    public void setCodingOffTopicCount(int codingOffTopicCount) { this.codingOffTopicCount = codingOffTopicCount; }
    public int getFollowUpCount() { return followUpCount; }
    public void setFollowUpCount(int followUpCount) { this.followUpCount = followUpCount; }
    public double getFollowUpRate() { return followUpRate; }
    public void setFollowUpRate(double followUpRate) { this.followUpRate = followUpRate; }
    public int getDuplicateQuestionPairs() { return duplicateQuestionPairs; }
    public void setDuplicateQuestionPairs(int duplicateQuestionPairs) { this.duplicateQuestionPairs = duplicateQuestionPairs; }
    public double getQuestionDuplicateRate() { return questionDuplicateRate; }
    public void setQuestionDuplicateRate(double questionDuplicateRate) { this.questionDuplicateRate = questionDuplicateRate; }
    public double getTopicCoverageRatio() { return topicCoverageRatio; }
    public void setTopicCoverageRatio(double topicCoverageRatio) { this.topicCoverageRatio = topicCoverageRatio; }
    public List<String> getUncoveredTopics() { return uncoveredTopics; }
    public void setUncoveredTopics(List<String> uncoveredTopics) { this.uncoveredTopics = uncoveredTopics; }
    public int getDegradedRoundCount() { return degradedRoundCount; }
    public void setDegradedRoundCount(int degradedRoundCount) { this.degradedRoundCount = degradedRoundCount; }
    public double getDegradedRate() { return degradedRate; }
    public void setDegradedRate(double degradedRate) { this.degradedRate = degradedRate; }
    public int getTimeoutAnswerCount() { return timeoutAnswerCount; }
    public void setTimeoutAnswerCount(int timeoutAnswerCount) { this.timeoutAnswerCount = timeoutAnswerCount; }
    public double getAvgScore() { return avgScore; }
    public void setAvgScore(double avgScore) { this.avgScore = avgScore; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
