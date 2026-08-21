package com.interview.agent.voice;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

/**
 * TTS 语音合成（DashScope qwen-audio-3.0-tts-plus，ttsv2 SpeechSynthesizer 协议）。
 *
 * <p>每次合成建立一条临时 WebSocket：SpeechSynthesisParam + 同步 call(text) 阻塞返回完整 PCM。
 * 同步模式（回调传 null）；失败返回空数组而非抛异常：题目文本已由 SSE 推送，
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

        SpeechSynthesizer synthesizer = null;
        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(cfg.getApiKey())
                    .model(cfg.getModel())
                    .voice(cfg.getVoice())
                    .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                    .build();
            // 同步模式：第二个参数传 null（无回调），call 阻塞直到音频返回
            synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audio = synthesizer.call(text, cfg.getTimeoutSeconds() * 1000L);
            if (audio == null || audio.remaining() == 0) {
                log.warn("TTS 返回空音频，降级为纯文本: textLength={}", text.length());
                return new byte[0];
            }
            byte[] pcm = new byte[audio.remaining()];
            audio.get(pcm);
            log.info("TTS 合成完成: textLength={}, pcmBytes={}", text.length(), pcm.length);
            return pcm;
        } catch (Exception e) {
            log.warn("TTS 合成异常，降级为纯文本: {}", e.getMessage());
            return new byte[0];
        } finally {
            if (synthesizer != null) {
                try {
                    synthesizer.getDuplexApi().close(1000, "bye");
                } catch (Exception e) {
                    log.debug("TTS 连接关闭异常（忽略）: {}", e.getMessage());
                }
            }
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
