package com.interview.agent.interview.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlanGenerator {
    private static final Logger log = LoggerFactory.getLogger(PlanGenerator.class);
    private final ChatClient chatClient;

    public PlanGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public InterviewPlan generatePlan(String resumeText, String jdText, String direction, String persona, int durationMinutes) {
        try {
            String prompt = buildPrompt(resumeText, jdText, direction, persona, durationMinutes);
            InterviewPlan plan = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(InterviewPlan.class);
            if (plan == null || plan.getAgentAssignments() == null || plan.getAgentAssignments().isEmpty()) {
                log.warn("LLM 返回空计划，使用降级计划");
                return fallbackPlan(direction, durationMinutes);
            }
            return plan;
        } catch (Exception e) {
            log.error("计划生成失败，使用降级计划", e);
            return fallbackPlan(direction, durationMinutes);
        }
    }

    private String buildPrompt(String resumeText, String jdText, String direction, String persona, int durationMinutes) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深技术面试官，请根据以下信息制定面试计划。\n\n");
        sb.append("## 面试方向\n").append(direction != null ? direction : "综合技术").append("\n\n");
        sb.append("## 面试时长（分钟）\n").append(durationMinutes).append("\n\n");
        sb.append("## 面试人格风格\n").append(persona != null ? persona : "neutral").append("\n\n");
        sb.append("## 候选人简历\n").append(resumeText != null ? resumeText : "无简历").append("\n\n");
        if (jdText != null && !jdText.isBlank()) {
            sb.append("## 职位描述\n").append(jdText).append("\n\n");
        }
        sb.append("请输出一个JSON格式的面试计划，包含以下字段：\n");
        sb.append("- overallStrategy: 整体面试策略描述\n");
        sb.append("- agentAssignments: 各Agent的分配，key为agent名称(technical/project/coding)，value包含topics(考察主题)、difficulty(难度)、estimatedRounds(预计轮次)\n");
        sb.append("- weakPointPriority: 薄弱点优先考察列表\n");
        sb.append("- estimatedTotalRounds: 预计总轮次数\n");
        return sb.toString();
    }

    private InterviewPlan fallbackPlan(String direction, int durationMinutes) {
        int roundsPerAgent = Math.max(3, durationMinutes / 10);
        InterviewPlan plan = new InterviewPlan();
        plan.setOverallStrategy("默认面试计划：技术 + 项目 + 编码各" + roundsPerAgent + "轮");
        plan.setWeakPointPriority(List.of("基础知识", "项目经验", "编码能力"));

        Map<String, InterviewPlan.AgentAssignment> assignments = new HashMap<>();
        
        InterviewPlan.AgentAssignment technical = new InterviewPlan.AgentAssignment();
        technical.setTopics("计算机基础、数据结构、算法、程序设计语言");
        technical.setDifficulty("中等");
        technical.setEstimatedRounds(roundsPerAgent);
        assignments.put("technical", technical);

        InterviewPlan.AgentAssignment project = new InterviewPlan.AgentAssignment();
        project.setTopics("项目经验、系统设计、架构决策、技术选型");
        project.setDifficulty("中等");
        project.setEstimatedRounds(roundsPerAgent);
        assignments.put("project", project);

        InterviewPlan.AgentAssignment coding = new InterviewPlan.AgentAssignment();
        coding.setTopics("算法题、编码实践、代码质量");
        coding.setDifficulty("中等");
        coding.setEstimatedRounds(roundsPerAgent);
        assignments.put("coding", coding);

        plan.setAgentAssignments(assignments);
        plan.setEstimatedTotalRounds(roundsPerAgent * 3);
        return plan;
    }
}
