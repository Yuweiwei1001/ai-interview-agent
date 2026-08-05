package com.interview.agent.common.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * LLM 调用配置
 */
@Configuration
public class LlmConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ExecutorService llmExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "llm-call-");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 创建 LLM 调用任务
     */
    public static <T> Callable<T> createCallable(Callable<T> callable) {
        return callable;
    }
}
