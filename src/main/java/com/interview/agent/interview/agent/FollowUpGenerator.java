package com.interview.agent.interview.agent;

import com.interview.agent.common.ai.LlmCallWrapper;
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
     * 生成追问（仅回答不达标时）。
     * 像真实面试官一样：答得好不点评不追问，直接进入下一题；答得不好才追问引导。
     * 返回空串表示无需追问。
     */
    public String generateFollowUp(String originalQuestion, String answer, java.util.Map<String, Object> evaluation, BehaviorPolicy policy) {
        Object scoreObj = evaluation.get("score");
        int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 50;

        // 根据追问策略判断是否需要追问
        switch (policy.followUpStrategy()) {
            case NEVER:
                return "";
            case ALWAYS:
                break;
            case ON_FAILURE:
            default:
                // 答得好：不反馈不追问，像正常面试官一样直接进入下一题
                if (score >= 60) {
                    return "";
                }
                break;
        }

        return LlmCallWrapper.callWithRetry(() -> {
            String prompt = buildPrompt(originalQuestion, answer, evaluation, policy);
            return chatClient.prompt().user(prompt).call().content();
        }, () -> fallbackFollowUp(originalQuestion, evaluation));
    }

    private String buildPrompt(String originalQuestion, String answer, java.util.Map<String, Object> evaluation, BehaviorPolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位面试官，请根据以下信息生成反馈。\n\n");
        sb.append("原始题目：").append(originalQuestion).append("\n\n");
        sb.append("候选人回答：").append(answer).append("\n\n");
        sb.append("评估结果：").append(evaluation).append("\n\n");

        sb.append("候选人的回答不够完善，请以面试官的口吻给出一个针对性的追问，引导候选人补充关键细节。\n");
        sb.append("要求：只输出追问问题本身，不要点评、不要重复已有内容，控制在80字以内。");
        return sb.toString();
    }

    private String fallbackFollowUp(String originalQuestion, java.util.Map<String, Object> evaluation) {
        Object score = evaluation.get("score");
        if (score instanceof Number && ((Number) score).intValue() < 60) {
            return "能否更详细地说明一下你的思路？可以从实际应用场景出发，结合具体例子。";
        }
        return "";
    }
}
