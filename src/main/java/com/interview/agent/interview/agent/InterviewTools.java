package com.interview.agent.interview.agent;

import com.interview.agent.common.context.BaseContext;
import com.interview.agent.memory.KnowledgePointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 面试 Agent 可调用的工具集。
 * 将原先由 Java 代码硬注入的上下文（如历史薄弱知识点）改为 LLM 按需调用，
 * 实现"信息推送 → 信息拉取"的转变。
 */
@Component
public class InterviewTools {
    private static final Logger log = LoggerFactory.getLogger(InterviewTools.class);

    private final KnowledgePointService knowledgePointService;

    public InterviewTools(KnowledgePointService knowledgePointService) {
        this.knowledgePointService = knowledgePointService;
    }

    /**
     * 获取候选人历史薄弱知识点，用于针对性出题。
     * 包含长期记忆中的薄弱点与掌握点，LLM 可据此决定出题方向。
     */
    @Tool(description = "获取候选人历史薄弱知识点，用于针对性出题。返回该候选人的历史知识点记录（含薄弱点和掌握点）")
    public String getCandidateWeakPoints() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            log.warn("getCandidateWeakPoints: 当前线程无用户上下文，返回空");
            return "暂无历史知识点记录";
        }
        log.info("工具调用: getCandidateWeakPoints, userId={}", userId);
        return knowledgePointService.buildKnowledgeSummary();
    }

    /**
     * 获取当前会话的完整对话历史，用于回顾候选人之前的具体回答。
     * 包含每轮的题目与回答，LLM 可据此了解候选人已表达过的内容。
     */
    @Tool(description = "获取当前会话的完整对话历史，用于回顾候选人之前的具体回答。包含每轮的题目与回答")
    public String getConversationHistory() {
        String history = ConversationContext.get();
        if (history == null || history.isBlank()) {
            return "暂无对话历史";
        }
        log.info("工具调用: getConversationHistory, length={}", history.length());
        return history;
    }
}