package com.interview.agent.interview.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
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
        return LlmCallWrapper.callWithRetry("coding", () -> {
            String prompt = buildPrompt(topic, difficulty, resumeText, askedTopics);
            String result = chatClient.prompt()
                    .options(DashScopeChatOptions.builder().withTemperature(0.8).build())
                    .user(prompt).call().content();
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
        // 成本优化：算法题与简历无关，不再注入全量简历（每轮省 ~2k 输入 token）；resumeText 参数保留仅为签名兼容
        if (askedTopics != null && !askedTopics.isEmpty()) {
            sb.append("已考察主题（请避免重复）：").append(String.join("、", askedTopics)).append("\n\n");
        }
        sb.append("请出一道纯算法题（LeetCode 风格），包含完整题目描述、输入输出示例、数据范围约束。\n");
        sb.append("要求：\n");
        sb.append("1. 候选人需写出完整可运行代码，不得仅口头描述。\n");
        sb.append("2. 【严禁】题目中不得包含考察要点、解题思路、算法提示、时间复杂度/空间复杂度要求、参考答案等任何泄露解题方向的内容——这些是面试官内部评估标准，候选人看到会直接获得答案线索。\n");
        sb.append("3. 禁止出系统设计、架构设计、分布式系统等非算法类题目。\n");
        sb.append("4. 避免输出《两数之和》《和为K的子数组》《反转链表》等众所周知的模板题，请在主题下挑选一道不常见、有区分度的题，不要与历史题目重复。\n");
        sb.append("直接输出题目内容，不需要额外说明。");
        return sb.toString();
    }

    private String fallbackQuestion(String topic, String difficulty) {
        // 兜底题库：按算法主题池（CoordinatorNode.CODING_TOPICS）各配一道经典题，避免兜底总出同一道
        Map<String, String> bank = Map.ofEntries(
            Map.entry("数组与字符串", "给定一个整数数组和一个目标值 target，返回数组中和为目标值的两个数的下标（假设每个输入只有一个答案）。请写出完整可运行的代码，并说明时间复杂度。"),
            Map.entry("链表", "给定单链表头节点 head，请反转链表并返回新的头节点。请写出完整可运行的代码，并说明时间/空间复杂度。"),
            Map.entry("哈希表", "给定一个整数数组 nums，请找出任意一个出现次数超过一半的多数元素。请写出完整可运行的代码。"),
            Map.entry("栈与队列", "请实现一个支持 push、pop、top、getMin 的栈（最小栈），所有操作时间复杂度 O(1)。请写出完整可运行的代码。"),
            Map.entry("二叉树", "给定二叉树根节点，请实现前序遍历并返回节点值序列（递归或迭代均可）。请写出完整可运行的代码。"),
            Map.entry("双指针", "给定一个已按升序排列的整数数组和一个目标值 target，请用双指针找出数组中和等于 target 的两个数的下标。请写出完整可运行的代码。"),
            Map.entry("排序与二分查找", "给定一个升序整数数组和一个目标值 target，请实现二分查找返回其下标，不存在则返回 -1。请写出完整可运行的代码。"),
            Map.entry("动态规划", "给定两个字符串 text1 和 text2，请返回它们的最长公共子序列长度。请写出完整可运行的代码，并说明时间/空间复杂度。")
        );
        return bank.getOrDefault(topic, bank.get("数组与字符串"));
    }
}
