package com.interview.agent.interview.policy;

public interface BehaviorPolicy {
    /**
     * 是否允许重试（回答不达标时给第二次机会）
     */
    boolean shouldAllowRetry(int roundNumber, int score);

    /**
     * 是否允许给提示
     */
    boolean shouldGiveHint(int roundNumber, int score);

    /**
     * 生成提示信息
     */
    String generateHint(String question, String answer, int score);

    /**
     * 评估严格度
     */
    EvaluationStrictness evaluationStrictness();

    /**
     * 追问策略
     */
    FollowUpStrategy followUpStrategy();

    enum EvaluationStrictness {
        STRICT,   // 严格：要求高分才通过
        STANDARD, // 标准
        LENIENT   // 宽松：低分也可通过
    }

    enum FollowUpStrategy {
        ALWAYS,      // 总是追问
        ON_FAILURE,  // 仅回答不达标时追问
        NEVER        // 不追问
    }
}
