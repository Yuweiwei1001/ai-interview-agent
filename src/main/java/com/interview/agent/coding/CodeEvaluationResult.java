package com.interview.agent.coding;

import java.util.List;

/**
 * 代码多维评估结果
 * correctness / codeQuality / edgeCaseHandling / timeComplexity 由 LLM 评估，
 * testPassRate 为沙箱测试客观通过率，overallScore 为加权综合评分
 */
public class CodeEvaluationResult {
    private int correctness;       // 正确性 0-100
    private int codeQuality;       // 代码质量 0-100
    private int edgeCaseHandling;  // 边界处理 0-100
    private int timeComplexity;    // 时间复杂度 0-100
    private int testPassRate;      // 测试通过率 0-100
    private int overallScore;      // 综合评分
    private List<String> suggestions;
    private String summary;
    private boolean degraded;       // LLM 评估不可用时标记为降级评分（提示候选人非代码问题）

    public int getCorrectness() { return correctness; }
    public void setCorrectness(int correctness) { this.correctness = correctness; }
    public int getCodeQuality() { return codeQuality; }
    public void setCodeQuality(int codeQuality) { this.codeQuality = codeQuality; }
    public int getEdgeCaseHandling() { return edgeCaseHandling; }
    public void setEdgeCaseHandling(int edgeCaseHandling) { this.edgeCaseHandling = edgeCaseHandling; }
    public int getTimeComplexity() { return timeComplexity; }
    public void setTimeComplexity(int timeComplexity) { this.timeComplexity = timeComplexity; }
    public int getTestPassRate() { return testPassRate; }
    public void setTestPassRate(int testPassRate) { this.testPassRate = testPassRate; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int overallScore) { this.overallScore = overallScore; }
    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public boolean isDegraded() { return degraded; }
    public void setDegraded(boolean degraded) { this.degraded = degraded; }
}
