package com.interview.agent.chat;

import java.time.LocalDateTime;

/** 问答消息：role=user 提问 / role=assistant 回答（answers 附引用来源 JSON） */
public class ChatMessage {
    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    /** assistant 消息的引用来源 JSON：[{docId,title,excerpt}]，user 消息为 null */
    private String sources;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSources() { return sources; }
    public void setSources(String sources) { this.sources = sources; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
