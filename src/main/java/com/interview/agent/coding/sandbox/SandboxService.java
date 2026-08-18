package com.interview.agent.coding.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 沙箱执行服务
 * 使用本地进程隔离执行代码（Docker 沙箱的简化版，后续可接入 docker-java）
 */
@Service
public class SandboxService {
    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    private final SandboxConfig config;

    public SandboxService(SandboxConfig config) {
        this.config = config;
    }

    /**
     * 执行代码
     * @param code 源代码
     * @param language 语言（java/python）
     * @param testInput 测试输入
     * @return 执行结果
     */
    public SandboxResult execute(String code, String language, String testInput) {
        // 安全检查
        String securityError = checkSecurity(code, language);
        if (securityError != null) {
            return new SandboxResult(false, "", securityError, 0, true);
        }

        Path tempDir = null;
        try {
            // 创建临时目录
            tempDir = Files.createTempDirectory("sandbox-");
            String fileName;
            List<String> command;

            if ("java".equalsIgnoreCase(language)) {
                fileName = "Solution.java";
                Path sourceFile = tempDir.resolve(fileName);
                Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

                // 编译
                ProcessBuilder compilePb = new ProcessBuilder(
                        "docker", "run", "--rm",
                        "--network", "none",
                        "--memory", config.getMemoryLimit(),
                        "--cpus", String.valueOf(config.getCpuCount()),
                        "--pids-limit", String.valueOf(config.getPidsLimit()),
                        // 编译阶段需写入 .class 文件，挂载为读写；运行阶段再以只读挂载
                        "-v", tempDir.toString() + ":/workspace",
                        config.getJavaImage(),
                        "javac", "/workspace/Solution.java"
                );
                Process compileProcess = compilePb.start();
                // 后台并发排水：防止输出写满管道缓冲区导致进程阻塞、waitFor 误判超时
                OutputGobbler compileOut = new OutputGobbler(compileProcess.getInputStream(), "sandbox-cout");
                OutputGobbler compileErr = new OutputGobbler(compileProcess.getErrorStream(), "sandbox-cerr");
                compileOut.start();
                compileErr.start();
                boolean compiled = compileProcess.waitFor(30, TimeUnit.SECONDS);
                if (!compiled || compileProcess.exitValue() != 0) {
                    compileProcess.destroyForcibly();
                    compileErr.await(2000);
                    return new SandboxResult(false, "", "编译错误:\n" + compileErr.content(), 0, false);
                }

                // 运行（-i 保持 stdin 打开以注入测试输入）
                command = Arrays.asList(
                        "docker", "run", "--rm",
                        "-i",
                        "--network", "none",
                        "--memory", config.getMemoryLimit(),
                        "--cpus", String.valueOf(config.getCpuCount()),
                        "--pids-limit", String.valueOf(config.getPidsLimit()),
                        "--read-only",
                        "-v", tempDir.toString() + ":/workspace:ro",
                        config.getJavaImage(),
                        "java", "-cp", "/workspace", "Solution"
                );
            } else if ("python".equalsIgnoreCase(language)) {
                fileName = "solution.py";
                Path sourceFile = tempDir.resolve(fileName);
                Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

                // 运行（-i 保持 stdin 打开以注入测试输入）
                command = Arrays.asList(
                        "docker", "run", "--rm",
                        "-i",
                        "--network", "none",
                        "--memory", config.getMemoryLimit(),
                        "--cpus", String.valueOf(config.getCpuCount()),
                        "--pids-limit", String.valueOf(config.getPidsLimit()),
                        "--read-only",
                        "-v", tempDir.toString() + ":/workspace:ro",
                        config.getPythonImage(),
                        "python", "/workspace/solution.py"
                );
            } else {
                return new SandboxResult(false, "", "不支持的语言: " + language, 0, false);
            }

            // 执行
            ProcessBuilder runPb = new ProcessBuilder(command);
            Process runProcess = runPb.start();

            // 后台并发读取 stdout/stderr：waitFor 期间持续排水，防止输出大于管道缓冲区（约 64KB）时
            // 进程阻塞在写输出上、被误判为「执行超时」且输出全部丢失
            OutputGobbler stdout = new OutputGobbler(runProcess.getInputStream(), "sandbox-out");
            OutputGobbler stderr = new OutputGobbler(runProcess.getErrorStream(), "sandbox-err");
            stdout.start();
            stderr.start();

            // 写入输入
            if (testInput != null && !testInput.isBlank()) {
                try (OutputStream os = runProcess.getOutputStream()) {
                    os.write(testInput.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            // 等待完成
            boolean finished = runProcess.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                runProcess.destroyForcibly();
                return new SandboxResult(false, "", "执行超时（" + config.getTimeoutSeconds() + "秒）", 0, false);
            }

            // 进程已退出，gobbler 很快读到 EOF；短暂 join 确保尾部数据读尽
            stdout.await(2000);
            stderr.await(2000);
            int exitCode = runProcess.exitValue();

            return new SandboxResult(exitCode == 0, stdout.content(), stderr.content(), exitCode, false);

        } catch (Exception e) {
            log.error("沙箱执行失败", e);
            return new SandboxResult(false, "", "执行异常: " + e.getMessage(), -1, false);
        } finally {
            // 清理临时目录
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                } catch (IOException e) {
                    log.warn("清理临时目录失败", e);
                }
            }
        }
    }

