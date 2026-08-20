package com.interview.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.auth.User;
import com.interview.agent.auth.UserMapper;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.eval.metrics.JudgeCalibrator;
import com.interview.agent.eval.metrics.LlmJudgeEvaluator;
import com.interview.agent.eval.metrics.RuleMetrics;
import com.interview.agent.eval.metrics.RuleMetricsEvaluator;
import com.interview.agent.interview.agent.AnswerEvaluator;
import com.interview.agent.voice.correction.AsrCorrectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 评测编排服务：加载用例 → 逐用例驱动面试 → 规则指标 + LLM-judge → 校准 → 汇总报告。
 * 异步执行（单线程串行跑用例，避免挤占面试线程池），runId 查询进度与报告。
 * 报告同时写入 ./eval-reports/ 目录，便于版本间回归对比留档。
 */
@Service
public class EvalService {
    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    /** 评测专用账号（需已注册，默认 testuser） */
    private static final String EVAL_USERNAME = "testuser";
    private static final String REPORT_DIR = "eval-reports";

    private final EvalDatasetLoader datasetLoader;
    private final EvalRunner evalRunner;
    private final RuleMetricsEvaluator ruleMetricsEvaluator;
    private final LlmJudgeEvaluator llmJudgeEvaluator;
    private final JudgeCalibrator judgeCalibrator;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final AnswerEvaluator answerEvaluator;
    private final AsrCorrectionService correctionService;

    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-exec");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, EvalRun> runs = new ConcurrentHashMap<>();

    public static class EvalRun {
        private String status = "RUNNING"; // RUNNING / DONE / FAILED
        private String error;
        private EvalReport report;

        public String getStatus() { return status; }
        public String getError() { return error; }
        public EvalReport getReport() { return report; }
    }

    public EvalService(EvalDatasetLoader datasetLoader, EvalRunner evalRunner,
                       RuleMetricsEvaluator ruleMetricsEvaluator, LlmJudgeEvaluator llmJudgeEvaluator,
                       JudgeCalibrator judgeCalibrator, UserMapper userMapper, ObjectMapper objectMapper,
                       AnswerEvaluator answerEvaluator, AsrCorrectionService correctionService) {
        this.datasetLoader = datasetLoader;
        this.evalRunner = evalRunner;
        this.ruleMetricsEvaluator = ruleMetricsEvaluator;
        this.llmJudgeEvaluator = llmJudgeEvaluator;
        this.judgeCalibrator = judgeCalibrator;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.answerEvaluator = answerEvaluator;
        this.correctionService = correctionService;
    }

    public List<EvalCase> loadCases() {
        return datasetLoader.loadCases();
    }

    /** 提交一次评测运行，返回 runId */
    public String runEval(List<String> caseIds, boolean skipLlmJudge, boolean runCalibration) {
        List<EvalCase> allCases = datasetLoader.loadCases();
        if (allCases.isEmpty()) {
            throw new BaseException("评测数据集为空");
        }
        List<EvalCase> selected = caseIds == null || caseIds.isEmpty()
                ? allCases
                : allCases.stream().filter(c -> caseIds.contains(c.getCaseId())).toList();
        if (selected.isEmpty()) {
            throw new BaseException("未匹配到任何评测用例: " + caseIds);
        }

        String runId = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        runs.put(runId, new EvalRun());
        evalExecutor.submit(() -> executeRun(runId, selected, skipLlmJudge, runCalibration));
        return runId;
    }

    public EvalRun getRun(String runId) {
        EvalRun run = runs.get(runId);
        if (run == null) {
            throw new BaseException("评测运行不存在: " + runId);
        }
        return run;
    }

    /** 同步执行 judge 校准（样本少，直接返回结果） */
    public JudgeCalibrator.CalibrationResult calibrate() {
        return judgeCalibrator.calibrate(datasetLoader.loadCalibrationSamples());
    }

