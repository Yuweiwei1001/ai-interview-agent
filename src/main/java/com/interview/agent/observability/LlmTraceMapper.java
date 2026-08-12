package com.interview.agent.observability;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface LlmTraceMapper {
    @Insert("INSERT INTO llm_trace(session_id, agent, model, prompt_tokens, completion_tokens, total_tokens, " +
            "duration_ms, status, error_msg, estimated_cost, prompt_excerpt, completion_excerpt) " +
            "VALUES(#{sessionId}, #{agent}, #{model}, #{promptTokens}, #{completionTokens}, #{totalTokens}, " +
            "#{durationMs}, #{status}, #{errorMsg}, #{estimatedCost}, #{promptExcerpt}, #{completionExcerpt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LlmTrace trace);

    @Select("SELECT * FROM llm_trace WHERE session_id = #{sessionId} ORDER BY created_at ASC, id ASC")
    List<LlmTrace> findBySessionId(String sessionId);

    /** 会话维度汇总（最近 limit 个会话，按最后调用时间倒序） */
    @Select("SELECT session_id AS sessionId, COUNT(*) AS callCount, " +
            "COALESCE(SUM(total_tokens), 0) AS totalTokens, " +
            "COALESCE(SUM(estimated_cost), 0) AS estimatedCost, " +
            "SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errorCount, " +
            "MIN(created_at) AS startedAt, MAX(created_at) AS lastAt " +
            "FROM llm_trace WHERE session_id IS NOT NULL " +
            "GROUP BY session_id ORDER BY lastAt DESC LIMIT #{limit}")
    List<Map<String, Object>> sessionSummaries(int limit);

    /** 时间范围内总体汇总 */
    @Select("SELECT COUNT(*) AS callCount, " +
            "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(total_tokens), 0) AS totalTokens, " +
            "COALESCE(SUM(estimated_cost), 0) AS estimatedCost, " +
            "SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errorCount " +
            "FROM llm_trace WHERE created_at >= #{from}")
    Map<String, Object> overallSummary(LocalDateTime from);

    /** 时间范围内按 agent 维度汇总 */
    @Select("SELECT COALESCE(agent, 'unknown') AS agent, COUNT(*) AS callCount, " +
            "COALESCE(SUM(total_tokens), 0) AS totalTokens, " +
            "COALESCE(SUM(estimated_cost), 0) AS estimatedCost, " +
            "SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errorCount " +
            "FROM llm_trace WHERE created_at >= #{from} GROUP BY agent ORDER BY totalTokens DESC")
    List<Map<String, Object>> agentSummary(LocalDateTime from);

    /** 保留期清理 */
    @Delete("DELETE FROM llm_trace WHERE created_at < #{before}")
    int deleteOlderThan(LocalDateTime before);
}
