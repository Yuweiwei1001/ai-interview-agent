package com.interview.agent.voice;

import com.interview.agent.common.utils.JwtUtil;
import com.interview.agent.interview.InterviewSessionMapper;
import com.interview.agent.interview.model.InterviewSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

/**
 * 语音面试 WebSocket 端点配置。
 *
 * <p>鉴权：HTTP 层的 JwtTokenInterceptor 只拦 /api/**，WS 握手在拦截器中自行校验——
 * query 参数携带 accessToken，校验 token 有效且 sessionId 归属当前用户。
 */
@Configuration
@EnableWebSocket
public class VoiceWebSocketConfig implements WebSocketConfigurer {
    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketConfig.class);

    private final VoiceInterviewWsHandler voiceInterviewWsHandler;
    private final JwtUtil jwtUtil;
    private final InterviewSessionMapper sessionMapper;
    private final VoiceProperties voiceProperties;

    public VoiceWebSocketConfig(VoiceInterviewWsHandler voiceInterviewWsHandler,
                                JwtUtil jwtUtil,
                                InterviewSessionMapper sessionMapper,
                                VoiceProperties voiceProperties) {
        this.voiceInterviewWsHandler = voiceInterviewWsHandler;
        this.jwtUtil = jwtUtil;
        this.sessionMapper = sessionMapper;
        this.voiceProperties = voiceProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceInterviewWsHandler, "/ws/voice/{sessionId}")
                .addInterceptors(new VoiceHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    /** 音频消息体积放宽（base64 PCM 帧） */
    @Bean
    public ServletServerContainerFactoryBean createVoiceWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        return container;
    }

    private class VoiceHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            try {
                if (!voiceProperties.isEnabled()) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return false;
                }
                if (!(request instanceof ServletServerHttpRequest servletRequest)) {
                    response.setStatusCode(HttpStatus.BAD_REQUEST);
                    return false;
                }
                String token = servletRequest.getServletRequest().getParameter("token");
                if (token == null || token.isBlank()) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                }
                Long userId = jwtUtil.getUserIdFromToken(token);

                String path = request.getURI().getPath();
                String sessionId = path.substring(path.lastIndexOf('/') + 1);
                InterviewSession interviewSession = sessionMapper.findById(sessionId);
                if (interviewSession == null || !userId.equals(interviewSession.getUserId())) {
                    log.warn("语音 WS 握手拒绝：会话不存在或无权访问, sessionId={}, userId={}", sessionId, userId);
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return false;
                }
                attributes.put("userId", userId);
                return true;
            } catch (Exception e) {
                log.warn("语音 WS 握手鉴权失败: {}", e.getMessage());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }
}
