package com.interview.agent.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 语音面试配置（interview.voice.*）。
 * ASR/TTS 均复用 DashScope 同一把 API Key（默认取 spring.ai.dashscope.api-key 同款环境变量）。
 */
@Component
@ConfigurationProperties(prefix = "interview.voice")
public class VoiceProperties {

    /** 语音功能总开关：关闭时 WS 端点拒绝连接、Speaker 直接透传 */
    private boolean enabled = true;

    private Asr asr = new Asr();
    private Tts tts = new Tts();

    public static class Asr {
        private String url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
        private String model = "qwen3-asr-flash-realtime";
        private String apiKey;
        private String language = "zh";
        private String format = "pcm";
        private int sampleRate = 16000;
        /** 服务端 VAD：静音多久判定一句话结束（毫秒） */
        private int turnDetectionSilenceDurationMs = 1000;
        /** ASR 就绪检查延迟（秒），超时未 ready 自动重连 */
        private long readyCheckDelaySeconds = 10;
        /** ASR 自动重连最大次数 */
        private int maxReadyRetry = 2;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
        public int getTurnDetectionSilenceDurationMs() { return turnDetectionSilenceDurationMs; }
        public void setTurnDetectionSilenceDurationMs(int v) { this.turnDetectionSilenceDurationMs = v; }
        public long getReadyCheckDelaySeconds() { return readyCheckDelaySeconds; }
        public void setReadyCheckDelaySeconds(long v) { this.readyCheckDelaySeconds = v; }
        public int getMaxReadyRetry() { return maxReadyRetry; }
        public void setMaxReadyRetry(int v) { this.maxReadyRetry = v; }
    }

    public static class Tts {
        private String model = "qwen3-tts-flash-realtime";
        private String apiKey;
        private String voice = "Cherry";
        private int sampleRate = 24000;
        private String mode = "commit";
        private String languageType = "Chinese";
        private float speechRate = 1.0f;
        /** 单次合成超时（秒），超时降级为纯文本（题目已由 SSE 推送，语音缺失不阻断面试） */
        private int timeoutSeconds = 15;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getLanguageType() { return languageType; }
        public void setLanguageType(String languageType) { this.languageType = languageType; }
        public float getSpeechRate() { return speechRate; }
        public void setSpeechRate(float speechRate) { this.speechRate = speechRate; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Asr getAsr() { return asr; }
    public void setAsr(Asr asr) { this.asr = asr; }
    public Tts getTts() { return tts; }
    public void setTts(Tts tts) { this.tts = tts; }
}
