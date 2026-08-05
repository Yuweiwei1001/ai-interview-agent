package com.interview.agent.interview;

import com.interview.agent.interview.model.InterviewRound;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InterviewRoundMapper {
    @Insert("INSERT INTO interview_round(session_id, round_number, agent_name, topic, question, candidate_answer, evaluation, is_followup, followup_target) VALUES(#{sessionId}, #{roundNumber}, #{agentName}, #{topic}, #{question}, #{candidateAnswer}, #{evaluation}, #{isFollowup}, #{followupTarget})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(InterviewRound round);

    @Select("SELECT * FROM interview_round WHERE session_id = #{sessionId} ORDER BY round_number ASC")
    List<InterviewRound> findBySessionId(String sessionId);

    @Select("SELECT * FROM interview_round WHERE id = #{id}")
    InterviewRound findById(Long id);
}
