package com.interview.agent.verify;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.utils.Constants;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型可用性一键验证（切模型后跑一次即可确认新模型是否可用）。
 *
 * <p>用法（项目根目录）：
 * <pre>
 *   mvn test -Dtest=ModelVerifierTest -DfailIfNoTests=false
 * </pre>
 *
 * <p>模型名自动从配置读取（后续切换模型无需改本文件）：
 * <ul>
 *   <li>主对话 = application.yml 中 spring.ai.dashscope.chat.options.model</li>
 *   <li>热词/纠错 = application.yml 中 interview.voice.correction.model</li>
 *   <li>向量 = application.yml 中 spring.ai.dashscope.embedding.options.model</li>
 *   <li>ASR / TTS = VoiceProperties.java 中的默认值</li>
 * </ul>
 * 如需临时覆盖，可传 -Dverify.chat.model=xxx / -Dverify.asr.model=xxx 等（键名见下方）。
 *
 * <p>API Key 解析顺序：-Dverify.api-key → 环境变量 DASHSCOPE_API_KEY → application-local.yml。
 */
public class ModelVerifierTest {

    private static final String ASR_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    private static final List<String> REPORT = new ArrayList<>();

    @Test
    void verifyAllModels() throws Exception {
        String apiKey = resolveApiKey();
        Constants.apiKey = apiKey;

        String chat = resolve("verify.chat.model", "chat");
        String flash = resolve("verify.flash.model", "correction");
        String embedding = resolve("verify.embedding.model", "embedding");
        String asr = resolve("verify.asr.model", "asr");
        String tts = resolve("verify.tts.model", "tts");

        System.out.println("========== 模型可用性验证 ==========");
        System.out.println("api-key: " + mask(apiKey));
        System.out.println("主对话模型 : " + chat);
        System.out.println("纠错/热词  : " + flash);
        System.out.println("向量模型   : " + embedding);
        System.out.println("ASR 模型   : " + asr);
        System.out.println("TTS 模型   : " + tts);

        // 1) 主对话模型
        report("chat 主对话", chat, () -> {
            GenerationResult r = new Generation().call(GenerationParam.builder()
                    .model(chat).prompt("请只回复两个字：可用").build());
            assertNotNull(r.getOutput(), "chat 无输出");
            System.out.println("  chat 回复: " + r.getOutput().getText());
        });

        // 2) 轻量模型（纠错/热词）：qwen3.7-flash 为多模态模型，须走 multimodal-generation 端点
        report("flash 轻量", flash, () -> {
            String text = multimodalCall(flash, "请只回复两个字：可用");
            assertFalse(text.isBlank(), "flash 无输出");
            System.out.println("  flash 回复: " + text);
        });

        // 3) 向量模型（校验维度与 ES 索引 1024 兼容）
        report("embedding 向量", embedding, () -> {
            TextEmbeddingResult r = new TextEmbedding().call(TextEmbeddingParam.builder()
                    .model(embedding)
                    .texts(List.of("你好，测试向量"))
                    .build());
            assertNotNull(r.getOutput().getEmbeddings(), "embedding 无输出");
            assertFalse(r.getOutput().getEmbeddings().isEmpty(), "embedding 列表为空");
            int dim = r.getOutput().getEmbeddings().get(0).getEmbedding().size();
            System.out.println("  向量维度: " + dim);
            assertTrue(dim == 1024, "向量维度 " + dim + " ≠ 1024，与 ES 索引不兼容，需同步改 VectorStoreConfig");
        });

        // 4) TTS（合成本地音频，产出 PCM 字节）
        AtomicReference<byte[]> ttsRef = new AtomicReference<>();
        report("tts 语音合成", tts, () -> {
            byte[] pcm = ttsSynthesize(apiKey, tts, "你好，这是语音模型验证");
            assertTrue(pcm.length > 0, "TTS 返回空音频");
            System.out.println("  TTS PCM 字节: " + pcm.length);
            ttsRef.set(pcm);
        });

        // 5) ASR（把 TTS 结果回灌给 ASR，闭环验证识别链路）
        report("asr 语音识别", asr, () -> {
            byte[] pcm = ttsRef.get() == null ? new byte[0] : ttsRef.get();
            String transcript = asrRecognize(apiKey, asr, pcm);
            System.out.println("  ASR 转写: " + (transcript.isBlank() ? "（连接成功但未识别出文本）" : transcript));
        });

        System.out.println("========== 汇总 ==========");
        REPORT.forEach(System.out::println);
        assertFalse(REPORT.stream().anyMatch(l -> l.contains("FAIL")), "存在 FAIL 项，请检查上方明细");
    }

