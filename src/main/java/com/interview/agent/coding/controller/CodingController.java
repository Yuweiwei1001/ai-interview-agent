package com.interview.agent.coding.controller;

import com.interview.agent.coding.CodeEvaluationEngine;
import com.interview.agent.coding.CodeEvaluationResult;
import com.interview.agent.coding.sandbox.SandboxService;
import com.interview.agent.coding.testcase.TestCaseService;
import com.interview.agent.coding.testcase.TestCaseService.TestCase;
import com.interview.agent.coding.testcase.TestCaseService.TestRunResult;
import com.interview.agent.common.result.Result;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 编码练习控制器
 * POST /api/coding/run     - 运行代码（带预设用例则执行测试，否则直接执行）
 * POST /api/coding/submit  - 提交代码（预设用例 + AI 动态用例 + 多维代码评估）
 */
@RestController
@RequestMapping("/api/coding")
public class CodingController {
    private final SandboxService sandboxService;
    private final TestCaseService testCaseService;
    private final CodeEvaluationEngine codeEvaluationEngine;

    public CodingController(SandboxService sandboxService, TestCaseService testCaseService,
                            CodeEvaluationEngine codeEvaluationEngine) {
        this.sandboxService = sandboxService;
        this.testCaseService = testCaseService;
        this.codeEvaluationEngine = codeEvaluationEngine;
    }

    @PostMapping("/run")
    public Result<TestRunResult> runCode(@RequestBody CodingRunRequest request) {
        // 如果有预设测试用例，运行测试
        if (request.getTestCases() != null && !request.getTestCases().isEmpty()) {
            TestRunResult result = testCaseService.runTests(request.getCode(), request.getLanguage(), request.getTestCases());
            return Result.success(result);
        }

        // 否则直接执行
        SandboxService.SandboxResult result = sandboxService.execute(request.getCode(), request.getLanguage(), request.getInput());
        return Result.success(new TestRunResult(
                result.isSuccess(),
                result.isSuccess() ? 100 : 0,
                List.of(),
                result.isSuccess() ? null : result.getError()
        ));
    }

    @PostMapping("/submit")
    public Result<CodeEvaluationResult> submitCode(@RequestBody CodingSubmitRequest request) {
        // 1. 运行测试用例（预设用例 + AI 动态用例）
        TestRunResult result = testCaseService.runWithDynamicTests(
                request.getCode(), request.getLanguage(), request.getQuestionTitle(), request.getTestCases());

        // 2. 多维代码评估（正确性/质量/边界/复杂度/测试通过率）
        CodeEvaluationResult evalResult = codeEvaluationEngine.evaluate(
                request.getCode(), request.getLanguage(), request.getQuestionTitle(), result);
        return Result.success(evalResult);
    }

    public static class CodingRunRequest {
        @NotBlank
        private String code;
        @NotBlank
        private String language;
        private String input;
        private List<TestCase> testCases;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public List<TestCase> getTestCases() { return testCases; }
        public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }
    }

    public static class CodingSubmitRequest {
        @NotBlank
        private String code;
        @NotBlank
        private String language;
        private String questionTitle;
        private List<TestCase> testCases;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getQuestionTitle() { return questionTitle; }
        public void setQuestionTitle(String questionTitle) { this.questionTitle = questionTitle; }
        public List<TestCase> getTestCases() { return testCases; }
        public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }
    }
}
