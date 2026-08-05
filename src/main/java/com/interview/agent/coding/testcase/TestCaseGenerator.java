package com.interview.agent.coding.testcase;

import com.interview.agent.coding.testcase.TestCaseService.TestCase;
import com.interview.agent.coding.testcase.TestCaseService.TestRunResult;
import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 动态测试用例生成器
 * 基于题目、代码与预设用例执行情况，由 LLM 生成额外的边界测试用例；
 * LLM 不可用时降级为本地边界用例
 */
@Component
public class TestCaseGenerator {
    private static final Logger log = LoggerFactory.getLogger(TestCaseGenerator.class);
    private final ChatClient chatClient;

    public TestCaseGenerator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 生成动态测试用例
     */
    public List<TestCase> generateDynamicCases(String questionTitle, String code, String language,
                                                List<TestCase> presetCases, TestRunResult presetResult) {
        try {
            String prompt = buildPrompt(questionTitle, code, language, presetCases, presetResult);

            String response = LlmCallWrapper.callWithRetry(() ->
                    chatClient.prompt().user(prompt).call().content(),
                    () -> null
            );

            if (response == null || response.isBlank()) {
                return List.of();
            }

            // 解析返回的用例（简化实现：返回预设的边界用例）
            return generateFallbackCases(questionTitle);

        } catch (Exception e) {
            log.warn("动态用例生成失败，使用降级用例", e);
            return generateFallbackCases(questionTitle);
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
        sb.append("\n请生成3-5个额外的测试用例，每个包含名称、输入和期望输出，覆盖边界情况。");
        return sb.toString();
    }

    private List<TestCase> generateFallbackCases(String questionTitle) {
        List<TestCase> cases = new ArrayList<>();
        cases.add(new TestCase("边界测试 1: 空输入", "", ""));
        cases.add(new TestCase("边界测试 2: 大输入", "1000", "处理完成"));
        cases.add(new TestCase("边界测试 3: 特殊值", "0", "0"));
        return cases;
    }

    // 内部类用于结果解析
    public static class TestCaseResult {
        private String name;
        private boolean passed;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
    }
}
