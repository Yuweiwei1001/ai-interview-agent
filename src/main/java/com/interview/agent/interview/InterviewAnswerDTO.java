package com.interview.agent.interview;

import jakarta.validation.constraints.NotBlank;

public class InterviewAnswerDTO {
    @NotBlank(message = "回答内容不能为空")
    private String answer;

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}
