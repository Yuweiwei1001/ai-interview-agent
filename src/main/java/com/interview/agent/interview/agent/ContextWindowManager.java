package com.interview.agent.interview.agent;

import com.interview.agent.interview.graph.InterviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Context 窗口管理器
 * 管理对话历史长度，避免超出 LLM 的 token 限制
 */
@Component
public class ContextWindowManager {
    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    // 阈值配置
    private static final int WARN_TOKEN_COUNT = 6000;   // 触发压缩警告
    private static final int HARD_LIMIT_TOKEN_COUNT = 8000; // 硬性裁剪
    private static final int SLIDING_WINDOW_ROUNDS = 3; // 滑窗保留的最近完整轮次
    private static final int SUMMARY_TOKEN_BUDGET = 200; // 每轮摘要预算 token

    private final ConversationSummarizer summarizer;

    public ContextWindowManager(ConversationSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * 构建压缩后的对话历史
     */
    public String buildCompressedHistory(InterviewState state) {
        List<InterviewState.RoundRecord> rounds = state.getRounds();
        if (rounds == null || rounds.isEmpty()) return "";

        int estimatedTokens = estimateTokens(rounds);
        log.debug("Context 预估 token 数: {}", estimatedTokens);

        if (estimatedTokens <= WARN_TOKEN_COUNT) {
            // 未达阈值，直接返回完整历史
            return buildFullHistory(rounds);
        }

        if (estimatedTokens > HARD_LIMIT_TOKEN_COUNT) {
            // 超过硬性限制，仅保留系统 prompt + 最近 1 轮
            log.warn("Context 超过硬性限制({}), 执行硬性裁剪", HARD_LIMIT_TOKEN_COUNT);
            return buildHardTrimmedHistory(rounds);
        }

        // 滑窗压缩：保留最近 3 轮完整 + 更早轮次摘要
        log.info("Context 触发滑窗压缩, estimatedTokens={}", estimatedTokens);
        return buildSlidingWindowHistory(rounds);
    }

    /**
     * 构建完整对话历史
     */
    public String buildFullHistory(List<InterviewState.RoundRecord> rounds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            InterviewState.RoundRecord r = rounds.get(i);
            sb.append("--- 第").append(i + 1).append("轮 ---\n");
            sb.append("Agent: ").append(r.getAgentName()).append("\n");
            sb.append("题目: ").append(r.getQuestion()).append("\n");
            sb.append("回答: ").append(r.getAnswer()).append("\n");
            sb.append("评估: ").append(r.getEvaluation()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 滑窗压缩：最近 N 轮完整 + 更早轮次摘要
     */
    private String buildSlidingWindowHistory(List<InterviewState.RoundRecord> rounds) {
        StringBuilder sb = new StringBuilder();

        // 需要压缩的轮次（早于滑动窗口的）
        int compressEnd = Math.max(0, rounds.size() - SLIDING_WINDOW_ROUNDS);
        if (compressEnd > 0) {
            List<InterviewState.RoundRecord> earlyRounds = rounds.subList(0, compressEnd);
            String earlySummary = summarizer.summarize(buildFullHistory(earlyRounds));
            sb.append("--- 早期对话摘要 ---\n").append(earlySummary).append("\n\n");
        }

        // 最近的完整轮次
        List<InterviewState.RoundRecord> recentRounds = rounds.subList(
                Math.max(0, rounds.size() - SLIDING_WINDOW_ROUNDS), rounds.size());
        sb.append("--- 最近对话 ---\n");
        for (InterviewState.RoundRecord r : recentRounds) {
            sb.append("题目: ").append(r.getQuestion()).append("\n");
            sb.append("回答: ").append(r.getAnswer()).append("\n");
            sb.append("评估: ").append(r.getEvaluation()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 硬性裁剪：仅保留最近 1 轮
     */
    private String buildHardTrimmedHistory(List<InterviewState.RoundRecord> rounds) {
        if (rounds.isEmpty()) return "";
        InterviewState.RoundRecord last = rounds.get(rounds.size() - 1);
        return "--- 最新对话 ---\n"
                + "题目: " + last.getQuestion() + "\n"
                + "回答: " + last.getAnswer() + "\n"
                + "评估: " + last.getEvaluation() + "\n";
    }

    /**
     * 估算 token 数（中文约 1 token/字，英文约 1 token/4字符）
     */
    public int estimateTokens(List<InterviewState.RoundRecord> rounds) {
        if (rounds == null) return 0;
        String text = rounds.stream()
                .map(r -> (r.getQuestion() != null ? r.getQuestion() : "")
                        + (r.getAnswer() != null ? r.getAnswer() : "")
                        + (r.getEvaluation() != null ? r.getEvaluation().toString() : ""))
                .collect(Collectors.joining());
        return estimateTokens(text);
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        // 粗略估算：中文约 1 token/字，英文约 1 token/4字符
        long chineseChars = text.chars().filter(c -> c > 0x4E00).count();
        long asciiChars = text.length() - chineseChars;
        return (int) (chineseChars + asciiChars / 4);
    }

    /**
     * 检查是否超出警告阈值
     */
    public boolean isOverWarningThreshold(List<InterviewState.RoundRecord> rounds) {
        return estimateTokens(rounds) > WARN_TOKEN_COUNT;
    }
}
