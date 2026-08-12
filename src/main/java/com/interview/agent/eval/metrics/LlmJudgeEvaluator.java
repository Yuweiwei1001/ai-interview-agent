package com.interview.agent.eval.metrics;

import com.interview.agent.common.ai.LlmCallWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.eval.EvalTrace;
import com.interview.agent.interview.model.InterviewRound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-as-Judge 评估器：对轨迹中难以规则化的质量维度打分。
 * - 出题-JD 相关性（QuestionRelevance）：每道主题 0-10 分
 * - 追问针对性（FollowUpQuality）：追问是否命中上一轮回答的薄弱点，0-10 分
 * Judge 调用失败时标记 degraded 而非伪造分数（诚实降级原则）。
 */
@Component
public class LlmJudgeEvaluator {
    private static final Logger log = LoggerFactory.getLogger(LlmJudgeEvaluator.class);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmJudgeEvaluator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public record JudgeScore(double score, String reason, boolean degraded) {}

    /** 单个用例的 judge 汇总结果 */
    public static class JudgeMetrics {
        private double avgQuestionRelevance;
        private int judgedQuestionCount;
        private double avgFollowUpQuality;
        private int judgedFollowUpCount;
        private int judgeDegradedCount;
        private List<JudgeDetail> details = new ArrayList<>();

        public record JudgeDetail(String dimension, int roundNumber, double score, String reason, boolean degraded) {}

        public double getAvgQuestionRelevance() { return avgQuestionRelevance; }
        public void setAvgQuestionRelevance(double v) { this.avgQuestionRelevance = v; }
        public int getJudgedQuestionCount() { return judgedQuestionCount; }
        public void setJudgedQuestionCount(int v) { this.judgedQuestionCount = v; }
        public double getAvgFollowUpQuality() { return avgFollowUpQuality; }
        public void setAvgFollowUpQuality(double v) { this.avgFollowUpQuality = v; }
        public int getJudgedFollowUpCount() { return judgedFollowUpCount; }
        public void setJudgedFollowUpCount(int v) { this.judgedFollowUpCount = v; }
        public int getJudgeDegradedCount() { return judgeDegradedCount; }
        public void setJudgeDegradedCount(int v) { this.judgeDegradedCount = v; }
        public List<JudgeDetail> getDetails() { return details; }
        public void setDetails(List<JudgeDetail> details) { this.details = details; }
    }

    public JudgeMetrics evaluate(EvalTrace trace, String jdText) {
        JudgeMetrics jm = new JudgeMetrics();
        List<InterviewRound> rounds = trace.getRounds() == null ? List.of() : trace.getRounds();

        double relevanceSum = 0;
        int relevanceCount = 0;
        double followUpSum = 0;
        int followUpCount = 0;
        int degraded = 0;

        InterviewRound previousMain = null;
        for (InterviewRound round : rounds) {
            boolean isFollowUp = Boolean.TRUE.equals(round.getIsFollowup());
            if (isFollowUp) {
                // 追问针对性：基于其所属主轮的题目与回答
                if (previousMain != null) {
                    JudgeScore s = judgeFollowUpQuality(previousMain.getQuestion(),
                            previousMain.getCandidateAnswer(), round.getQuestion());
                    if (s.degraded()) degraded++;
                    else {
                        followUpSum += s.score();
                        followUpCount++;
                    }
                    jm.getDetails().add(new JudgeMetrics.JudgeDetail(
                            "followUpQuality", round.getRoundNumber(), s.score(), s.reason(), s.degraded()));
                }
            } else {
                if (jdText != null && !jdText.isBlank()) {
                    JudgeScore s = judgeQuestionRelevance(jdText, round.getQuestion());
                    if (s.degraded()) degraded++;
                    else {
                        relevanceSum += s.score();
                        relevanceCount++;
                    }
                    jm.getDetails().add(new JudgeMetrics.JudgeDetail(
                            "questionRelevance", round.getRoundNumber(), s.score(), s.reason(), s.degraded()));
                }
                previousMain = round;
            }
        }

        jm.setAvgQuestionRelevance(relevanceCount == 0 ? 0 : round1(relevanceSum / relevanceCount));
        jm.setJudgedQuestionCount(relevanceCount);
        jm.setAvgFollowUpQuality(followUpCount == 0 ? 0 : round1(followUpSum / followUpCount));
        jm.setJudgedFollowUpCount(followUpCount);
        jm.setJudgeDegradedCount(degraded);
        return jm;
    }

    /** 出题与 JD 相关性：0（完全无关）~ 10（高度相关且能有效考察 JD 要求的能力） */
    public JudgeScore judgeQuestionRelevance(String jdText, String question) {
        String prompt = "你是 AI 面试系统的评测员。请判断下面的面试题目与岗位 JD 的相关性。\n\n"
                + "岗位 JD：\n" + truncate(jdText, 2000) + "\n\n"
                + "面试题目：" + truncate(question, 1000) + "\n\n"
                + "评分标准：9-10 高度相关且能有效考察 JD 要求的核心能力；6-8 相关但偏通用；"
                + "3-5 弱相关；0-2 与 JD 无关。\n"
                + "只输出 JSON：{\"score\": <0-10的数>, \"reason\": \"<40字以内理由>\"}";
        return callJudge(prompt);
    }

    /** 追问针对性：0（与回答无关的模板式追问）~ 10（精准命中回答中的薄弱点/可深挖点） */
    public JudgeScore judgeFollowUpQuality(String question, String answer, String followUp) {
        String prompt = "你是 AI 面试系统的评测员。请评估下面这条追问的质量。\n\n"
                + "原始题目：" + truncate(question, 500) + "\n\n"
                + "候选人回答：" + truncate(answer, 1500) + "\n\n"
                + "追问：" + truncate(followUp, 500) + "\n\n"
                + "评分标准：9-10 精准承接回答内容，命中薄弱点或可深挖点；6-8 与回答相关但较泛；"
                + "3-5 与原始题目相关但未结合回答；0-2 与回答无关的模板式追问。\n"
                + "只输出 JSON：{\"score\": <0-10的数>, \"reason\": \"<40字以内理由>\"}";
        return callJudge(prompt);
    }

    private JudgeScore callJudge(String prompt) {
        return LlmCallWrapper.callWithRetry(
                () -> {
                    String content = chatClient.prompt().user(prompt).call().content();
                    int start = content == null ? -1 : content.indexOf('{');
                    int end = content == null ? -1 : content.lastIndexOf('}');
                    if (start < 0 || end <= start) {
                        throw new IllegalArgumentException("judge 输出缺少 JSON: " + content);
                    }
                    JsonNode node = objectMapper.readTree(content.substring(start, end + 1));
                    double score = Math.max(0, Math.min(10, node.path("score").asDouble(0)));
                    String reason = node.path("reason").asText("");
                    return new JudgeScore(score, reason, false);
                },
                () -> new JudgeScore(-1, "judge 调用失败，本条不计入统计", true));
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "……";
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
