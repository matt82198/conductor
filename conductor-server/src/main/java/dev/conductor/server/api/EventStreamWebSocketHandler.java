package dev.conductor.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.process.ClaudeProcessManager.AgentStreamEvent;
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
 * Raw WebSocket handler that streams all agent events to connected clients.
 *
 * <p>Listens for {@link AgentStreamEvent}s published by {@link dev.conductor.server.process.ClaudeProcessManager}
 * and broadcasts them to all connected WebSocket sessions as JSON messages.
 *
 * <p>Each message sent to clients has the shape:
 * <pre>
 * {
 *   "agentId": "uuid",
 *   "eventType": "system|assistant|user|rate_limit_event|result|parse_error",
 *   "event": { ... full event fields ... }
 * }
 * </pre>
 *
 * <p>Uses raw WebSocket (not STOMP) for simplicity. Clients connect to
 * {@code ws://localhost:8090/ws/events}.
 */
@Component
public class EventStreamWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventStreamWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public EventStreamWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket client connected: {} (total: {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket client disconnected: {} status={} (total: {})",
                session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error for {}: {}", session.getId(), exception.getMessage());
        sessions.remove(session);
    }

    /**
     * Receives agent events from the Spring event bus and broadcasts
     * to all connected WebSocket clients.
     */
    @EventListener
    public void onAgentEvent(AgentStreamEvent agentEvent) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            String eventType = resolveEventType(agentEvent.event());
            Map<String, Object> payload = Map.of(
                    "agentId", agentEvent.agentId().toString(),
                    "eventType", eventType,
                    "event", agentEvent.event()
            );
            String json = objectMapper.writeValueAsString(payload);
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
        } catch (Exception e) {
            log.error("Error broadcasting agent event: {}", e.getMessage(), e);
        }
    }

    /**
     * Resolves the event type string from the sealed interface instance.
     */
    private String resolveEventType(dev.conductor.common.StreamJsonEvent event) {
        return switch (event) {
            case dev.conductor.common.StreamJsonEvent.SystemInitEvent ignored -> "system";
            case dev.conductor.common.StreamJsonEvent.AssistantEvent ignored -> "assistant";
            case dev.conductor.common.StreamJsonEvent.UserEvent ignored -> "user";
            case dev.conductor.common.StreamJsonEvent.RateLimitEvent ignored -> "rate_limit_event";
            case dev.conductor.common.StreamJsonEvent.ResultEvent ignored -> "result";
            case dev.conductor.common.StreamJsonEvent.ParseErrorEvent ignored -> "parse_error";
        };
    }

    /**
     * Returns the number of currently connected WebSocket clients.
     */
    public int getConnectedClientCount() {
        return sessions.size();
    }
}
