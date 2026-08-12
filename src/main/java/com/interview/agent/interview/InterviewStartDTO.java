package com.interview.agent.interview;

import jakarta.validation.constraints.Min;

public class InterviewStartDTO {
    private Long resumeId;
    private Long jdId;
    private String direction;
    private String persona;
    @Min(value = 1, message = "面试时长至少1分钟")
    private int durationMinutes = 30;
    /** 可选：关联知识库，面试出题/评估时检索注入 */
    private Long knowledgeBaseId;

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
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
}
