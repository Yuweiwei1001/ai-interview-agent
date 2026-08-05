package com.interview.agent.interview.controller;

import com.interview.agent.common.result.Result;
import com.interview.agent.interview.InterviewAnswerDTO;
import com.interview.agent.interview.InterviewService;
import com.interview.agent.interview.InterviewStartDTO;
import com.interview.agent.interview.model.InterviewRound;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.interview.plan.InterviewPlan;
import com.interview.agent.interview.report.InterviewReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService interviewService;
    private final ObjectMapper objectMapper;

    public InterviewController(InterviewService interviewService, ObjectMapper objectMapper) {
        this.interviewService = interviewService;
        this.objectMapper = objectMapper;
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
}
