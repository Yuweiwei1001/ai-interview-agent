package com.interview.agent.coding;

import com.interview.agent.common.result.Result;
import com.interview.agent.interview.InterviewService;
import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.sse.SseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coding")
public class CodingSubmitController {
    private static final Logger log = LoggerFactory.getLogger(CodingSubmitController.class);
    private final InterviewSessionMapper sessionMapper;
    private final CodingSubmissionMapper submissionMapper;
    private final InterviewService interviewService;
    private final SseRegistry sseRegistry;

    public CodingSubmitController(InterviewSessionMapper sessionMapper,
                                   CodingSubmissionMapper submissionMapper,
                                   InterviewService interviewService,
                                   SseRegistry sseRegistry) {
        this.sessionMapper = sessionMapper;
        this.submissionMapper = submissionMapper;
        this.interviewService = interviewService;
        this.sseRegistry = sseRegistry;
    }

    /**
     * 提交代码（恢复面试图执行）
     */
    @PostMapping("/submit/{sessionId}")
    public Result<Void> submitCode(@PathVariable String sessionId, @RequestBody CodingSubmitRequest request) {
        log.info("收到代码提交: sessionId={}, language={}", sessionId, request.getLanguage());

        // 验证会话存在且处于 waiting_code 状态
        InterviewSession session = sessionMapper.findById(sessionId);
        if (session == null) {
            return Result.error("面试会话不存在");
        }
        if (!"waiting_code".equals(session.getStatus())) {
            return Result.error("当前会话状态不允许提交代码: " + session.getStatus());
        }

        // 保存提交记录
        CodingSubmission submission = new CodingSubmission();
        submission.setSessionId(sessionId);
        submission.setCode(request.getCode());
        submission.setLanguage(request.getLanguage());
        submission.setStatus("pending");
        submissionMapper.insert(submission);

        // 更新会话状态
        sessionMapper.updateStatus(sessionId, "in_progress");

        // 触发 SSE 通知
        sseRegistry.sendEvent(sessionId, "CODE_SUBMITTED", "代码已提交，评估中...");

        // 异步恢复面试图执行
        interviewService.resumeCoding(sessionId, request.getCode(), request.getLanguage());

        return Result.success();
    }

    public static class CodingSubmitRequest {
        private String code;
        private String language;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
    }
}