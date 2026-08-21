package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.CodingAgent;
import com.interview.agent.interview.agent.ProjectAgent;
import com.interview.agent.interview.agent.QuestionDeduper;
import com.interview.agent.interview.agent.TechnicalAgent;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.observability.LlmTraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 面试编排节点：确定性固定工作流（不再由 LLM 决定环节顺序）。
 *
 * <p>环节顺序：八股（technical）→ 项目（project）→ 编程（coding，恒为最后一题）。
 * 总轮次以 maxRounds 为准：编程固定占 1 轮，剩余轮次技术（向上取整）与项目均分。
 * 环节内的考察主题与难度取自面试计划的 agentAssignments（按轮次顺序消费），
 * 并注入计划 weakPointPriority 与候选人历史薄弱知识点引导优先考察；无计划时回退内置主题池。
 * 候选人历史薄弱知识点改为由 LLM 通过 getCandidateWeakPoints 工具按需获取。
 */
public class CoordinatorNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorNode.class);
    private static final int RECENT_ROUNDS = 3; // 最近 N 轮全量对话注入 prompt
    private final TechnicalAgent technicalAgent;
    private final ProjectAgent projectAgent;
    private final CodingAgent codingAgent;
    private final QuestionDeduper questionDeduper;
    private final ChatClient chatClient;

    public CoordinatorNode(TechnicalAgent technicalAgent,
                          ProjectAgent projectAgent, CodingAgent codingAgent,
                          QuestionDeduper questionDeduper, ChatClient.Builder builder) {
        this.technicalAgent = technicalAgent;
        this.projectAgent = projectAgent;
        this.codingAgent = codingAgent;
        this.questionDeduper = questionDeduper;
        this.chatClient = builder.build();
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("CoordinatorNode: 决定下一个Agent, round={}", state.getCurrentRound() + 1);

        // 新轮次观测关联 ID：串联本轮出题/检索/评分/追问的 llm_trace 行；
        // 追问轮不经过 Coordinator（followUp → evaluate），自然沿用主轮 traceId。
        // 注意：withTraceContext 在节点体执行前已用旧 state 设置 ThreadLocal，
        // 这里生成新 ID 后必须立即刷新当前线程上下文，否则本节点内的出题/检索 span 会拿到上一轮的 traceId
        state.setRoundTraceId("rt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        LlmTraceContextHolder.setSessionAndRound(state.getSessionId(), state.getRoundTraceId());

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
        if (codingDone >= 1) {
            // 兜底护栏：编程题已出但面试未结束（正常不应发生），改派轮次较少的一方补充
            nextAgent = techDone <= projDone ? "technical" : "project";
            log.warn("编程题已出但面试未结束，改派补充题: agent={}", nextAgent);
        } else if (techDone < techSlots) {
            nextAgent = "technical";
        } else if (projDone < projSlots) {
            nextAgent = "project";
        } else {
            // 八股与项目均已问完 → 编程题收尾（全场仅 1 道，纯算法主题）
            nextAgent = "coding";
        }

        // 计划驱动出题：主题/难度优先取自面试计划的 agentAssignments，无计划时回退内置主题池
        InterviewPlan plan = state.getPlan();
        InterviewPlan.AgentAssignment assignment =
                (plan != null && plan.getAgentAssignments() != null) ? plan.getAgentAssignments().get(nextAgent) : null;
        String topic = pickTopic(nextAgent, assignment, techDone, projDone);
        String difficulty = (assignment != null && assignment.getDifficulty() != null && !assignment.getDifficulty().isBlank())
                ? normalizeDifficulty(assignment.getDifficulty(), state.getPersona())
                : difficultyOf(state.getPersona());
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
        // 会话热词（ASR 热词纠错方案 P0）：出题优先围绕候选人术语体系，使用官方写法
        List<String> sessionHotwords = state.getSessionHotwords() == null ? List.of() : state.getSessionHotwords();
        // 长期记忆薄弱点已改为由 LLM 通过 getCandidateWeakPoints 工具按需获取，
        // 不再由 CoordinatorNode 加载并注入 agent prompt
        // 对话历史：最近 3 轮全量注入，更早的轮次由 LLM 生成的摘要代替
        String conversationHistory = formatConversationHistory(state.getRounds());
        String recentConversation = formatRecentConversation(state.getRounds(), RECENT_ROUNDS);
        String conversationSummary = updateConversationSummary(state);
        String question = generateQuestion(nextAgent, topic, difficulty, state.getResumeText(), askedTopics, persona, sessionHotwords, conversationHistory, recentConversation, conversationSummary);
        int retryCount = 0;
        while (questionDeduper.isDuplicate(question, existingQuestions) && retryCount < 3) {
            log.warn("题目重复，重新生成: retry={}, agent={}", retryCount, nextAgent);
            question = generateQuestion(nextAgent, topic, difficulty, state.getResumeText(), askedTopics, persona, sessionHotwords, conversationHistory, recentConversation, conversationSummary);
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

    /**
     * 选题：计划 agentAssignments 的 topics 按已消耗轮次顺序消费（保证出题与计划一致）；
     * 无计划/无 topics 时回退内置主题池轮换。
     */
    private String pickTopic(String nextAgent, InterviewPlan.AgentAssignment assignment, long techDone, long projDone) {
        // 编程题：恒用内置算法池按已轮次轮换（每场仅 1 道，轮换保证题不重复）。
        // 不依赖计划 topics：fallbackPlan 的 coding topics 恒单元素，若按计划取将场场同主题、
        // LLM 在该主题下总输出同一道经典题（如"和为K的子数组"）。
        if ("coding".equals(nextAgent)) {
            return CODING_TOPICS.get((int) ((techDone + projDone) % CODING_TOPICS.size()));
        }
        if (assignment != null && assignment.getTopics() != null && !assignment.getTopics().isEmpty()) {
            List<String> topics = assignment.getTopics();
            long consumed = "technical".equals(nextAgent) ? techDone : projDone;
            return topics.get((int) (consumed % topics.size()));
        }
        return "technical".equals(nextAgent)
                ? TECHNICAL_TOPICS.get((int) techDone % TECHNICAL_TOPICS.size())
                : PROJECT_TOPICS.get((int) projDone % PROJECT_TOPICS.size());
    }

    /** 计划难度归一化（兼容 LLM 输出 medium/中等 等写法）；无法识别时回退人格难度 */
    private String normalizeDifficulty(String planDifficulty, String persona) {
        String d = planDifficulty.toLowerCase();
        if (d.contains("easy") || d.contains("简单")) return "简单";
        if (d.contains("hard") || d.contains("难")) return "偏难";
        if (d.contains("medium") || d.contains("中")) return "中等";
        return difficultyOf(persona);
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

    private String generateQuestion(String nextAgent, String topic, String difficulty, String resumeText,
                                    List<String> askedTopics, String persona,
                                    List<String> sessionHotwords, String conversationHistory, String recentConversation, String conversationSummary) {
        return switch (nextAgent) {
            case "technical" -> technicalAgent.generateQuestion(topic, difficulty, resumeText, askedTopics, persona, sessionHotwords, conversationHistory, recentConversation, conversationSummary);
            case "project" -> projectAgent.generateQuestion(topic, difficulty, resumeText, askedTopics, persona, sessionHotwords, conversationHistory, recentConversation, conversationSummary);
            case "coding" -> codingAgent.generateQuestion(topic, difficulty, resumeText, askedTopics);
            default -> "请介绍一下你的技术背景和项目经验。";
        };
    }

    /**
     * 更新对话摘要：当已完成轮次超过 RECENT_ROUNDS 且摘要未覆盖所有早期轮次时，
     * 调用 LLM 生成/更新前情摘要。
     * 摘要覆盖 rounds[0..n-RECENT_ROUNDS-1]（即除最近 3 轮外的所有轮次），
     * 最近 3 轮以全量 Q&A 注入 prompt。
     */
    private String updateConversationSummary(InterviewState state) {
        List<InterviewState.RoundRecord> rounds = state.getRounds();
        if (rounds == null || rounds.size() <= RECENT_ROUNDS) {
            return ""; // 轮次太少，无需摘要
        }

        int summaryEnd = rounds.size() - RECENT_ROUNDS; // 需要摘要覆盖的轮次数
        if (state.getSummarizedRoundCount() >= summaryEnd) {
            return state.getConversationSummary(); // 摘要已是最新
        }

        // 生成摘要：使用旧摘要 + 新增轮次对话
        StringBuilder sb = new StringBuilder();
        String oldSummary = state.getConversationSummary();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("旧摘要：").append(oldSummary).append("\n\n");
        }
        sb.append("新增对话：\n");
        int start = Math.max(0, state.getSummarizedRoundCount());
        for (int i = start; i < summaryEnd; i++) {
            InterviewState.RoundRecord round = rounds.get(i);
            if (round.getQuestion() != null) {
                sb.append("面试官：").append(round.getQuestion()).append("\n");
            }
            if (round.getAnswer() != null) {
                sb.append("候选人：").append(round.getAnswer()).append("\n");
            }
            sb.append("\n");
        }

        String prompt = sb.toString();
        log.info("生成对话摘要: summaryEnd={},新增轮次={}", summaryEnd, summaryEnd - start);

        try {
            String summary = chatClient.prompt()
                    .user("你是一个面试对话摘要助手。请根据以下面试对话，生成一个简洁的前情摘要（约100-150字），"
                            + "概括候选人已经展示的能力、薄弱点和关键回答。摘要将用于后续出题时的参考。\n\n"
                            + prompt)
                    .call()
                    .content();
            if (summary != null && !summary.isBlank()) {
                state.setConversationSummary(summary);
                state.setSummarizedRoundCount(summaryEnd);
                log.info("对话摘要生成成功: {}", summary);
                return summary;
            }
        } catch (Exception e) {
            log.warn("对话摘要生成失败，沿用旧摘要", e);
        }

        // 失败时返回旧摘要（如果有）
        return state.getConversationSummary() != null ? state.getConversationSummary() : "";
    }

    /**
     * 将已完成轮次格式化为完整对话历史文本，供 LLM 通过 getConversationHistory 工具按需获取。
     * 格式：每轮包含面试官题目和候选人回答，不含评估结果。
     */
    private String formatConversationHistory(List<InterviewState.RoundRecord> rounds) {
        if (rounds == null || rounds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (InterviewState.RoundRecord round : rounds) {
            String question = round.getQuestion();
            String answer = round.getAnswer();
            if (question == null || question.isBlank()) continue;
            sb.append("面试官：").append(question).append("\n");
            if (answer != null && !answer.isBlank()) {
                sb.append("候选人：").append(answer).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 提取最近 N 轮对话历史，用于自动注入 prompt（短期记忆）。
     * LLM 可以直接看到最近几轮的问答，更早的通过 getConversationHistory 工具按需获取。
     */
    private String formatRecentConversation(List<InterviewState.RoundRecord> rounds, int count) {
        if (rounds == null || rounds.isEmpty()) return "";
        int start = Math.max(0, rounds.size() - count);
        return formatConversationHistory(rounds.subList(start, rounds.size()));
    }
}