package com.interview.agent.voice;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 实时语音识别（DashScope qwen-audio-3.0-asr-flash-streaming，Omni Realtime WebSocket）。
 *
 * <p>每个语音面试会话独占一条 ASR 连接，服务端 VAD（server_vad）自动按静音切段：
 * <ul>
 *   <li>partial（.text/.delta 事件）→ 实时字幕预览</li>
 *   <li>final（.completed 事件）→ 一句话定稿，由前端累积为回答草稿，候选人手动提交</li>
 * </ul>
 *
 * <p>可靠性设计（借鉴 interview-guide 踩坑修复）：
 * <ul>
 *   <li>ready latch：连接建立 + updateSession 完成后才标记就绪，未就绪时收到的音频块直接丢弃</li>
 *   <li>onClose 仅移除“自己这条连接”（compute 比较引用），避免重连后旧回调误删新连接</li>
 *   <li>断线由上层（WS handler）检测后调 restartTranscription 重建</li>
 * </ul>
 *
 * <p>会话级热词 corpus 偏置（ASR 热词纠错方案 4.2）：仅会话级热词（≤maxTerms）作为 corpus 传给 ASR，
 * 从识别阶段减少术语错误；corpus 存在幻觉输出风险（原样输出词表），final 侧做编辑距离检测，
 * 疑似幻觉不丢弃字幕，仅 suspect 标记下发由候选人核对（丢弃字幕违反“不能比没有更差”总原则）。
 */
@Service
public class VoiceAsrService {
    private static final Logger log = LoggerFactory.getLogger(VoiceAsrService.class);

    private final VoiceProperties properties;

    /** sessionId → ASR 会话 */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();
    /** 防止同一会话并发 start/stop/restart */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public VoiceAsrService(VoiceProperties properties) {
        this.properties = properties;
    }

    /** final 定稿回调载体：text + 疑似 corpus 幻觉标记 */
    public record FinalTranscript(String text, boolean suspect) {}

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 启动转录会话（异步建连，就绪后回调 onReady）。
     *
     * @param corpusText 会话级热词 corpus 偏置（逗号分隔术语串，null/空 = 不传 corpus）
     * @param onFinal    一句话定稿回调（VAD 切段，携带 corpus 幻觉 suspect 标记）
     * @param onPartial  实时片段回调（字幕预览），可为 null
     * @param onReady    连接就绪回调，可为 null
     * @param onError    错误回调
     */
    public void startTranscription(String sessionId,
                                   String corpusText,
                                   Consumer<FinalTranscript> onFinal,
                                   Consumer<String> onPartial,
                                   Runnable onReady,
                                   Consumer<Throwable> onError) {
        synchronized (lockFor(sessionId)) {
            startLocked(sessionId, corpusText, onFinal, onPartial, onReady, onError);
        }
    }

    /** 断线重连：停旧连接（停不掉也忽略）后重建，并最多等待 1s 验证就绪 */
    public void restartTranscription(String sessionId,
                                     String corpusText,
                                     Consumer<FinalTranscript> onFinal,
                                     Consumer<String> onPartial,
                                     Runnable onReady,
                                     Consumer<Throwable> onError) {
        synchronized (lockFor(sessionId)) {
            log.info("[{}] 重连 ASR（stop + start）", sessionId);
            stopLocked(sessionId);
            startLocked(sessionId, corpusText, onFinal, onPartial, onReady, onError);
        }
    }

    private void startLocked(String sessionId,
                             String corpusText,
                             Consumer<FinalTranscript> onFinal,
                             Consumer<String> onPartial,
                             Runnable onReady,
                             Consumer<Throwable> onError) {
        VoiceProperties.Asr cfg = properties.getAsr();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            onError.accept(new IllegalStateException("ASR 未配置 api-key"));
            return;
        }
        if (sessions.containsKey(sessionId)) {
            log.warn("[{}] ASR 会话已存在，忽略重复启动", sessionId);
            return;
        }

