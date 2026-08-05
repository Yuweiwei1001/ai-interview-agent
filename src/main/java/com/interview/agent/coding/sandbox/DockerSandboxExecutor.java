package com.interview.agent.coding.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Docker 沙箱执行器（带预热池）
 * 管理执行线程池，控制并发执行数量
 */
@Component
public class DockerSandboxExecutor {
    private static final Logger log = LoggerFactory.getLogger(DockerSandboxExecutor.class);
    private final SandboxService sandboxService;
    private final ExecutorService executor;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final int maxConcurrent;

    public DockerSandboxExecutor(SandboxService sandboxService, SandboxConfig config) {
        this.sandboxService = sandboxService;
        this.maxConcurrent = config.getMaxPoolSize();
        this.executor = new ThreadPoolExecutor(
                1, maxConcurrent,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r, "sandbox-exec-");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 提交执行任务
     */
    public CompletableFuture<SandboxService.SandboxResult> executeAsync(String code, String language, String testInput) {
        if (activeCount.get() >= maxConcurrent) {
            return CompletableFuture.completedFuture(
                    new SandboxService.SandboxResult(false, "", "沙箱并发数已达上限（" + maxConcurrent + "）", -1, false)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            activeCount.incrementAndGet();
            try {
                return sandboxService.execute(code, language, testInput);
            } finally {
                activeCount.decrementAndGet();
            }
        }, executor);
    }

    /**
     * 同步执行
     */
    public SandboxService.SandboxResult executeSync(String code, String language, String testInput) {
        activeCount.incrementAndGet();
        try {
            return sandboxService.execute(code, language, testInput);
        } finally {
            activeCount.decrementAndGet();
        }
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }
}