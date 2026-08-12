package com.interview.agent.interview;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.common.exception.BaseException;
import com.interview.agent.common.exception.InterviewTerminatedException;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.graph.InterviewGraphBuilder;
import com.interview.agent.interview.graph.InterviewState;
import com.interview.agent.interview.model.InterviewRound;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.interview.plan.PlanGenerator;
import com.interview.agent.interview.report.ReportGenerator;
import com.interview.agent.jd.JdService;
import com.interview.agent.knowledge.KnowledgeService;
import com.interview.agent.resume.ResumeService;
import com.interview.agent.sse.SseRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
    private final ReportGenerator reportGenerator;
    private final RoundPersistenceService roundPersistenceService;
    private final KnowledgeService knowledgeService;
    private final Executor interviewExecutor;

    public InterviewService(InterviewSessionMapper sessionMapper, InterviewRoundMapper roundMapper,
                            ResumeService resumeService, JdService jdService,
                            PlanGenerator planGenerator, InterviewGraphBuilder graphBuilder,
                            AskQuestionTool askQuestionTool, SseRegistry sseRegistry, ObjectMapper objectMapper,
                            ReportGenerator reportGenerator, RoundPersistenceService roundPersistenceService,
                            KnowledgeService knowledgeService,
                            @Qualifier("interviewExecutor") Executor interviewExecutor) {
        this.sessionMapper = sessionMapper;
        this.roundMapper = roundMapper;
        this.resumeService = resumeService;
        this.jdService = jdService;
        this.planGenerator = planGenerator;
        this.graphBuilder = graphBuilder;
        this.askQuestionTool = askQuestionTool;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
        this.reportGenerator = reportGenerator;
        this.roundPersistenceService = roundPersistenceService;
        this.knowledgeService = knowledgeService;
        this.interviewExecutor = interviewExecutor;
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

        // 知识库归属校验（不存在/无权限直接拒绝，不创建会话）
        if (dto.getKnowledgeBaseId() != null) {
            knowledgeService.getKb(dto.getKnowledgeBaseId(), userId);
        }

        // 注册 SSE 连接
        SseEmitter emitter = sseRegistry.register(sessionId);
        sseRegistry.sendEvent(sessionId, "CONNECTED", sessionId);

        // 创建会话记录（interview_plan 先为 null，异步生成后回填）
        InterviewSession session = new InterviewSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setResumeId(dto.getResumeId());
        session.setJdId(dto.getJdId());
        session.setDirection(dto.getDirection());
        session.setPersona(dto.getPersona());
        session.setDurationMinutes(dto.getDurationMinutes());
        session.setKnowledgeBaseId(dto.getKnowledgeBaseId());
        session.setStatus("in_progress");
        session.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        // 异步执行面试（专用线程池，不阻塞 SSE 首包；计划生成也在异步线程完成）
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
                initialState.setKnowledgeBaseId(dto.getKnowledgeBaseId());

                // 生成计划（异步，不阻塞 SSE 首包）
                InterviewPlan plan = createPlan(dto.getResumeId(), dto.getJdId(), dto.getDirection(), dto.getPersona(), dto.getDurationMinutes());
                if (plan != null) {
                    initialState.setPlan(plan);
                    try {
                        session.setInterviewPlan(objectMapper.writeValueAsString(plan));
                    } catch (JsonProcessingException e) {
                        log.warn("序列化面试计划失败", e);
                    }
                    sessionMapper.updatePlan(sessionId, session.getInterviewPlan());
                }

                // 执行面试图
                InterviewState finalState = graphBuilder.executeInterview(initialState);

                // 检查是否挂起等待代码提交
                if (finalState.isWaitingForCode()) {
                    log.info("面试图已挂起，等待代码提交: sessionId={}", sessionId);
                    sessionMapper.updateStatus(sessionId, "waiting_code");
                    String question = finalState.getCurrentQuestion() != null ? finalState.getCurrentQuestion() : "编码题已出，请提交代码";
                    sseRegistry.sendEvent(sessionId, "WAITING_CODE", buildQuestionPayload(finalState.getCurrentRound() + 1, question, false));
                    return;
                }

                // 完成流程前重新读取会话：若已被 endInterview 置为 interrupted 则跳过，不覆盖状态
                InterviewSession latest = sessionMapper.findById(sessionId);
                if (latest != null && "interrupted".equals(latest.getStatus())) {
                    log.info("面试已被手动结束，跳过完成逻辑: sessionId={}", sessionId);
                    return;
                }

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

                // 报告生成（同步，保证 REPORT_READY 先于 COMPLETE 推送）
                reportGenerator.generateReport(sessionId);

                sseRegistry.sendEvent(sessionId, "COMPLETE", "面试完成");
                sseRegistry.complete(sessionId);

            } catch (InterviewTerminatedException e) {
                log.info("面试已被终止: sessionId={}", sessionId);
                // 不发送 ERROR，保持 interrupted 状态
            } catch (Exception e) {
                log.error("面试执行失败: sessionId={}", sessionId, e);
                sessionMapper.updateStatus(sessionId, "interrupted");
                sseRegistry.sendError(sessionId, "面试执行失败: " + e.getMessage());
            } finally {
                BaseContext.removeCurrentId();
            }
        }, interviewExecutor);

        return emitter;
    }

    /**
     * 恢复 Coding 面试执行（代码提交后恢复图执行）
     */
    public void resumeCoding(String sessionId, String code, String language) {
        CompletableFuture.runAsync(() -> {
            Long userId = null;
            try {
                // 从会话查询 userId，恢复用户上下文（getById 依赖 ThreadLocal 校验）
                InterviewSession session = sessionMapper.findById(sessionId);
                if (session == null) {
                    log.warn("会话不存在，无法恢复: sessionId={}", sessionId);
                    return;
                }
                userId = session.getUserId();
                BaseContext.setCurrentId(userId);

                log.info("恢复 Coding 面试: sessionId={}, language={}", sessionId, language);

                // 从 Checkpoint 恢复图执行，携带代码
                InterviewState finalState = graphBuilder.resumeInterview(sessionId, code, language);

                // 处理恢复后的结果
                handleGraphResult(sessionId, finalState);

            } catch (InterviewTerminatedException e) {
                log.info("面试已被终止，忽略恢复执行: sessionId={}", sessionId);
            } catch (Exception e) {
                log.error("恢复 Coding 面试失败: sessionId={}", sessionId, e);
                sessionMapper.updateStatus(sessionId, "interrupted");
                sseRegistry.sendError(sessionId, "编码提交处理失败: " + e.getMessage());
            } finally {
                if (userId != null) {
                    BaseContext.removeCurrentId();
                }
            }
        }, interviewExecutor);
    }

    /**
     * 处理图执行结果（完成或继续挂起）
     */
    private void handleGraphResult(String sessionId, InterviewState finalState) {
        if (finalState == null) {
            log.warn("图执行返回空状态: sessionId={}", sessionId);
            sessionMapper.updateStatus(sessionId, "interrupted");
            return;
        }

        if (finalState.isWaitingForCode()) {
            // 又遇到编码题，继续挂起等待；带上人格策略生成的提示
            log.info("面试图再次挂起，等待新编码题提交: sessionId={}", sessionId);
            sessionMapper.updateStatus(sessionId, "waiting_code");
            String message = "新的编码题已出，请提交代码";
            if (finalState.getCodingHint() != null && !finalState.getCodingHint().isBlank()) {
                message = finalState.getCodingHint();
            }
            sseRegistry.sendEvent(sessionId, "WAITING_CODE", buildQuestionPayload(finalState.getCurrentRound() + 1, message, false));
            return;
        }

        // 完成流程前重新读取会话：若已被 endInterview 置为 interrupted 则跳过，不覆盖状态
        InterviewSession latest = sessionMapper.findById(sessionId);
        if (latest != null && "interrupted".equals(latest.getStatus())) {
            log.info("面试已被手动结束，跳过完成逻辑: sessionId={}", sessionId);
            return;
        }

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

        InterviewSession session = sessionMapper.findById(sessionId);
        if (session == null) {
            log.warn("会话不存在: sessionId={}", sessionId);
            return;
        }
        session.setOverallScore(BigDecimal.valueOf(avgScore));
        session.setStatus("completed");
        session.setCompletedAt(LocalDateTime.now());
        sessionMapper.update(session);

        // 报告生成（同步，保证 REPORT_READY 先于 COMPLETE 推送）
        reportGenerator.generateReport(sessionId);

        sseRegistry.sendEvent(sessionId, "COMPLETE", "面试完成");
        sseRegistry.complete(sessionId);
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

        // 手动结束也生成报告（已评估轮次均增量落库，可直接汇总），
        // 避免前端“报告生成中”无限等待；无轮次数据时跳过
        try {
            long roundCount = roundMapper.countBySessionId(sessionId);
            if (roundCount > 0) {
                reportGenerator.generateReport(sessionId);
                sseRegistry.sendEvent(sessionId, "REPORT_READY", "报告已生成");
            }
        } catch (Exception e) {
            log.warn("手动结束生成报告失败: sessionId={}", sessionId, e);
        }

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

    private String buildQuestionPayload(int questionNumber, String question, boolean isFollowUp) {
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("questionNumber", questionNumber);
            payload.put("question", question);
            payload.put("isFollowUp", isFollowUp);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("题目事件序列化失败，回退纯文本", e);
            return question;
        }
    }

    private void saveRounds(String sessionId, InterviewState state) {
        // 面试结束时整体重建：EvaluateNode 已逐轮增量保存，此处删除后重插保证最终一致
        roundPersistenceService.rebuildRounds(sessionId, state.getRounds());
    }
}