    // ---------- ASR 热词纠错方案 4.5：评分漂移回归 + 干扰集门禁 ----------

    /** 评分漂移阈值（验收指标初值 0.5 分，随人工标注校准修订） */
    private static final double SCORE_DRIFT_THRESHOLD = 0.5;

    /** 漂移评估固定热词快照（可复现性要求：热词表不同则门禁结果不同） */
    private static final List<String> DRIFT_HOTWORDS = List.of(
            "Raft", "Redis", "零拷贝", "幂等", "Kafka", "Kubernetes", "HashMap", "三次握手");

    /** 漂移评估校准样例（干净版含映射表术语，噪声版由 NoiseInjector 按固定映射替换） */
    private static final List<String[]> DRIFT_SAMPLES = List.of(
            new String[]{"raft-consensus",
                    "Raft 通过领导选举、日志复制和安全性约束保证一致性，选举用随机超时避免选票瓜分，"
                            + "日志复制要求多数派确认后才提交，因此能够容忍少数节点故障。"},
            new String[]{"redis-model",
                    "Redis 单线程命令处理避免了锁竞争，基于 IO 多路复用提升吞吐；"
                            + "持久化有 RDB 快照和 AOF 追加日志两种方式，性能与数据安全性各有取舍。"},
            new String[]{"zero-copy",
                    "零拷贝通过 sendfile 系统调用直接从页缓存发送到网卡，"
                            + "减少了内核态与用户态之间的两次数据拷贝和上下文切换，大幅降低传输延迟。"},
            new String[]{"idempotent",
                    "接口幂等通过唯一请求号加数据库唯一索引实现，重复请求直接返回首次结果，"
                            + "配合分布式锁防止并发写入，避免重复扣款这类资金安全问题。"},
            new String[]{"tcp-handshake",
                    "TCP 三次握手同步双方的初始序列号并确认双向链路可达，"
                            + "防止历史重复连接请求造成资源浪费，滑动窗口机制则用于流量控制。"});

    /**
     * 评分漂移回归（方案 4.5.1）：固定校准回答干净版 vs 噪声版各过一次评估器，
     * 统计 |噪声分 − 干净分|。带热词 + asrTranscribed=true 走真实语音面试评分链路。
     * 门禁：maxDelta ≤ 0.5（纠错链路/Prompt 改动后回归不得劣化）。
     */
    public NoiseDriftResult evaluateScoreDrift() {
        List<NoiseDriftItem> items = new ArrayList<>();
        for (String[] sample : DRIFT_SAMPLES) {
            String id = sample[0];
            String clean = sample[1];
            String noisy = NoiseInjector.inject(clean);
            int covered = NoiseInjector.coveredTerms(clean);
            AnswerEvaluator.EvaluationResult cleanEval = answerEvaluator.evaluate(
                    "请结合实际场景阐述相关技术原理", clean, DRIFT_HOTWORDS, true);
            AnswerEvaluator.EvaluationResult noisyEval = answerEvaluator.evaluate(
                    "请结合实际场景阐述相关技术原理", noisy, DRIFT_HOTWORDS, true);
            items.add(new NoiseDriftItem(id, covered, cleanEval.score(), noisyEval.score(),
                    Math.abs(noisyEval.score() - cleanEval.score())));
        }
        double avgDelta = items.stream().mapToDouble(NoiseDriftItem::delta).average().orElse(0);
        double maxDelta = items.stream().mapToDouble(NoiseDriftItem::delta).max().orElse(0);
        boolean pass = maxDelta <= SCORE_DRIFT_THRESHOLD;
        return new NoiseDriftResult(SCORE_DRIFT_THRESHOLD, avgDelta, maxDelta, pass, items);
    }

    /** 干扰集门禁固定热词快照（可复现性要求 P2-6：热词表硬编码，不动态拉取） */
    private static final List<String> GATE_HOTWORDS = List.of(
            "Redis", "MySQL", "Kafka", "RabbitMQ", "Spring Boot", "Raft", "零拷贝", "幂等", "Kubernetes", "DNS");

