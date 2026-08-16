package com.interview.agent.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class SseRegistry {
    private static final Logger log = LoggerFactory.getLogger(SseRegistry.class);
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    // 连接存活上限 2 小时：单题等待 30 分钟 × 多轮，面试总时长轻松超过 30 分钟；
    // 旧值 30 分钟与单题等待上限相同，长面试必然触发 AsyncRequestTimeout 断连（后续事件全部丢弃导致前端卡死）
    private static final long SSE_TIMEOUT = 120 * 60 * 1000L; // 2小时

    /** 心跳调度器：每 15 秒向所有连接发 SSE 注释帧，防代理/容器空闲断连，并及时暴露死连接 */
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseRegistry() {
        heartbeat.scheduleWithFixedDelay(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    private void sendHeartbeat() {
        emitters.forEach((sessionId, emitter) -> {
            try {
                // 注释帧（以 : 开头）不产生前端事件，仅保活；连接已死则抛异常走清理
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                log.info("SSE 心跳失败，清理死连接: sessionId={}", sessionId);
                emitters.remove(sessionId, emitter);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
    }

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
