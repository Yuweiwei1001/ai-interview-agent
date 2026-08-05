package com.interview.agent.interview.controller;

import com.interview.agent.common.exception.BaseException;
import com.interview.agent.common.result.Result;
import com.interview.agent.interview.InterviewAnswerDTO;
import com.interview.agent.interview.InterviewService;
import com.interview.agent.interview.InterviewStartDTO;
import com.interview.agent.interview.model.InterviewRound;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.interview.report.InterviewReport;
import com.interview.agent.sse.SseRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService interviewService;
    private final ObjectMapper objectMapper;
    private final SseRegistry sseRegistry;

    public InterviewController(InterviewService interviewService, ObjectMapper objectMapper, SseRegistry sseRegistry) {
        this.interviewService = interviewService;
        this.objectMapper = objectMapper;
        this.sseRegistry = sseRegistry;
    }

    @PostMapping("/plan")
    public Result<InterviewPlan> createPlan(@Valid @RequestBody InterviewStartDTO dto) {
        InterviewPlan plan = interviewService.createPlan(
                dto.getResumeId(), dto.getJdId(), dto.getDirection(), dto.getPersona(), dto.getDurationMinutes());
        return Result.success(plan);
    }

    @PostMapping("/start")
    public SseEmitter startInterview(@Valid @RequestBody InterviewStartDTO dto) {
        return interviewService.startInterview(dto);
    }

    /**
     * 重连 SSE 流（面试中途刷新页面/编码页监听复用）
     */
    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable String id) {
        InterviewSession session = interviewService.getSession(id);
        return sseRegistry.register(id);
    }

    @PostMapping("/{id}/answer")
    public Result<Void> submitAnswer(@PathVariable String id, @Valid @RequestBody InterviewAnswerDTO dto) {
        interviewService.submitAnswer(id, dto.getAnswer());
        return Result.success();
    }

    @PostMapping("/{id}/end")
    public Result<Void> endInterview(@PathVariable String id) {
        interviewService.endInterview(id);
        return Result.success();
    }

    @GetMapping("/sessions")
    public Result<List<InterviewSession>> getSessions() {
        return Result.success(interviewService.getSessions());
    }

    @GetMapping("/sessions/{id}")
    public Result<InterviewSession> getSession(@PathVariable String id) {
        return Result.success(interviewService.getSession(id));
    }

    @GetMapping("/sessions/{id}/rounds")
    public Result<List<InterviewRound>> getRounds(@PathVariable String id) {
        return Result.success(interviewService.getRounds(id));
    }

    @GetMapping("/sessions/{id}/report")
    public Result<InterviewReport> getReport(@PathVariable String id) {
        InterviewSession session = interviewService.getSession(id);
        if (session.getReport() == null) {
            return Result.error("报告尚未生成");
        }
        try {
            InterviewReport report = objectMapper.readValue(session.getReport(), InterviewReport.class);
            return Result.success(report);
        } catch (Exception e) {
            return Result.error("报告解析失败");
        }
    }

    @GetMapping("/sessions/{id}/report.pdf")
    public void downloadReportPdf(@PathVariable String id, HttpServletResponse response) {
        InterviewSession session = interviewService.getSession(id);
        if (session.getReport() == null) {
            throw new BaseException("报告尚未生成");
        }

        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=interview-report-" + id + ".txt");

        try {
            String reportText = buildReportText(session);
            response.getOutputStream().write(reportText.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BaseException("报告导出失败");
        }
    }

    private String buildReportText(InterviewSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI 面试报告\n");
        sb.append("================\n\n");
        sb.append("会话ID: ").append(session.getId()).append("\n");
        sb.append("状态: ").append(session.getStatus()).append("\n");
        sb.append("总分: ").append(session.getOverallScore()).append("\n");
        sb.append("开始时间: ").append(session.getStartedAt()).append("\n");
        sb.append("结束时间: ").append(session.getCompletedAt()).append("\n\n");
        if (session.getReport() != null) {
            sb.append("报告详情:\n").append(session.getReport()).append("\n");
        }
        return sb.toString();
    }
}
