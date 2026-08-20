package com.interview.agent.interview.agent;

import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TechnicalAgent {
    private static final Logger log = LoggerFactory.getLogger(TechnicalAgent.class);
    private final ChatClient chatClient;

    public TechnicalAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, "neutral", List.of());
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, persona, List.of());
    }

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics, String persona, List<String> weakPoints) {
        return generateQuestion(topic, difficulty, resumeText, askedTopics, persona, weakPoints, List.of());
    }

    /** 出题（ASR 热词纠错方案 P0：注入会话热词表，题目优先围绕候选人术语体系提问） */
    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics,
                                   String persona, List<String> weakPoints, List<String> sessionHotwords) {
        return LlmCallWrapper.callWithRetry("technical", () -> {
            String prompt = buildPrompt(topic, difficulty, resumeText, askedTopics, persona, weakPoints, sessionHotwords);
            String result = chatClient.prompt().user(prompt).call().content();
            if (result == null || result.isBlank()) {
                throw new RuntimeException("Agent 出题返回空");
            }
            return result;
        }, () -> fallbackQuestion(topic, difficulty));
    }

    private String buildPrompt(String topic, String difficulty, String resumeText, List<String> askedTopics,
                               String persona, List<String> weakPoints, List<String> sessionHotwords) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深技术面试官，负责考察候选人的技术基础能力。\n\n");
        sb.append("考察主题：").append(topic).append("\n");
        sb.append("难度级别：").append(difficulty).append("\n\n");
        // 成本优化：八股题主题由面试计划 + Coordinator 决策保证，全量简历对出题无增益，不再注入（每轮省 ~2k 输入 token）；
        // resumeText 参数保留仅为调用方签名兼容。项目经验题（ProjectAgent）仍注入全量简历。
        if (askedTopics != null && !askedTopics.isEmpty()) {
            sb.append("已考察主题（请避免重复）：").append(String.join("、", askedTopics)).append("\n\n");
        }
        if (weakPoints != null && !weakPoints.isEmpty()) {
            sb.append("候选人薄弱知识点（面试计划优先考察项 + 历史记忆）：").append(String.join("、", weakPoints)).append("\n");
            sb.append("若某个薄弱点与本次考察主题相关，优先围绕它出题；不相关则忽略。\n\n");
        }
        // P0 热词注入：题目优先围绕候选人简历/JD 中的技术术语提问，并使用官方写法
        if (sessionHotwords != null && !sessionHotwords.isEmpty()) {
            sb.append("候选人技术栈术语表（若与考察主题相关，优先围绕这些术语出题并使用官方写法）：")
                    .append(String.join("、", sessionHotwords)).append("\n\n");
        }
        sb.append("请出一道技术基础题：考察概念理解、原理说明或日常开发经验，候选人口头阐述即可，不要求写代码。\n");
        sb.append("要求：\n");
        sb.append("1. 像真实面试官聊天一样自然：只问一个简短的问题（最好不超过 50 字），口语化，例如“说说 HashMap 的底层原理呗”。\n");
        sb.append("2. 严禁一次列出多个子问题或用编号列表要求逐条回答；深入细节留给后续追问。\n");
        sb.append("3. 严禁输出任何 Markdown 格式（列表、加粗、标题），只输出一段自然的口语问句。\n");
        sb.append("4. 以基础概念和常见知识点为主，不要出大型系统设计题（如×××服务如何设计、×××架构方案选型）。\n");
        sb.append("5. 严禁与已考察主题重复；不要围绕简历中同一个技术点反复出题（如已考过分布式ID，就不许再考ID/时钟/雪花相关）。\n");
        if ("gentle".equalsIgnoreCase(persona)) {
            sb.append("6. 候选人选择温和人格：题目难度简单，贴近教材基础，避免任何偏难怪题。\n");
        }
        sb.append("直接输出题目内容，不要带任何前缀或说明。");
        return sb.toString();
    }

    private String fallbackQuestion(String topic, String difficulty) {
        Map<String, String> bank = Map.ofEntries(
            Map.entry("Java", "请解释Java内存模型（JMM）和volatile关键字的作用，以及它们在并发编程中的应用场景。"),
            Map.entry("Spring", "请解释Spring Boot的自动配置原理，以及Spring IOC容器的Bean生命周期。"),
            Map.entry("数据结构", "请比较HashMap和ConcurrentHashMap的实现原理，说明它们在并发环境下的区别。"),
            Map.entry("算法", "请设计一个LRU缓存，要求分析时间复杂度和空间复杂度，并说明你的设计思路。"),
            Map.entry("数据库", "请解释MySQL的索引原理，以及如何通过EXPLAIN分析查询性能。"),
            Map.entry("综合技术", "请解释分布式系统中的CAP理论，以及在实际项目中如何权衡一致性、可用性和分区容错性。")
        );
        return bank.getOrDefault(topic, bank.get("综合技术"));
    }
}
