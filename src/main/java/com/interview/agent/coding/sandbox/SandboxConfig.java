package com.interview.agent.coding.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sandbox")
public class SandboxConfig {
    private String dockerHost = "unix:///var/run/docker.sock";
    private int cpuCount = 1;
    private String memoryLimit = "512m";
    private int memorySwap = 512;
    private int pidsLimit = 64;
    private int timeoutSeconds = 30;
    private String javaImage = "eclipse-temurin:21-jdk-alpine";
    private String pythonImage = "python:3.12-slim";
    private int maxPoolSize = 3;

    // getters/setters
    public String getDockerHost() { return dockerHost; }
    public void setDockerHost(String dockerHost) { this.dockerHost = dockerHost; }
    public int getCpuCount() { return cpuCount; }
    public void setCpuCount(int cpuCount) { this.cpuCount = cpuCount; }
    public String getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; }
    public int getMemorySwap() { return memorySwap; }
    public void setMemorySwap(int memorySwap) { this.memorySwap = memorySwap; }
    public int getPidsLimit() { return pidsLimit; }
    public void setPidsLimit(int pidsLimit) { this.pidsLimit = pidsLimit; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getJavaImage() { return javaImage; }
    public void setJavaImage(String javaImage) { this.javaImage = javaImage; }
    public String getPythonImage() { return pythonImage; }
    public void setPythonImage(String pythonImage) { this.pythonImage = pythonImage; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
}