package com.interview.agent.coding.testcase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.coding.testcase.TestCaseService.TestCase;
import com.interview.agent.coding.testcase.TestCaseService.TestRunResult;
import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 动态测试用例生成器
 * 基于题目、代码与预设用例执行情况，由 LLM 生成额外的边界测试用例；
 * LLM 不可用或 JSON 解析失败时返回空列表（不注入假用例，避免拉低真实分数）
 */
@Component
public class TestCaseGenerator {
    private static final Logger log = LoggerFactory.getLogger(TestCaseGenerator.class);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public TestCaseGenerator(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 生成动态测试用例
     */
    public List<TestCase> generateDynamicCases(String questionTitle, String code, String language,
                                                List<TestCase> presetCases, TestRunResult presetResult) {
        try {
            String prompt = buildPrompt(questionTitle, code, language, presetCases, presetResult);

            String response = LlmCallWrapper.callWithRetry("testcase", () ->
                    chatClient.prompt().user(prompt).call().content(),
                    () -> null
            );

            return parseCases(response);
        } catch (Exception e) {
            log.warn("动态用例生成失败，返回空列表", e);
            return List.of();
        }
    }

    private String buildPrompt(String questionTitle, String code, String language,
                                List<TestCase> presetCases, TestRunResult presetResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位算法测试工程师，请根据以下信息生成额外的测试用例，覆盖未测试的边界情况。\n\n");
        sb.append("题目：").append(questionTitle).append("\n\n");
        sb.append("代码语言：").append(language).append("\n\n");
        sb.append("预设用例通过情况：\n");
        for (TestCaseService.TestCaseResult r : presetResult.getResults()) {
            sb.append("- ").append(r.getName()).append(": ").append(r.isPassed() ? "通过" : "失败").append("\n");
        }
        sb.append("请根据题目和代码生成3-5个额外的边界测试用例，输出JSON数组，格式：[{\"name\":\"用例名\",\"input\":\"输入内容\",\"expectedOutput\":\"期望输出\"}]。只输出JSON，不要其他文字。\n");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 用例数组；解析失败返回空列表（不注入假用例）
     */
    private List<TestCase> parseCases(String response) {
        if (response == null || response.isBlank()) return List.of();
        try {
            // 去除可能的 ```json 包裹
            String json = response.replaceAll("```json|```", "").trim();
            List<TestCase> cases = objectMapper.readValue(json, new TypeReference<List<TestCase>>() {});
            return cases != null ? cases : List.of();
        } catch (Exception e) {
            log.warn("动态用例 JSON 解析失败", e);
            return List.of();
        }
    }
}
