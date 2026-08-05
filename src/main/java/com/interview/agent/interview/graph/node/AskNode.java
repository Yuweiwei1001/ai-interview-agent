package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.plan.InterviewPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class AskNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(AskNode.class);
    private final AskQuestionTool askQuestionTool;

    public AskNode(AskQuestionTool askQuestionTool) {
        this.askQuestionTool = askQuestionTool;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("AskNode: 出题并等待回答, round={}, sessionId={}", state.getCurrentRound() + 1, state.getSessionId());
        
        // 构建题目
        String question = buildQuestion(state);
        state.setCurrentQuestion(question);

        // 推送思考中状态
        askQuestionTool.sendThinking(state.getSessionId());

        // 等待回答（阻塞）
        String answer = askQuestionTool.askAndWait(state.getSessionId(), question);
        state.setCurrentAnswer(answer);

        // 记录轮次
        state.setCurrentRound(state.getCurrentRound() + 1);
        
        log.info("AskNode: 收到回答, round={}, answerLength={}", state.getCurrentRound(), answer.length());
        return state;
    }

    private String buildQuestion(InterviewState state) {
        InterviewPlan plan = state.getPlan();
        if (plan == null || plan.getAgentAssignments() == null || plan.getAgentAssignments().isEmpty()) {
            return "请介绍一下你的技术背景和项目经验。";
        }

        // 简化版：轮流从各 Agent 主题出题
        String[] agents = {"technical", "project", "coding"};
        String agent = agents[state.getCurrentRound() % 3];
        state.setCurrentAgent(agent);

        InterviewPlan.AgentAssignment assignment = plan.getAgentAssignments().get(agent);
        String topics = assignment != null ? assignment.getTopics() : "综合技术";

        return String.format("（第%d轮/%s方向）请围绕以下主题回答：%s。请详细说明你的理解和实践经验。",
                state.getCurrentRound() + 1, agent, topics);
    }
}
