package com.interview.agent.interview.agent;

import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CodingAgent {
    private static final Logger log = LoggerFactory.getLogger(CodingAgent.class);
    private final ChatClient chatClient;

    public CodingAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 系统设计类关键词：编程题必须是纯算法题，命中这些词视为出题偏移 */
    private static final java.util.regex.Pattern DESIGN_KEYWORDS = java.util.regex.Pattern.compile(
            "系统设计|架构设计|分布式|微服务|Redis|Kafka|RabbitMQ|RocketMQ|消息队列|限流|熔断|负载均衡|短链|秒杀|缓存设计|数据库设计");

    public String generateQuestion(String topic, String difficulty, String resumeText, List<String> askedTopics) {
        return LlmCallWrapper.callWithRetry(() -> {
            String prompt = buildPrompt(topic, difficulty, resumeText, askedTopics);
            String result = chatClient.prompt().user(prompt).call().content();
            if (result == null || result.isBlank()) {
                throw new RuntimeException("Agent 出题返回空");
            }
            // 偏移护栏：LLM 出了系统设计题 → 加强约束重试一次，仍偏移则用算法题库兜底
            if (DESIGN_KEYWORDS.matcher(result).find()) {
                log.warn("编程题偏移为系统设计题，加强约束重试: topic={}", topic);
                String retryPrompt = prompt + "\n\n【警告】上次输出偏系统设计，严重错误。必须出一道数据结构与算法题（LeetCode 风格），禁止提及任何中间件、框架、系统架构。";
                result = chatClient.prompt().user(retryPrompt).call().content();
                if (result == null || result.isBlank() || DESIGN_KEYWORDS.matcher(result).find()) {
                    log.warn("重试仍偏移，使用算法题库兜底: topic={}", topic);
                    return fallbackQuestion(topic, difficulty);
                }
            }
            return result;
        }, () -> fallbackQuestion(topic, difficulty));
    }

    private String buildPrompt(String topic, String difficulty, String resumeText, List<String> askedTopics) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位算法面试官，负责考察候选人的编码和算法能力。\n\n");
        sb.append("考察主题：").append(topic).append("\n");
        sb.append("难度级别：").append(difficulty).append("\n\n");
        if (resumeText != null && !resumeText.isBlank()) {
            sb.append("候选人简历：").append(resumeText).append("\n\n");
        }
        if (askedTopics != null && !askedTopics.isEmpty()) {
            sb.append("已考察主题（请避免重复）：").append(String.join("、", askedTopics)).append("\n\n");
        }
        sb.append("请出一道纯算法题（LeetCode 风格），包含完整题目描述、输入输出示例、以及考察要点。\n");
        sb.append("要求：候选人需写出完整可运行代码，不得仅口头描述。\n");
        sb.append("禁止出系统设计、架构设计、分布式系统等非算法类题目。\n");
        sb.append("直接输出题目内容，不需要额外说明。");
        return sb.toString();
    }

    private String fallbackQuestion(String topic, String difficulty) {
        Map<String, String> bank = Map.ofEntries(
            Map.entry("算法", "给定一个整数数组 nums 和一个整数目标值 target，请找出数组中和为目标值的两个数，并返回它们的下标。请写出完整可运行的代码，并说明时间复杂度。"),
            Map.entry("数据结构", "请实现一个最小栈（MinStack），支持 push、pop、top 和 getMin 操作，且所有操作的时间复杂度为 O(1)。请写出完整可运行的代码。"),
            Map.entry("编码", "请实现一个函数，判断一个字符串是否是有效的括号组合（包含小括号、中括号、大括号）。请写出完整可运行的代码。"),
            Map.entry("综合编码", "请实现一个算法，找到两个有序数组的中位数。要求时间复杂度 O(log(m+n))，请写出完整可运行的代码。")
        );
        return bank.getOrDefault(topic, bank.get("算法"));
    }
}
