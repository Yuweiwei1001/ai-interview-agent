package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.agent.FollowUpGenerator;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.graph.InterviewState.RoundRecord;
import com.interview.agent.interview.policy.BehaviorPolicy;
import com.interview.agent.interview.policy.BehaviorPolicyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class EvaluateNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(EvaluateNode.class);
    private final BehaviorPolicyFactory policyFactory;
    private final FollowUpGenerator followUpGenerator;

    public EvaluateNode(BehaviorPolicyFactory policyFactory, FollowUpGenerator followUpGenerator) {
        this.policyFactory = policyFactory;
        this.followUpGenerator = followUpGenerator;
    }

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("EvaluateNode: 评估回答, round={}, sessionId={}", state.getCurrentRound(), state.getSessionId());

        BehaviorPolicy policy = policyFactory.getPolicy(state.getPersona());
        String answer = state.getCurrentAnswer();
        Map<String, Object> evaluation = new HashMap<>();

        // 基础评估
        int baseScore = Math.min(100, answer.length() * 2);
        
        // 根据人格调整评分
        int score = adjustScore(baseScore, policy);
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

        return state;
    }

    private int adjustScore(int baseScore, BehaviorPolicy policy) {
        return switch (policy.evaluationStrictness()) {
            case STRICT -> Math.max(0, baseScore - 15);   // 严格：扣分
            case LENIENT -> Math.min(100, baseScore + 15); // 宽松：加分
            case STANDARD -> baseScore;                     // 标准：不变
        };
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
