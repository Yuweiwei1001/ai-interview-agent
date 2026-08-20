package com.interview.agent.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.interview.graph.InterviewGraphBuilder;
import com.interview.agent.voice.correction.AsrCorrectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 语音面试 WebSocket 处理器（/ws/voice/{sessionId}）。
 *
 * <p>职责边界（与面试主流程解耦）：
 * <ul>
 *   <li>上行：接收前端 PCM 音频帧 → 转发 ASR 实时识别</li>
 *   <li>下行：推送 ASR 字幕（partial/final）；TTS 音频由 SpeakerAgent 经 VoiceChannelRegistry 推送</li>
 *   <li>回答提交不走本通道——前端把字幕草稿编辑后经 REST /api/interviews/{id}/answer 提交
 *       （复用既有权限校验与图唤醒逻辑）</li>
 * </ul>
 *
 * <p>回声问题说明：采用“手动提交”交互（候选人讲完点发送），ASR 误识别只会进入可编辑草稿，
 * 不会自动送入图流程；故不实现借鉴对象的 AI 说话门控/冷却期，前端建议佩戴耳机。
 */
@Component
public class VoiceInterviewWsHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(VoiceInterviewWsHandler.class);

    /** WS 发送时限/缓冲：1 秒 PCM ≈ 42KB base64，音频消息给足余量 */
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final VoiceAsrService asrService;
    private final VoiceChannelRegistry channelRegistry;
    private final VoiceProperties properties;
    private final ObjectMapper objectMapper;
    private final AsrCorrectionService correctionService;
    private final InterviewGraphBuilder graphBuilder;

    /** per-session final 字幕序号（ASR 热词纠错方案 4.4.3）：seq 由 WS Handler 统一分配，
     *  不靠前端自己数（partial/断线重连/丢弃都会导致前端计数漂移）；
     *  subtitle(final) 与 asr_correction 消息携带同 seq，前端按 seq 定位草稿句 */
    private final Map<String, AtomicInteger> finalSeqBySession = new ConcurrentHashMap<>();

    /** ASR 就绪检查调度（延迟任务，量小，2 线程足够） */
    private final ScheduledExecutorService readyCheckScheduler;

    public VoiceInterviewWsHandler(VoiceAsrService asrService,
                                   VoiceChannelRegistry channelRegistry,
                                   VoiceProperties properties,
                                   ObjectMapper objectMapper,
                                   AsrCorrectionService correctionService,
                                   InterviewGraphBuilder graphBuilder) {
        this.asrService = asrService;
        this.channelRegistry = channelRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.correctionService = correctionService;
        this.graphBuilder = graphBuilder;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-asr-ready-check");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.readyCheckScheduler = executor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = extractSessionId(session);
        // 音频消息体积放宽（1 秒 16kHz PCM ≈ 42KB base64）
        session.setTextMessageSizeLimit(256 * 1024);

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES);
        channelRegistry.register(sessionId, safeSession);
        log.info("语音通道已建立: sessionId={}", sessionId);

        startAsr(sessionId);
        sendControl(sessionId, "connected", "语音通道已连接，正在初始化语音识别");
        scheduleReadyCheck(sessionId, 0);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = extractSessionId(session);
        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.has("type") ? msg.get("type").asText() : "";
            if ("audio".equals(type)) {
                String data = msg.has("data") ? msg.get("data").asText() : null;
                if (data != null && !data.isEmpty()) {
                    forwardAudioToAsr(sessionId, Base64.getDecoder().decode(data));
                }
            } else if ("ping".equals(type)) {
                sendControl(sessionId, "pong", null);
            } else {
                log.debug("未知语音消息类型: sessionId={}, type={}", sessionId, type);
            }
        } catch (Exception e) {
            log.warn("语音消息处理失败: sessionId={}", sessionId, e);
            sendError(sessionId, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = extractSessionId(session);
        asrService.stopTranscription(sessionId);
        finalSeqBySession.remove(sessionId);
        channelRegistry.unregister(sessionId, session);
        log.info("语音通道已关闭: sessionId={}, status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("语音通道传输错误: sessionId={}", extractSessionId(session), exception);
    }

    @PreDestroy
    public void destroy() {
        readyCheckScheduler.shutdownNow();
    }

    // ---------- ASR 生命周期 ----------

    private void startAsr(String sessionId) {
        // 会话热词快照：从 graph checkpoint 恢复（断线重连/页面刷新后 corpus 偏置与纠错不静默失效，方案 3.3）
        List<String> sessionHotwords = peekHotwords(sessionId);
        // corpus 拼接：逗号分隔术语串，不附加解释文字（corpus 越像自然词表越好，减少幻觉诱因）
        String corpusText = null;
        VoiceProperties.Corpus corpusCfg = properties.getCorpus();
        if (corpusCfg.isEnabled() && !sessionHotwords.isEmpty()) {
            int limit = Math.min(sessionHotwords.size(), corpusCfg.getMaxTerms());
            corpusText = String.join(", ", sessionHotwords.subList(0, limit));
        }
        asrService.startTranscription(
                sessionId,
                corpusText,
                // final：一句话定稿（VAD 切段），携带 seq 下发，前端累积为回答草稿；
                // 疑似 corpus 幻觉的 final 照常下发但携带 suspect 标记（裁决权留给候选人）
                finalTranscript -> {
                    int seq = finalSeqBySession
                            .computeIfAbsent(sessionId, k -> new AtomicInteger())
                            .incrementAndGet();
                    sendSubtitle(sessionId, seq, finalTranscript.text(), true, finalTranscript.suspect());
                    // 异步纠错：原字幕先行下发，correction 异步补发（P95 < 2s，失败静默回退原文）
                    correctionService.correctAsync(sessionId, seq, finalTranscript.text(), sessionHotwords);
                },
                // partial：实时片段，前端做字幕预览（预览性质，不带 seq，不做对齐）
                text -> sendSubtitle(sessionId, null, text, false, false),
                () -> sendControl(sessionId, "asr_ready", "语音识别已就绪"),
                error -> {
                    log.warn("ASR 错误: sessionId={}, err={}", sessionId, error.getMessage());
                    sendError(sessionId, "语音识别失败: " + error.getMessage());
                });
    }

    /** 读取会话当前热词快照（InterviewState.sessionHotwords，随 checkpoint 持久化）；失败降级为空表 */
    private List<String> peekHotwords(String sessionId) {
        try {
            return graphBuilder.peekSessionHotwords(sessionId);
        } catch (Exception e) {
            log.debug("[{}] 会话热词读取失败（降级为无热词）: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private void scheduleReadyCheck(String sessionId, int retryCount) {
        readyCheckScheduler.schedule(
                () -> checkReadyOrReconnect(sessionId, retryCount),
                properties.getAsr().getReadyCheckDelaySeconds(),
                TimeUnit.SECONDS);
    }

    private void checkReadyOrReconnect(String sessionId, int retryCount) {
        if (!channelRegistry.isActive(sessionId) || asrService.isReady(sessionId)) {
            return;
        }
        if (retryCount < properties.getAsr().getMaxReadyRetry()) {
            log.warn("[{}] ASR {}s 未就绪，自动重连（{}/{})", sessionId,
                    properties.getAsr().getReadyCheckDelaySeconds(), retryCount + 1, properties.getAsr().getMaxReadyRetry());
            sendControl(sessionId, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            startAsr(sessionId);
            scheduleReadyCheck(sessionId, retryCount + 1);
        } else {
            log.warn("[{}] ASR 重连 {} 次后仍未就绪", sessionId, retryCount);
            sendError(sessionId, "语音识别连接准备超时，请刷新页面重试");
        }
    }

    /** 音频帧转发 ASR：未就绪直接丢弃；连接失效则重连（借鉴对象踩坑：ASR 连接会被服务端静默关闭） */
    private void forwardAudioToAsr(String sessionId, byte[] audioData) {
        try {
            asrService.sendAudio(sessionId, audioData);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("not ready")) {
                // ASR 尚未就绪的音频帧直接丢弃（毫秒级损失，无感知）
                return;
            }
            if (msg.contains("No active") || msg.contains("append failed")) {
                log.warn("[{}] ASR 连接失效（{}），自动重连", sessionId, msg);
                startAsr(sessionId);
                return;
            }
            throw e;
        }
    }

    // ---------- 下行消息 ----------

    private void sendSubtitle(String sessionId, Integer seq, String text, boolean isFinal, boolean suspect) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "subtitle");
        // seq 仅 final 携带：correction 异步补发时前端据此定位草稿句（方案 4.4.3）
        if (seq != null) {
            msg.put("seq", seq);
        }
        msg.put("text", text);
        msg.put("final", isFinal);
        if (suspect) {
            msg.put("suspect", true);
        }
        channelRegistry.sendJson(sessionId, msg);
    }

    private void sendControl(String sessionId, String action, String message) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "control");
        msg.put("action", action);
        if (message != null) {
            msg.put("message", message);
        }
        msg.put("timestamp", System.currentTimeMillis());
        channelRegistry.sendJson(sessionId, msg);
    }

    private void sendError(String sessionId, String error) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "error");
        msg.put("message", error);
        channelRegistry.sendJson(sessionId, msg);
    }

    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
