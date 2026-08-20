package com.interview.agent.interview.plan;

import com.interview.agent.common.ai.LlmCallWrapper;
import com.interview.agent.memory.KnowledgePointService;
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
    private final KnowledgePointService knowledgePointService;

    public PlanGenerator(ChatClient.Builder chatClientBuilder, KnowledgePointService knowledgePointService) {
        this.chatClient = chatClientBuilder.build();
        this.knowledgePointService = knowledgePointService;
    }

    public InterviewPlan generatePlan(String resumeText, String jdText, String direction, String persona, int durationMinutes) {
        return LlmCallWrapper.callWithRetry("plan", () -> {
            String prompt = buildPrompt(resumeText, jdText, direction, persona, durationMinutes);
            InterviewPlan plan = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(InterviewPlan.class);
            if (plan == null || plan.getAgentAssignments() == null || plan.getAgentAssignments().isEmpty()) {
                throw new RuntimeException("LLM 返回空计划");
            }
            return plan;
        }, () -> fallbackPlan(direction, durationMinutes));
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
        String knowledgeSummary = knowledgePointService.buildKnowledgeSummary();
        if (!knowledgeSummary.isBlank()) {
            sb.append("## 历史知识点记录\n").append(knowledgeSummary).append("\n\n");
        }
        sb.append("请输出一个JSON格式的面试计划，包含以下字段：\n");
        sb.append("- overallStrategy: 整体面试策略描述\n");
        sb.append("- agentAssignments: 各Agent的分配，key为agent名称(technical/project/coding)，value包含topics(考察主题，字符串数组)、difficulty(难度)、estimatedRounds(预计轮次)\n");
        sb.append("- weakPointPriority: 薄弱点优先考察列表\n");
        sb.append("- estimatedTotalRounds: 预计总轮次数\n");
        sb.append("- hotwords: 本场面试相关的技术术语列表（从简历/JD 中提取，英文使用官方大小写如 Redis、Spring Boot，最多 30 个，宁多勿漏）\n");
        sb.append("硬性规则：\n");
        sb.append("1. coding 的 estimatedRounds 必须为 1（全场仅 1 道上机编程题），且固定安排在面试最后一题。\n");
        sb.append("2. estimatedTotalRounds 按面试时长估算，约每 5 分钟 1 题，取值 5-8 之间。\n");
        sb.append("3. 环节顺序固定为：先 technical（八股基础）再 project（项目经验），两者均分除 coding 外的剩余轮次；coding 的 topics 仅限数据结构与算法。\n");
        return sb.toString();
    }

    private InterviewPlan fallbackPlan(String direction, int durationMinutes) {
        // 兜底结构：编程题固定 1 道，总轮次按时长估算（约每 5 分钟 1 题，限 5-8），技术与项目均分剩余
        int totalRounds = Math.min(8, Math.max(5, durationMinutes / 5));
        int remaining = totalRounds - 1;
        int techRounds = (remaining + 1) / 2;
        int projRounds = remaining / 2;

        InterviewPlan plan = new InterviewPlan();
        plan.setOverallStrategy("默认面试计划：先技术八股 " + techRounds + " 轮，再项目 " + projRounds + " 轮，最后编码 1 轮");
        plan.setWeakPointPriority(List.of("基础知识", "项目经验", "编码能力"));

        Map<String, InterviewPlan.AgentAssignment> assignments = new HashMap<>();

        InterviewPlan.AgentAssignment technical = new InterviewPlan.AgentAssignment();
        technical.setTopics(List.of("计算机基础", "数据结构", "程序设计语言"));
        technical.setDifficulty("中等");
        technical.setEstimatedRounds(techRounds);
        assignments.put("technical", technical);

        InterviewPlan.AgentAssignment project = new InterviewPlan.AgentAssignment();
        project.setTopics(List.of("项目经验", "系统设计", "技术选型"));
        project.setDifficulty("中等");
        project.setEstimatedRounds(projRounds);
        assignments.put("project", project);

        InterviewPlan.AgentAssignment coding = new InterviewPlan.AgentAssignment();
        coding.setTopics(List.of("数据结构与算法"));
        coding.setDifficulty("中等");
        coding.setEstimatedRounds(1);
        assignments.put("coding", coding);

        plan.setAgentAssignments(assignments);
        plan.setEstimatedTotalRounds(totalRounds);
        return plan;
    }
}
