package com.interview.agent.interview.model;

import java.time.LocalDateTime;

public class InterviewRound {
    private Long id;
    private String sessionId;
    private Integer roundNumber;
    private String agentName;
    private String topic;
    private String question;
    private String candidateAnswer;
    private String evaluation; // JSON string
    private Boolean isFollowup;
    private Long followupTarget;
    private LocalDateTime createdAt;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Integer getRoundNumber() { return roundNumber; }
    public void setRoundNumber(Integer roundNumber) { this.roundNumber = roundNumber; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getCandidateAnswer() { return candidateAnswer; }
    public void setCandidateAnswer(String candidateAnswer) { this.candidateAnswer = candidateAnswer; }
    public String getEvaluation() { return evaluation; }
    public void setEvaluation(String evaluation) { this.evaluation = evaluation; }
    public Boolean getIsFollowup() { return isFollowup; }
    public void setIsFollowup(Boolean isFollowup) { this.isFollowup = isFollowup; }
    public Long getFollowupTarget() { return followupTarget; }
    public void setFollowupTarget(Long followupTarget) { this.followupTarget = followupTarget; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
