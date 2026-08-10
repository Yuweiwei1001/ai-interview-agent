package com.interview.agent.interview.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.coding.CodeEvaluationEngine;
import com.interview.agent.coding.testcase.TestCaseService;
import com.interview.agent.interview.agent.AnswerEvaluator;
import com.interview.agent.interview.RoundPersistenceService;
import com.interview.agent.interview.agent.CodingAgent;
import com.interview.agent.interview.agent.FollowUpGenerator;
import com.interview.agent.interview.agent.ProjectAgent;
import com.interview.agent.interview.agent.QuestionDeduper;
import com.interview.agent.interview.agent.SpeakerAgent;
import com.interview.agent.interview.agent.TechnicalAgent;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.node.AskNode;
import com.interview.agent.interview.graph.node.CoordinatorNode;
import com.interview.agent.interview.graph.node.EvaluateNode;
import com.interview.agent.interview.graph.node.FollowUpNode;
import com.interview.agent.interview.graph.node.PlanNode;
import com.interview.agent.interview.plan.PlanGenerator;
import com.interview.agent.interview.policy.BehaviorPolicyFactory;
import com.interview.agent.memory.KnowledgePointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 构建并执行 StateGraph 单 Agent 面试图。
 *
 * <p>图结构（参照 ThinkVerse/probe 已验证模式，Phase 1b 多 Agent 编排）：
 * <pre>
 * START → plan → coordinator ─条件边─→ codingWait(挂起) → evaluate
 *                  │                    ask → evaluate ─条件边─→ 未结束→coordinator（继续）
 *                  │                                              已结束→END
 *                  └─条件边(phase=TEXT 跳过 speaker)→evaluate
 * </pre>
 *
 * <p>关键点：
 * <ul>
 *   <li>coordinator 节点按确定性固定编排路由（八股→项目→编程收尾），各 Agent 内部由 LLM 自由出题</li>
 *   <li>{@link StateGraph} 全部 key 使用 {@link ReplaceStrategy}（keyStrategyFactory）</li>
 *   <li>节点用 {@code node_async} 包装，条件边用 {@code edge_async} 包装</li>
 *   <li>领域状态 {@link InterviewState} 整体作为单一 key 存入 OverAllState，
 *       checkpoint 持久化时由 Jackson 默认序列化器携带 {@code @class} 类型信息，可原样恢复</li>
 *   <li>{@link MysqlSaver} 按 threadId（= sessionId）持久化 Checkpoint</li>
 * </ul>
 */
@Component
public class InterviewGraphBuilder {
    private static final Logger log = LoggerFactory.getLogger(InterviewGraphBuilder.class);

    /** 领域状态在 OverAllState 中的唯一 key */
    private static final String STATE_KEY = "interviewState";

    /** 全部状态 key 均使用覆盖策略（与官方示例/ThinkVerse 一致） */
    private static final List<String> STATE_KEYS = List.of(
            STATE_KEY, "sessionId", "userId", "resumeText", "jdText", "direction", "persona",
            "durationMinutes", "plan", "currentRound", "maxRounds", "rounds", "currentQuestion",
            "currentAnswer", "currentAgent", "phase", "status", "waitingForCode",
            "pendingFollowUp", "isFollowUpRound");

    private final PlanGenerator planGenerator;
    private final AskQuestionTool askQuestionTool;
    private final DataSource dataSource;
    private final TechnicalAgent technicalAgent;
    private final ProjectAgent projectAgent;
    private final CodingAgent codingAgent;
    private final SpeakerAgent speakerAgent;
    private final BehaviorPolicyFactory policyFactory;
    private final QuestionDeduper questionDeduper;
    private final FollowUpGenerator followUpGenerator;
    private final KnowledgePointService knowledgePointService;
    private final CodeEvaluationEngine codeEvaluationEngine;
    private final TestCaseService testCaseService;
    private final AnswerEvaluator answerEvaluator;
    private final RoundPersistenceService roundPersistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewGraphBuilder(PlanGenerator planGenerator, AskQuestionTool askQuestionTool, DataSource dataSource,
                                 TechnicalAgent technicalAgent,
                                 ProjectAgent projectAgent, CodingAgent codingAgent, SpeakerAgent speakerAgent,
                                 BehaviorPolicyFactory policyFactory, QuestionDeduper questionDeduper,
                                 FollowUpGenerator followUpGenerator,
                                 KnowledgePointService knowledgePointService, CodeEvaluationEngine codeEvaluationEngine,
                                 TestCaseService testCaseService, AnswerEvaluator answerEvaluator,
                                 RoundPersistenceService roundPersistenceService) {
        this.planGenerator = planGenerator;
        this.askQuestionTool = askQuestionTool;
        this.dataSource = dataSource;
        this.technicalAgent = technicalAgent;
        this.projectAgent = projectAgent;
        this.codingAgent = codingAgent;
        this.speakerAgent = speakerAgent;
        this.policyFactory = policyFactory;
        this.questionDeduper = questionDeduper;
        this.followUpGenerator = followUpGenerator;
        this.knowledgePointService = knowledgePointService;
        this.codeEvaluationEngine = codeEvaluationEngine;
        this.testCaseService = testCaseService;
        this.answerEvaluator = answerEvaluator;
        this.roundPersistenceService = roundPersistenceService;
    }

