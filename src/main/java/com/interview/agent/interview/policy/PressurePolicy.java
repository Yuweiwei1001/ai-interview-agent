package com.interview.agent.interview.policy;

import org.springframework.stereotype.Component;

/**
 * 压力型面试策略
 * - 不给予重试机会
 * - 不给提示
 * - 严格评估
 * - 回答不达标时压迫式追问（追问是施压与深挖手段，而非扶持；不放水、不给第二次机会）
 */
@Component
public class PressurePolicy implements BehaviorPolicy {

    @Override
    public boolean shouldAllowRetry(int roundNumber, int score) {
        return false;
    }

    @Override
    public boolean shouldGiveHint(int roundNumber, int score) {
        return false;
    }

    @Override
    public String generateHint(String question, String answer, int score) {
        return "";
    }

    @Override
    public EvaluationStrictness evaluationStrictness() {
        return EvaluationStrictness.STRICT;
    }

    @Override
    public FollowUpStrategy followUpStrategy() {
        return FollowUpStrategy.ON_FAILURE;
    }
}
