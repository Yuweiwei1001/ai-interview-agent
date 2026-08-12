package com.interview.agent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测用例（golden case）：一场可复现的模拟面试。
 * 数据文件位于 resources/eval/dataset/*.json
 */
public class EvalCase {
    private String caseId;
    private String description;
    /** 简历文本（不依赖 resume 表，直接注入面试状态） */
    private String resumeText;
    /** JD 文本 */
    private String jdText;
    private String direction;
    private String persona;
    private int durationMinutes = 15;
    /**
     * 模拟候选人回答质量档位：GOOD / MEDIUM / POOR。
     * 驱动面试时按档位套用对应的回答脚本，用于验证评分与流程行为。
     */
    private String answerLevel = "MEDIUM";
    /** 编程题提交序列：每次进入 waiting_code 依次取用（首个可为故意错误代码以触发重试路径） */
    private List<CodingSubmission> codingSubmissions = new ArrayList<>();
    /** 单场面试驱动超时（分钟），超时强制结束并记为超时 */
    private int timeoutMinutes = 20;

    public static class CodingSubmission {
        private String code;
        private String language = "java";

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getResumeText() { return resumeText; }
    public void setResumeText(String resumeText) { this.resumeText = resumeText; }
    public String getJdText() { return jdText; }
    public void setJdText(String jdText) { this.jdText = jdText; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getAnswerLevel() { return answerLevel; }
    public void setAnswerLevel(String answerLevel) { this.answerLevel = answerLevel; }
    public List<CodingSubmission> getCodingSubmissions() { return codingSubmissions; }
    public void setCodingSubmissions(List<CodingSubmission> codingSubmissions) { this.codingSubmissions = codingSubmissions; }
    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
}
