package com.interview.agent.observability;

/**
 * LLM 调用追踪上下文（不可变值对象）。
 * 携带 agent 名称与面试 sessionId，用于 llm_trace 归因。
 */
public final class LlmTraceContext {
    private final String agent;
    private final String sessionId;

    public LlmTraceContext(String agent, String sessionId) {
        this.agent = agent;
        this.sessionId = sessionId;
    }

    public String getAgent() {
        return agent;
    }

    public String getSessionId() {
        return sessionId;
    }

    public LlmTraceContext withAgent(String newAgent) {
        return new LlmTraceContext(newAgent, this.sessionId);
    }

    public LlmTraceContext withSessionId(String newSessionId) {
        return new LlmTraceContext(this.agent, newSessionId);
    }

    @Override
    public String toString() {
        return "LlmTraceContext{agent='" + agent + "', sessionId='" + sessionId + "'}";
    }
}
