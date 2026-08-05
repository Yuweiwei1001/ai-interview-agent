package com.interview.agent.coding;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CodingSubmissionMapper {
    @Insert("INSERT INTO coding_submission(session_id, round_number, code, language, status) VALUES(#{sessionId}, #{roundNumber}, #{code}, #{language}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CodingSubmission submission);

    @Update("UPDATE coding_submission SET test_results=#{testResults}, pass_rate=#{passRate}, evaluation=#{evaluation}, status=#{status}, updated_at=NOW() WHERE id=#{id}")
    void update(CodingSubmission submission);

    @Select("SELECT * FROM coding_submission WHERE session_id = #{sessionId} ORDER BY round_number ASC")
    List<CodingSubmission> findBySessionId(String sessionId);

    @Select("SELECT * FROM coding_submission WHERE id = #{id}")
    CodingSubmission findById(Long id);
}