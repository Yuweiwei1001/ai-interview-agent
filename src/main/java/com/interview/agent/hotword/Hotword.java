package com.interview.agent.hotword;

import java.time.LocalDateTime;

/** 会话级热词（简历/JD/面试计划抽取的技术术语） */
public class Hotword {
    private Long id;
    private Long userId;
    /** resume / jd / plan */
    private String sourceType;
    /** resume.id / jd.id / interview_plan 所在 session.id */
    private Long sourceId;
    /** 规范写法，如 Redis、Spring Boot */
    private String term;
    private String category;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
