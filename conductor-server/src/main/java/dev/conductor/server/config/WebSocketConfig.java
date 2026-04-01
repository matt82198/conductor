package dev.conductor.server.config;

import dev.conductor.server.api.EventStreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Configures raw WebSocket endpoints for real-time event streaming.
 *
 * <p>Uses raw WebSocket (not STOMP) for simplicity in Phase 0.
 * The {@code /ws/events} endpoint pushes all agent stream-json events
 * to connected clients as JSON messages.
 *
 * <p>CORS is allowed from all origins during development. This must be
 * locked down before any multi-user deployment.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventStreamWebSocketHandler eventStreamHandler;

    public WebSocketConfig(EventStreamWebSocketHandler eventStreamHandler) {
        this.eventStreamHandler = eventStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eventStreamHandler, "/ws/events")
                .setAllowedOrigins("*");
    }
}
