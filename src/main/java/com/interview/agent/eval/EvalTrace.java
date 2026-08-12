package com.interview.agent.eval;

import com.interview.agent.interview.model.InterviewRound;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测轨迹：一场模拟面试的完整执行记录（对应业界的 trajectory 概念）。
 * 包含会话终态、时间线事件与全部轮次，是规则指标与 LLM-judge 的评测输入。
 */
public class EvalTrace {
    private String caseId;
    private String sessionId;
    /** 会话终态：completed / interrupted / waiting_code / in_progress */
    private String finalStatus;
    /** 驱动层超时（与面试自身超时无关） */
    private boolean driverTimeout;
    /** 驱动层错误（启动失败等） */
    private String error;
    private long durationMs;
    private List<TimelineEvent> timeline = new ArrayList<>();
    private List<InterviewRound> rounds = new ArrayList<>();
    /** 会话中的面试计划 JSON（解析 InterviewPlan 用于计划符合度指标） */
    private String interviewPlanJson;

    public record TimelineEvent(long ts, String type, String detail) {}

    public void addEvent(String type, String detail) {
        timeline.add(new TimelineEvent(System.currentTimeMillis(), type, detail));
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getFinalStatus() { return finalStatus; }
    public void setFinalStatus(String finalStatus) { this.finalStatus = finalStatus; }
    public boolean isDriverTimeout() { return driverTimeout; }
    public void setDriverTimeout(boolean driverTimeout) { this.driverTimeout = driverTimeout; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public List<TimelineEvent> getTimeline() { return timeline; }
    public void setTimeline(List<TimelineEvent> timeline) { this.timeline = timeline; }
    public List<InterviewRound> getRounds() { return rounds; }
    public void setRounds(List<InterviewRound> rounds) { this.rounds = rounds; }
    public String getInterviewPlanJson() { return interviewPlanJson; }
    public void setInterviewPlanJson(String interviewPlanJson) { this.interviewPlanJson = interviewPlanJson; }
}
