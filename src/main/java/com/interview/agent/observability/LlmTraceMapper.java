package com.interview.agent.observability;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface LlmTraceMapper {
    @Insert("INSERT INTO llm_trace(session_id, trace_id, agent, kind, model, prompt_tokens, completion_tokens, total_tokens, " +
            "duration_ms, status, error_msg, estimated_cost, prompt_excerpt, completion_excerpt) " +
            "VALUES(#{sessionId}, #{traceId}, #{agent}, #{kind}, #{model}, #{promptTokens}, #{completionTokens}, #{totalTokens}, " +
            "#{durationMs}, #{status}, #{errorMsg}, #{estimatedCost}, #{promptExcerpt}, #{completionExcerpt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(LlmTrace trace);

    /** 评分回写：本轮评估完成后把调整分写到该轮所有 trace 行（llm + retrieval） */
    @Update("UPDATE llm_trace SET eval_score = #{score} WHERE trace_id = #{traceId}")
    int updateEvalScoreByTraceId(String traceId, int score);

    @Select("SELECT * FROM llm_trace WHERE session_id = #{sessionId} ORDER BY created_at ASC, id ASC")
    List<LlmTrace> findBySessionId(String sessionId);

    /** 会话维度汇总（最近 limit 个会话，按最后调用时间倒序；仅统计 LLM 调用，检索 span 不计入） */
    @Select("SELECT session_id AS sessionId, COUNT(*) AS callCount, " +
            "COALESCE(SUM(total_tokens), 0) AS totalTokens, " +
            "COALESCE(SUM(estimated_cost), 0) AS estimatedCost, " +
            "SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errorCount, " +
            "MIN(created_at) AS startedAt, MAX(created_at) AS lastAt " +
            "FROM llm_trace WHERE session_id IS NOT NULL AND kind = 'llm' " +
            "GROUP BY session_id ORDER BY lastAt DESC LIMIT #{limit}")
    List<Map<String, Object>> sessionSummaries(int limit);

    /** 时间范围内总体汇总（仅统计 LLM 调用，检索 span 不计入调用数） */
    @Select("SELECT COUNT(*) AS callCount, " +
            "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(total_tokens), 0) AS totalTokens, " +
            "COALESCE(SUM(estimated_cost), 0) AS estimatedCost, " +
            "SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errorCount " +
            "FROM llm_trace WHERE created_at >= #{from} AND kind = 'llm'")
    Map<String, Object> overallSummary(LocalDateTime from);

    /** 时间范围内按 agent 维度汇总（含检索 span 的 retriever 归因，便于观测检索调用量） */
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
