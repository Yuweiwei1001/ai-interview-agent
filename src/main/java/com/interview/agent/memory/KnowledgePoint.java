package com.interview.agent.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class KnowledgePoint {
    private Long id;
    private Long userId;
    private String topic;
    private String status; // mastered/weak/untested
    private BigDecimal confidence;
    private LocalDateTime lastAssessed;
    private Integer assessmentCount;
    private Boolean verified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public LocalDateTime getLastAssessed() { return lastAssessed; }
    public void setLastAssessed(LocalDateTime lastAssessed) { this.lastAssessed = lastAssessed; }
    public Integer getAssessmentCount() { return assessmentCount; }
    public void setAssessmentCount(Integer assessmentCount) { this.assessmentCount = assessmentCount; }
    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}