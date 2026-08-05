package com.interview.agent.coding;

import com.interview.agent.coding.testcase.TestCaseService;
import com.interview.agent.common.ai.LlmCallWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代码评估引擎
 * 多维评估：正确性、代码质量、边界处理、时间复杂度、测试通过率
 * 测试通过率为沙箱客观指标，其余维度由 LLM 评估；LLM 不可用时规则降级
 */
@Component
public class CodeEvaluationEngine {
    private static final Logger log = LoggerFactory.getLogger(CodeEvaluationEngine.class);
    private final ChatClient chatClient;
    private final TestCaseService testCaseService;

    // 各维度权重
    private static final double WEIGHT_CORRECTNESS = 0.35;
    private static final double WEIGHT_CODE_QUALITY = 0.20;
    private static final double WEIGHT_EDGE_CASE = 0.15;
    private static final double WEIGHT_TIME_COMPLEXITY = 0.10;
    private static final double WEIGHT_TEST_PASS = 0.20;

    public CodeEvaluationEngine(ChatClient.Builder builder, TestCaseService testCaseService) {
        this.chatClient = builder.build();
        this.testCaseService = testCaseService;
    }

    /**
     * 评估代码
     */
    public CodeEvaluationResult evaluate(String code, String language, String questionTitle,
                                          TestCaseService.TestRunResult testResult) {
        // 1. 测试通过率（客观指标）
        int testPassRate = (int) Math.round(testResult.getPassRate());

        // 2. LLM 评估其他维度
        CodeEvaluationResult llmResult = evaluateWithLLM(code, language, questionTitle);

        // 3. 合并结果
        CodeEvaluationResult result = new CodeEvaluationResult();
        result.setTestPassRate(testPassRate);
        result.setCorrectness(llmResult != null ? llmResult.getCorrectness() : testPassRate);
        result.setCodeQuality(llmResult != null ? llmResult.getCodeQuality() : Math.max(0, testPassRate - 10));
        result.setEdgeCaseHandling(llmResult != null ? llmResult.getEdgeCaseHandling() : testPassRate);
        result.setTimeComplexity(llmResult != null ? llmResult.getTimeComplexity() : 70);
        result.setSuggestions(llmResult != null ? llmResult.getSuggestions() : List.of("注意代码质量"));
        result.setSummary(llmResult != null ? llmResult.getSummary() : "评估完成");

        // 4. 计算综合评分
        int overallScore = (int) Math.round(
                result.getCorrectness() * WEIGHT_CORRECTNESS +
                result.getCodeQuality() * WEIGHT_CODE_QUALITY +
                result.getEdgeCaseHandling() * WEIGHT_EDGE_CASE +
                result.getTimeComplexity() * WEIGHT_TIME_COMPLEXITY +
                result.getTestPassRate() * WEIGHT_TEST_PASS
        );
        result.setOverallScore(overallScore);

        return result;
    }

    /**
     * 规则降级评估（当 LLM 不可用时）
     */
    public CodeEvaluationResult ruleBasedEvaluate(String code, String language,
                                                   TestCaseService.TestRunResult testResult) {
        int testPassRate = (int) Math.round(testResult.getPassRate());

        CodeEvaluationResult result = new CodeEvaluationResult();
        result.setTestPassRate(testPassRate);
        result.setCorrectness(testPassRate);
        result.setCodeQuality(Math.max(0, testPassRate - 15));
        result.setEdgeCaseHandling(testPassRate);
        result.setTimeComplexity(70);
        result.setOverallScore((int) Math.round(
                testPassRate * WEIGHT_CORRECTNESS +
                Math.max(0, testPassRate - 15) * WEIGHT_CODE_QUALITY +
                testPassRate * WEIGHT_EDGE_CASE +
                70 * WEIGHT_TIME_COMPLEXITY +
                testPassRate * WEIGHT_TEST_PASS
        ));
        result.setSuggestions(List.of("建议检查代码风格和边界情况"));
        result.setSummary("规则评估完成");
        return result;
    }

    private CodeEvaluationResult evaluateWithLLM(String code, String language, String questionTitle) {
        try {
            String prompt = buildPrompt(code, language, questionTitle);
            return LlmCallWrapper.callEntity(
                () -> chatClient.prompt().user(prompt).call().entity(CodeEvaluationResult.class),
                () -> null
            );
        } catch (Exception e) {
            log.warn("LLM 代码评估失败", e);
            return null;
        }
    }

    private String buildPrompt(String code, String language, String questionTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位代码评审专家，请对以下代码进行多维评估。\n\n");
        sb.append("编程语言：").append(language).append("\n");
        sb.append("题目：").append(questionTitle != null ? questionTitle : "算法题").append("\n\n");
        sb.append("代码：\n```\n").append(code).append("\n```\n\n");
        sb.append("请输出JSON格式的评估结果，包含以下字段：\n");
        sb.append("- correctness: 正确性评分（0-100）\n");
        sb.append("- codeQuality: 代码质量评分（0-100），包括命名、结构、注释等\n");
        sb.append("- edgeCaseHandling: 边界处理评分（0-100）\n");
        sb.append("- timeComplexity: 时间复杂度评分（0-100）\n");
        sb.append("- suggestions: 改进建议列表\n");
        sb.append("- summary: 总体评价摘要\n");
        return sb.toString();
    }
}