    /**
     * 危险 API 黑名单：真正的正则匹配（空白容忍），修复旧实现 contains+删 "\s*" 导致
     * 「Runtime . getRuntime ( ) . exec」这类插空写法完全绕过检测的问题。
     * 注：Docker 隔离（断网/资源限制）是主防线，此处为纵深防御。
     */
    private static final List<DangerCheck> DANGEROUS_CHECKS = List.of(
            new DangerCheck(Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec"), "Runtime.getRuntime().exec"),
            new DangerCheck(Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*halt"), "Runtime.getRuntime().halt"),
            new DangerCheck(Pattern.compile("ProcessBuilder"), "ProcessBuilder"),
            new DangerCheck(Pattern.compile("System\\s*\\.\\s*exit"), "System.exit"),
            new DangerCheck(Pattern.compile("java\\s*\\.\\s*lang\\s*\\.\\s*reflect"), "java.lang.reflect"),
            new DangerCheck(Pattern.compile("Class\\s*\\.\\s*forName"), "Class.forName"),
            new DangerCheck(Pattern.compile("java\\s*\\.\\s*net\\s*\\.\\s*(Server)?Socket"), "java.net.Socket/ServerSocket"),
            new DangerCheck(Pattern.compile("new\\s+Socket"), "new Socket"),
            new DangerCheck(Pattern.compile("java\\s*\\.\\s*io\\s*\\.\\s*File(Output|Input)Stream"), "FileOutputStream/FileInputStream"),
            new DangerCheck(Pattern.compile("Files\\s*\\.\\s*write"), "java.nio.file.Files.write")
    );

    private record DangerCheck(Pattern pattern, String label) {}

    private String checkSecurity(String code, String language) {
        if (code == null || code.isBlank()) {
            return "代码不能为空";
        }

        for (DangerCheck check : DANGEROUS_CHECKS) {
            if (check.pattern().matcher(code).find()) {
                return "代码包含禁止的 API: " + check.label();
            }
        }

        return null;
    }

    public static class SandboxResult {
        private final boolean success;
        private final String output;
        private final String error;
        private final int exitCode;
        private final boolean securityViolation;

        public SandboxResult(boolean success, String output, String error, int exitCode, boolean securityViolation) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.exitCode = exitCode;
            this.securityViolation = securityViolation;
        }

        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getError() { return error; }
        public int getExitCode() { return exitCode; }
        public boolean isSecurityViolation() { return securityViolation; }
    }

    /** 单条输出软上限：防止恶意刷屏撑爆内存，超出部分只排水不保留 */
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    /**
     * 后台输出排水线程：进程运行期间持续读取 stdout/stderr，
     * 防止输出写满管道缓冲区导致进程阻塞、waitFor 误判超时。
     */
    private static final class OutputGobbler extends Thread {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();
        private boolean truncated = false;

        OutputGobbler(InputStream in, String name) {
            super(name);
            setDaemon(true);
            this.in = in;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() >= MAX_OUTPUT_CHARS) {
                        truncated = true;
                        continue;
                    }
                    sb.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程被强杀时流关闭属预期，排水即可
            }
        }

        /** 进程退出后短暂等待读尽尾部数据 */
        void await(long millis) {
            try {
                join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String content() {
            if (truncated) {
                sb.append("\n...[输出过长，已截断]");
                truncated = false;
            }
            return sb.toString().trim();
        }
    }
}