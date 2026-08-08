package com.interview.agent.interview.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.interview.agent.common.ai.LlmCallWrapper;
import com.interview.agent.interview.graph.InterviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CoordinatorAgent {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgent.class);
    private final ChatClient chatClient;

    public CoordinatorAgent(ChatClient.Builder builder) {
        // Coordinator 使用 qwen-turbo 节约成本
        this.chatClient = builder.defaultOptions(
                DashScopeChatOptions.builder().model("qwen-turbo").build()
        ).build();
    }

    /**
     * 决定下一个出题 Agent
     * @return 包含 nextAgent, reason, topic, difficulty 的 Map
     */
    public Map<String, String> decideNextAgent(InterviewState state) {
        return decideNextAgent(state, null);
    }

    /**
     * 决定下一个出题 Agent（携带对话历史，历史由 ContextWindowManager 压缩）
     * @param state 面试状态
     * @param conversationHistory 压缩后的对话历史（可为 null）
     * @return 包含 nextAgent, reason, topic, difficulty 的 Map
     */
    public Map<String, String> decideNextAgent(InterviewState state, String conversationHistory) {
        return LlmCallWrapper.callWithRetry(() -> {
            String prompt = buildDecisionPrompt(state, conversationHistory);
            // 使用结构化输出
            CoordinatorDecision decision = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(CoordinatorDecision.class);

            if (decision == null || decision.getNextAgent() == null) {
                throw new RuntimeException("Coordinator 返回空决策");
            }

            return Map.of(
                "nextAgent", decision.getNextAgent(),
                "reason", decision.getReason() != null ? decision.getReason() : "",
                "topic", decision.getTopic() != null ? decision.getTopic() : "综合技术",
                "difficulty", decision.getDifficulty() != null ? decision.getDifficulty() : "中等"
            );
        }, () -> fallbackDecision(state));
    }

    private String buildDecisionPrompt(InterviewState state, String conversationHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位面试协调员，需要根据当前面试进度决定下一个出题的 Agent。\n\n");
        sb.append("当前面试状态：\n");
        sb.append("- 已进行轮次：").append(state.getCurrentRound()).append("\n");
        sb.append("- 当前 Agent：").append(state.getCurrentAgent() != null ? state.getCurrentAgent() : "无").append("\n");
        sb.append("- 面试方向：").append(state.getDirection() != null ? state.getDirection() : "综合技术").append("\n");
        sb.append("- 面试人格：").append(state.getPersona() != null ? state.getPersona() : "neutral").append("\n\n");

        // 已考察主题（去重强约束）
        List<String> askedTopics = state.getRounds().stream()
                .map(InterviewState.RoundRecord::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        if (!askedTopics.isEmpty()) {
            sb.append("已考察主题（严禁再次选择相同或相似主题，务必避开）：").append(String.join("、", askedTopics)).append("\n\n");
        }

        if (conversationHistory != null && !conversationHistory.isBlank()) {
            sb.append("对话历史：\n").append(conversationHistory).append("\n\n");
        }

        sb.append("可选 Agent：\n");
        sb.append("- technical：技术基础（计算机基础、数据结构、算法、编程语言特性）\n");
        sb.append("- project：项目经验（项目经历深挖、技术选型、架构）\n");
        sb.append("- coding：编码能力（算法实现、代码质量、调试能力）\n\n");

        sb.append("请输出JSON格式决策，包含字段：nextAgent(选择上述Agent名称), reason(选择原因), topic(具体考察主题), difficulty(难度: 简单/中等/困难)\n");
        sb.append("规则：\n");
        sb.append("1. 避免连续超过3次同一Agent，优先考察未涉及的Agent。\n");
        sb.append("2. coding 全场最多安排一次，且只安排在面试中后段（前2题不要安排 coding）。\n");
        sb.append("3. coding 仅用于纯算法题（数组/链表/哈希/树/动态规划等）；系统设计、架构、中间件应用类问题归 project 或 technical。\n");
        sb.append("4. 题目类型分布：technical 以基础概念/原理/日常开发八股为主，不得出大型系统设计题；project 以候选人项目经历深挖为主，系统设计题最多占 project 题量的三分之一。\n");
        sb.append("5. 严禁重复考察同一主题/同一技术点（如已问过分布式ID生成器，就不得再问任何 ID 生成、RingBuffer、时钟回拨相关题目）。\n");
        if ("gentle".equalsIgnoreCase(state.getPersona())) {
            sb.append("6. 候选人选择温和人格：难度以简单为主、最多中等，避免偏难怪题，优先基础概念与常规项目问题。\n");
        }
        return sb.toString();
    }

    private Map<String, String> fallbackDecision(InterviewState state) {
        String[] agents = {"technical", "project", "coding"};
        int currentRound = state.getCurrentRound();
        String agent = agents[currentRound % 3];

        // 反循环：连续同 Agent 超过 3 次强制切换
        if (state.getCurrentAgent() != null && state.getCurrentAgent().equals(agent)) {
            int sameCount = 0;
            for (int i = state.getRounds().size() - 1; i >= 0; i--) {
                if (agent.equals(state.getRounds().get(i).getAgentName())) {
                    sameCount++;
                } else break;
            }
            if (sameCount >= 3) {
                agent = agents[(currentRound + 1) % 3];
            }
        }

        return Map.of(
            "nextAgent", agent,
            "reason", "轮换出题",
            "topic", "综合技术",
            "difficulty", "中等"
        );
    }

    public static class CoordinatorDecision {
        private String nextAgent;
        private String reason;
        private String topic;
        private String difficulty;

        public String getNextAgent() { return nextAgent; }
        public void setNextAgent(String nextAgent) { this.nextAgent = nextAgent; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    }
}
