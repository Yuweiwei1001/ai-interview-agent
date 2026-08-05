package com.interview.agent.interview.graph.node;

import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.graph.InterviewState.RoundRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class EvaluateNode implements Function<InterviewState, InterviewState> {
    private static final Logger log = LoggerFactory.getLogger(EvaluateNode.class);

    @Override
    public InterviewState apply(InterviewState state) {
        log.info("EvaluateNode: 评估回答, round={}, sessionId={}", state.getCurrentRound(), state.getSessionId());

        // 简化的规则评估（Phase 1b 将接入 LLM 评估）
        String answer = state.getCurrentAnswer();
        Map<String, Object> evaluation = new HashMap<>();
        
        // 基础评估：回答长度作为简单指标
        int score = Math.min(100, answer.length() * 2);
        evaluation.put("score", score);
        evaluation.put("knowledgePoints", extractKnowledgePoints(answer));
        evaluation.put("completeness", score >= 60 ? "good" : "needs_improvement");
        evaluation.put("summary", score >= 60 ? "回答基本完整" : "回答不够充分，需要进一步考察");

        // 记录到 rounds
        RoundRecord record = new RoundRecord();
        record.setRoundNumber(state.getCurrentRound());
        record.setAgentName(state.getCurrentAgent());
        record.setQuestion(state.getCurrentQuestion());
        record.setAnswer(state.getCurrentAnswer());
        record.setEvaluation(evaluation);
        state.getRounds().add(record);

        return state;
    }

    private List<String> extractKnowledgePoints(String answer) {
        List<String> points = new ArrayList<>();
        if (answer == null || answer.isBlank()) return points;
        
        // 简单关键词提取（Phase 1b 将用 LLM）
        String[] keywords = {"Java", "Spring", "分布式", "微服务", "数据库", "Redis", "MQ", "Docker", "K8s", "算法", "设计模式"};
        for (String kw : keywords) {
            if (answer.contains(kw)) {
                points.add(kw);
            }
        }
        return points;
    }
}
