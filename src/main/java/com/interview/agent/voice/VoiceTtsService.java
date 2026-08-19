package com.interview.agent.voice;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TTS 语音合成（DashScope qwen3-tts-flash-realtime，WebSocket commit 模式）。
 *
 * <p>每次合成建立一条临时连接：appendText → commit → 收集 response.audio.delta 音频分片
 * → response.done 后关闭连接。同步等待（CountDownLatch + 超时），返回 24kHz 16bit 单声道 PCM。
 *
 * <p>失败（超时/异常/空音频）返回空数组而非抛异常：题目文本已由 SSE 推送，
 * 语音缺失时前端仅无播报，面试流程不中断（与全链路降级哲学一致）。
 */
@Service
public class VoiceTtsService {
    private static final Logger log = LoggerFactory.getLogger(VoiceTtsService.class);

    private final VoiceProperties properties;

    public VoiceTtsService(VoiceProperties properties) {
        this.properties = properties;
    }

    /**
     * 合成文本为 PCM 音频（24kHz/16bit/mono）。
     * @return PCM 字节；失败返回空数组（调用方按“无语音降级”处理）
     */
    public byte[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        VoiceProperties.Tts cfg = properties.getTts();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            log.warn("TTS 未配置 api-key，跳过语音合成");
            return new byte[0];
        }

        CountDownLatch done = new CountDownLatch(1);
        ByteArrayOutputStream audioBuf = new ByteArrayOutputStream();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        QwenTtsRealtime tts = null;
        try {
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(cfg.getModel())
                    .apikey(cfg.getApiKey())
                    .build();

            tts = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket 已连接");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleEvent(message, audioBuf, done, errorRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket 关闭: code={}, reason={}", code, reason);
                    done.countDown();
                }
            });

            tts.connect();
            QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                    .voice(cfg.getVoice())
                    .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                    .mode(cfg.getMode())
                    .languageType(cfg.getLanguageType())
                    .speechRate(cfg.getSpeechRate())
                    .build();
            tts.updateSession(config);
            tts.appendText(text);
            tts.commit();

            boolean completed = done.await(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                log.warn("TTS 合成超时（{}s），降级为纯文本: textLength={}", cfg.getTimeoutSeconds(), text.length());
                return new byte[0];
            }
            if (errorRef.get() != null) {
                log.warn("TTS 合成失败，降级为纯文本: {}", errorRef.get().getMessage());
                return new byte[0];
            }
            byte[] pcm = audioBuf.toByteArray();
            log.info("TTS 合成完成: textLength={}, pcmBytes={}", text.length(), pcm.length);
            return pcm;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("TTS 合成被中断");
            return new byte[0];
        } catch (Exception e) {
            log.warn("TTS 合成异常，降级为纯文本: {}", e.getMessage());
            return new byte[0];
        } finally {
            if (tts != null) {
                try {
                    tts.close();
                } catch (Exception e) {
                    log.debug("TTS 连接关闭异常（忽略）: {}", e.getMessage());
                }
            }
        }
    }

    private void handleEvent(JsonObject message, ByteArrayOutputStream audioBuf,
                             CountDownLatch done, AtomicReference<Throwable> errorRef) {
        try {
            String type = message.has("type") ? message.get("type").getAsString() : "";
            switch (type) {
                case "response.audio.delta" -> {
                    if (message.has("delta")) {
                        byte[] chunk = Base64.getDecoder().decode(message.get("delta").getAsString());
                        synchronized (audioBuf) {
                            audioBuf.write(chunk, 0, chunk.length);
                        }
                    }
                }
                case "response.done" -> done.countDown();
                case "error" -> {
                    String msg = message.has("error") ? message.get("error").toString() : "unknown";
                    errorRef.set(new IllegalStateException("TTS Error: " + msg));
                    done.countDown();
                }
                default -> log.trace("未处理的 TTS 事件: {}", type);
            }
        } catch (Exception e) {
            errorRef.set(e);
            done.countDown();
        }
    }

    /**
     * PCM（24kHz/16bit/mono）加 44 字节 WAV 头，使前端可直接 decodeAudioData 播放。
     */
    public byte[] pcmToWav(byte[] pcmData) {
        int sampleRate = properties.getTts().getSampleRate();
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;

        byte[] wav = new byte[dataSize + 44];
        int pos = 0;
        wav[pos++] = 'R'; wav[pos++] = 'I'; wav[pos++] = 'F'; wav[pos++] = 'F';
        writeIntLE(wav, pos, dataSize + 36); pos += 4;
        wav[pos++] = 'W'; wav[pos++] = 'A'; wav[pos++] = 'V'; wav[pos++] = 'E';
        wav[pos++] = 'f'; wav[pos++] = 'm'; wav[pos++] = 't'; wav[pos++] = ' ';
        writeIntLE(wav, pos, 16); pos += 4;
        writeShortLE(wav, pos, (short) 1); pos += 2;
        writeShortLE(wav, pos, (short) numChannels); pos += 2;
        writeIntLE(wav, pos, sampleRate); pos += 4;
        writeIntLE(wav, pos, byteRate); pos += 4;
        writeShortLE(wav, pos, (short) blockAlign); pos += 2;
        writeShortLE(wav, pos, (short) bitsPerSample); pos += 2;
        wav[pos++] = 'd'; wav[pos++] = 'a'; wav[pos++] = 't'; wav[pos++] = 'a';
        writeIntLE(wav, pos, dataSize);
        System.arraycopy(pcmData, 0, wav, 44, dataSize);
        return wav;
    }

    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
