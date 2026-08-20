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
     * 评估回答并返回评分与点评（无热词上下文版，兼容既有调用方/评测链路）。
     */
    public EvaluationResult evaluate(String question, String answer) {
        return evaluate(question, answer, List.of(), false);
    }

    /**
     * 评估回答并返回评分与点评（ASR 热词纠错方案 P0：注入会话热词表 + ASR 噪声声明）。
     * LLM 调用失败时降级为参考分 60（不影响流程推进）。
     *
     * @param sessionHotwords 本场面试相关术语表（同音错字按术语表正确术语理解，不因错字扣分）
     * @param asrTranscribed  回答是否经语音识别转写（语音面试才注入噪声声明；文字面试无此问题）
     */
    public EvaluationResult evaluate(String question, String answer, List<String> sessionHotwords, boolean asrTranscribed) {
        return LlmCallWrapper.callWithRetry("evaluator",
                () -> {
                    String content = chatClient.prompt().user(buildPrompt(question, answer, sessionHotwords, asrTranscribed)).call().content();
                    return parse(content);
                },
                () -> new EvaluationResult(60, 60, List.of(), "评估服务暂不可用，按参考分计入"));
    }

    private String buildPrompt(String question, String answer, List<String> sessionHotwords, boolean asrTranscribed) {
        String q = question == null ? "" : question;
        String a = answer == null ? "" : answer;
        // 控制 prompt 长度，避免超长回答拖垮上下文
        if (a.length() > 4000) {
            a = a.substring(0, 4000) + "……";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的技术面试官，请评估候选人对面试题的回答质量。\n\n");
        sb.append("面试题：").append(q).append("\n\n");
        sb.append("候选人回答：").append(a).append("\n\n");
        // P0 评分容忍 ASR 噪声：语音转写产生的同音错字不应导致“答对了评成答错”
        if (sessionHotwords != null && !sessionHotwords.isEmpty()) {
            sb.append("本场面试相关术语表：").append(String.join("、", sessionHotwords)).append("\n");
            if (asrTranscribed) {
                sb.append("注意：候选人回答经语音识别（ASR）转写，技术术语可能存在同音/近音错字"
                        + "（如 Raft→拉夫特、Redis→瑞迪斯、Kubernetes→库伯内提斯）。"
                        + "若某词与术语表中术语发音相同或相近，按术语表的正确术语理解作答内容，"
                        + "不要因转写错字扣分。\n");
            }
            sb.append("\n");
        }
        sb.append("评分标准：90-100 优秀（准确、深入、有实践见解）；70-89 良好（基本正确但深度或广度不足）；"
                + "50-69 一般（部分正确、有明显缺漏）；0-49 不达标（错误较多或答非所问）。\n");
        sb.append("同时评估沟通表达（communication）：表达是否清晰有条理、是否紧扣问题、是否易于理解，与内容正确性无关。\n");
        sb.append("同时提取本题实际考察的具体知识点（knowledgePoints）：针对题目与回答内容提取 1-4 个具体技术概念"
                + "（如\"HashMap扩容机制\"、\"TCP三次握手\"，而非\"Java\"这类粗粒度词），回答未涉及的不要列。"
                + "术语转写错字按术语表规范写法提取。\n");
        sb.append("只输出 JSON，不要输出任何其他内容：{\"score\": <0-100的整数>, \"communication\": <0-100的整数>, "
                + "\"knowledgePoints\": [\"<知识点1>\", ...], \"summary\": \"<60字以内的简要点评>\"}");
        return sb.toString();
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
