package com.interview.agent.interview.agent;

import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ConversationSummarizer {
    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);
    private final ChatClient chatClient;

    public ConversationSummarizer(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 压缩对话历史为摘要
     */
    public String summarize(String conversationHistory) {
        return LlmCallWrapper.callWithRetry("summarizer", () -> {
            String prompt = "请将以下对话历史压缩为简洁的摘要（200字以内），保留关键信息：考察主题、候选人回答要点、评估结果。\n\n"
                    + conversationHistory;
            return chatClient.prompt().user(prompt).call().content();
        }, () -> fallbackSummarize(conversationHistory));
    }

    private String fallbackSummarize(String text) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= 500) return text;
        return text.substring(0, 500) + "...[已截断]";
    }
}
