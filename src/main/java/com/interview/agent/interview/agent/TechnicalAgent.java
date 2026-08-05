package com.interview.agent.interview.agent;

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
        try {
            String prompt = buildPrompt(topic, difficulty, resumeText, askedTopics);
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("TechnicalAgent 出题失败，使用题库", e);
            return fallbackQuestion(topic, difficulty);
        }
    }

    private String buildPrompt(String topic, String difficulty, String resumeText, List<String> askedTopics) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深技术面试官，负责考察候选人的技术基础。\n\n");
        sb.append("考察主题：").append(topic).append("\n");
        sb.append("难度级别：").append(difficulty).append("\n\n");
        if (resumeText != null && !resumeText.isBlank()) {
            sb.append("候选人简历：").append(resumeText).append("\n\n");
        }
        if (askedTopics != null && !askedTopics.isEmpty()) {
            sb.append("已考察主题（请避免重复）：").append(String.join("、", askedTopics)).append("\n\n");
        }
        sb.append("请出一道技术面试题，包含题目描述和期望的考察要点。直接输出题目内容，不需要额外说明。");
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
