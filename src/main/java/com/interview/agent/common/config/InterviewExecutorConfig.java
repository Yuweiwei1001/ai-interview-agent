package com.interview.agent.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 面试执行专用线程池
 * 避免长阻塞任务（LLM 调用、面试图执行）占用 ForkJoinPool.commonPool
 */
@Configuration
public class InterviewExecutorConfig {
    @Bean(name = "interviewExecutor")
    public Executor interviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("interview-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
