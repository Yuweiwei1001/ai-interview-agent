package com.interview.agent.common.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * ReAct（推理-行动-观察）循环 Agent 执行器。
 * <p>
 * 封装 Spring AI 的 ChatClient + .tools() 调用，实现多轮工具调用循环：
 * 思考 → 行动（调工具）→ 观察（看结果）→ 再思考 → 最终答案
 * <p>
 * Spring AI 内部自动处理多轮工具调用，本类提供：
 * <ul>
 *   <li>ReAct 循环的日志记录与耗时统计</li>
 *   <li>最大迭代次数限制（通过 spring.ai.chat.client.tool-call-attempts 配置）</li>
 *   <li>工具调用失败时的降级兜底</li>
 * </ul>
 */
@Component
public class ReActAgent {
    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private final ChatClient chatClient;

    public ReActAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 执行 ReAct 循环，将系统提示与用户消息分别注入。
     *
     * @param systemPrompt 系统角色设定与 ReAct 行为指引
     * @param userPrompt   用户消息（具体的出题/评估/追问请求）
     * @param toolInstances 工具实例（@Tool 注解的 bean）
     * @return LLM 最终输出
     */
    public String execute(String systemPrompt, String userPrompt, Object... toolInstances) {
        long start = System.currentTimeMillis();
        log.info("ReAct 循环开始: systemPrompt={}.., userPrompt={}.., tools={}",
                truncate(systemPrompt, 60), truncate(userPrompt, 60), toolInstances.length);

        try {
            String result = chatClient.prompt()
                    .system(systemPrompt)
                    .tools(toolInstances)
                    .user(userPrompt)
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - start;
            log.info("ReAct 循环结束: 耗时={}ms, 结果长度={}, 结果={}..",
                    elapsed, result != null ? result.length() : 0,
                    truncate(result, 80));
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("ReAct 循环异常终止: 耗时={}ms, error={}", elapsed, e.getMessage());
            throw e;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen).replace('\n', ' ');
    }
}