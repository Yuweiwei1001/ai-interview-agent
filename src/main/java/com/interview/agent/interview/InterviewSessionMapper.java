package com.interview.agent.interview;

import com.interview.agent.interview.model.InterviewSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InterviewSessionMapper {
    @Insert("INSERT INTO interview_session(id, user_id, resume_id, jd_id, direction, persona, duration_minutes, status, interview_plan) VALUES(#{id}, #{userId}, #{resumeId}, #{jdId}, #{direction}, #{persona}, #{durationMinutes}, #{status}, #{interviewPlan})")
    void insert(InterviewSession session);

    @Select("SELECT * FROM interview_session WHERE id = #{id}")
    InterviewSession findById(String id);

    @Select("SELECT * FROM interview_session WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<InterviewSession> findByUserId(Long userId);

    @Update("UPDATE interview_session SET status = #{status}, overall_score = #{overallScore}, report = #{report}, updated_at = NOW() WHERE id = #{id}")
    void update(InterviewSession session);

    @Update("UPDATE interview_session SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    void updateStatus(@Param("id") String id, @Param("status") String status);

    @Update("UPDATE interview_session SET current_question = #{question}, updated_at = NOW() WHERE id = #{id}")
    void updateCurrentQuestion(@Param("id") String id, @Param("question") String question);

    @Update("UPDATE interview_session SET interview_plan = #{plan}, updated_at = NOW() WHERE id = #{id}")
    void updatePlan(@Param("id") String id, @Param("plan") String plan);
}
