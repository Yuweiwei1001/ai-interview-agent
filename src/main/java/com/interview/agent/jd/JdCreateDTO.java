package com.interview.agent.jd;

import jakarta.validation.constraints.NotBlank;

public class JdCreateDTO {
    @NotBlank(message = "职位标题不能为空")
    private String title;

    @NotBlank(message = "职位描述不能为空")
    private String rawText;

    private String sourceUrl;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
}
