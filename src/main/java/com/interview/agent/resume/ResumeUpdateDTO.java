package com.interview.agent.resume;

import jakarta.validation.constraints.NotBlank;

public class ResumeUpdateDTO {
    @NotBlank(message = "简历内容不能为空")
    private String rawText;

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
}
