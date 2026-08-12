package com.interview.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.auth.User;
import com.interview.agent.auth.UserMapper;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.eval.metrics.JudgeCalibrator;
import com.interview.agent.eval.metrics.LlmJudgeEvaluator;
import com.interview.agent.eval.metrics.RuleMetrics;
import com.interview.agent.eval.metrics.RuleMetricsEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
                       JudgeCalibrator judgeCalibrator, UserMapper userMapper, ObjectMapper objectMapper) {
        this.datasetLoader = datasetLoader;
        this.evalRunner = evalRunner;
        this.ruleMetricsEvaluator = ruleMetricsEvaluator;
        this.llmJudgeEvaluator = llmJudgeEvaluator;
        this.judgeCalibrator = judgeCalibrator;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
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
