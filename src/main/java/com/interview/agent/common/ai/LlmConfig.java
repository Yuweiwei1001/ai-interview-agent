package com.interview.agent.common.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 调用配置
 */
@Configuration
public class LlmConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ExecutorService llmExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            // 线程名带编号便于日志排查（旧实现所有线程同名 "llm-call-"）
            Thread t = new Thread(r, "llm-call-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // 注入静态工具类复用：消除每次 LLM 调用创建/销毁临时线程的开销；
        // cached 池不排队，超时语义与旧实现（每次调用独享线程）保持一致
        LlmCallWrapper.initSharedExecutor(executor);
        log.info("LLM 共享线程池已初始化并注入 LlmCallWrapper");
        return executor;
    }

    /**
     * 创建 LLM 调用任务
     */
    public static <T> Callable<T> createCallable(Callable<T> callable) {
        return callable;
    }
}
