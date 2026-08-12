package com.interview.agent.eval;

import com.interview.agent.coding.CodingSubmission;
import com.interview.agent.coding.CodingSubmissionMapper;
import com.interview.agent.common.context.BaseContext;
import com.interview.agent.interview.InterviewRoundMapper;
import com.interview.agent.interview.InterviewService;
import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.interview.InterviewStartDTO;
import com.interview.agent.interview.agent.tool.AskQuestionTool;
import com.interview.agent.interview.model.InterviewSession;
import com.interview.agent.jd.Jd;
import com.interview.agent.jd.JdMapper;
import com.interview.agent.resume.Resume;
import com.interview.agent.resume.ResumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 评测驱动器：进程内驱动一场完整模拟面试并采集轨迹。
 * 驱动方式（不依赖 SSE/HTTP，直接复用核心链路）：
 * 1. 注入评测专用简历/JD 记录，以预置 sessionId 启动面试；
 * 2. 轮询 interview_session.current_question，发现新题即按 answerLevel 脚本提交回答；
 * 3. 轮询会话状态，waiting_code 时依次提交用例预置代码（可触发重试路径）；
 * 4. 状态到达终态或驱动超时后结束，收集全部轮次作为评测轨迹。
 */
@Component
public class EvalRunner {
    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final long POLL_INTERVAL_MS = 500;

    private final InterviewService interviewService;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewRoundMapper roundMapper;
    private final AskQuestionTool askQuestionTool;
    private final ResumeMapper resumeMapper;
    private final JdMapper jdMapper;
    private final CodingSubmissionMapper codingSubmissionMapper;

    public EvalRunner(InterviewService interviewService, InterviewSessionMapper sessionMapper,
                      InterviewRoundMapper roundMapper, AskQuestionTool askQuestionTool,
                      ResumeMapper resumeMapper, JdMapper jdMapper,
                      CodingSubmissionMapper codingSubmissionMapper) {
        this.interviewService = interviewService;
        this.sessionMapper = sessionMapper;
        this.roundMapper = roundMapper;
        this.askQuestionTool = askQuestionTool;
        this.resumeMapper = resumeMapper;
        this.jdMapper = jdMapper;
        this.codingSubmissionMapper = codingSubmissionMapper;
    }

    public EvalTrace run(EvalCase evalCase, Long userId) {
        EvalTrace trace = new EvalTrace();
        trace.setCaseId(evalCase.getCaseId());
        String sessionId = "eval-" + evalCase.getCaseId() + "-" + System.currentTimeMillis();
        trace.setSessionId(sessionId);
        long start = System.currentTimeMillis();

        BaseContext.setCurrentId(userId);
        try {
            Long resumeId = insertResume(userId, evalCase);
            Long jdId = insertJd(userId, evalCase);

            InterviewStartDTO dto = new InterviewStartDTO();
            dto.setResumeId(resumeId);
            dto.setJdId(jdId);
            dto.setDirection(evalCase.getDirection());
            dto.setPersona(evalCase.getPersona());
            dto.setDurationMinutes(evalCase.getDurationMinutes());

            interviewService.startInterview(dto, sessionId);
            trace.addEvent("STARTED", "answerLevel=" + evalCase.getAnswerLevel());

            drive(sessionId, evalCase, trace);
        } catch (Exception e) {
            log.error("评测用例驱动失败: caseId={}", evalCase.getCaseId(), e);
            trace.setError(e.getMessage());
        } finally {
            collect(trace, sessionId);
            trace.setDurationMs(System.currentTimeMillis() - start);
            BaseContext.removeCurrentId();
        }
        return trace;
    }

