package com.interview.agent.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseRegistry {
    private static final Logger log = LoggerFactory.getLogger(SseRegistry.class);
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30分钟

    public SseEmitter register(String sessionId) {
        // 如果已有连接，先完成旧连接
        SseEmitter old = emitters.remove(sessionId);
        if (old != null) {
            try {
                old.complete();
            } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.put(sessionId, emitter);

        // 注意：回调必须只移除"自己这个 emitter"（remove(key, value) 语义）。
        // 否则页面跳转重连后旧连接的异步回调会把新注册的 emitter 误删，
        // 导致重连后 QUESTION_DELTA 等事件全部被丢弃（编程题返回后流式输出消失的根因）
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成: {}", sessionId);
            emitters.remove(sessionId, emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: {}", sessionId);
            emitters.remove(sessionId, emitter);
        });
        emitter.onError(e -> {
            log.warn("SSE 连接错误: {}", sessionId, e);
            emitters.remove(sessionId, emitter);
        });

        return emitter;
    }

    public void send(String sessionId, SseEmitter.SseEventBuilder event) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            // 静默丢弃会让前端永久等待（如编程题提交后收不到结果），必须留痕
            log.warn("SSE 无活跃连接，事件已丢弃: sessionId={}", sessionId);
            return;
        }
        try {
            emitter.send(event);
        } catch (IOException e) {
            log.warn("SSE 发送失败: {}", sessionId, e);
            emitters.remove(sessionId, emitter);
        } catch (IllegalStateException e) {
            // emitter 已完成（complete/超时后容器已收尾），发送会抛 IllegalStateException
            log.warn("SSE 连接已关闭，事件发送失败: sessionId={}", sessionId, e);
            emitters.remove(sessionId, emitter);
        }
    }

    public void sendEvent(String sessionId, String eventName, String data) {
        send(sessionId, SseEmitter.event().name(eventName).data(data));
    }

    public void complete(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    public void sendError(String sessionId, String error) {
        send(sessionId, SseEmitter.event().name("ERROR").data(error));
        complete(sessionId);
    }
}
