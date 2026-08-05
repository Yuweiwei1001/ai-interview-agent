package com.interview.agent.interview.graph.node;

import com.interview.agent.coding.CodeEvaluationEngine;
import com.interview.agent.coding.CodeEvaluationResult;
import com.interview.agent.coding.testcase.TestCaseService;
import com.interview.agent.interview.agent.FollowUpGenerator;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.graph.InterviewState.RoundRecord;
import com.interview.agent.interview.policy.BehaviorPolicy;
import com.interview.agent.interview.policy.BehaviorPolicyFactory;
import com.interview.agent.memory.KnowledgePointService;
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

    public EvaluateNode(BehaviorPolicyFactory policyFactory, FollowUpGenerator followUpGenerator,
                        KnowledgePointService knowledgePointService, CodeEvaluationEngine codeEvaluationEngine,
                        TestCaseService testCaseService) {
        this.policyFactory = policyFactory;
        this.followUpGenerator = followUpGenerator;
        this.knowledgePointService = knowledgePointService;
        this.codeEvaluationEngine = codeEvaluationEngine;
        this.testCaseService = testCaseService;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("EvaluateNode: 评估回答, round={}, sessionId={}", state.getCurrentRound(), state.getSessionId());

        BehaviorPolicy policy = policyFactory.getPolicy(state.getPersona());
        String answer = state.getCurrentAnswer();
        Map<String, Object> evaluation = new HashMap<>();

        // 基础评估（Coding 环节使用代码评估引擎，其他环节按回答长度）
        int baseScore;
        if ("coding".equals(state.getCurrentAgent())) {
            baseScore = evaluateCodingCode(state, evaluation);
        } else {
            baseScore = Math.min(100, (answer != null ? answer.length() : 0) * 2);
        }
        
        // 根据人格调整评分
        int score = adjustScore(baseScore, policy);
        state.setCodingScore(score);
        evaluation.put("score", score);
        evaluation.put("knowledgePoints", extractKnowledgePoints(answer));
        evaluation.put("completeness", score >= 60 ? "good" : "needs_improvement");
        evaluation.put("summary", score >= 60 ? "回答基本完整" : "回答不够充分，需要进一步考察");

        // 策略决策
        boolean shouldRetry = policy.shouldAllowRetry(state.getCurrentRound(), score);
        boolean shouldGiveHint = policy.shouldGiveHint(state.getCurrentRound(), score);
        String hint = shouldGiveHint ? policy.generateHint(state.getCurrentQuestion(), answer, score) : "";
        
        evaluation.put("shouldRetry", shouldRetry);
        evaluation.put("shouldGiveHint", shouldGiveHint);
        evaluation.put("hint", hint);
        evaluation.put("strictness", policy.evaluationStrictness().name());
        evaluation.put("followUpStrategy", policy.followUpStrategy().name());

        // 评估完成后，根据策略生成追问
        String followUp = followUpGenerator.generateFollowUp(
                state.getCurrentQuestion(),
                state.getCurrentAnswer(),
                evaluation,
                policy
        );
        evaluation.put("followUp", followUp);

        // 记录到 rounds
        RoundRecord record = new RoundRecord();
        record.setRoundNumber(state.getCurrentRound());
        record.setAgentName(state.getCurrentAgent());
        record.setTopic(state.getCurrentQuestion() != null ? state.getCurrentQuestion().substring(0, Math.min(50, state.getCurrentQuestion().length())) : "");
        record.setQuestion(state.getCurrentQuestion());
        record.setAnswer(state.getCurrentAnswer());
        record.setEvaluation(evaluation);
        state.getRounds().add(record);

        // 评估完成后更新知识点
        try {
            List<String> knowledgePoints = (List<String>) evaluation.get("knowledgePoints");
            knowledgePointService.updateFromEvaluation(knowledgePoints, evaluation);
        } catch (Exception e) {
            log.warn("知识点更新失败", e);
        }

        return state;
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

            log.info("Coding 评估完成: sessionId={}, overallScore={}, testPassRate={}",
                    state.getSessionId(), codeEval.getOverallScore(), codeEval.getTestPassRate());
            return codeEval.getOverallScore();
        } catch (Exception e) {
            log.warn("代码评估失败，使用基础评分: sessionId={}", state.getSessionId(), e);
            evaluation.put("codeSummary", "代码评估失败，按基础规则评分");
            return Math.min(100, code.length() * 2);
        }
    }

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
