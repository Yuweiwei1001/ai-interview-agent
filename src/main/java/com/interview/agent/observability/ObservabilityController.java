package com.interview.agent.observability;

import com.interview.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 可观测性查询接口（LLM 调用追踪 + token/成本统计）。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {
    private final ObservabilityService observabilityService;

    public ObservabilityController(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    /** 会话维度汇总列表（最近 limit 场，默认 50） */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(observabilityService.sessionSummaries(limit));
    }

    /** 单场面试的 LLM 调用明细（按时间升序） */
    @GetMapping("/traces")
    public Result<List<LlmTrace>> traces(@RequestParam String sessionId) {
        return Result.success(observabilityService.tracesBySession(sessionId));
    }

    /** 汇总统计：总 token/成本/错误数 + 按 agent 维度拆分（默认近 7 天） */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam(defaultValue = "7") int days) {
        return Result.success(observabilityService.summary(days));
    }
}
