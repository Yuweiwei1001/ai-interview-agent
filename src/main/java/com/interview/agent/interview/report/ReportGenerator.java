package com.interview.agent.interview.report;

import com.interview.agent.interview.InterviewRoundMapper;
import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.interview.model.InterviewRound;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.sse.SseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ReportGenerator {
    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);
    private final InterviewSessionMapper sessionMapper;
    private final InterviewRoundMapper roundMapper;
    private final SseRegistry sseRegistry;
    private final ObjectMapper objectMapper;
    private final GrowthComparator growthComparator;

    public ReportGenerator(InterviewSessionMapper sessionMapper, InterviewRoundMapper roundMapper,
                           SseRegistry sseRegistry, ObjectMapper objectMapper,
                           GrowthComparator growthComparator) {
        this.sessionMapper = sessionMapper;
        this.roundMapper = roundMapper;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
        this.growthComparator = growthComparator;
    }

    /**
     * 生成报告（同步调用，保证 REPORT_READY 先于 COMPLETE 推送；由面试执行线程池承载）
     */
    public void generateReport(String sessionId) {
        try {
            log.info("开始生成报告: sessionId={}", sessionId);
            InterviewSession session = sessionMapper.findById(sessionId);
            if (session == null) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            List<InterviewRound> rounds = roundMapper.findBySessionId(sessionId);
            InterviewReport report = buildReport(session, rounds);

            // 序列化并存储
            String reportJson = objectMapper.writeValueAsString(report);
            session.setReport(reportJson);
            session.setOverallScore(report.getOverallScore());
            sessionMapper.update(session);

            // SSE 推送报告就绪
            sseRegistry.sendEvent(sessionId, "REPORT_READY", reportJson);
            log.info("报告生成完成: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("报告生成失败: sessionId={}", sessionId, e);
        }
    }

    private InterviewReport buildReport(InterviewSession session, List<InterviewRound> rounds) {
        InterviewReport report = new InterviewReport();

        // 逐题反馈
        List<InterviewReport.QuestionFeedback> feedbacks = rounds.stream().map(r -> {
            InterviewReport.QuestionFeedback f = new InterviewReport.QuestionFeedback();
            f.setRoundNumber(r.getRoundNumber());
            f.setQuestion(r.getQuestion());
            f.setAnswer(r.getCandidateAnswer());
            f.setScore(extractScore(r.getEvaluation()));
            f.setFeedback(extractFeedback(r.getEvaluation()));
            return f;
        }).collect(Collectors.toList());
        report.setPerQuestionFeedback(feedbacks);

        // 计算总分
        double avgScore = feedbacks.stream()
                .mapToDouble(f -> f.getScore() != null ? f.getScore().doubleValue() : 0)
                .average()
                .orElse(0);
        report.setOverallScore(BigDecimal.valueOf(Math.round(avgScore)));

        // 维度评分
        InterviewReport.DimensionScores dimScores = new InterviewReport.DimensionScores();
        Map<String, List<InterviewRound>> byAgent = rounds.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getAgentName() != null ? r.getAgentName() : "unknown",
                        Collectors.toList()
                ));
        byAgent.forEach((agent, agentRounds) -> {
            double agentAvg = agentRounds.stream()
                    .mapToInt(r -> extractScore(r.getEvaluation()).intValue())
                    .average()
                    .orElse(0);
            BigDecimal score = BigDecimal.valueOf(Math.round(agentAvg));
            switch (agent) {
                case "technical" -> dimScores.setTechnical(score);
                case "project" -> dimScores.setProject(score);
                case "coding" -> dimScores.setCoding(score);
                default -> { /* 未知 agent 不再映射到任何维度 */ }
            }
        });
        // 沟通表达维度：聚合各轮评估中的 communicationScore（文本题由 LLM 单独评估表达质量）
        double communicationAvg = rounds.stream()
                .map(r -> extractCommunication(r.getEvaluation()))
                .filter(c -> c != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);
        if (communicationAvg > 0) {
            dimScores.setCommunication(BigDecimal.valueOf(Math.round(communicationAvg)));
        }
        report.setDimensionScores(dimScores);

        // 优势与不足
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (avgScore >= 80) {
            strengths.add("整体表现优秀，对各考察点有深入理解");
            suggestions.add("保持当前学习节奏，可进一步深入系统设计领域");
        } else if (avgScore >= 60) {
            strengths.add("基础知识扎实，具备一定的实践经验");
            weaknesses.add("部分知识点需要加强深度理解");
            suggestions.add("建议针对薄弱环节进行专项练习，注重理论与实践结合");
        } else {
            weaknesses.add("基础知识需要系统性地补充");
            suggestions.add("建议从基础开始系统学习，配合项目实践巩固");
        }

        if (dimScores.getTechnical() != null && dimScores.getTechnical().doubleValue() < 60) {
            weaknesses.add("技术基础较薄弱，需要加强计算机基础、数据结构与算法");
            suggestions.add("推荐系统学习《算法导论》核心章节，配合 LeetCode 练习");
        }
        if (dimScores.getCoding() != null && dimScores.getCoding().doubleValue() < 60) {
            weaknesses.add("编码能力有待提高，需要多动手实践");
            suggestions.add("建议每天坚持编码练习，关注代码质量和设计模式");
        }

        report.setStrengths(strengths.isEmpty() ? List.of("继续努力，持续进步") : strengths);
        report.setWeaknesses(weaknesses.isEmpty() ? List.of("无明显短板，继续保持") : weaknesses);
        report.setSuggestions(suggestions.isEmpty() ? List.of("保持学习，持续提升") : suggestions);

        // 成长对比
        GrowthComparator.GrowthComparison growth = growthComparator.compare(session.getId());
        if (growth.hasGrowth()) {
            InterviewReport.GrowthData growthData = new InterviewReport.GrowthData();
            growthData.setPreviousScore(growth.getPreviousScore());
            growthData.setCurrentScore(growth.getCurrentScore());
            growthData.setImprovement(growth.getImprovement());
            report.setGrowthComparison(growthData);
        }

        return report;
    }

    private BigDecimal extractScore(String evaluationJson) {
        if (evaluationJson == null) return BigDecimal.ZERO;
        try {
            Map map = objectMapper.readValue(evaluationJson, Map.class);
            Object score = map.get("score");
            if (score instanceof Number) {
                return BigDecimal.valueOf(((Number) score).doubleValue());
            }
        } catch (Exception e) {
            log.warn("解析评估分数失败", e);
        }
        return BigDecimal.ZERO;
    }

    /** 提取单轮沟通表达分；旧数据/编程题无此字段时返回 null（不参与聚合） */
    private BigDecimal extractCommunication(String evaluationJson) {
        if (evaluationJson == null) return null;
        try {
            Map map = objectMapper.readValue(evaluationJson, Map.class);
            Object communication = map.get("communicationScore");
            if (communication instanceof Number) {
                return BigDecimal.valueOf(((Number) communication).doubleValue());
            }
        } catch (Exception e) {
            log.warn("解析沟通表达分失败", e);
        }
        return null;
    }

    private String extractFeedback(String evaluationJson) {
        if (evaluationJson == null) return "";
        try {
            Map map = objectMapper.readValue(evaluationJson, Map.class);
            Object summary = map.get("summary");
            if (summary != null) return summary.toString();
            Object completeness = map.get("completeness");
            if (completeness != null) return completeness.toString();
        } catch (Exception e) {
            log.warn("解析评估反馈失败", e);
        }
        return "";
    }
}
