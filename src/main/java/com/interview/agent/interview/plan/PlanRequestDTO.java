package com.interview.agent.interview.plan;

import jakarta.validation.constraints.Min;

public class PlanRequestDTO {
    private Long resumeId;
    private Long jdId;
    private String direction;
    private String persona;
    @Min(value = 1, message = "面试时长至少1分钟")
    private int durationMinutes = 30;

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
}
