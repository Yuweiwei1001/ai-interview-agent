package com.interview.agent.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本题回答评估器：由 LLM 对回答质量进行真实评分。
 * 替代历史的长度启发式（answer.length()*2），避免分数失真导致面试被提前终结。
 */
@Component
public class AnswerEvaluator {
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluator.class);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnswerEvaluator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 评估结果：score 0-100（内容质量），communication 0-100（表达清晰度），
     * knowledgePoints 本题实际考察的具体知识点，summary 简要点评
     */
    public record EvaluationResult(int score, int communication, List<String> knowledgePoints, String summary) {}

    /**
     * 评估回答并返回评分与点评；LLM 调用失败时降级为参考分 60（不影响流程推进）。
     */
    public EvaluationResult evaluate(String question, String answer) {
        return evaluate(question, answer, null);
    }

    /**
     * 带知识库参考片段评估：referenceKnowledge 为检索到的权威知识，作为评分事实依据。
     */
    public EvaluationResult evaluate(String question, String answer, String referenceKnowledge) {
        return LlmCallWrapper.callWithRetry("evaluator",
                () -> {
                    String content = chatClient.prompt().user(buildPrompt(question, answer, referenceKnowledge)).call().content();
                    return parse(content);
                },
                () -> new EvaluationResult(60, 60, List.of(), "评估服务暂不可用，按参考分计入"));
    }

    private String buildPrompt(String question, String answer, String referenceKnowledge) {
        String q = question == null ? "" : question;
        String a = answer == null ? "" : answer;
        // 控制 prompt 长度，避免超长回答拖垮上下文
        if (a.length() > 4000) {
            a = a.substring(0, 4000) + "……";
        }
        return "你是一位专业的技术面试官，请评估候选人对面试题的回答质量。\n\n"
                + "面试题：" + q + "\n\n"
                + "候选人回答：" + a + "\n\n"
                + (referenceKnowledge != null && !referenceKnowledge.isBlank()
                        ? "参考知识（来自面试官知识库，作为评分的事实依据）：\n" + referenceKnowledge + "\n\n"
                        : "")
                + "评分标准：90-100 优秀（准确、深入、有实践见解）；70-89 良好（基本正确但深度或广度不足）；"
                + "50-69 一般（部分正确、有明显缺漏）；0-49 不达标（错误较多或答非所问）。\n"
                + "同时评估沟通表达（communication）：表达是否清晰有条理、是否紧扣问题、是否易于理解，与内容正确性无关。\n"
                + "同时提取本题实际考察的具体知识点（knowledgePoints）：针对题目与回答内容提取 1-4 个具体技术概念"
                + "（如\"HashMap扩容机制\"、\"TCP三次握手\"，而非\"Java\"这类粗粒度词），回答未涉及的不要列。\n"
                + "只输出 JSON，不要输出任何其他内容：{\"score\": <0-100的整数>, \"communication\": <0-100的整数>, "
                + "\"knowledgePoints\": [\"<知识点1>\", ...], \"summary\": \"<60字以内的简要点评>\"}";
    }

    private EvaluationResult parse(String content) {
        try {
            String json = content == null ? "" : content;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("LLM 评分输出缺少 JSON: " + json);
            }
            JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
            int score = Math.max(0, Math.min(100, node.path("score").asInt(60)));
            // LLM 未输出沟通分时以内容分兜底，保证维度分不为 0
            int communication = Math.max(0, Math.min(100, node.path("communication").asInt(score)));
            List<String> knowledgePoints = new ArrayList<>();
            JsonNode kpNode = node.path("knowledgePoints");
            if (kpNode.isArray()) {
                for (JsonNode kp : kpNode) {
                    String text = kp.asText("").trim();
                    if (!text.isBlank() && text.length() <= 30) {
                        knowledgePoints.add(text);
                    }
                    if (knowledgePoints.size() >= 4) break;
                }
            }
            String summary = node.path("summary").asText("");
            return new EvaluationResult(score, communication, knowledgePoints, summary);
        } catch (Exception e) {
            // 解析失败视为本次 LLM 调用失败，交由重试/降级处理
            throw new RuntimeException("LLM 评分结果解析失败", e);
        }
    }
}