    /** 所有 state 键均使用覆盖策略（与 ThinkVerse 模式一致） */
    private KeyStrategyFactory keyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : STATE_KEYS) {
                strategies.put(key, new ReplaceStrategy());
            }
            return strategies;
        };
    }

    /**
     * 构建并编译 StateGraph（含 MySQL Checkpoint Saver）
     */
    public CompiledGraph buildGraph() throws Exception {
        // 创建节点实例
        PlanNode planNode = new PlanNode(planGenerator);
        CoordinatorNode coordinatorNode = new CoordinatorNode(technicalAgent, projectAgent, codingAgent, questionDeduper);
        AskNode askNode = new AskNode(askQuestionTool);
        EvaluateNode evaluateNode = new EvaluateNode(policyFactory, followUpGenerator, knowledgePointService,
                codeEvaluationEngine, testCaseService, answerEvaluator, roundPersistenceService);

        // 构建图（非泛型：状态为 OverAllState，领域对象整体存放于 STATE_KEY）
        StateGraph graph = new StateGraph(keyStrategyFactory());

        graph.addNode("plan", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = planNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));
        graph.addNode("coordinator", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = coordinatorNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));
        graph.addNode("ask", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = askNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));
        graph.addNode("speaker", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            // Phase 1: 文字面试直接透传；Phase 2 数字人启用语音合成
            String spoken = speakerAgent.speak(interviewState.getCurrentQuestion());
            log.info("SpeakerNode: phase={}, 输出文本长度={}", interviewState.getPhase(), spoken.length());
            return Map.of(STATE_KEY, interviewState);
        }));
        graph.addNode("evaluate", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = evaluateNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));

        graph.addEdge(START, "plan");
        graph.addEdge("plan", "coordinator");
        // codingWait 节点：Coding 环节挂起点，interruptBefore 挂起后等待代码提交
        // 节点实际执行时说明代码已提交，重置 waitingForCode 标志和状态
        graph.addNode("codingWait", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            interviewState.setWaitingForCode(false);
            interviewState.setStatus("in_progress");
            log.info("CodingWait: 代码已提交，继续执行, round={}, sessionId={}",
                    interviewState.getCurrentRound(), interviewState.getSessionId());
            return Map.of(STATE_KEY, interviewState);
        }));

        // codingRetryWait 节点：代码不达标时再次挂起，等待修改后的代码。
        // 挂起决策（waitingForCode/hint）已由 EvaluateNode 写入状态；
        // 节点实际执行时说明代码已重新提交，仅重置标志并累计重试次数。
        graph.addNode("codingRetryWait", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            interviewState.setWaitingForCode(false);
            interviewState.setStatus("in_progress");
            interviewState.setCodingRetryCount(interviewState.getCodingRetryCount() + 1);
            log.info("CodingRetryWait: 修改后代码已提交，继续评估, sessionId={}, retryCount={}",
                    interviewState.getSessionId(), interviewState.getCodingRetryCount());
            return Map.of(STATE_KEY, interviewState);
        }));

        // 条件边：coordinator → codingWait（Coding 环节）或 ask（其他环节）
        graph.addConditionalEdges("coordinator",
                edge_async(state -> {
                    InterviewState interviewState = toInterviewState(state);
                    return "coding".equals(interviewState.getCurrentAgent()) ? "codingWait" : "ask";
                }),
                Map.of("ask", "ask", "codingWait", "codingWait"));

        graph.addEdge("codingWait", "evaluate");
        graph.addEdge("codingRetryWait", "evaluate");

        // Speaker bypass：文字面试（phase == TEXT）跳过 Speaker 节点直接评估；语音面试走 Speaker 合成
        graph.addConditionalEdges("ask",
                edge_async(state -> "TEXT".equalsIgnoreCase(toInterviewState(state).getPhase()) ? "skip_speaker" : "speaker"),
                Map.of("speaker", "speaker", "skip_speaker", "evaluate"));
        graph.addEdge("speaker", "evaluate");

        // 添加 FollowUp 节点
        FollowUpNode followUpNode = new FollowUpNode(askQuestionTool);
        graph.addNode("followUp", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = followUpNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));

        // 条件边：结束→END；未结束→回到 coordinator 继续多 Agent 编排；
        // Coding 环节代码评估完成后，按行为策略分流（压力型直接切题 / 温和型给提示重试 / 中性型一次修改机会）
        // 非追问轮且有追问内容 → followUp 节点
        graph.addConditionalEdges("evaluate",
                edge_async(state -> {
                    InterviewState interviewState = toInterviewState(state);
                    // Coding 环节：waitingForCode 由 EvaluateNode 统一决策（唯一事实来源）；
                    // 重试优先于结束判断（新编排下编程题恒为最后一题，currentRound 已达 maxRounds）
                    if ("coding".equals(interviewState.getCurrentAgent())) {
                        if (interviewState.isWaitingForCode()) {
                            return "codingRetryWait";
                        }
                        if (shouldEnd(interviewState)) {
                            return "end";
                        }
                        return "coordinator";
                    }
                    if (shouldEnd(interviewState)) {
                        return "end";
                    }
                    // 非追问轮且有追问内容 → followUp
                    if (!interviewState.isFollowUpRound()
                            && interviewState.getPendingFollowUp() != null
                            && !interviewState.getPendingFollowUp().isBlank()) {
                        return "followUp";
                    }
                    return "coordinator";
                }),
                Map.of("coordinator", "coordinator", "end", END, "codingRetryWait", "codingRetryWait", "followUp", "followUp"));

        graph.addEdge("followUp", "evaluate");

        // MySQL Checkpoint Saver
        MysqlSaver saver = MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();

        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .recursionLimit(50)
                .interruptBefore("codingWait", "codingRetryWait")
                .build());
    }

    /**
     * 从 OverAllState 中取出 InterviewState。
     * 正常情况下直接取回领域对象；若 checkpoint 恢复时被反序列化为 Map，则用 Jackson 兜底转换。
     */
    private InterviewState toInterviewState(OverAllState state) {
        Object value = state.data().get(STATE_KEY);
        if (value instanceof InterviewState interviewState) {
            return interviewState;
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, InterviewState.class);
        }
        log.warn("状态中缺少 interviewState（或类型异常: {}），返回空状态", value == null ? "null" : value.getClass().getSimpleName());
        return new InterviewState();
    }

    /**
     * 判断是否结束面试
     */
    private boolean shouldEnd(InterviewState state) {
        // 结束条件以面试计划轮次为准（maxRounds 由 PlanNode 按面试时长生成）。
        // 注：历史上的「连续3轮达标提前结束」在文本评分失真（长度启发式恒定满分）时会让面试在
        //     编程题后直接终结，候选人感知为“没有进入下一题”，与计划轮次冲突，故移除；
        //     如需恢复提前结束特性，应以真实评分为前提并设置最低轮次门槛。
        return state.getCurrentRound() >= state.getMaxRounds();
    }

    /**
     * 运行面试图（threadId = sessionId，checkpoint 按 threadId 持久化到 MySQL）
     */
    public InterviewState executeInterview(InterviewState initialState) throws Exception {
        CompiledGraph compiled = buildGraph();
        String threadId = initialState.getSessionId();

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        Optional<OverAllState> result = compiled.invoke(Map.of(STATE_KEY, initialState), config);
        return result.map(this::toInterviewState).orElse(initialState);
    }

    /**
     * 从 Checkpoint 恢复面试（同一 threadId 再次执行，从断点继续）
     */
    public InterviewState resumeInterview(String sessionId) throws Exception {
        CompiledGraph compiled = buildGraph();

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        if (compiled.stateOf(config).isEmpty()) {
            throw new IllegalStateException("未找到会话 checkpoint，无法恢复: " + sessionId);
        }

        // 空输入 + 已存在 checkpoint：从最新 checkpoint 的 nextNodeId 继续执行
        Optional<OverAllState> result = compiled.invoke(Map.of(), config);
        return result.map(this::toInterviewState).orElse(null);
    }

    /**
     * 从 Checkpoint 恢复 Coding 面试（携带代码提交，从 codingWait 继续执行）
     *
     * @param sessionId 会话 ID
     * @param code      提交的代码
     * @param language  编程语言
     * @return 恢复执行后的面试状态
     */
    public InterviewState resumeInterview(String sessionId, String code, String language) throws Exception {
        CompiledGraph compiled = buildGraph();

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .resume()
                .build();

        Optional<StateSnapshot> currentState = compiled.stateOf(config);
        if (currentState.isEmpty()) {
            throw new IllegalStateException("未找到会话 checkpoint，无法恢复: " + sessionId);
        }

        // 从 Checkpoint 获取当前状态，注入代码作为答案
        InterviewState state = toInterviewState(currentState.get().state());
        state.setCurrentAnswer(code);
        state.setCurrentLanguage(language);
        state.setStatus("in_progress");

        // 携带更新后的状态恢复执行（从 codingWait 节点继续）
        Optional<OverAllState> result = compiled.invoke(Map.of(STATE_KEY, state), config);
        return result.map(this::toInterviewState).orElse(null);
    }
}
