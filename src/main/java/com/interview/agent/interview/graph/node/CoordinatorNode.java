package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.CodingAgent;
import com.interview.agent.interview.agent.ProjectAgent;
import com.interview.agent.interview.agent.QuestionDeduper;
import com.interview.agent.interview.agent.TechnicalAgent;
import com.interview.agent.interview.graph.InterviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 面试编排节点：确定性固定工作流（不再由 LLM 决定环节顺序）。
 *
 * <p>环节顺序：八股（technical）→ 项目（project）→ 编程（coding，恒为最后一题）。
 * 总轮次以 maxRounds 为准：编程固定占 1 轮，剩余轮次技术（向上取整）与项目均分。
 * 环节内部的具体题目仍由各 Agent 的 LLM 自由发挥。
 */
public class CoordinatorNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorNode.class);
    private final TechnicalAgent technicalAgent;
    private final ProjectAgent projectAgent;
    private final CodingAgent codingAgent;
    private final QuestionDeduper questionDeduper;

    public CoordinatorNode(TechnicalAgent technicalAgent,
                          ProjectAgent projectAgent, CodingAgent codingAgent,
                          QuestionDeduper questionDeduper) {
        this.technicalAgent = technicalAgent;
        this.projectAgent = projectAgent;
        this.codingAgent = codingAgent;
        this.questionDeduper = questionDeduper;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("CoordinatorNode: 决定下一个Agent, round={}", state.getCurrentRound() + 1);

        // 获取已问主题列表（用于 Agent 出题时避重）
        List<String> askedTopics = state.getRounds().stream()
                .map(InterviewState.RoundRecord::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toList());

        // 固定编排：按已完成的主轮次（排除追问轮）确定下一环节
        long techDone = countMainRounds(state, "technical");
        long projDone = countMainRounds(state, "project");
        long codingDone = countMainRounds(state, "coding");
        int textTotal = Math.max(2, state.getMaxRounds() - 1);
        int techSlots = (textTotal + 1) / 2;
        int projSlots = textTotal - techSlots;

        String nextAgent;
        String topic;
        if (codingDone >= 1) {
            // 兜底护栏：编程题已出但面试未结束（正常不应发生），改派轮次较少的一方补充
            nextAgent = techDone <= projDone ? "technical" : "project";
            topic = "technical".equals(nextAgent)
                    ? TECHNICAL_TOPICS.get((int) techDone % TECHNICAL_TOPICS.size())
                    : PROJECT_TOPICS.get((int) projDone % PROJECT_TOPICS.size());
            log.warn("编程题已出但面试未结束，改派补充题: agent={}", nextAgent);
        } else if (techDone < techSlots) {
            nextAgent = "technical";
            topic = TECHNICAL_TOPICS.get((int) techDone % TECHNICAL_TOPICS.size());
        } else if (projDone < projSlots) {
            nextAgent = "project";
            topic = PROJECT_TOPICS.get((int) projDone % PROJECT_TOPICS.size());
        } else {
            // 八股与项目均已问完 → 编程题收尾（全场仅 1 道，纯算法主题）
            nextAgent = "coding";
            topic = CODING_TOPICS.get((int) ((techDone + projDone) % CODING_TOPICS.size()));
        }
        String difficulty = difficultyOf(state.getPersona());
        log.info("固定编排路由: agent={}, topic={}, tech={}/{}, proj={}/{}, maxRounds={}",
                nextAgent, topic, techDone, techSlots, projDone, projSlots, state.getMaxRounds());

        state.setCurrentAgent(nextAgent);

        // 编程题不经过 AskNode，需在此计入轮次，保证评估后 shouldEnd 能正常终结面试
        if ("coding".equals(nextAgent)) {
            state.setCurrentRound(state.getCurrentRound() + 1);
        }

        // 对应 Agent 出题（含去重检查，最多重试3次）
        List<String> existingQuestions = state.getRounds().stream()
                .map(InterviewState.RoundRecord::getQuestion)
                .filter(q -> q != null && !q.isBlank())
                .collect(Collectors.toList());

        String persona = state.getPersona() != null ? state.getPersona() : "neutral";
        String question = generateQuestion(nextAgent, topic, difficulty, state.getResumeText(), askedTopics, persona);
        int retryCount = 0;
        while (questionDeduper.isDuplicate(question, existingQuestions) && retryCount < 3) {
            log.warn("题目重复，重新生成: retry={}, agent={}", retryCount, nextAgent);
            question = generateQuestion(nextAgent, topic, difficulty, state.getResumeText(), askedTopics, persona);
            retryCount++;
        }

        state.setCurrentQuestion(question);

        // 当路由到 coding Agent 时，设置显式挂起标志
        if ("coding".equals(nextAgent)) {
            state.setWaitingForCode(true);
        }

        return state;
    }

    /** 统计某 Agent 已完成的主轮次（追问轮继承 agentName，需排除） */
    private long countMainRounds(InterviewState state, String agent) {
        return state.getRounds().stream()
                .filter(r -> agent.equals(r.getAgentName()) && !r.isFollowup())
                .count();
    }

    /** 八股主题池（按轮次轮换，覆盖后端常见基础方向） */
    private static final List<String> TECHNICAL_TOPICS = List.of(
            "Java 基础与并发", "集合与数据结构", "计算机网络", "数据库与索引",
            "常用框架与中间件", "操作系统基础", "分布式基础概念", "综合技术");

    /** 项目主题池（按轮次轮换，从整体介绍逐步过渡到细节深挖） */
    private static final List<String> PROJECT_TOPICS = List.of(
            "项目整体介绍与职责", "技术方案与亮点", "项目难点与踩坑", "技术决策与权衡",
            "性能优化实践", "团队协作与联调");

    /** 编程题算法主题池（纯数据结构与算法，杜绝系统设计题混入） */
    private static final List<String> CODING_TOPICS = List.of(
            "数组与字符串", "链表", "哈希表", "栈与队列", "二叉树", "双指针", "排序与二分查找", "动态规划");

    /** 难度随人格调整 */
    private String difficultyOf(String persona) {
        if (persona == null) return "中等";
        return switch (persona.toLowerCase()) {
            case "gentle" -> "简单";
            case "pressure" -> "偏难";
            default -> "中等";
        };
    }

    private String generateQuestion(String nextAgent, String topic, String difficulty, String resumeText, List<String> askedTopics, String persona) {
        return switch (nextAgent) {
            case "technical" -> technicalAgent.generateQuestion(topic, difficulty, resumeText, askedTopics, persona);
            case "project" -> projectAgent.generateQuestion(topic, difficulty, resumeText, askedTopics, persona);
            case "coding" -> codingAgent.generateQuestion(topic, difficulty, resumeText, askedTopics);
            default -> "请介绍一下你的技术背景和项目经验。";
        };
    }
}
