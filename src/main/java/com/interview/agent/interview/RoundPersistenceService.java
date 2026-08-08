package com.interview.agent.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.graph.InterviewState.RoundRecord;
import com.interview.agent.interview.model.InterviewRound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 面试轮次持久化：
 * 面试进行中由 EvaluateNode 每轮增量落库（刷新/重进页面可恢复历史对话）；
 * 面试结束时整体重建（delete + insert），保证最终数据与图状态一致。
 */
@Component
public class RoundPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(RoundPersistenceService.class);
    private final InterviewRoundMapper roundMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RoundPersistenceService(InterviewRoundMapper roundMapper, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.roundMapper = roundMapper;
        this.objectMapper = objectMapper;
    }

    /** 增量保存单轮（评估完成后调用） */
    public void saveRound(String sessionId, RoundRecord record) {
        try {
            roundMapper.insert(toEntity(sessionId, record));
        } catch (Exception e) {
            log.warn("增量保存轮次失败: sessionId={}, roundNumber={}", sessionId, record.getRoundNumber(), e);
        }
    }

    /** 面试结束时整体重建（幂等，避免增量数据与最终状态不一致/重复） */
    public void rebuildRounds(String sessionId, List<RoundRecord> records) {
        roundMapper.deleteBySessionId(sessionId);
        for (RoundRecord record : records) {
            try {
                roundMapper.insert(toEntity(sessionId, record));
            } catch (Exception e) {
                log.warn("重建轮次失败: sessionId={}, roundNumber={}", sessionId, record.getRoundNumber(), e);
            }
        }
    }

    private InterviewRound toEntity(String sessionId, RoundRecord record) throws JsonProcessingException {
        InterviewRound round = new InterviewRound();
        round.setSessionId(sessionId);
        round.setRoundNumber(record.getRoundNumber());
        round.setAgentName(record.getAgentName());
        round.setTopic(record.getTopic());
        round.setQuestion(record.getQuestion());
        round.setCandidateAnswer(record.getAnswer());
        round.setIsFollowup(record.isFollowup());
        round.setFollowupTarget(record.getFollowupTarget());
        round.setEvaluation(objectMapper.writeValueAsString(record.getEvaluation()));
        return round;
    }
}