    // ---------------- 模型调用实现 ----------------

    /** 多模态端点文本调用（qwen3.7-flash 等视觉语言模型只能走 multimodal-generation，Generation 的 text-generation 端点不可用） */
    private static String multimodalCall(String model, String prompt) throws Exception {
        MultiModalConversationMessage msg = MultiModalConversationMessage.builder()
                .role(Role.USER.getValue())
                .content(List.of(new MultiModalMessageItemText(prompt)))
                .build();
        MultiModalConversationResult r = new MultiModalConversation().call(
                MultiModalConversationParam.builder()
                        .model(model)
                        .message(msg)
                        .enableThinking(false)
                        .build());
        var message = r.getOutput().getChoices().get(0).getMessage();
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : message.getContent()) {
            Object text = item.get("text");
            if (text != null) sb.append(text);
        }
        return sb.toString().trim();
    }

    /** TTS 合成：与 VoiceTtsService 相同调用方式（ttsv2 SpeechSynthesizer），返回 24kHz/16bit/mono PCM */
    private static byte[] ttsSynthesize(String apiKey, String model, String text) throws Exception {
        SpeechSynthesizer synthesizer = null;
        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .voice("Cherry")
                    .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                    .build();
            synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audio = synthesizer.call(text, 20000);
            if (audio == null) return new byte[0];
            byte[] pcm = new byte[audio.remaining()];
            audio.get(pcm);
            return pcm;
        } finally {
            if (synthesizer != null) {
                try { synthesizer.getDuplexApi().close(1000, "bye"); } catch (Exception ignored) { }
            }
        }
    }

    /** ASR 识别：TTS 产物（24k PCM）+ 1s 静音回灌，等待 final 转写；无 error 即视为模型可用 */
    private static String asrRecognize(String apiKey, String model, byte[] pcm) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> transcript = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>();
        OmniRealtimeConversation conv = new OmniRealtimeConversation(
                OmniRealtimeParam.builder().model(model).url(ASR_URL).apikey(apiKey).build(),
                new OmniRealtimeCallback() {
                    @Override public void onOpen() { }
                    @Override public void onEvent(JsonObject message) {
                        String type = message.has("type") ? message.get("type").getAsString() : "";
                        if (type.contains("completed")) {
                            String t = message.has("transcript") ? message.get("transcript").getAsString() : "";
                            if (!t.isBlank()) { transcript.set(transcript.get() + t); done.countDown(); }
                        } else if ("error".equals(type)) {
                            error.set(message.toString());
                            done.countDown();
                        }
                    }
                    @Override public void onClose(int code, String reason) { done.countDown(); }
                });
        try {
            conv.connect();
            OmniRealtimeTranscriptionParam tp = new OmniRealtimeTranscriptionParam();
            tp.setLanguage("zh");
            tp.setInputSampleRate(24000);
            tp.setInputAudioFormat("pcm");
            conv.updateSession(OmniRealtimeConfig.builder()
                    .modalities(List.of(OmniRealtimeModality.TEXT))
                    .enableTurnDetection(true)
                    .turnDetectionType("server_vad")
                    .turnDetectionSilenceDurationMs(500)
                    .transcriptionConfig(tp)
                    .build());
            Thread.sleep(1500); // 等 session 就绪
            if (pcm.length > 0) {
                // 语音 + 尾随静音，触发 VAD 定稿
                byte[] feed = new byte[pcm.length + 24000 * 2]; // +1s 静音
                System.arraycopy(pcm, 0, feed, 0, pcm.length);
                conv.appendAudio(Base64.getEncoder().encodeToString(feed));
            }
            done.await(15, TimeUnit.SECONDS);
            if (error.get() != null) throw new IllegalStateException("ASR Error: " + error.get());
            return transcript.get();
        } finally {
            try { conv.endSession(); } catch (Exception ignored) { }
            try { conv.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------- 配置读取 ----------------

    private static String resolve(String sysProp, String key) {
        String override = System.getProperty(sysProp);
        if (override != null && !override.isBlank()) return override;
        try {
            if ("asr".equals(key) || "tts".equals(key)) {
                String src = Files.readString(voiceProperties(), StandardCharsets.UTF_8);
                String m = regexFirst(src, "(?s)class " + cap(key) + " \\{.*?model\\s*=\\s*\"([^\"]+)\"");
                if (m != null) return m;
            } else {
                String src = Files.readString(appYml(), StandardCharsets.UTF_8);
                String m = regexFirst(src, switch (key) {
                    case "chat" -> "(?s)chat:.*?options:\\s*model:\\s*(\\S+)";
                    case "correction" -> "(?s)correction:.*?model:\\s*(\\S+)";
                    case "embedding" -> "(?s)embedding:.*?options:\\s*model:\\s*(\\S+)";
                    default -> null;
                });
                if (m != null) return m;
            }
        } catch (Exception e) {
            System.out.println("  配置解析失败（" + key + "）: " + e.getMessage());
        }
        return switch (key) {
            case "chat" -> "qwen3.7-max-2026-05-17";
            case "correction" -> "qwen3.7-flash";
            case "embedding" -> "qwen3.7-text-embedding";
            case "asr" -> "qwen-audio-3.0-asr-flash-streaming";
            case "tts" -> "qwen-audio-3.0-tts-plus";
            default -> "";
        };
    }

    private static String resolveApiKey() throws Exception {
        String p = System.getProperty("verify.api-key");
        if (p != null && !p.isBlank()) return p;
        String env = System.getenv("DASHSCOPE_API_KEY");
        if (env != null && !env.isBlank()) return env;
        String local = Files.readString(localYml(), StandardCharsets.UTF_8);
        String m = regexFirst(local, "api-key:\\s*(\\S+)");
        if (m != null) return m;
        throw new IllegalStateException("未找到 API Key：请设置环境变量 DASHSCOPE_API_KEY 或 -Dverify.api-key=sk-xxx");
    }

    private static Path appYml() { return Paths.get("src/main/resources/application.yml"); }
    private static Path localYml() { return Paths.get("src/main/resources/application-local.yml"); }
    private static Path voiceProperties() { return Paths.get("src/main/java/com/interview/agent/voice/VoiceProperties.java"); }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String regexFirst(String src, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(src);
        return m.find() ? m.group(1) : null;
    }

    private static String mask(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 可抛受检异常的 Runnable（SDK 的 call 方法声明抛出 NoApiKeyException 等） */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 执行一项检查并记录结果 */
    private static void report(String label, String model, ThrowingRunnable check) {
        long start = System.currentTimeMillis();
        try {
            check.run();
            long cost = System.currentTimeMillis() - start;
            System.out.println("[PASS] " + label + " (" + model + ") " + cost + "ms");
            REPORT.add("[PASS] " + label + " (" + model + ")");
        } catch (Throwable e) {
            System.out.println("[FAIL] " + label + " (" + model + "): " + e.getMessage());
            REPORT.add("[FAIL] " + label + " (" + model + "): " + e.getMessage());
        }
    }
}
