package com.interview.agent.interview.policy;

import org.springframework.stereotype.Component;

/**
 * 温和型面试策略
 * - 允许重试
 * - 给提示
 * - 宽松评估
 * - 回答不达标时追问
 */
@Component
public class GentlePolicy implements BehaviorPolicy {

    @Override
    public boolean shouldAllowRetry(int roundNumber, int score) {
        return true;
    }

    @Override
    public boolean shouldGiveHint(int roundNumber, int score) {
        return score < 60;
    }

    @Override
    public String generateHint(String question, String answer, int score) {
        if (score >= 60) return "";
        return "你的回答有一定的基础，但可以更深入一些。建议从以下几个方面补充：\n"
                + "1. 结合实际项目经验说明\n"
                + "2. 考虑边界情况和异常处理\n"
                + "3. 对比不同方案的优势劣势\n"
                + "请尝试重新回答。";
    }

    @Override
    public EvaluationStrictness evaluationStrictness() {
        return EvaluationStrictness.LENIENT;
    }

    @Override
    public FollowUpStrategy followUpStrategy() {
        return FollowUpStrategy.ON_FAILURE;
    }
}