        try {
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(cfg.getModel())
                    .url(cfg.getUrl())
                    .apikey(cfg.getApiKey())
                    .build();

            AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[{}] ASR WebSocket 已连接", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    dispatchEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[{}] ASR WebSocket 关闭: code={}, reason={}", sessionId, code, reason);
                    // 仅移除与本次连接对应的会话，避免重连后旧 onClose 误删新连接
                    sessions.compute(sessionId, (id, existing) ->
                            existing != null && closed != null && existing.conversation == closed ? null : existing);
                }
            };

            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);
            AsrSession asrSession = new AsrSession(conversation, corpusText);
            sessions.put(sessionId, asrSession);

            Thread connectThread = new Thread(() -> {
                try {
                    conversation.connect();

                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(cfg.getLanguage());
                    transcriptionParam.setInputSampleRate(cfg.getSampleRate());
                    transcriptionParam.setInputAudioFormat(cfg.getFormat());
                    // 会话级热词 corpus 偏置：仅词表克制地传（≤maxTerms，逗号分隔不附加解释文字）。
                    // 词表越长幻觉爆炸半径越大；未开启/无热词则完全不传，行为与旧版本一致
                    VoiceProperties.Corpus corpusCfg = properties.getCorpus();
                    if (corpusCfg.isEnabled() && corpusText != null && !corpusText.isBlank()) {
                        transcriptionParam.setCorpusText(corpusText);
                        log.info("[{}] ASR corpus 偏置已启用: terms={}", sessionId,
                                corpusText.split(",").length);
                    }

                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                            .enableTurnDetection(true)
                            .turnDetectionType("server_vad")
                            .turnDetectionSilenceDurationMs(cfg.getTurnDetectionSilenceDurationMs())
                            .transcriptionConfig(transcriptionParam)
                            .build();
                    conversation.updateSession(config);

                    // 就绪校验：可能已被 stop/restart 替换，旧连接回调直接忽略
                    if (sessions.get(sessionId) != asrSession) {
                        log.debug("[{}] 忽略过期 ASR 连接的 ready 回调", sessionId);
                        return;
                    }
                    asrSession.markReady();
                    if (onReady != null) {
                        onReady.run();
                    }
                    log.info("[{}] ASR 转录会话就绪", sessionId);
                } catch (Exception e) {
                    log.error("[{}] ASR 连接建立失败", sessionId, e);
                    sessions.compute(sessionId, (id, existing) ->
                            existing != null && existing.conversation == conversation ? null : existing);
                    onError.accept(e);
                }
            }, "voice-asr-connect-" + sessionId);
            connectThread.setDaemon(true);
            connectThread.start();
        } catch (Exception e) {
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException("ASR 会话创建失败: " + e.getMessage(), e));
        }
    }

    /**
     * 发送一帧 PCM 音频（16kHz/16bit/mono）。
     * @throws IllegalStateException 会话不存在或未就绪（上层据此丢弃或触发重连）
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active ASR session: " + sessionId);
        }
        if (!session.isReady()) {
            throw new IllegalStateException("ASR session not ready: " + sessionId);
        }
        try {
            session.conversation.appendAudio(Base64.getEncoder().encodeToString(audioData));
        } catch (Exception e) {
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    public boolean isReady(String sessionId) {
        AsrSession session = sessions.get(sessionId);
        return session != null && session.isReady();
    }

    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public void stopTranscription(String sessionId) {
        synchronized (lockFor(sessionId)) {
            stopLocked(sessionId);
        }
    }

    private void stopLocked(String sessionId) {
        AsrSession session = sessions.remove(sessionId);
        sessionLocks.remove(sessionId);
        if (session == null) {
            return;
        }
        try {
            session.conversation.endSession();
        } catch (Exception e) {
            log.debug("[{}] ASR endSession 异常（忽略）: {}", sessionId, e.getMessage());
        }
        try {
            session.conversation.close();
        } catch (Exception e) {
            log.debug("[{}] ASR close 异常（忽略）: {}", sessionId, e.getMessage());
        }
        log.info("[{}] ASR 转录会话已停止", sessionId);
    }

    @PreDestroy
    public void destroy() {
        log.info("关闭全部 ASR 会话: {} 个", sessions.size());
        sessions.keySet().forEach(id -> {
            try {
                stopTranscription(id);
            } catch (Exception e) {
                log.warn("[{}] ASR 清理失败: {}", id, e.getMessage());
            }
        });
        sessions.clear();
    }

    /** 事件分发：completed → onFinal（携带幻觉 suspect 标记）；text/delta → onPartial；error → onError */
    private void dispatchEvent(String sessionId, JsonObject message,
                               Consumer<FinalTranscript> onFinal, Consumer<String> onPartial,
                               Consumer<Throwable> onError) {
        try {
            String type = message.has("type") ? message.get("type").getAsString() : "";
            switch (type) {
                case "conversation.item.input_audio_transcription.completed" -> {
                    String transcript = message.has("transcript") ? message.get("transcript").getAsString() : "";
                    if (transcript != null && !transcript.isBlank()) {
                        onFinal.accept(new FinalTranscript(transcript, isSuspectedCorpusHallucination(sessionId, transcript)));
                    }
                }
                case "conversation.item.input_audio_transcription.text",
                     "conversation.item.input_audio_transcription.delta" -> {
                    if (onPartial != null) {
                        String text = extractPartialText(message);
                        if (text != null && !text.isBlank()) {
                            onPartial.accept(text);
                        }
                    }
                }
                case "error" -> {
                    JsonObject err = message.has("error") ? message.getAsJsonObject("error") : null;
                    String msg = err != null && err.has("message") ? err.get("message").getAsString() : "unknown";
                    log.error("[{}] ASR 服务错误: {}", sessionId, msg);
                    onError.accept(new IllegalStateException("ASR Error: " + msg));
                }
                default -> log.trace("[{}] ASR 事件: {}", sessionId, type);
            }
        } catch (Exception e) {
            log.error("[{}] ASR 事件处理异常", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * 提取实时片段文本：partial 的正式预览为 text（已确认前缀）+ stash（草稿后缀）；
     * 兼容 transcript / delta 字段变体。
     */
    static String extractPartialText(JsonObject message) {
        if (message.has("transcript") && message.get("transcript").isJsonPrimitive()) {
            return message.get("transcript").getAsString();
        }
        String prefix = "";
        String suffix = "";
        if (message.has("text") && message.get("text").isJsonPrimitive()) {
            prefix = message.get("text").getAsString();
        }
        if (message.has("stash") && message.get("stash").isJsonPrimitive()) {
            suffix = message.get("stash").getAsString();
        }
        String combined = prefix + suffix;
        if (!combined.isBlank()) {
            return combined;
        }
        if (message.has("delta")) {
            JsonElement d = message.get("delta");
            if (d.isJsonPrimitive()) {
                return d.getAsString();
            }
            if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                if (o.has("text") && o.get("text").isJsonPrimitive()) {
                    return o.get("text").getAsString();
                }
                if (o.has("transcript") && o.get("transcript").isJsonPrimitive()) {
                    return o.get("transcript").getAsString();
                }
            }
        }
        return null;
    }

    /**
     * corpus 幻觉检测（ASR 热词纠错方案 4.2.2）：已知风险（bailian-speech-demo#50）是模型
     * 有概率把 corpus 内容整体输出为转写结果。幻觉特征是原样输出词表拼接串、无自然语句结构，
     * 检测信号为 final 文本与 corpus 拼接串的归一化编辑距离极小。
     * 判据用编辑距离而非字符重叠率：后者对术语密集句误杀率极高
     * （开场高频句式“我熟悉 Redis、MySQL、Kafka…”重叠率轻松超 60%）。
     * 处置是降级不丢弃：suspect 标记下发，裁决权留给候选人。
     */
    private boolean isSuspectedCorpusHallucination(String sessionId, String transcript) {
        try {
            AsrSession session = sessions.get(sessionId);
            String corpus = session == null ? null : session.corpusText;
            if (corpus == null || corpus.isBlank()) {
                return false;
            }
            VoiceProperties.Corpus cfg = properties.getCorpus();
            if (!cfg.isEnabled()) {
                return false;
            }
            double ratio = normalizedEditDistanceRatio(transcript, corpus);
            if (ratio <= cfg.getHallucinationEditDistanceThreshold()) {
                log.warn("[{}] corpus_hallucination: final 与 corpus 拼接串编辑距离 {} ≤ {}（疑似幻觉，suspect 标记下发）",
                        sessionId, String.format("%.3f", ratio), cfg.getHallucinationEditDistanceThreshold());
                return true;
            }
            return false;
        } catch (Exception e) {
            // 检测自身异常不影响字幕下发
            log.debug("[{}] corpus 幻觉检测异常（按非幻觉处理）: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 归一化（去空格/标点/小写）后的编辑距离比率：dist / max(len)。final ≈ corpus 原样输出时 ≈ 0 */
    static double normalizedEditDistanceRatio(String a, String b) {
        String x = a == null ? "" : a.toLowerCase().replaceAll("\\s+|\\p{Punct}", "");
        String y = b == null ? "" : b.toLowerCase().replaceAll("\\s+|\\p{Punct}", "");
        if (x.isEmpty() || y.isEmpty()) return 1.0;
        int max = Math.max(x.length(), y.length());
        return (double) editDistance(x, y) / max;
    }

    /** 经典 Levenshtein 编辑距离（双行 DP） */
    static int editDistance(String a, String b) {
        if (a.equals(b)) return 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** ASR 会话：连接 + 就绪 latch（updateSession 完成后就绪）+ 会话级 corpus 快照 */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final CountDownLatch readyLatch = new CountDownLatch(1);
        /** 本连接的 corpus 快照（幻觉检测用，连接重建时随回调重建） */
        private final String corpusText;

        AsrSession(OmniRealtimeConversation conversation, String corpusText) {
            this.conversation = conversation;
            this.corpusText = corpusText;
        }

        void markReady() {
            readyLatch.countDown();
        }

        boolean isReady() {
            return readyLatch.getCount() == 0;
        }
    }
}
