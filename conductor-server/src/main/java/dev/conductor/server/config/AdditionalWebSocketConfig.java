package dev.conductor.server.config;

import dev.conductor.server.api.AdditionalEventWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the supplementary {@code /ws/notifications} WebSocket endpoint.
 *
 * <p>This configuration complements the existing {@link WebSocketConfig} which
 * registers {@code /ws/events} for raw agent stream events. The notifications
 * endpoint broadcasts domain-level events:
 * <ul>
 *   <li>{@code human_input_needed} — when an agent needs user input</li>
 *   <li>{@code queued_message} — when a message passes through the queue pipeline</li>
 * </ul>
 *
 * <p>CORS is allowed from all origins during development. This must be
 * locked down before any multi-user deployment.
 */
@Configuration
public class AdditionalWebSocketConfig implements WebSocketConfigurer {

    private final AdditionalEventWebSocketHandler additionalEventHandler;

    public AdditionalWebSocketConfig(AdditionalEventWebSocketHandler additionalEventHandler) {
        this.additionalEventHandler = additionalEventHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(additionalEventHandler, "/ws/notifications")
                .setAllowedOrigins("*");
    }
}
