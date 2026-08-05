package com.interview.agent.interview.policy;

import org.springframework.stereotype.Component;

/**
 * 中性型面试策略（默认）
 * - 允许重试一次
 * - 不主动给提示
 * - 标准评估
 * - 回答不达标时追问
 */
@Component
public class NeutralPolicy implements BehaviorPolicy {

    @Override
    public boolean shouldAllowRetry(int roundNumber, int score) {
        // 仅允许一次重试
        return score < 60 && roundNumber <= 1;
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
        return EvaluationStrictness.STANDARD;
    }

    @Override
    public FollowUpStrategy followUpStrategy() {
        return FollowUpStrategy.ON_FAILURE;
    }
}
