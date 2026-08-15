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
     * 生成追问（默认策略下仅回答不达标时）。
     * 像真实面试官一样：答得好不点评不追问，直接进入下一题；答得不好才追问引导。
     * 追问语气按面试人格区分：温和引导 / 中性挖细节 / 压迫式施压。
     * 返回空串表示无需追问。
     */
    public String generateFollowUp(String originalQuestion, String answer, java.util.Map<String, Object> evaluation, BehaviorPolicy policy, String persona) {
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

        return LlmCallWrapper.callWithRetry("followup", () -> {
            String prompt = buildPrompt(originalQuestion, answer, evaluation, persona);
            return chatClient.prompt().user(prompt).call().content();
        }, () -> fallbackFollowUp(originalQuestion, evaluation));
    }

    private String buildPrompt(String originalQuestion, String answer, java.util.Map<String, Object> evaluation, String persona) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位面试官，请根据以下信息生成反馈。\n\n");
        sb.append("原始题目：").append(originalQuestion).append("\n\n");
        sb.append("候选人回答：").append(answer).append("\n\n");
        sb.append("评估结果：").append(evaluation).append("\n\n");

        sb.append("候选人的回答不够完善，请以面试官的口吻给出一个针对性的追问，引导候选人补充关键细节。\n");
        sb.append("追问语气要求：").append(toneOf(persona)).append("\n");
        sb.append("只输出追问问题本身，不要点评、不要重复已有内容，控制在80字以内。");
        return sb.toString();
    }

    /** 人格对应的追问语气：温和引导 / 中性挖细节 / 压迫式施压 */
    private String toneOf(String persona) {
        String p = persona == null ? "neutral" : persona.toLowerCase();
        return switch (p) {
            case "pressure" -> "压迫式、尖锐质疑，步步紧逼，直接指出回答的漏洞并施压要求解释（如\"你确定吗\"\"这真的成立吗\"），但不得人身攻击";
            case "gentle" -> "温和引导，鼓励候选人从实际场景和具体例子出发补充细节";
            default -> "中性客观，平静地追问缺失的关键细节";
        };
    }

    private String fallbackFollowUp(String originalQuestion, java.util.Map<String, Object> evaluation) {
        Object score = evaluation.get("score");
        if (score instanceof Number && ((Number) score).intValue() < 60) {
            return "能否更详细地说明一下你的思路？可以从实际应用场景出发，结合具体例子。";
        }
        return "";
    }
}