    /** 干扰集：干净转写文本直接过纠错链路（术语密集句是误纠高发区，重点覆盖） */
    private static final List<String> GATE_SENTENCES = List.of(
            "我熟悉 Redis、MySQL、Kafka、RabbitMQ、Spring Boot 等技术栈。",
            "Raft 协议通过领导选举和日志复制实现一致性。",
            "我们用零拷贝和页缓存优化了文件传输性能。",
            "接口做了幂等设计，重复请求不会重复扣款。",
            "Kubernetes 集群里服务通过 DNS 发现彼此。",
            "这个话题我们聊过了，换下一个方向吧。");

    /**
     * 干扰集门禁（方案 4.5.2，钉钉同款）：干净转写文本直接过纠错链路，
     * corrections 总数必须为 0——防止“纠错比不纠更差”（把正确的纠错了）。
     * 任何 Prompt/词库变更必须跑此门禁。
     */
    public CorrectionGateResult evaluateCorrectionGate() {
        List<CorrectionGateItem> items = new ArrayList<>();
        int totalCorrections = 0;
        for (String sentence : GATE_SENTENCES) {
            AsrCorrectionService.CorrectionResult result = correctionService.correctSync(sentence, GATE_HOTWORDS);
            List<String> corrections = result.corrections().stream()
                    .map(c -> c.from() + "→" + c.to() + "(" + c.confidence() + ")")
                    .toList();
            totalCorrections += corrections.size();
            items.add(new CorrectionGateItem(sentence, corrections));
        }
        boolean pass = totalCorrections == 0;
        return new CorrectionGateResult(GATE_SENTENCES.size(), totalCorrections, pass, items);
    }

    /** 单条评分漂移样例结果 */
    public record NoiseDriftItem(String sampleId, int injectedTerms, int cleanScore, int noisyScore, double delta) {}

    /** 评分漂移回归结果：avgDelta/maxDelta + 门禁结论（阈值初值 0.5） */
    public record NoiseDriftResult(double threshold, double avgDelta, double maxDelta, boolean pass,
                                    List<NoiseDriftItem> items) {}

    /** 单句干扰门禁结果 */
    public record CorrectionGateItem(String sentence, List<String> corrections) {}

    /** 干扰集门禁结果：干净文本过纠错链路 corrections 必须为 0 */
    public record CorrectionGateResult(int totalSentences, int totalCorrections, boolean pass,
                                       List<CorrectionGateItem> items) {}

    private void executeRun(String runId, List<EvalCase> cases, boolean skipLlmJudge, boolean runCalibration) {
        EvalRun run = runs.get(runId);
        EvalReport report = new EvalReport();
        report.setRunId(runId);
        report.setStartedAt(LocalDateTime.now());
        report.setEvalUsername(EVAL_USERNAME);
        try {
            User evalUser = userMapper.findActiveByUsername(EVAL_USERNAME);
            if (evalUser == null) {
                throw new BaseException("评测账号不存在或未激活，请先注册: " + EVAL_USERNAME);
            }
            Long userId = evalUser.getId();

            for (EvalCase evalCase : cases) {
                log.info("[eval {}] 开始用例: {}", runId, evalCase.getCaseId());
                EvalReport.CaseResult caseResult = new EvalReport.CaseResult();
                caseResult.setCaseId(evalCase.getCaseId());
                caseResult.setDescription(evalCase.getDescription());
                caseResult.setAnswerLevel(evalCase.getAnswerLevel());
                try {
                    EvalTrace trace = evalRunner.run(evalCase, userId);
                    caseResult.setTrace(trace);
                    caseResult.setError(trace.getError());

                    RuleMetrics ruleMetrics = ruleMetricsEvaluator.evaluate(trace);
                    caseResult.setRuleMetrics(ruleMetrics);

                    if (!skipLlmJudge) {
                        caseResult.setJudgeMetrics(llmJudgeEvaluator.evaluate(trace, evalCase.getJdText()));
                    }
                } catch (Exception e) {
                    log.error("[eval {}] 用例执行失败: {}", runId, evalCase.getCaseId(), e);
                    caseResult.setError(e.getMessage());
                }
                report.getCaseResults().add(caseResult);
                log.info("[eval {}] 用例完成: {} -> {}", runId, evalCase.getCaseId(),
                        caseResult.getRuleMetrics() != null ? caseResult.getRuleMetrics().getFinalStatus() : "ERROR");
            }

            if (runCalibration) {
                report.setCalibration(judgeCalibrator.calibrate(datasetLoader.loadCalibrationSamples()));
            }

            report.setAggregate(buildAggregate(report));
            report.setFinishedAt(LocalDateTime.now());
            writeReportFile(report);

            run.report = report;
            run.status = "DONE";
        } catch (Exception e) {
            log.error("[eval {}] 评测运行失败", runId, e);
            run.status = "FAILED";
            run.error = e.getMessage();
            report.setFinishedAt(LocalDateTime.now());
            run.report = report;
        }
    }

