package com.interview.agent.common.ai;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 轻量模型直连客户端（multimodal-generation 端点）。
 *
 * <p>qwen3.7-flash 为原生视觉语言（多模态）模型，只认 multimodal-generation 端点；
 * Spring AI 的 DashScope ChatModel 走 text-generation 端点，调 qwen3.7-flash 会报
 * InvalidParameter: url error。热词抽取/术语纠错等轻量任务经本客户端直连多模态端点。
 * 保持与 Spring AI 一致的 api-key（spring.ai.dashscope.api-key）。
 */
@Component
public class LightweightLlmClient {

    public LightweightLlmClient(@Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            Constants.apiKey = apiKey;
        }
    }

    /**
     * 关思考的轻量文本生成。
     * @return 模型回复纯文本；异常向上抛出（由 LlmCallWrapper 负责重试/降级）
     */
    public String callText(String model, String prompt, float temperature) throws Exception {
        MultiModalConversationMessage msg = MultiModalConversationMessage.builder()
                .role(Role.USER.getValue())
                .content(List.of(new MultiModalMessageItemText(prompt)))
                .build();
        MultiModalConversationResult result = new MultiModalConversation().call(
                MultiModalConversationParam.builder()
                        .model(model)
                        .message(msg)
                        .enableThinking(false)
                        .temperature(temperature)
                        .build());
        return extractText(result);
    }

    /** 从多模态输出中取纯文本（output.choices[0].message.content 的 text 项） */
    private static String extractText(MultiModalConversationResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return "";
        }
        var msg = result.getOutput().getChoices().get(0).getMessage();
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : msg.getContent()) {
            Object text = item.get("text");
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }
}
