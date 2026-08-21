package com.interview.agent.interview.agent;

/**
 * 会话上下文持有者，用于在 LLM 调用线程中传递当前会话的对话历史。
 * 由 TechnicalAgent/ProjectAgent 在 LLM 调用前设置，InterviewTools 的 @Tool 方法读取。
 * 与 BaseContext 模式相同，通过 ThreadLocal 在 executor 线程内传递。
 */
public class ConversationContext {
    private static final ThreadLocal<String> currentHistory = new ThreadLocal<>();

    public static void set(String history) {
        currentHistory.set(history);
    }

    public static String get() {
        return currentHistory.get();
    }

    public static void clear() {
        currentHistory.remove();
    }
}