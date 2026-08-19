package com.interview.agent.interview.agent;

import com.interview.agent.voice.VoiceChannelRegistry;
import com.interview.agent.voice.VoiceProperties;
import com.interview.agent.voice.VoiceTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Speaker Agent - 面试官语音播报（TTS）。
 *
 * <p>语音面试（phase=VOICE）时把题目文本合成为语音，经 VoiceChannelRegistry 推送到前端。
 * 设计要点：
 * <ul>
 *   <li>播报钩子必须在推题（askAndWait）之前调用：TTS 合成与 SSE 文字推题并行，
 *       若放在 ask 之后会在「收到回答后」才播报，时序错误</li>
 *   <li>虚拟线程异步合成：图线程不被 TTS 网络耗时阻塞</li>
 *   <li>全链路降级：未启用/非语音模式/无语音通道/合成失败时静默跳过，
 *       题目文本已由 SSE 推送，面试主流程不受影响</li>
 *   <li>无活跃语音通道时跳过合成，省 DashScope 调用成本</li>
 * </ul>
 */
@Component
public class SpeakerAgent {
    private static final Logger log = LoggerFactory.getLogger(SpeakerAgent.class);

    private final VoiceProperties voiceProperties;
    private final VoiceTtsService ttsService;
    private final VoiceChannelRegistry channelRegistry;

    public SpeakerAgent(VoiceProperties voiceProperties, VoiceTtsService ttsService,
                        VoiceChannelRegistry channelRegistry) {
        this.voiceProperties = voiceProperties;
        this.ttsService = ttsService;
        this.channelRegistry = channelRegistry;
    }

    /**
     * 语音模式下播报题目（异步，尽力而为，失败降级为纯文本）。
     *
     * @param sessionId 面试会话 ID
     * @param phase     交互模式（TEXT/VOICE），非 VOICE 直接跳过
     * @param text      待播报文本（题目/追问）
     */
    public void speakIfVoice(String sessionId, String phase, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!voiceProperties.isEnabled() || !"VOICE".equalsIgnoreCase(phase)) {
            return;
        }
        if (!channelRegistry.isActive(sessionId)) {
            // 候选人未开启语音通道：跳过合成省成本（文本已走 SSE）
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                byte[] pcm = ttsService.synthesize(text);
                if (pcm.length == 0) {
                    return; // 合成失败已降级（TtsService 内记日志）
                }
                byte[] wav = ttsService.pcmToWav(pcm);
                channelRegistry.sendAudio(sessionId, wav);
                log.info("TTS 播报已推送: sessionId={}, textLength={}, wavBytes={}",
                        sessionId, text.length(), wav.length);
            } catch (Exception e) {
                log.warn("TTS 播报失败（降级为纯文本）: sessionId={}", sessionId, e);
            }
        });
    }
}
