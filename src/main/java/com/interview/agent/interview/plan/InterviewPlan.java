package com.interview.agent.interview.plan;

import java.util.List;
import java.util.Map;

public class InterviewPlan {
    private String overallStrategy;
    private Map<String, AgentAssignment> agentAssignments;
    private List<String> weakPointPriority;
    private int estimatedTotalRounds;
    /** 本场面试相关技术术语（ASR 热词纠错方案 4.1.1：计划生成顺带输出，零额外 LLM 调用） */
    private List<String> hotwords;

    public String getOverallStrategy() { return overallStrategy; }
    public void setOverallStrategy(String overallStrategy) { this.overallStrategy = overallStrategy; }
    public Map<String, AgentAssignment> getAgentAssignments() { return agentAssignments; }
    public void setAgentAssignments(Map<String, AgentAssignment> agentAssignments) { this.agentAssignments = agentAssignments; }
    public List<String> getWeakPointPriority() { return weakPointPriority; }
    public void setWeakPointPriority(List<String> weakPointPriority) { this.weakPointPriority = weakPointPriority; }
    public int getEstimatedTotalRounds() { return estimatedTotalRounds; }
    public void setEstimatedTotalRounds(int estimatedTotalRounds) { this.estimatedTotalRounds = estimatedTotalRounds; }
    public List<String> getHotwords() { return hotwords; }
    public void setHotwords(List<String> hotwords) { this.hotwords = hotwords; }

    public static class AgentAssignment {
        // LLM 结构化输出恒返回字符串数组，必须用 List 接收（String 会反序列化失败走兜底计划）
        private List<String> topics;
        private String difficulty;
        private int estimatedRounds;

        public List<String> getTopics() { return topics; }
        public void setTopics(List<String> topics) { this.topics = topics; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
        public int getEstimatedRounds() { return estimatedRounds; }
        public void setEstimatedRounds(int estimatedRounds) { this.estimatedRounds = estimatedRounds; }
    }
}
