package dev.conductor.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.brain.task.TaskProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Broadcasts {@link TaskProgressEvent}s to all connected WebSocket clients
 * via the existing {@link EventStreamWebSocketHandler}'s session pool.
 *
 * <p>This component listens for Spring application events published by
 * {@link dev.conductor.server.brain.task.TaskExecutor} and translates them
 * into WebSocket messages with the shape:
 * <pre>
 * {
 *   "type": "task_progress",
 *   "planId": "uuid",
 *   "completed": 2,
 *   "total": 5,
 *   "currentPhase": "wave-2"
 * }
 * </pre>
 *
 * <p>Uses the existing {@link EventStreamWebSocketHandler} to access the
 * shared session pool — the handler exposes its sessions through this
 * component pattern. Since the existing handler cannot be modified, this
 * broadcaster uses its own WebSocket endpoint at {@code /ws/tasks}.
 *
 * <p>Note: This component creates a separate WebSocket endpoint specifically
 * for task progress. The UI can connect to both {@code /ws/events} and
 * {@code /ws/tasks} to receive all event types.
 */
@Component
public class TaskProgressWebSocketBroadcaster extends org.springframework.web.socket.handler.TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressWebSocketBroadcaster.class);

    private final java.util.Set<WebSocketSession> sessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public TaskProgressWebSocketBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Task progress WebSocket client connected: {} (total: {})",
                session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session,
                                       org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
        log.info("Task progress WebSocket client disconnected: {} (total: {})",
                session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Task progress WebSocket transport error for {}: {}",
                session.getId(), exception.getMessage());
        sessions.remove(session);
    }

    /**
     * Receives task progress events from the Spring event bus and broadcasts
     * to all connected WebSocket clients.
     *
     * @param event the task progress event from TaskExecutor
     */
    @EventListener
    public void onTaskProgress(TaskProgressEvent event) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "task_progress");
            payload.put("planId", event.planId());
            payload.put("completed", event.completed());
            payload.put("total", event.total());
            payload.put("currentPhase", event.currentPhase());

            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(message);
                        }
                    } catch (IOException e) {
                        log.debug("Failed to send task progress to session {}: {}",
                                session.getId(), e.getMessage());
                        sessions.remove(session);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error broadcasting task progress event: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the number of currently connected WebSocket clients.
     */
    public int getConnectedClientCount() {
        return sessions.size();
    }
}