    /** 驱动循环：轮询题目与状态，模拟候选人作答/交代码 */
    private void drive(String sessionId, EvalCase evalCase, EvalTrace trace) {
        long deadline = System.currentTimeMillis() + evalCase.getTimeoutMinutes() * 60_000L;
        List<EvalCase.CodingSubmission> codingSubs = evalCase.getCodingSubmissions();
        String lastAnsweredQuestion = null;
        String lastStatus = null;
        int codingSubmitIndex = 0;
        boolean codingExhaustedHandled = false;

        while (System.currentTimeMillis() < deadline) {
            InterviewSession session = sessionMapper.findById(sessionId);
            if (session == null) {
                sleepQuietly();
                continue;
            }

            String status = session.getStatus();
            if (!Objects.equals(status, lastStatus)) {
                trace.addEvent("STATUS_CHANGED", String.valueOf(status));
                lastStatus = status;
            }
            if ("completed".equals(status) || "interrupted".equals(status) || "cancelled".equals(status)) {
                return;
            }

            if ("waiting_code".equals(status)) {
                // 重试路径下状态会持续保持 waiting_code（题目也可能不变），
                // 以 DB 中的提交记录数为事实来源决定提交第几份代码，避免重复/遗漏提交
                int recordedCount;
                try {
                    recordedCount = codingSubmissionMapper.findBySessionId(sessionId).size();
                } catch (Exception e) {
                    recordedCount = codingSubmitIndex;
                }
                if (recordedCount == codingSubmitIndex) {
                    if (codingSubmitIndex < codingSubs.size()) {
                        EvalCase.CodingSubmission sub = codingSubs.get(codingSubmitIndex++);
                        trace.addEvent("CODE_SUBMITTED", "第" + codingSubmitIndex + "次提交, language=" + sub.getLanguage());
                        submitCodeLikeController(sessionId, sub);
                    } else if (!codingExhaustedHandled) {
                        codingExhaustedHandled = true;
                        trace.addEvent("CODING_SUBMISSIONS_EXHAUSTED", "无更多预置代码，主动结束面试");
                        interviewService.endInterview(sessionId);
                    }
                }
            }

            String question = session.getCurrentQuestion();
            if (question != null && !question.isBlank() && !question.equals(lastAnsweredQuestion)) {
                lastAnsweredQuestion = question;
                String answer = answerScript(evalCase.getAnswerLevel());
                askQuestionTool.submitAnswer(sessionId, answer);
                trace.addEvent("ANSWER_SUBMITTED", preview(question));
            }

            sleepQuietly();
        }

        // 驱动超时：强制结束，避免挂起阻塞后续用例
        trace.setDriverTimeout(true);
        trace.addEvent("DRIVER_TIMEOUT", "超过 " + evalCase.getTimeoutMinutes() + " 分钟，强制结束");
        try {
            interviewService.endInterview(sessionId);
        } catch (Exception e) {
            log.warn("驱动超时后结束面试失败: sessionId={}", sessionId);
        }
        // 等待收尾（报告生成）最多 30 秒
        long settle = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < settle) {
            InterviewSession session = sessionMapper.findById(sessionId);
            if (session != null && ("completed".equals(session.getStatus()) || "interrupted".equals(session.getStatus()))) {
                return;
            }
            sleepQuietly();
        }
    }

    /**
     * 复刻 CodingSubmitController 的提交链路：落提交记录 → 置 in_progress → 恢复图执行。
     * 进程内直调 resumeCoding 不会写 coding_submission，会导致轨迹丢失提交记录且 DB 计数失效。
     */
    private void submitCodeLikeController(String sessionId, EvalCase.CodingSubmission sub) {
        CodingSubmission submission = new CodingSubmission();
        submission.setSessionId(sessionId);
        submission.setRoundNumber(roundMapper.countBySessionId(sessionId) + 1);
        submission.setCode(sub.getCode());
        submission.setLanguage(sub.getLanguage());
        submission.setStatus("pending");
        codingSubmissionMapper.insert(submission);
        sessionMapper.updateStatus(sessionId, "in_progress");
        interviewService.resumeCoding(sessionId, sub.getCode(), sub.getLanguage());
    }

    private void collect(EvalTrace trace, String sessionId) {
        try {
            InterviewSession session = sessionMapper.findById(sessionId);
            if (session != null) {
                trace.setFinalStatus(session.getStatus());
                trace.setInterviewPlanJson(session.getInterviewPlan());
            }
            trace.setRounds(roundMapper.findBySessionId(sessionId));
        } catch (Exception e) {
            log.warn("评测轨迹收集失败: sessionId={}", sessionId, e);
        }
    }

    private Long insertResume(Long userId, EvalCase evalCase) {
        if (evalCase.getResumeText() == null || evalCase.getResumeText().isBlank()) {
            return null;
        }
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setFileName("eval-" + evalCase.getCaseId() + ".txt");
        resume.setFileType("eval");
        resume.setFileSize((long) evalCase.getResumeText().length());
        resume.setRawText(evalCase.getResumeText());
        resume.setContentHash("eval-" + evalCase.getCaseId());
        resumeMapper.insert(resume);
        return resume.getId();
    }

    private Long insertJd(Long userId, EvalCase evalCase) {
        if (evalCase.getJdText() == null || evalCase.getJdText().isBlank()) {
            return null;
        }
        Jd jd = new Jd();
        jd.setUserId(userId);
        jd.setTitle("eval-" + evalCase.getCaseId());
        jd.setRawText(evalCase.getJdText());
        jdMapper.insert(jd);
        return jd.getId();
    }

    /** 按档位返回模拟回答脚本（通用文本，不针对具体题目） */
    private String answerScript(String level) {
        return switch (level == null ? "MEDIUM" : level.toUpperCase()) {
            case "GOOD" -> "我从原理、实现和实践三个层面来回答。原理层面：这个知识点的核心机制涉及底层数据结构与关键流程的设计权衡，"
                    + "我理解其设计动机与适用边界；实现层面：需要考虑并发安全、内存占用与性能开销，关键路径上通常有锁粒度或无锁化的优化手段；"
                    + "实践层面：我在真实项目中结合业务场景使用过，针对高并发场景做过参数调优与压测验证，遇到过典型的线上问题并定位解决，"
                    + "也对比过同类方案的优劣。总结来说，我认为它适合对吞吐与一致性有明确要求的场景，边界情况下需要注意容量规划与降级策略。";
            case "MEDIUM" -> "这个知识点我了解过。它的大致原理我知道，核心思想是通过某种机制来解决常见问题，一般的使用方式也比较熟悉，"
                    + "但底层的一些细节我掌握得不够深入，实际项目中用过基础功能，没有做过针对性的性能优化。";
            case "POOR" -> "这个我好像听说过，但不太清楚具体细节，可能和缓存有关系吧。实际项目中没有怎么用过，说不太上来。";
            default -> "这个知识点我了解过，基本原理知道，但细节不太确定。";
        };
    }

    private String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
