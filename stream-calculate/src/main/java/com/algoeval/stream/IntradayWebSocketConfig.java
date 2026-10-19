package com.algoeval.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日内监控 WebSocket：/ws/intraday 文本 JSON 推送（实时指标 + 告警）。
 */
@Configuration
@EnableWebSocket
public class IntradayWebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new IntradayWebSocketHandler(), "/ws/intraday").setAllowedOrigins("*");
    }

    /** 会话注册与广播 */
    public static class IntradayWebSocketHandler extends TextWebSocketHandler {

        private static final Logger log = LoggerFactory.getLogger(IntradayWebSocketHandler.class);
        private static final Set<WebSocketSession> SESSIONS = ConcurrentHashMap.newKeySet();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            SESSIONS.add(session);
            log.info("[stream-calculate] 日内监控客户端接入: {}（在线 {}）", session.getId(), SESSIONS.size());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            SESSIONS.remove(session);
        }

        /** 向全部在线客户端广播 JSON */
        public static void broadcast(String json) {
            for (WebSocketSession session : SESSIONS) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                } catch (IOException e) {
                    log.warn("[stream-calculate] WebSocket 推送失败: {}", e.getMessage());
                }
            }
        }

        public static int onlineCount() {
            return SESSIONS.size();
        }
    }
}
