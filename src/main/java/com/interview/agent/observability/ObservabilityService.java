package com.interview.agent.observability;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 可观测性查询服务：trace 明细、会话汇总、token/成本统计。
 */
@Service
public class ObservabilityService {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    private final LlmTraceMapper traceMapper;
    private final ObservabilityProperties properties;

    public ObservabilityService(LlmTraceMapper traceMapper, ObservabilityProperties properties) {
        this.traceMapper = traceMapper;
        this.properties = properties;
    }

    /** 启动时按保留期清理过期 trace（MVP 不做定时任务） */
    @PostConstruct
    public void purgeExpired() {
        try {
            int deleted = traceMapper.deleteOlderThan(LocalDateTime.now().minusDays(properties.getRetentionDays()));
            if (deleted > 0) {
                log.info("llm_trace 保留期清理: 删除 {} 条过期记录", deleted);
            }
        } catch (Exception e) {
            log.warn("llm_trace 保留期清理失败（已忽略）", e);
        }
    }

    public List<LlmTrace> tracesBySession(String sessionId) {
        return traceMapper.findBySessionId(sessionId);
    }

    public List<Map<String, Object>> sessionSummaries(int limit) {
        return traceMapper.sessionSummaries(Math.max(1, Math.min(limit, 200)));
    }

    public Map<String, Object> summary(int days) {
        LocalDateTime from = LocalDateTime.now().minusDays(Math.max(1, days));
        Map<String, Object> overall = traceMapper.overallSummary(from);
        overall.put("days", days);
        overall.put("byAgent", traceMapper.agentSummary(from));
        return overall;
    }
}
