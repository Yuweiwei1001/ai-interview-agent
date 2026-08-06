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

        // 编程题确定性护栏（不信任 LLM 决策）：
        // 1. 全场最多 1 道编程题；2. 至少先进行 2 轮非编程题（编程题放在面试中后段）；
        // 3. 编程题主题强制从算法池选取，避免“Redis 限流器”这类系统设计题进入代码编辑器。
        long codingCount = state.getRounds().stream()
                .filter(r -> "coding".equals(r.getAgentName()))
                .count();
        if ("coding".equals(nextAgent)) {
            if (codingCount >= 1 || state.getCurrentRound() < CODING_MIN_ROUND) {
                log.info("编程题护栏：改派非编程 Agent, codingCount={}, round={}", codingCount, state.getCurrentRound());
                nextAgent = pickNonCodingAgent(state);
                topic = "technical".equals(nextAgent) ? "计算机基础" : "项目经验";
            } else {
                topic = CODING_TOPICS.get(state.getCurrentRound() % CODING_TOPICS.size());
            }
        } else if (codingCount == 0 && state.getCurrentRound() >= CODING_MIN_ROUND) {
            // 进入中后段还没出编程题 → 强制安排（防止 LLM 一直不出 coding，或提前结束跳过编程环节）
            log.info("编程题护栏：round={} 尚未出编程题，强制安排 coding", state.getCurrentRound());
            nextAgent = "coding";
            topic = CODING_TOPICS.get(state.getCurrentRound() % CODING_TOPICS.size());
        }

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

    /** 编程题最早出现轮次（0 基：第 3 题起才允许编程题） */
    private static final int CODING_MIN_ROUND = 2;

    /** 编程题算法主题池（纯数据结构与算法，杜绝系统设计题混入） */
    private static final List<String> CODING_TOPICS = List.of(
            "数组与字符串", "链表", "哈希表", "栈与队列", "二叉树", "双指针", "排序与二分查找", "动态规划");

    /** 改派 technical/project 中已出轮次较少者，保证覆盖均衡 */
    private String pickNonCodingAgent(InterviewState state) {
        long techCount = state.getRounds().stream().filter(r -> "technical".equals(r.getAgentName())).count();
        long projCount = state.getRounds().stream().filter(r -> "project".equals(r.getAgentName())).count();
        return techCount <= projCount ? "technical" : "project";
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
