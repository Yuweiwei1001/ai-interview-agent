package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.CodingAgent;
import com.interview.agent.interview.agent.CoordinatorAgent;
import com.interview.agent.interview.agent.ProjectAgent;
import com.interview.agent.interview.agent.TechnicalAgent;
import com.interview.agent.interview.graph.InterviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CoordinatorNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorNode.class);
    private final CoordinatorAgent coordinatorAgent;
    private final TechnicalAgent technicalAgent;
    private final ProjectAgent projectAgent;
    private final CodingAgent codingAgent;

    public CoordinatorNode(CoordinatorAgent coordinatorAgent, TechnicalAgent technicalAgent,
                          ProjectAgent projectAgent, CodingAgent codingAgent) {
        this.coordinatorAgent = coordinatorAgent;
        this.technicalAgent = technicalAgent;
        this.projectAgent = projectAgent;
        this.codingAgent = codingAgent;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("CoordinatorNode: 决定下一个Agent, round={}", state.getCurrentRound() + 1);

        // 获取已问主题列表
        List<String> askedTopics = state.getRounds().stream()
                .map(InterviewState.RoundRecord::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());

        // Coordinator 决策
        Map<String, String> decision = coordinatorAgent.decideNextAgent(state);
        String nextAgent = decision.get("nextAgent");
        String topic = decision.get("topic");
        String difficulty = decision.get("difficulty");

        state.setCurrentAgent(nextAgent);

        // 对应 Agent 出题
        String question;
        switch (nextAgent) {
            case "technical":
                question = technicalAgent.generateQuestion(topic, difficulty, state.getResumeText(), askedTopics);
                break;
            case "project":
                question = projectAgent.generateQuestion(topic, difficulty, state.getResumeText(), askedTopics);
                break;
            case "coding":
                question = codingAgent.generateQuestion(topic, difficulty, state.getResumeText(), askedTopics);
                break;
            default:
                question = "请介绍一下你的技术背景和项目经验。";
        }

        state.setCurrentQuestion(question);
        return state;
    }
}
