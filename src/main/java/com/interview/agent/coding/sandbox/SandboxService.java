package com.interview.agent.coding.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
                        "-v", tempDir.toString() + ":/workspace:ro",
                        config.getJavaImage(),
                        "javac", "/workspace/Solution.java"
                );
                Process compileProcess = compilePb.start();
                boolean compiled = compileProcess.waitFor(30, TimeUnit.SECONDS);
                if (!compiled || compileProcess.exitValue() != 0) {
                    String error = readStream(compileProcess.getErrorStream());
                    compileProcess.destroyForcibly();
                    return new SandboxResult(false, "", "编译错误:\n" + error, 0, false);
                }

                // 运行
                command = Arrays.asList(
                        "docker", "run", "--rm",
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

                command = Arrays.asList(
                        "docker", "run", "--rm",
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

            String stdout = readStream(runProcess.getInputStream());
            String stderr = readStream(runProcess.getErrorStream());
            int exitCode = runProcess.exitValue();

            return new SandboxResult(exitCode == 0, stdout, stderr, exitCode, false);

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
     * 代码安全检查
     */
    private String checkSecurity(String code, String language) {
        if (code == null || code.isBlank()) {
            return "代码不能为空";
        }

        // 禁止的危险 API
        String[] blacklist = {
            "Runtime.getRuntime().exec", "Runtime\\s*\\.getRuntime\\s*\\.\\s*exec",
            "ProcessBuilder", "java.lang.reflect", "java.lang.Class.forName",
            "java.net.Socket", "java.net.ServerSocket", "java.io.FileOutputStream",
            "java.io.FileInputStream", "new Socket", "java.nio.file.Files.write",
            "Runtime.getRuntime().halt", "System.exit"
        };

        String codeLower = code.toLowerCase();
        for (String pattern : blacklist) {
            if (codeLower.contains(pattern.toLowerCase().replaceAll("\\\\s\\*", ""))) {
                return "代码包含禁止的 API: " + pattern;
            }
        }

        return null;
    }

    private String readStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
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
}