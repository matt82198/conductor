package dev.conductor.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.humaninput.HumanInputNeededEvent;
import dev.conductor.server.queue.QueuedMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplementary WebSocket handler that broadcasts domain events beyond
 * the original agent stream.
 *
 * <p>Listens for {@link HumanInputNeededEvent} (from humaninput/) and
 * {@link QueuedMessageEvent} (from queue/) and broadcasts them to all
 * connected WebSocket clients as JSON messages.
 *
 * <p>This handler is registered at {@code /ws/notifications} to complement
 * the existing {@code /ws/events} endpoint. The UI should connect to both
 * endpoints to receive the full event stream:
 * <ul>
 *   <li>{@code /ws/events} — raw agent stream-json events</li>
 *   <li>{@code /ws/notifications} — human input requests and queued messages</li>
 * </ul>
 *
 * <h3>Message Formats</h3>
 * <p>Human input needed:
 * <pre>
 * {
 *   "type": "human_input_needed",
 *   "request": { ...HumanInputRequest fields... }
 * }
 * </pre>
 *
 * <p>Queued message:
 * <pre>
 * {
 *   "type": "queued_message",
 *   "message": { ...QueuedMessage fields... }
 * }
 * </pre>
 */
@Component
public class AdditionalEventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AdditionalEventWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public AdditionalEventWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Notification WebSocket client connected: {} (total: {})",
                session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Notification WebSocket client disconnected: {} status={} (total: {})",
                session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Notification WebSocket transport error for {}: {}",
                session.getId(), exception.getMessage());
        sessions.remove(session);
    }

    /**
     * Broadcasts a human input needed event to all connected clients.
     *
     * <p>Published by the humaninput/ domain when an agent is detected
     * as waiting for user input.
     */
    @EventListener
    public void onHumanInputNeeded(HumanInputNeededEvent event) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "type", "human_input_needed",
                    "request", event.request()
            );
            broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Error broadcasting human input event: {}", e.getMessage(), e);
        }
    }

    /**
     * Broadcasts a queued message event to all connected clients.
     *
     * <p>Published by the queue/ domain when a message passes through
     * the full pipeline (classify, dedup, batch, filter).
     *
     * <p>Note: this consumes {@code dev.conductor.server.queue.QueuedMessageEvent},
     * not the notification/ domain's local event type.
     */
    @EventListener
    public void onQueuedMessage(QueuedMessageEvent event) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "type", "queued_message",
                    "message", event.message()
            );
            broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Error broadcasting queued message event: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the number of currently connected WebSocket clients.
     */
    public int getConnectedClientCount() {
        return sessions.size();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private void broadcast(String json) {
        TextMessage message = new TextMessage(json);

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    // WebSocketSession.sendMessage is not thread-safe;
                    // synchronize per-session to prevent interleaved frames
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException e) {
                    log.debug("Failed to send to session {}: {}", session.getId(), e.getMessage());
                    sessions.remove(session);
                }
            }
        }
    }
}
