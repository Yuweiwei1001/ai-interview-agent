package com.interview.agent.interview.graph.node;

import com.interview.agent.coding.CodeEvaluationEngine;
import com.interview.agent.coding.CodeEvaluationResult;
import com.interview.agent.coding.testcase.TestCaseService;
import com.interview.agent.interview.agent.AnswerEvaluator;
import com.interview.agent.interview.agent.FollowUpGenerator;
import com.interview.agent.interview.RoundPersistenceService;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.graph.InterviewState.RoundRecord;
import com.interview.agent.interview.policy.BehaviorPolicy;
import com.interview.agent.interview.policy.BehaviorPolicyFactory;
import com.interview.agent.memory.KnowledgePointService;
import com.interview.agent.observability.LlmTraceObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class EvaluateNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(EvaluateNode.class);
    private final BehaviorPolicyFactory policyFactory;
    private final FollowUpGenerator followUpGenerator;
    private final KnowledgePointService knowledgePointService;
    private final CodeEvaluationEngine codeEvaluationEngine;
    private final TestCaseService testCaseService;
    private final AnswerEvaluator answerEvaluator;
    private final RoundPersistenceService roundPersistenceService;
    private final LlmTraceObservationHandler traceHandler;

    public EvaluateNode(BehaviorPolicyFactory policyFactory, FollowUpGenerator followUpGenerator,
                        KnowledgePointService knowledgePointService, CodeEvaluationEngine codeEvaluationEngine,
                        TestCaseService testCaseService, AnswerEvaluator answerEvaluator,
                        RoundPersistenceService roundPersistenceService,
                        LlmTraceObservationHandler traceHandler) {
        this.policyFactory = policyFactory;
        this.followUpGenerator = followUpGenerator;
        this.knowledgePointService = knowledgePointService;
        this.codeEvaluationEngine = codeEvaluationEngine;
        this.testCaseService = testCaseService;
        this.answerEvaluator = answerEvaluator;
        this.roundPersistenceService = roundPersistenceService;
        this.traceHandler = traceHandler;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("EvaluateNode: 评估回答, round={}, sessionId={}", state.getCurrentRound(), state.getSessionId());

        BehaviorPolicy policy = policyFactory.getPolicy(state.getPersona());
        String answer = state.getCurrentAnswer();
        Map<String, Object> evaluation = new HashMap<>();

        // 基础评估（Coding 环节使用代码评估引擎，文本题由 LLM 真实评分）
        int baseScore;
        String llmSummary = null;
        List<String> llmKnowledgePoints = List.of();
        if ("coding".equals(state.getCurrentAgent())) {
            baseScore = evaluateCodingCode(state, evaluation);
        } else {
            // 历史的长度启发式（answer.length()*2）会让长回答恒定满分，触发面试提前结束，已替换为 LLM 评分
            // P0 热词注入：语音面试的回答经 ASR 转写，注入会话热词表 + 噪声声明让评分容忍同音错字
            List<String> hotwords = state.getSessionHotwords() == null ? List.of() : state.getSessionHotwords();
            boolean asrTranscribed = "VOICE".equalsIgnoreCase(state.getPhase());
            AnswerEvaluator.EvaluationResult textEval = answerEvaluator.evaluate(
                    state.getCurrentQuestion(), answer, hotwords, asrTranscribed);
            baseScore = textEval.score();
            llmSummary = textEval.summary();
            llmKnowledgePoints = textEval.knowledgePoints();
            // 沟通表达分随评估落库，报告阶段聚合为 communication 维度（此前从未写入导致报告恒为 0）
            evaluation.put("communicationScore", textEval.communication());
            log.info("EvaluateNode: 文本题评分完成, round={}, sessionId={}, score={}, communication={}, knowledgePoints={}",
                    state.getCurrentRound(), state.getSessionId(), baseScore, textEval.communication(), llmKnowledgePoints);
        }
        
        // 根据人格调整评分
        int score = adjustScore(baseScore, policy);
        state.setCodingScore(score);
        evaluation.put("score", score);
        // 评分回写：把本轮调整分写到该轮全部 llm_trace 行（含检索 span，按 roundTraceId 关联）；
        // 入队异步执行，单线程顺序保证晚于同轮已入队的 insert；任何异常不影响主链路
        try {
            traceHandler.submitEvalWriteback(state.getRoundTraceId(), score);
        } catch (Exception e) {
            log.warn("评分回写 llm_trace 失败（已忽略）", e);
        }
        // 知识点优先用 LLM 从题目+回答提取的具体概念；LLM 未输出时降级为关键词匹配兜底
        List<String> knowledgePoints = (llmKnowledgePoints != null && !llmKnowledgePoints.isEmpty())
                ? llmKnowledgePoints
                : extractKnowledgePoints(answer);
        evaluation.put("knowledgePoints", knowledgePoints);
        evaluation.put("completeness", score >= 60 ? "good" : "needs_improvement");
        evaluation.put("summary", llmSummary != null && !llmSummary.isBlank()
                ? llmSummary
                : (score >= 60 ? "回答基本完整" : "回答不够充分，需要进一步考察"));

        // 策略决策
        boolean shouldRetry = policy.shouldAllowRetry(state.getCurrentRound(), score);
        boolean shouldGiveHint = policy.shouldGiveHint(state.getCurrentRound(), score);
        String hint = shouldGiveHint ? policy.generateHint(state.getCurrentQuestion(), answer, score) : "";
        
        evaluation.put("shouldRetry", shouldRetry);
        evaluation.put("shouldGiveHint", shouldGiveHint);
        evaluation.put("hint", hint);
        evaluation.put("strictness", policy.evaluationStrictness().name());
        evaluation.put("followUpStrategy", policy.followUpStrategy().name());

        // Coding 环节：评估后判定是否挂起等待重试。
        // waitingForCode 是图中 decideCodingNext 边路由的唯一事实来源，必须在此（节点体内）设置，
        // 否则 interruptBefore(codingRetryWait) 挂起时节点体不执行，外层无法感知挂起原因。
        if ("coding".equals(state.getCurrentAgent())) {
            boolean degraded = Boolean.TRUE.equals(evaluation.get("degraded"));
            decideCodingRetry(state, policy, score, degraded);
        }

        // 评估完成后，根据策略生成追问
        // 仅在非追问轮且非 coding 环节时生成追问
        String followUp = "";
        if (!state.isFollowUpRound() && !"coding".equals(state.getCurrentAgent())) {
            followUp = followUpGenerator.generateFollowUp(
                    state.getCurrentQuestion(),
                    state.getCurrentAnswer(),
                    evaluation,
                    policy,
                    state.getPersona()
            );
            state.setPendingFollowUp(followUp);
        }
        evaluation.put("followUp", followUp);

        // 记录到 rounds
        RoundRecord record = new RoundRecord();
        // 追问轮沿用所属主轮题号（最近一条非追问记录的题号）；
        // 与 SSE 事件题号保持一致且连续（编程题占一个题号）
        if (state.isFollowUpRound()) {
            record.setRoundNumber(state.getRounds().stream()
                    .filter(r -> !r.isFollowup())
                    .reduce((first, second) -> second)
                    .map(InterviewState.RoundRecord::getRoundNumber)
                    .orElse(state.getCurrentRound()));
        } else {
            long distinctMainRounds = state.getRounds().stream()
                    .filter(r -> !r.isFollowup())
                    .map(InterviewState.RoundRecord::getRoundNumber)
                    .distinct()
                    .count();
            boolean codingRetry = "coding".equals(state.getCurrentAgent()) && state.getCodingRetryCount() > 0;
            record.setRoundNumber((int) distinctMainRounds + (codingRetry ? 0 : 1));
        }
        record.setAgentName(state.getCurrentAgent());
        record.setTopic(state.getCurrentQuestion() != null ? state.getCurrentQuestion().substring(0, Math.min(50, state.getCurrentQuestion().length())) : "");
        record.setQuestion(state.getCurrentQuestion());
        record.setAnswer(state.getCurrentAnswer());
        record.setEvaluation(evaluation);
        // 追问轮标记
        if (state.isFollowUpRound()) {
            record.setFollowup(true);
            record.setFollowupTarget((long) record.getRoundNumber());
        }
        state.getRounds().add(record);

        // 增量持久化本轮（面试进行中即可从历史恢复，刷新/重进页面不丢对话）
        roundPersistenceService.saveRound(state.getSessionId(), record);

        // 追问轮评估完成后重置标志
        if (state.isFollowUpRound()) {
            state.setIsFollowUpRound(false);
        }

        // 评估完成后更新知识点
        try {
            List<String> pointsForMemory = (List<String>) evaluation.get("knowledgePoints");
            knowledgePointService.updateFromEvaluation(pointsForMemory, evaluation);
        } catch (Exception e) {
            log.warn("知识点更新失败", e);
        }

        return state;
    }

    /**
     * Coding 环节代码评估后的重试决策：
     * 达标 / 人格不给重试机会 / 已达重试上限 → 不挂起，流向 coordinator 或 END；
     * 否则置 waitingForCode=true 并生成提示，图将在 codingRetryWait 前挂起，等待重新提交代码。
     * 注：新编排下编程题恒为最后一题，不再用「最后一轮不重试」拦截（重试不消耗新题，由 maxRetry 封顶）。
     */
    private void decideCodingRetry(InterviewState state, BehaviorPolicy policy, int score, boolean degraded) {
        int passThreshold = switch (policy.evaluationStrictness()) {
            case STRICT -> 80;
            case STANDARD -> 60;
            case LENIENT -> 40;
        };
        if (score >= passThreshold) {
            return;
        }
        String persona = state.getPersona() == null ? "neutral" : state.getPersona().toLowerCase();
        int maxRetry = switch (persona) {
            case "pressure" -> 0;
            case "gentle" -> 2;
            default -> 1;
        };
        if (state.getCodingRetryCount() >= maxRetry) {
            log.info("Coding 不重试，直接切题: score={}, retryCount={}, round={}/{}, sessionId={}",
                    score, state.getCodingRetryCount(), state.getCurrentRound(), state.getMaxRounds(), state.getSessionId());
            return;
        }
        String retryHint = policy.generateHint(state.getCurrentQuestion(), state.getCurrentAnswer(), score);
        if (retryHint == null || retryHint.isBlank()) {
            retryHint = "当前代码未通过评估，请检查逻辑与边界情况后重新提交。";
        }
        // 服务降级（LLM 不可用、沙箱异常等）导致的低分：提示服务异常而非代码问题，
        // 避免候选人反复修改本来正确的代码
        if (degraded) {
            retryHint = "评估服务暂不可用（已按测试结果降级评分），代码本身可能没有问题，请稍后重新提交试试";
        }
        state.setCodingHint(retryHint);
        state.setWaitingForCode(true);
        log.info("Coding 将挂起等待重试: score={}, retryCount={}, sessionId={}",
                score, state.getCodingRetryCount(), state.getSessionId());
    }

    private int adjustScore(int baseScore, BehaviorPolicy policy) {
        return switch (policy.evaluationStrictness()) {
            case STRICT -> Math.max(0, baseScore - 15);   // 严格：扣分
            case LENIENT -> Math.min(100, baseScore + 15); // 宽松：加分
            case STANDARD -> baseScore;                     // 标准：不变
        };
    }

    /**
     * Coding 环节：使用代码评估引擎进行多维评估
     * 运行沙箱测试用例（无预设用例时由 AI 生成动态用例）+ LLM 多维评分，
     * 评估结果写入 evaluation 并返回综合评分作为基础分。
     */
    private int evaluateCodingCode(InterviewState state, Map<String, Object> evaluation) {
        String code = state.getCurrentAnswer() != null ? state.getCurrentAnswer() : "";
        String language = state.getCurrentLanguage() != null ? state.getCurrentLanguage() : "java";
        String question = state.getCurrentQuestion() != null ? state.getCurrentQuestion() : "算法题";

        try {
            // 1. 运行测试用例（预设为空时走 AI 动态用例生成）
            TestCaseService.TestRunResult testResult = testCaseService.runWithDynamicTests(
                    code, language, question, List.of());

            // 2. 多维评估（正确性/质量/边界/复杂度/测试通过率）
            CodeEvaluationResult codeEval = codeEvaluationEngine.evaluate(code, language, question, testResult);

            evaluation.put("correctness", codeEval.getCorrectness());
            evaluation.put("codeQuality", codeEval.getCodeQuality());
            evaluation.put("edgeCaseHandling", codeEval.getEdgeCaseHandling());
            evaluation.put("timeComplexity", codeEval.getTimeComplexity());
            evaluation.put("testPassRate", codeEval.getTestPassRate());
            evaluation.put("suggestions", codeEval.getSuggestions() != null ? codeEval.getSuggestions() : List.of());
            evaluation.put("codeSummary", codeEval.getSummary());
            if (codeEval.isDegraded()) {
                evaluation.put("degraded", true);
            }

            log.info("Coding 评估完成: sessionId={}, overallScore={}, testPassRate={}",
                    state.getSessionId(), codeEval.getOverallScore(), codeEval.getTestPassRate());
            return codeEval.getOverallScore();
        } catch (Exception e) {
            log.warn("代码评估失败，标记为降级评分: sessionId={}", state.getSessionId(), e);
            evaluation.put("codeSummary", "代码评估失败（沙箱或评分服务异常），按降级规则评分");
            evaluation.put("degraded", true);
            // 评估链路异常（非代码问题）：给中性分，由降级提示引导重试，不再用长度启发式
            return 40;
        }
    }

    /** 关键词匹配兜底：仅在 LLM 未返回知识点时使用（粗粒度，勿作为主路径） */
    private java.util.List<String> extractKnowledgePoints(String answer) {
        java.util.List<String> points = new java.util.ArrayList<>();
        if (answer == null || answer.isBlank()) return points;
        String[] keywords = {"Java", "Spring", "分布式", "微服务", "数据库", "Redis", "MQ", "Docker", "K8s", "算法", "设计模式"};
        for (String kw : keywords) {
            if (answer.contains(kw)) {
                points.add(kw);
            }
        }
        return points;
    }
}
