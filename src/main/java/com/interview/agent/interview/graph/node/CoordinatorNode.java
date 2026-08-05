package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.CodingAgent;
import com.interview.agent.interview.agent.ContextWindowManager;
import com.interview.agent.interview.agent.CoordinatorAgent;
import com.interview.agent.interview.agent.ProjectAgent;
import com.interview.agent.interview.agent.QuestionDeduper;
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
    private final QuestionDeduper questionDeduper;
    private final ContextWindowManager contextWindowManager;

    public CoordinatorNode(CoordinatorAgent coordinatorAgent, TechnicalAgent technicalAgent,
                          ProjectAgent projectAgent, CodingAgent codingAgent,
                          QuestionDeduper questionDeduper, ContextWindowManager contextWindowManager) {
        this.coordinatorAgent = coordinatorAgent;
        this.technicalAgent = technicalAgent;
        this.projectAgent = projectAgent;
        this.codingAgent = codingAgent;
        this.questionDeduper = questionDeduper;
        this.contextWindowManager = contextWindowManager;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("CoordinatorNode: 决定下一个Agent, round={}", state.getCurrentRound() + 1);

        // 获取已问主题列表
        List<String> askedTopics = state.getRounds().stream()
                .map(InterviewState.RoundRecord::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());

        // Coordinator 决策（使用 ContextWindowManager 压缩后的对话历史，避免 Context 溢出）
        String compressedHistory = contextWindowManager.buildCompressedHistory(state);
        Map<String, String> decision = coordinatorAgent.decideNextAgent(state, compressedHistory);
        String nextAgent = decision.get("nextAgent");
        String topic = decision.get("topic");
        String difficulty = decision.get("difficulty");

        state.setCurrentAgent(nextAgent);

        // 对应 Agent 出题（含去重检查，最多重试3次）
        List<String> existingQuestions = state.getRounds().stream()
                .map(InterviewState.RoundRecord::getQuestion)
                .filter(q -> q != null && !q.isBlank())
                .collect(Collectors.toList());

        String question = generateQuestion(nextAgent, topic, difficulty, state.getResumeText(), askedTopics);
        int retryCount = 0;
        while (questionDeduper.isDuplicate(question, existingQuestions) && retryCount < 3) {
            log.warn("题目重复，重新生成: retry={}, agent={}", retryCount, nextAgent);
            question = generateQuestion(nextAgent, topic, difficulty, state.getResumeText(), askedTopics);
            retryCount++;
        }

        state.setCurrentQuestion(question);

        // 当路由到 coding Agent 时，设置显式挂起标志
        if ("coding".equals(nextAgent)) {
            state.setWaitingForCode(true);
        }

        return state;
    }

    private String generateQuestion(String nextAgent, String topic, String difficulty, String resumeText, List<String> askedTopics) {
        return switch (nextAgent) {
            case "technical" -> technicalAgent.generateQuestion(topic, difficulty, resumeText, askedTopics);
            case "project" -> projectAgent.generateQuestion(topic, difficulty, resumeText, askedTopics);
            case "coding" -> codingAgent.generateQuestion(topic, difficulty, resumeText, askedTopics);
            default -> "请介绍一下你的技术背景和项目经验。";
        };
    }
}