    private EvalReport.Aggregate buildAggregate(EvalReport report) {
        EvalReport.Aggregate agg = new EvalReport.Aggregate();
        List<EvalReport.CaseResult> results = report.getCaseResults();
        agg.setTotalCases(results.size());
        if (results.isEmpty()) return agg;

        long completed = 0;
        long goalAchieved = 0;
        double adherenceSum = 0, dupSum = 0, coverageSum = 0, degradedSum = 0;
        int offTopicTotal = 0;
        double relevanceSum = 0, followUpSum = 0;
        int relevanceCount = 0, followUpCount = 0;

        for (EvalReport.CaseResult r : results) {
            RuleMetrics m = r.getRuleMetrics();
            if (m == null) continue;
            if (m.isCompleted()) completed++;
            if (m.isGoalAchieved()) goalAchieved++;
            adherenceSum += m.getRoundAdherence();
            dupSum += m.getQuestionDuplicateRate();
            coverageSum += m.getTopicCoverageRatio();
            degradedSum += m.getDegradedRate();
            offTopicTotal += m.getCodingOffTopicCount();

            LlmJudgeEvaluator.JudgeMetrics jm = r.getJudgeMetrics();
            if (jm != null) {
                if (jm.getJudgedQuestionCount() > 0) {
                    relevanceSum += jm.getAvgQuestionRelevance();
                    relevanceCount++;
                }
                if (jm.getJudgedFollowUpCount() > 0) {
                    followUpSum += jm.getAvgFollowUpQuality();
                    followUpCount++;
                }
            }
        }

        int n = results.size();
        agg.setCompletionRate(round2((double) completed / n));
        agg.setGoalAchievedRate(round2((double) goalAchieved / n));
        agg.setAvgRoundAdherence(round2(adherenceSum / n));
        agg.setAvgQuestionDuplicateRate(round2(dupSum / n));
        agg.setAvgTopicCoverage(round2(coverageSum / n));
        agg.setTotalCodingOffTopic(offTopicTotal);
        agg.setAvgDegradedRate(round2(degradedSum / n));
        agg.setAvgQuestionRelevance(relevanceCount == 0 ? 0 : round2(relevanceSum / relevanceCount));
        agg.setAvgFollowUpQuality(followUpCount == 0 ? 0 : round2(followUpSum / followUpCount));
        return agg;
    }

    private void writeReportFile(EvalReport report) {
        try {
            File dir = new File(REPORT_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("评测报告目录创建失败: {}", dir.getAbsolutePath());
                return;
            }
            File file = new File(dir, report.getRunId() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, report);
            log.info("[eval] 报告已写入: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("[eval] 报告写盘失败", e);
        }
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
