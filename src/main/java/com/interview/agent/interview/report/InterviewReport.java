package com.interview.agent.interview.report;

import java.math.BigDecimal;
import java.util.List;

public class InterviewReport {
    private BigDecimal overallScore;
    private DimensionScores dimensionScores;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> suggestions;
    private List<QuestionFeedback> perQuestionFeedback;
    private GrowthData growthComparison;

    public BigDecimal getOverallScore() { return overallScore; }
    public void setOverallScore(BigDecimal overallScore) { this.overallScore = overallScore; }
    public DimensionScores getDimensionScores() { return dimensionScores; }
    public void setDimensionScores(DimensionScores dimensionScores) { this.dimensionScores = dimensionScores; }
    public List<String> getStrengths() { return strengths; }
    public void setStrengths(List<String> strengths) { this.strengths = strengths; }
    public List<String> getWeaknesses() { return weaknesses; }
    public void setWeaknesses(List<String> weaknesses) { this.weaknesses = weaknesses; }
    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
    public List<QuestionFeedback> getPerQuestionFeedback() { return perQuestionFeedback; }
    public void setPerQuestionFeedback(List<QuestionFeedback> perQuestionFeedback) { this.perQuestionFeedback = perQuestionFeedback; }
    public GrowthData getGrowthComparison() { return growthComparison; }
    public void setGrowthComparison(GrowthData growthComparison) { this.growthComparison = growthComparison; }

    public static class DimensionScores {
        private BigDecimal technical;
        private BigDecimal project;
        private BigDecimal coding;
        private BigDecimal communication;

        public BigDecimal getTechnical() { return technical; }
        public void setTechnical(BigDecimal technical) { this.technical = technical; }
        public BigDecimal getProject() { return project; }
        public void setProject(BigDecimal project) { this.project = project; }
        public BigDecimal getCoding() { return coding; }
        public void setCoding(BigDecimal coding) { this.coding = coding; }
        public BigDecimal getCommunication() { return communication; }
        public void setCommunication(BigDecimal communication) { this.communication = communication; }
    }

    public static class QuestionFeedback {
        private int roundNumber;
        private String question;
        private String answer;
        private BigDecimal score;
        private String feedback;

        public int getRoundNumber() { return roundNumber; }
        public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public BigDecimal getScore() { return score; }
        public void setScore(BigDecimal score) { this.score = score; }
        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
    }

    public static class GrowthData {
        private BigDecimal previousScore;
        private BigDecimal currentScore;
        private BigDecimal improvement;

        public BigDecimal getPreviousScore() { return previousScore; }
        public void setPreviousScore(BigDecimal previousScore) { this.previousScore = previousScore; }
        public BigDecimal getCurrentScore() { return currentScore; }
        public void setCurrentScore(BigDecimal currentScore) { this.currentScore = currentScore; }
        public BigDecimal getImprovement() { return improvement; }
        public void setImprovement(BigDecimal improvement) { this.improvement = improvement; }
    }
}
