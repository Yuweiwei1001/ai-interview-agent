package com.interview.agent.interview;

import com.interview.agent.interview.plan.InterviewPlan;
import jakarta.validation.constraints.Min;

public class InterviewStartDTO {
    private Long resumeId;
    private Long jdId;
    private String direction;
    private String persona;
    @Min(value = 1, message = "面试时长至少1分钟")
    private int durationMinutes = 30;
    /** 可选：前端预览阶段已生成的面试计划，原样透传复用，避免启动时重新生成导致出题与展示的计划不一致 */
    private InterviewPlan plan;
    /** 交互模式：TEXT（文字面试，默认）/ VOICE（语音面试，ASR 识别 + TTS 播报） */
    private String phase = "TEXT";

    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long resumeId) { this.resumeId = resumeId; }
    public Long getJdId() { return jdId; }
    public void setJdId(Long jdId) { this.jdId = jdId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public InterviewPlan getPlan() { return plan; }
    public void setPlan(InterviewPlan plan) { this.plan = plan; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
}
