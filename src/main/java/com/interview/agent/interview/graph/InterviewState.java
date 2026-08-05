package com.interview.agent.interview.graph;

import com.interview.agent.interview.plan.InterviewPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InterviewState {
    private String sessionId;
    private Long userId;
    private String resumeText;
    private String jdText;
    private String direction;
    private String persona;
    private int durationMinutes;
    private InterviewPlan plan;
    private int currentRound;
    private int maxRounds = 20;
    private List<RoundRecord> rounds;
    private String currentQuestion;
    private String currentAnswer;
    private String currentAgent;
    private String status; // in_progress, completed, interrupted
    private String phase = "TEXT"; // TEXT: 文字面试（跳过 Speaker），VOICE: 语音面试（Speaker 合成）

    public InterviewState() {
        this.rounds = new ArrayList<>();
        this.currentRound = 0;
        this.status = "in_progress";
    }

    // Getters and Setters
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getResumeText() { return resumeText; }
    public void setResumeText(String resumeText) { this.resumeText = resumeText; }
    public String getJdText() { return jdText; }
    public void setJdText(String jdText) { this.jdText = jdText; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public InterviewPlan getPlan() { return plan; }
    public void setPlan(InterviewPlan plan) { this.plan = plan; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public List<RoundRecord> getRounds() { return rounds; }
    public void setRounds(List<RoundRecord> rounds) { this.rounds = rounds; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public String getCurrentAnswer() { return currentAnswer; }
    public void setCurrentAnswer(String currentAnswer) { this.currentAnswer = currentAnswer; }
    public String getCurrentAgent() { return currentAgent; }
    public void setCurrentAgent(String currentAgent) { this.currentAgent = currentAgent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public static class RoundRecord {
        private int roundNumber;
        private String agentName;
        private String topic;
        private String question;
        private String answer;
        private Map<String, Object> evaluation;
        private boolean isFollowup;
        private Long followupTarget;

        public int getRoundNumber() { return roundNumber; }
        public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public Map<String, Object> getEvaluation() { return evaluation; }
        public void setEvaluation(Map<String, Object> evaluation) { this.evaluation = evaluation; }
        public boolean isFollowup() { return isFollowup; }
        public void setFollowup(boolean followup) { isFollowup = followup; }
        public Long getFollowupTarget() { return followupTarget; }
        public void setFollowupTarget(Long followupTarget) { this.followupTarget = followupTarget; }
    }
}
