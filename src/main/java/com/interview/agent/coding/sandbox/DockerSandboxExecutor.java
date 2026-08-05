package com.interview.agent.coding.sandbox;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
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
    private final SandboxConfig config;
    private final ExecutorService executor;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final int maxConcurrent;
    /**
     * 预热状态管理：记录各语言镜像的就绪状态
     */
    private final Map<String, Boolean> warmUpStatus = new ConcurrentHashMap<>();

    public DockerSandboxExecutor(SandboxService sandboxService, SandboxConfig config) {
        this.sandboxService = sandboxService;
        this.config = config;
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
     * 应用启动时预热 Docker 沙箱镜像（异步执行，不阻塞启动）
     */
    @PostConstruct
    public void warmUp() {
        warmUpStatus.put("java", false);
        warmUpStatus.put("python", false);
        // 异步预热 Java 镜像
        CompletableFuture.runAsync(() -> {
            try {
                ensureImage(config.getJavaImage());
                warmUpStatus.put("java", true);
                log.info("Java 沙箱镜像预热完成: {}", config.getJavaImage());
            } catch (Exception e) {
                log.error("Java 沙箱镜像预热失败", e);
            }
        });
        // 异步预热 Python 镜像
        CompletableFuture.runAsync(() -> {
            try {
                ensureImage(config.getPythonImage());
                warmUpStatus.put("python", true);
                log.info("Python 沙箱镜像预热完成: {}", config.getPythonImage());
            } catch (Exception e) {
                log.error("Python 沙箱镜像预热失败", e);
            }
        });
    }

    /**
     * 确保镜像已存在，不存在则拉取
     */
    private void ensureImage(String image) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", image);
        Process p = pb.start();
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
            return; // 镜像已存在
        }
        // 拉取镜像
        ProcessBuilder pullPb = new ProcessBuilder("docker", "pull", image);
        Process pull = pullPb.start();
        boolean done = pull.waitFor(300, TimeUnit.SECONDS);
        if (!done || pull.exitValue() != 0) {
            throw new Exception("镜像拉取失败: " + image);
        }
    }

    /**
     * 查询指定语言的镜像是否已预热就绪
     */
    public boolean isWarmUpReady(String language) {
        return Boolean.TRUE.equals(warmUpStatus.get(language));
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

    /**
     * 获取所有语言的预热状态快照
     */
    public Map<String, Boolean> getWarmUpStatus() {
        return new ConcurrentHashMap<>(warmUpStatus);
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }
}