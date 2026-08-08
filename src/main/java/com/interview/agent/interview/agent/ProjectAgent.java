package com.interview.agent.interview.agent;

import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProjectAgent {
    private static final Logger log = LoggerFactory.getLogger(ProjectAgent.class);
    private final ChatClient chatClient;

    public ProjectAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, "neutral");
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona) {
        return LlmCallWrapper.callWithRetry(() -> {
            String prompt = buildPrompt(topic, difficulty, resumeText, askedTopics, persona);
            String result = chatClient.prompt().user(prompt).call().content();
            if (result == null || result.isBlank()) {
                throw new RuntimeException("Agent 出题返回空");
            }
            return result;
        }, () -> fallbackQuestion(topic, difficulty));
    }

    private String buildPrompt(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深项目面试官，负责考察候选人的项目经验。\n\n");
        sb.append("考察主题：").append(topic).append("\n");
        sb.append("难度级别：").append(difficulty).append("\n\n");
        if (resumeText != null && !resumeText.isBlank()) {
            sb.append("候选人简历：").append(resumeText).append("\n\n");
        }
        if (askedTopics != null && !askedTopics.isEmpty()) {
            sb.append("已考察主题（请避免重复）：").append(String.join("、", askedTopics)).append("\n\n");
        }
        sb.append("请出一道项目经验题，要求候选人结合真实项目经历回答。\n");
        sb.append("要求：\n");
        sb.append("1. 优先考察项目经历细节（项目背景、难点、方案、权衡、踩坑），系统设计题最多占三分之一。\n");
        sb.append("2. 严禁与已考察主题重复；不要反复围绕简历中同一个项目/同一技术点出题（如已问过ID生成器，就不许再问ID、RingBuffer、时钟回拨）。\n");
        if ("gentle".equalsIgnoreCase(persona)) {
            sb.append("3. 候选人选择温和人格：优先基础的项目介绍/职责/技术栈类问题，避免大型架构设计题。\n");
        }
        sb.append("直接输出题目内容。");
        return sb.toString();
    }

    private String fallbackQuestion(String topic, String difficulty) {
        Map<String, String> bank = Map.ofEntries(
            Map.entry("系统设计", "请设计一个高并发的短链接服务，要求从架构设计、数据库选型、缓存策略等方面进行阐述。"),
            Map.entry("架构设计", "请描述一个你参与过的微服务架构项目，包括服务拆分原则、服务间通信方式、以及遇到的挑战和解决方案。"),
            Map.entry("技术选型", "在项目中进行技术选型时，你会考虑哪些因素？请举例说明你在实际项目中做过的重要技术决策。"),
            Map.entry("项目管理", "请描述一个你在项目中遇到的重大困难，以及你是如何协调团队资源、推动问题解决的。"),
            Map.entry("综合项目", "请描述你最满意的一个项目，包括项目背景、你的角色、技术亮点和业务成果。")
        );
        return bank.getOrDefault(topic, bank.get("综合项目"));
    }
}
