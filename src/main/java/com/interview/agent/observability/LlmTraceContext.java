package com.interview.agent.observability;

/**
 * LLM 调用追踪上下文（不可变值对象）。
 * 携带 agent 名称、面试 sessionId 与轮次 traceId，用于 llm_trace 归因与同轮调用串联。
 */
public final class LlmTraceContext {
    private final String agent;
    private final String sessionId;
    /** 轮次关联 ID：同轮多次调用（出题/检索/评分/追问）共享，可为 null（图外调用） */
    private final String roundTraceId;

    public LlmTraceContext(String agent, String sessionId) {
        this(agent, sessionId, null);
    }

    public LlmTraceContext(String agent, String sessionId, String roundTraceId) {
        this.agent = agent;
        this.sessionId = sessionId;
        this.roundTraceId = roundTraceId;
    }

    public String getAgent() {
        return agent;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRoundTraceId() {
        return roundTraceId;
    }

    public LlmTraceContext withAgent(String newAgent) {
        return new LlmTraceContext(newAgent, this.sessionId, this.roundTraceId);
    }

    public LlmTraceContext withSessionId(String newSessionId) {
        return new LlmTraceContext(this.agent, newSessionId, this.roundTraceId);
    }

    public LlmTraceContext withRoundTraceId(String newRoundTraceId) {
        return new LlmTraceContext(this.agent, this.sessionId, newRoundTraceId);
    }

    @Override
    public String toString() {
        return "LlmTraceContext{agent='" + agent + "', sessionId='" + sessionId
                + "', roundTraceId='" + roundTraceId + "'}";
    }
}
