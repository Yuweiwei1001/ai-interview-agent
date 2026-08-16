package com.interview.agent.common.exception;

/**
 * 单题等待回答超时（候选人长时间未作答）。
 * 与手动终止（InterviewTerminatedException）区分：超时后系统已自动收尾
 * （生成已完成轮次的报告并置 interrupted），图执行侧只需停止、不再发 ERROR。
 */
public class InterviewTimeoutException extends RuntimeException {
    public InterviewTimeoutException(String message) {
        super(message);
    }
}
