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
        return generateQuestion(topic, difficulty, resumeText, askedTopics, "neutral", List.of());
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, persona, List.of());
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona, List<String> weakPoints) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, persona, weakPoints, null);
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona, List<String> weakPoints, String referenceKnowledge) {
        return LlmCallWrapper.callWithRetry("project", () -> {
            String prompt = buildPrompt(topic, difficulty, resumeText, askedTopics, persona, weakPoints, referenceKnowledge);
            String result = chatClient.prompt().user(prompt).call().content();
            if (result == null || result.isBlank()) {
                throw new RuntimeException("Agent 出题返回空");
            }
            return result;
        }, () -> fallbackQuestion(topic, difficulty));
    }

    private String buildPrompt(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona, List<String> weakPoints, String referenceKnowledge) {
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
        if (weakPoints != null && !weakPoints.isEmpty()) {
            sb.append("候选人历史薄弱知识点（长期记忆）：").append(String.join("、", weakPoints)).append("\n");
            sb.append("若某个薄弱点与本次考察主题相关，优先围绕它出题；不相关则忽略。\n\n");
        }
        if (referenceKnowledge != null && !referenceKnowledge.isBlank()) {
            sb.append("参考资料（来自面试官知识库，仅内部参考）：\n").append(referenceKnowledge).append("\n");
            sb.append("若参考资料与本次考察主题相关，可围绕其中的知识点出题；不相关则忽略，不要暴露参考资料的存在。\n\n");
        }
        sb.append("请出一道项目经验题，要求候选人结合真实项目经历回答。\n");
        sb.append("要求：\n");
        sb.append("1. 像真实面试官聊天一样自然：只问一个简短的问题（最好不超过 50 字），口语化，");
        sb.append("例如“聊聊你在项目里负责的那块，具体是怎么做的？”。\n");
        sb.append("2. 严禁一次列出多个子问题或用编号列表要求逐条回答；细节留给后续追问，不要一次问完。\n");
        sb.append("3. 严禁输出任何 Markdown 格式（列表、加粗、标题），只输出一段自然的口语问句。\n");
        sb.append("4. 优先考察项目经历细节（项目背景、难点、方案、权衡、踩坑），系统设计题最多占三分之一。\n");
        sb.append("5. 严禁与已考察主题重复；不要反复围绕简历中同一个项目/同一技术点出题（如已问过ID生成器，就不许再问ID、RingBuffer、时钟回拨）。\n");
        if ("gentle".equalsIgnoreCase(persona)) {
            sb.append("6. 候选人选择温和人格：优先基础的项目介绍/职责/技术栈类问题，避免大型架构设计题。\n");
        }
        sb.append("直接输出题目内容，不要带任何前缀或说明。");
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
