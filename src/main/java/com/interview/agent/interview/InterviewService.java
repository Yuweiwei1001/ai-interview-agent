package com.interview.agent.interview;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.InterviewGraphBuilder;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.model.InterviewRound;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.interview.plan.PlanGenerator;
import com.interview.agent.jd.JdService;
import com.interview.agent.resume.ResumeService;
import com.interview.agent.sse.SseRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class InterviewService {
    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    private final InterviewSessionMapper sessionMapper;
    private final InterviewRoundMapper roundMapper;
    private final ResumeService resumeService;
    private final JdService jdService;
    private final PlanGenerator planGenerator;
    private final InterviewGraphBuilder graphBuilder;
    private final AskQuestionTool askQuestionTool;
    private final SseRegistry sseRegistry;
    private final ObjectMapper objectMapper;

    public InterviewService(InterviewSessionMapper sessionMapper, InterviewRoundMapper roundMapper,
                            ResumeService resumeService, JdService jdService,
                            PlanGenerator planGenerator, InterviewGraphBuilder graphBuilder,
                            AskQuestionTool askQuestionTool, SseRegistry sseRegistry, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.roundMapper = roundMapper;
        this.resumeService = resumeService;
        this.jdService = jdService;
        this.planGenerator = planGenerator;
        this.graphBuilder = graphBuilder;
        this.askQuestionTool = askQuestionTool;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建面试计划
     */
    public InterviewPlan createPlan(Long resumeId, Long jdId, String direction, String persona, int durationMinutes) {
        String resumeText = null;
        if (resumeId != null) {
            resumeText = resumeService.getById(resumeId).getRawText();
        }
        String jdText = null;
        if (jdId != null) {
            jdText = jdService.getById(jdId).getRawText();
        }
        return planGenerator.generatePlan(resumeText, jdText, direction, persona, durationMinutes);
    }

    /**
     * 启动面试（SSE 长连接）
     */
    public SseEmitter startInterview(InterviewStartDTO dto) {
        Long userId = BaseContext.getCurrentId();
        String sessionId = UUID.randomUUID().toString();

        // 注册 SSE 连接
        SseEmitter emitter = sseRegistry.register(sessionId);
        sseRegistry.sendEvent(sessionId, "CONNECTED", sessionId);

        // 创建会话记录
        InterviewSession session = new InterviewSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setResumeId(dto.getResumeId());
        session.setJdId(dto.getJdId());
        session.setDirection(dto.getDirection());
        session.setPersona(dto.getPersona());
        session.setDurationMinutes(dto.getDurationMinutes());
        session.setStatus("in_progress");
        session.setStartedAt(LocalDateTime.now());

        // 生成计划
        InterviewPlan plan = createPlan(dto.getResumeId(), dto.getJdId(), dto.getDirection(), dto.getPersona(), dto.getDurationMinutes());
        try {
            session.setInterviewPlan(objectMapper.writeValueAsString(plan));
        } catch (JsonProcessingException e) {
            log.warn("序列化面试计划失败", e);
        }
        sessionMapper.insert(session);

        // 异步执行面试图
        CompletableFuture.runAsync(() -> {
            // 异步线程中恢复用户上下文（getById 依赖 ThreadLocal 校验）
            BaseContext.setCurrentId(userId);
            try {
                // 构建初始状态
                InterviewState initialState = new InterviewState();
                initialState.setSessionId(sessionId);
                initialState.setUserId(userId);
                if (dto.getResumeId() != null) {
                    initialState.setResumeText(resumeService.getById(dto.getResumeId()).getRawText());
                }
                if (dto.getJdId() != null) {
                    initialState.setJdText(jdService.getById(dto.getJdId()).getRawText());
                }
                initialState.setDirection(dto.getDirection());
                initialState.setPersona(dto.getPersona());
                initialState.setDurationMinutes(dto.getDurationMinutes());

                // 执行面试图
                InterviewState finalState = graphBuilder.executeInterview(initialState);

                // 保存轮次记录
                saveRounds(sessionId, finalState);

                // 计算总分
                double avgScore = finalState.getRounds().stream()
                        .mapToInt(r -> {
                            Object score = r.getEvaluation().get("score");
                            return score instanceof Number ? ((Number) score).intValue() : 0;
                        })
                        .average()
                        .orElse(0);
                session.setOverallScore(BigDecimal.valueOf(avgScore));
                session.setStatus("completed");
                session.setCompletedAt(LocalDateTime.now());
                sessionMapper.update(session);

                sseRegistry.sendEvent(sessionId, "COMPLETE", "面试完成");
                sseRegistry.complete(sessionId);

            } catch (Exception e) {
                log.error("面试执行失败: sessionId={}", sessionId, e);
                sessionMapper.updateStatus(sessionId, "interrupted");
                sseRegistry.sendError(sessionId, "面试执行失败: " + e.getMessage());
            } finally {
                BaseContext.removeCurrentId();
            }
        });

        return emitter;
    }

    /**
     * 提交回答
     */
    public void submitAnswer(String sessionId, String answer) {
        InterviewSession session = sessionMapper.findById(sessionId);
        if (session == null) throw new BaseException("面试会话不存在");

        Long userId = BaseContext.getCurrentId();
        if (!session.getUserId().equals(userId)) {
            throw new BaseException("无权操作该面试");
        }

        askQuestionTool.submitAnswer(sessionId, answer);
    }

    /**
     * 结束面试
     */
    public void endInterview(String sessionId) {
        InterviewSession session = sessionMapper.findById(sessionId);
        if (session == null) throw new BaseException("面试会话不存在");

        Long userId = BaseContext.getCurrentId();
        if (!session.getUserId().equals(userId)) {
            throw new BaseException("无权操作该面试");
        }

        askQuestionTool.cancel(sessionId);
        sessionMapper.updateStatus(sessionId, "interrupted");
        sseRegistry.sendEvent(sessionId, "COMPLETE", "面试已手动结束");
        sseRegistry.complete(sessionId);
    }

    /**
     * 获取会话列表
     */
    public List<InterviewSession> getSessions() {
        return sessionMapper.findByUserId(BaseContext.getCurrentId());
    }

    /**
     * 获取会话详情
     */
    public InterviewSession getSession(String id) {
        InterviewSession session = sessionMapper.findById(id);
        if (session == null) throw new BaseException("面试会话不存在");
        if (!session.getUserId().equals(BaseContext.getCurrentId())) {
            throw new BaseException("无权访问该面试");
        }
        return session;
    }

    /**
     * 获取轮次列表
     */
    public List<InterviewRound> getRounds(String sessionId) {
        InterviewSession session = getSession(sessionId);
        return roundMapper.findBySessionId(sessionId);
    }

    private void saveRounds(String sessionId, InterviewState state) {
        for (InterviewState.RoundRecord record : state.getRounds()) {
            InterviewRound round = new InterviewRound();
            round.setSessionId(sessionId);
            round.setRoundNumber(record.getRoundNumber());
            round.setAgentName(record.getAgentName());
            round.setTopic(record.getTopic());
            round.setQuestion(record.getQuestion());
            round.setCandidateAnswer(record.getAnswer());
            round.setIsFollowup(record.isFollowup());
            round.setFollowupTarget(record.getFollowupTarget());
            try {
                round.setEvaluation(objectMapper.writeValueAsString(record.getEvaluation()));
            } catch (JsonProcessingException e) {
                log.warn("序列化评估结果失败", e);
            }
            roundMapper.insert(round);
        }
    }
}
