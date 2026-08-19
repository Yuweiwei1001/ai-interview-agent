package com.interview.agent.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音通道注册表：面试 sessionId → 语音 WebSocket 连接。
 *
 * <p>SpeakerAgent（图执行线程）与 VoiceInterviewWsHandler（WS IO 线程）之间的桥梁：
 * 图线程在出题后经此注册表找到该会话的语音连接并推送 TTS 音频。
 */
@Component
public class VoiceChannelRegistry {
    private static final Logger log = LoggerFactory.getLogger(VoiceChannelRegistry.class);

    private final Map<String, WebSocketSession> channels = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public VoiceChannelRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String sessionId, WebSocketSession session) {
        channels.put(sessionId, session);
    }

    /** 仅当注册的仍是同一连接时移除（防重连后旧连接回调误删新连接） */
    public void unregister(String sessionId, WebSocketSession session) {
        channels.remove(sessionId, session);
    }

    /** 该面试会话是否有活跃语音通道（无通道时跳过 TTS 合成，省调用成本） */
    public boolean isActive(String sessionId) {
        WebSocketSession session = channels.get(sessionId);
        return session != null && session.isOpen();
    }

    /**
     * 推送 WAV 音频到前端播放。
     * 消息格式：{type:"audio", data: base64(wav)}
     */
    public void sendAudio(String sessionId, byte[] wavAudio) {
        if (wavAudio == null || wavAudio.length == 0) {
            return;
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "audio");
        msg.put("data", Base64.getEncoder().encodeToString(wavAudio));
        sendJson(sessionId, msg);
    }

    public void sendJson(String sessionId, Map<String, Object> msg) {
        WebSocketSession session = channels.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (Exception e) {
            log.warn("语音通道发送失败: sessionId={}, type={}", sessionId, msg.get("type"), e);
        }
    }
}
