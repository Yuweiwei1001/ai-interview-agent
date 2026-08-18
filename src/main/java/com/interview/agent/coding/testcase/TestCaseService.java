package com.interview.agent.coding.testcase;

import com.interview.agent.coding.sandbox.DockerSandboxExecutor;
import com.interview.agent.coding.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用例引擎
 * 负责执行预设测试用例，并联动 AI 生成动态测试用例
 */
@Service
public class TestCaseService {
    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);
    private final DockerSandboxExecutor executor;
    private final TestCaseGenerator testCaseGenerator;

    public TestCaseService(DockerSandboxExecutor executor, TestCaseGenerator testCaseGenerator) {
        this.executor = executor;
        this.testCaseGenerator = testCaseGenerator;
    }

    /**
     * 运行测试用例
     */
    public TestRunResult runTests(String code, String language, List<TestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return new TestRunResult(false, 0, List.of(), "无测试用例");
        }

        List<TestCaseResult> results = new ArrayList<>();
        int passed = 0;

        for (TestCase tc : testCases) {
            SandboxService.SandboxResult result = executor.executeSync(code, language, tc.getInput());

            boolean testPassed = false;
            String detail = "";

            if (result.isSuccess()) {
                String actualOutput = result.getOutput().trim();
                String expectedOutput = tc.getExpectedOutput().trim();
                testPassed = actualOutput.equals(expectedOutput);
                detail = testPassed ? "通过" : "期望: " + expectedOutput + ", 实际: " + actualOutput;
            } else {
                detail = result.getError();
            }

            if (testPassed) passed++;
            results.add(new TestCaseResult(tc.getName(), testPassed, detail));
        }

        double passRate = (double) passed / testCases.size() * 100;
        return new TestRunResult(passed == testCases.size(), passRate, results, null);
    }

    /**
     * 运行测试并生成动态用例
     */
    public TestRunResult runWithDynamicTests(String code, String language, String questionTitle,
                                              List<TestCase> presetCases) {
        // 先运行预设用例
        TestRunResult presetResult = runTests(code, language, presetCases);

        // 根据预设用例结果生成动态用例
        try {
            List<TestCase> dynamicCases = testCaseGenerator.generateDynamicCases(
                    questionTitle, code, language, presetCases, presetResult);
            if (dynamicCases != null && !dynamicCases.isEmpty()) {
                TestRunResult dynamicResult = runTests(code, language, dynamicCases);

                // 合并结果
                List<TestCaseResult> allResults = new ArrayList<>();
                allResults.addAll(presetResult.getResults());
                allResults.addAll(dynamicResult.getResults());

                int totalPassed = (int) allResults.stream().filter(TestCaseResult::isPassed).count();
                double totalPassRate = (double) totalPassed / allResults.size() * 100;

                // 标记动态用例来源
                dynamicResult.getResults().forEach(r -> r.setSource("dynamic"));

                return new TestRunResult(
                        totalPassed == allResults.size(),
                        totalPassRate,
                        allResults,
                        null
                );
            }
        } catch (Exception e) {
            log.warn("动态用例生成失败，仅使用预设用例", e);
        }

        // 预设用例与动态用例均为空（如 LLM 服务不可用时）：用内置模板用例兜底，
        // 保证代码能真实跑沙箱测试，正确代码不会被误判为“未通过”
        if (presetCases == null || presetCases.isEmpty()) {
            List<TestCase> templates = matchBuiltinTemplates(questionTitle);
            if (!templates.isEmpty()) {
                TestRunResult tplResult = runTests(code, language, templates);
                tplResult.getResults().forEach(r -> r.setSource("builtin"));
                log.info("使用内置模板用例兜底: questionTitle={}, 用例数={}", questionTitle, templates.size());
                return tplResult;
            }
        }

        return presetResult;
    }

    /**
     * 常见算法题内置模板用例：仅用于 LLM 动态用例生成失败时的兜底，
     * 保证沙箱能真实运行代码、正确代码不被误判。
     * 输入格式约定：首行数组长度 n，次行 n 个数，末行 target；输出下标对。
     */
    private List<TestCase> matchBuiltinTemplates(String questionTitle) {
        String title = questionTitle == null ? "" : questionTitle.toLowerCase();
        // 覆盖常见题目变体：两数之和 / 两个数 / two sum / 含 nums 与 target 的题干
        if (title.contains("两数之和") || title.contains("两个数") || title.contains("两数")
                || title.contains("two sum") || title.contains("twosum") || title.contains("two_sum")
                || (title.contains("nums") && title.contains("target"))) {
            return List.of(
                    new TestCase("基本用例", "4\n2 7 11 15\n9", "0 1"),
                    new TestCase("负数和零", "3\n-3 0 1\n-3", "0 1"),
                    new TestCase("解在末尾", "4\n1 2 3 4\n7", "2 3"),
                    new TestCase("重复值", "4\n3 3 4 5\n6", "0 1")
            );
        }
        // 其他题型可继续补充模板
        return List.of();
    }

    public static class TestCase {
        private String name;
        private String input;
        private String expectedOutput;
        private int timeoutSeconds = 10;

        public TestCase() {}
        public TestCase(String name, String input, String expectedOutput) {
            this.name = name;
            this.input = input;
            this.expectedOutput = expectedOutput;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public String getExpectedOutput() { return expectedOutput; }
        public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class TestCaseResult {
        private String name;
        private boolean passed;
        private String detail;
        private String source = "preset";

        public TestCaseResult() {}
        public TestCaseResult(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public static class TestRunResult {
        private final boolean allPassed;
        private final double passRate;
        private final List<TestCaseResult> results;
        private final String error;
        /** 直接执行（无测试用例）时的程序 stdout，用于前端控制台展示 */
        private final String stdout;

        public TestRunResult(boolean allPassed, double passRate, List<TestCaseResult> results, String error) {
            this(allPassed, passRate, results, error, null);
        }

        public TestRunResult(boolean allPassed, double passRate, List<TestCaseResult> results, String error, String stdout) {
            this.allPassed = allPassed;
            this.passRate = passRate;
            this.results = results;
            this.error = error;
            this.stdout = stdout;
        }

        public boolean isAllPassed() { return allPassed; }
        public double getPassRate() { return passRate; }
        public List<TestCaseResult> getResults() { return results; }
        public String getError() { return error; }
        public String getStdout() { return stdout; }
    }
}
