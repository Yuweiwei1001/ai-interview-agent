package com.interview.agent.eval;

import com.interview.agent.common.result.Result;
import com.interview.agent.eval.metrics.JudgeCalibrator;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评测模块 REST 入口（JWT 保护，与其他 /api/** 一致）。
 * - GET  /api/eval/cases            列出 golden 用例
 * - POST /api/eval/run              提交评测运行（异步），返回 runId
 * - GET  /api/eval/runs/{runId}     查询运行状态与报告
 * - POST /api/eval/calibrate        同步执行 judge 校准（人工标注一致性）
 * - POST /api/eval/noise-drift      评分漂移回归（ASR 噪声注入，纠错链路/Prompt 改动后必跑）
 * - POST /api/eval/correction-gate  干扰集门禁（干净文本过纠错链路，corrections 必须为 0）
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {
    private final EvalService evalService;
    private final EvalDatasetLoader datasetLoader;

    public EvalController(EvalService evalService, EvalDatasetLoader datasetLoader) {
        this.evalService = evalService;
        this.datasetLoader = datasetLoader;
    }

    public static class EvalRunRequest {
        /** 为空则跑全部用例 */
        private List<String> caseIds;
        /** 跳过 LLM-judge（只跑规则指标，快且不耗 LLM） */
        private boolean skipLlmJudge;
        /** 同时执行 judge 校准 */
        private boolean runCalibration;

        public List<String> getCaseIds() { return caseIds; }
        public void setCaseIds(List<String> caseIds) { this.caseIds = caseIds; }
        public boolean isSkipLlmJudge() { return skipLlmJudge; }
        public void setSkipLlmJudge(boolean skipLlmJudge) { this.skipLlmJudge = skipLlmJudge; }
        public boolean isRunCalibration() { return runCalibration; }
        public void setRunCalibration(boolean runCalibration) { this.runCalibration = runCalibration; }
    }

    @GetMapping("/cases")
    public Result<List<Map<String, Object>>> listCases() {
        return Result.success(evalService.loadCases().stream().map(datasetLoader::caseSummary).toList());
    }

    @PostMapping("/run")
    public Result<Map<String, String>> run(@RequestBody(required = false) EvalRunRequest request) {
        EvalRunRequest req = request == null ? new EvalRunRequest() : request;
        String runId = evalService.runEval(req.getCaseIds(), req.isSkipLlmJudge(), req.isRunCalibration());
        return Result.success(Map.of("runId", runId, "status", "RUNNING"));
    }

    @GetMapping("/runs/{runId}")
    public Result<Map<String, Object>> getRun(@PathVariable String runId) {
        EvalService.EvalRun run = evalService.getRun(runId);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", run.getStatus());
        if (run.getError() != null) {
            body.put("error", run.getError());
        }
        if (run.getReport() != null) {
            body.put("report", run.getReport());
        }
        return Result.success(body);
    }

    @PostMapping("/calibrate")
    public Result<JudgeCalibrator.CalibrationResult> calibrate() {
        return Result.success(evalService.calibrate());
    }

    /** 评分漂移回归（ASR 热词纠错方案 4.5.1）：固定样例干净/噪声版各评一次，delta ≤ 0.5 通过 */
    @PostMapping("/noise-drift")
    public Result<EvalService.NoiseDriftResult> noiseDrift() {
        return Result.success(evalService.evaluateScoreDrift());
    }

    /** 干扰集门禁（ASR 热词纠错方案 4.5.2）：干净文本过纠错链路，corrections = 0 通过 */
    @PostMapping("/correction-gate")
    public Result<EvalService.CorrectionGateResult> correctionGate() {
        return Result.success(evalService.evaluateCorrectionGate());
    }
}
