package com.interview.agent.interview.agent;

import com.interview.agent.interview.policy.BehaviorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class FollowUpGenerator {
    private static final Logger log = LoggerFactory.getLogger(FollowUpGenerator.class);
    private final ChatClient chatClient;

    public FollowUpGenerator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 生成追问
     */
    public String generateFollowUp(String originalQuestion, String answer, java.util.Map<String, Object> evaluation, BehaviorPolicy policy) {
        // 根据追问策略判断是否需要追问
        switch (policy.followUpStrategy()) {
            case NEVER:
                return "";
            case ON_FAILURE:
                Object score = evaluation.get("score");
                if (score instanceof Number && ((Number) score).intValue() >= 60) {
                    return "";
                }
                break;
            case ALWAYS:
                break;
        }

        try {
            String prompt = buildPrompt(originalQuestion, answer, evaluation, policy);
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("追问生成失败，使用默认追问", e);
            return fallbackFollowUp(originalQuestion, evaluation);
        }
    }

    private String buildPrompt(String originalQuestion, String answer, java.util.Map<String, Object> evaluation, BehaviorPolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位面试官，请根据以下信息生成一个追问。\n\n");
        sb.append("原始题目：").append(originalQuestion).append("\n\n");
        sb.append("候选人回答：").append(answer).append("\n\n");
        sb.append("评估结果：").append(evaluation).append("\n\n");

        if (policy.shouldGiveHint(-1, 0)) {
            sb.append("请给出一个提示性的追问，引导候选人补充回答。\n");
        } else {
            sb.append("请给出一个更深入的追问，考察候选人对该主题的理解深度。\n");
        }

        sb.append("要求：追问要有针对性，避免重复已有内容，控制在100字以内。");
        return sb.toString();
    }

    private String fallbackFollowUp(String originalQuestion, java.util.Map<String, Object> evaluation) {
        Object score = evaluation.get("score");
        if (score instanceof Number && ((Number) score).intValue() < 60) {
            return "能否更详细地说明一下你的思路？可以从实际应用场景出发，结合具体例子。";
        }
        return "还有没有其他方面想要补充的？";
    }
}
