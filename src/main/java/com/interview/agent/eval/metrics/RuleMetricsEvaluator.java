package com.interview.agent.eval.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.eval.EvalTrace;
import com.interview.agent.interview.model.InterviewRound;
import com.interview.agent.interview.plan.InterviewPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则类指标评估器：只依赖轨迹数据，无 LLM 调用，快且可复现。
 */
@Component
public class RuleMetricsEvaluator {
    private static final Logger log = LoggerFactory.getLogger(RuleMetricsEvaluator.class);

    /** 系统设计关键词：编程题命中即视为跑题（与 CodingAgent 偏移检测同源） */
    private static final String[] SYSTEM_DESIGN_KEYWORDS = {
            "Redis", "Kafka", "RocketMQ", "分布式", "限流", "短链", "秒杀", "网关", "微服务", "消息队列"
    };
    /** 判定为重复题的最长公共子串占比阈值 */
    private static final double DUPLICATE_LCS_RATIO = 0.8;

    private final ObjectMapper objectMapper;

    public RuleMetricsEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RuleMetrics evaluate(EvalTrace trace) {
        RuleMetrics m = new RuleMetrics();
        m.setSessionId(trace.getSessionId());
        m.setFinalStatus(trace.getFinalStatus());
        m.setCompleted("completed".equals(trace.getFinalStatus()));
        m.setDriverTimeout(trace.isDriverTimeout());
        m.setDurationMs(trace.getDurationMs());

        List<InterviewRound> rounds = trace.getRounds() == null ? List.of() : trace.getRounds();
        List<InterviewRound> mainRounds = rounds.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsFollowup()))
                .toList();
        // 主轮去重：coding 重试会产生同题多条记录，按题目内容去重后才是真实主轮数
        List<InterviewRound> distinctMainRounds = mainRounds.stream()
                .filter(distinctByKey(r -> normalize(r.getQuestion())))
                .toList();

        // 计划符合度（GoalAccuracy）
        InterviewPlan plan = parsePlan(trace.getInterviewPlanJson());
        int planRounds = plan != null ? plan.getEstimatedTotalRounds() : 0;
        m.setPlanRounds(planRounds);
        m.setActualMainRounds(distinctMainRounds.size());
        m.setRoundAdherence(planRounds > 0 ? Math.min(1.0, (double) distinctMainRounds.size() / planRounds) : 0);

        // 编程题统计（TopicAdherence）：按去重后的主轮计数
        int codingRounds = (int) distinctMainRounds.stream()
                .filter(r -> "coding".equalsIgnoreCase(r.getAgentName()))
                .count();
        m.setCodingRoundCount(codingRounds);
        int offTopic = 0;
        for (InterviewRound r : rounds) {
            if ("coding".equalsIgnoreCase(r.getAgentName()) && hitSystemDesignKeyword(r.getQuestion(), r.getTopic())) {
                offTopic++;
            }
        }
        m.setCodingOffTopicCount(offTopic);
        m.setGoalAchieved(m.isCompleted() && m.getRoundAdherence() >= 0.8 && codingRounds == 1);

        // 追问率
        int followUps = rounds.size() - mainRounds.size();
        m.setFollowUpCount(followUps);
        m.setFollowUpRate(distinctMainRounds.isEmpty() ? 0 : (double) followUps / distinctMainRounds.size());

        // 题目重复率（主轮题目两两比对；coding 重试产生的同题记录已去重，避免误判）
        List<String> questions = distinctMainRounds.stream()
                .map(InterviewRound::getQuestion)
                .filter(q -> q != null && !q.isBlank())
                .toList();
        int dupPairs = 0;
        for (int i = 0; i < questions.size(); i++) {
            for (int j = i + 1; j < questions.size(); j++) {
                if (lcsRatio(questions.get(i), questions.get(j)) >= DUPLICATE_LCS_RATIO) {
                    dupPairs++;
                }
            }
        }
        m.setDuplicateQuestionPairs(dupPairs);
        m.setQuestionDuplicateRate(questions.size() < 2 ? 0 : (double) dupPairs / (questions.size() - 1));

        // 计划主题覆盖率：按 agent 分组匹配（主题只与该 agent 的轮次比对），
        // 文本未命中但 agent 轮次已达计划时视为已覆盖（兼容泛化主题如"系统设计"无法字面命中）
        if (plan != null && plan.getAgentAssignments() != null) {
            int total = 0;
            int covered = 0;
            List<String> uncovered = new ArrayList<>();
            for (Map.Entry<String, InterviewPlan.AgentAssignment> entry : plan.getAgentAssignments().entrySet()) {
                String agent = entry.getKey();
                InterviewPlan.AgentAssignment assignment = entry.getValue();
                List<String> topics = assignment.getTopics() == null ? List.of() : assignment.getTopics();
                if (topics.isEmpty()) continue;
                List<InterviewRound> agentRounds = distinctMainRounds.stream()
                        .filter(r -> agent.equalsIgnoreCase(r.getAgentName()))
                        .toList();
                boolean agentFulfilled = assignment.getEstimatedRounds() > 0
                        && agentRounds.size() >= assignment.getEstimatedRounds();
                boolean anyMatched = false;
                for (String topic : topics) {
                    total++;
                    boolean matched = agentRounds.stream().anyMatch(r -> topicMatches(topic, roundMatchText(r)));
                    if (matched) anyMatched = true;
                    // 兼容泛化主题：agent 按计划执行了轮次但字面未命中，视为已覆盖
                    boolean fallbackCovered = !matched && agentFulfilled;
                    if (matched || fallbackCovered) {
                        covered++;
                    } else {
                        uncovered.add(topic);
                    }
                }
                if (!anyMatched && !agentFulfilled && !agentRounds.isEmpty()) {
                    log.info("评测：agent={} 轮次 {}/{} 且主题均未命中", agent, agentRounds.size(), assignment.getEstimatedRounds());
                }
            }
            m.setUncoveredTopics(uncovered);
            m.setTopicCoverageRatio(total == 0 ? 1.0 : (double) covered / total);
        } else {
            m.setTopicCoverageRatio(0);
        }

        // 降级率与评分统计
        int degraded = 0;
        int timeoutAnswers = 0;
        double scoreSum = 0;
        int scored = 0;
        for (InterviewRound r : rounds) {
            JsonNode evalNode = parseEvalJson(r.getEvaluation());
            if (evalNode != null) {
                if (evalNode.path("degraded").asBoolean(false)) degraded++;
                JsonNode score = evalNode.path("score");
                if (score.isNumber()) {
                    scoreSum += score.asDouble();
                    scored++;
                }
            }
            if (r.getCandidateAnswer() != null && r.getCandidateAnswer().contains("【超时未回答】")) {
                timeoutAnswers++;
            }
        }
        m.setDegradedRoundCount(degraded);
        m.setDegradedRate(rounds.isEmpty() ? 0 : (double) degraded / rounds.size());
        m.setTimeoutAnswerCount(timeoutAnswers);
        m.setAvgScore(scored == 0 ? 0 : Math.round(scoreSum / scored * 10) / 10.0);
        return m;
    }

    private InterviewPlan parsePlan(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, InterviewPlan.class);
        } catch (Exception e) {
            log.warn("评测：面试计划解析失败", e);
            return null;
        }
    }

    private JsonNode parseEvalJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hitSystemDesignKeyword(String question, String topic) {
        String text = ((question == null ? "" : question) + " " + (topic == null ? "" : topic)).toLowerCase(Locale.ROOT);
        for (String kw : SYSTEM_DESIGN_KEYWORDS) {
            if (text.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 轮次可匹配文本：主题 + 题目 + 评分提取的考察知识点 */
    private String roundMatchText(InterviewRound r) {
        StringBuilder sb = new StringBuilder();
        if (r.getTopic() != null) sb.append(r.getTopic()).append(' ');
        if (r.getQuestion() != null) sb.append(r.getQuestion()).append(' ');
        JsonNode evalNode = parseEvalJson(r.getEvaluation());
        if (evalNode != null) {
            JsonNode kps = evalNode.path("knowledgePoints");
            if (kps.isArray()) {
                for (JsonNode kp : kps) sb.append(kp.asText("")).append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * 主题匹配：计划主题可能是长短语（如"JVM原理与并发编程"），轮次 topic 是具体考察点，
     * 双向子串匹配会大量失配。采用关键词命中：切分计划主题的实义词，
     * 命中过半即视为该主题在轮次主题/题目/考察知识点中被覆盖。
     */
    private boolean topicMatches(String planTopic, String matchText) {
        if (planTopic == null || planTopic.isBlank()) return true;
        String t = planTopic.toLowerCase(Locale.ROOT);
        String haystack = (matchText == null ? "" : matchText).toLowerCase(Locale.ROOT);
        if (haystack.contains(t)) return true;
        List<String> words = splitWords(t);
        if (words.isEmpty()) return false;
        long hit = words.stream().filter(haystack::contains).count();
        return hit * 2 >= words.size();
    }

    /** 切分主题实义词：英文按非字母数字切；中文按连词切后再取 2 字以上片段 */
    private List<String> splitWords(String topic) {
        List<String> words = new ArrayList<>();
        for (String part : topic.split("[\\s、，,；;:：()（）/\\-]+")) {
            if (part.isBlank()) continue;
            if (part.matches(".*[a-z0-9].*")) {
                for (String w : part.split("[^a-z0-9+#]+")) {
                    if (w.length() >= 2) words.add(w);
                }
            }
            // 中文片段：去掉"与和及的"等连接词后，整体及其 2 字滑窗片段都作为候选关键词
            String cn = part.replaceAll("[与和及或的了之]", "");
            if (cn.length() >= 2) {
                words.add(cn);
                for (int i = 0; i + 2 <= cn.length(); i++) {
                    words.add(cn.substring(i, i + 2));
                }
            }
        }
        return words.stream().distinct().toList();
    }

    /** 归一化后最长公共子串占比（相对较短串），用于重复题检测 */
    private double lcsRatio(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int max = 0;
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int i = 1; i <= x.length(); i++) {
            for (int j = 1; j <= y.length(); j++) {
                if (x.charAt(i - 1) == y.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > max) max = cur[j];
                } else {
                    cur[j] = 0;
                }
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            java.util.Arrays.fill(cur, 0);
        }
        return (double) max / Math.min(x.length(), y.length());
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）]", "");
    }

    /** 按 key 去重的流过滤器 */
    private static <T> java.util.function.Predicate<T> distinctByKey(java.util.function.Function<? super T, ?> keyExtractor) {
        java.util.Set<Object> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }
}
