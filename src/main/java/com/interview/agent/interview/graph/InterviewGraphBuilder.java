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
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.node.AskNode;
import com.interview.agent.interview.graph.node.EvaluateNode;
import com.interview.agent.interview.graph.node.PlanNode;
import com.interview.agent.interview.plan.PlanGenerator;
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
 * <p>图结构（参照 ThinkVerse/probe 已验证模式）：
 * <pre>
 * START → plan → ask → evaluate ──条件边──→ 未达标/未超限→ask（继续）
 *                                           达标/轮次超限→END
 * </pre>
 *
 * <p>关键点：
 * <ul>
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
            "currentAnswer", "currentAgent", "status");

    private final PlanGenerator planGenerator;
    private final AskQuestionTool askQuestionTool;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewGraphBuilder(PlanGenerator planGenerator, AskQuestionTool askQuestionTool, DataSource dataSource) {
        this.planGenerator = planGenerator;
        this.askQuestionTool = askQuestionTool;
        this.dataSource = dataSource;
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
        AskNode askNode = new AskNode(askQuestionTool);
        EvaluateNode evaluateNode = new EvaluateNode();

        // 构建图（非泛型：状态为 OverAllState，领域对象整体存放于 STATE_KEY）
        StateGraph graph = new StateGraph(keyStrategyFactory());

        graph.addNode("plan", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = planNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));
        graph.addNode("ask", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = askNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));
        graph.addNode("evaluate", node_async((NodeAction) state -> {
            InterviewState interviewState = toInterviewState(state);
            InterviewState updated = evaluateNode.apply(interviewState);
            return Map.of(STATE_KEY, updated);
        }));

        graph.addEdge(START, "plan");
        graph.addEdge("plan", "ask");
        graph.addEdge("ask", "evaluate");

        // 条件边：达标或轮次超限→END；未达标→回到ask
        graph.addConditionalEdges("evaluate",
                edge_async(state -> shouldEnd(toInterviewState(state)) ? "end" : "ask"),
                Map.of("ask", "ask", "end", END));

        // MySQL Checkpoint Saver
        MysqlSaver saver = MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();

        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .recursionLimit(50)
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
        if (state.getCurrentRound() >= state.getMaxRounds()) {
            return true;
        }
        // 简单结束条件：连续3轮得分>=60视为通过
        if (state.getRounds().size() >= 3) {
            List<InterviewState.RoundRecord> last3 = state.getRounds()
                    .subList(state.getRounds().size() - 3, state.getRounds().size());
            boolean allPassed = last3.stream()
                    .allMatch(r -> {
                        Object score = r.getEvaluation().get("score");
                        return score instanceof Number && ((Number) score).intValue() >= 60;
                    });
            if (allPassed) {
                return true;
            }
        }
        return false;
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
}
