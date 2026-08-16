package com.interview.agent.observability;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 调用追踪记录（llm_trace 表）。
 * 每次 ChatModel 调用落一行：成功/失败均记录，重试产生多行（成本按全量求和）。
 * kind=retrieval 的行是知识库检索 span（token/成本恒为 0）；
 * traceId 为轮次关联 ID，同轮的 llm/retrieval 行共享，evalScore 由评分完成后回写。
 */
public class LlmTrace {
    private Long id;
    private String sessionId;
    /** 轮次关联 ID：CoordinatorNode 派发新题时生成，同轮多次调用共享（追问轮沿用主轮） */
    private String traceId;
    private String agent;
    /** llm=LLM 调用 / retrieval=知识库检索 span */
    private String kind = "llm";
    /** 评分回写：本轮评估调整分 */
    private Integer evalScore;
    private String model;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private long durationMs;
    /** success / error */
    private String status;
    private String errorMsg;
    private BigDecimal estimatedCost;
    private String promptExcerpt;
    private String completionExcerpt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Integer getEvalScore() {
        return evalScore;
    }

    public void setEvalScore(Integer evalScore) {
        this.evalScore = evalScore;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public String getPromptExcerpt() {
        return promptExcerpt;
    }

    public void setPromptExcerpt(String promptExcerpt) {
        this.promptExcerpt = promptExcerpt;
    }

    public String getCompletionExcerpt() {
        return completionExcerpt;
    }

    public void setCompletionExcerpt(String completionExcerpt) {
        this.completionExcerpt = completionExcerpt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
