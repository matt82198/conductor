package dev.conductor.server.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Notification channel that delivers messages to the UI via WebSocket.
 *
 * <p>Publishes a {@link NotificationEvent} to the Spring application event bus.
 * The existing {@code EventStreamWebSocketHandler} (or a future dedicated
 * notification handler) picks up these events and pushes them to connected
 * WebSocket clients.
 *
 * <p>This design keeps the notification domain decoupled from the api/ domain's
 * WebSocket internals. The handler in api/ owns the WebSocket sessions and
 * decides how to serialize and deliver the event.
 *
 * <p>The published event has the shape:
 * <pre>
 * {
 *   "type": "notification",
 *   "agentId": "uuid",
 *   "text": "...",
 *   "urgency": "CRITICAL",
 *   "channel": "websocket",
 *   "sentAt": "2026-03-31T..."
 * }
 * </pre>
 */
@Component
public class WebSocketChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebSocketChannel.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WebSocketChannel(ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(NotificationPayload payload) {
        try {
            NotificationEvent event = new NotificationEvent(payload, channelName(), Instant.now());
            eventPublisher.publishEvent(event);

            log.debug("Published WebSocket notification: agentId={} urgency={} text={}",
                    payload.agentId(),
                    payload.urgency(),
                    truncate(payload.text(), 80));
        } catch (Exception e) {
            log.error("Failed to publish WebSocket notification for agent {}: {}",
                    payload.agentId(), e.getMessage(), e);
        }
    }

    @Override
    public String channelName() {
        return "websocket";
    }

    /**
     * Truncates a string to the given max length, appending "..." if truncated.
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ─── Event published to Spring bus ────────────────────────────────────

    /**
     * Spring application event that carries a notification for WebSocket delivery.
     *
     * <p>Any WebSocket handler can listen for this event type via {@code @EventListener}
     * and broadcast it to connected clients. This keeps the notification domain
     * decoupled from WebSocket session management.
     *
     * @param payload the notification content
     * @param channel the channel name that routed this notification
     * @param sentAt  when the notification was sent
     */
    public record NotificationEvent(
            NotificationPayload payload,
            String channel,
            Instant sentAt
    ) {

        /**
         * Converts this event to a Map suitable for JSON serialization
         * and WebSocket delivery. Uses HashMap to avoid NPE on null values.
         */
        public Map<String, Object> toWireFormat() {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "notification");
            map.put("agentId", payload.agentId().toString());
            map.put("text", payload.text());
            map.put("urgency", payload.urgency().name());
            map.put("channel", channel);
            map.put("sentAt", sentAt.toString());
            return map;
        }
    }
}
