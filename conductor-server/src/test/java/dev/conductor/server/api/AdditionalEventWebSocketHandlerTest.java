package dev.conductor.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.humaninput.HumanInputNeededEvent;
import dev.conductor.server.humaninput.HumanInputRequest;
import dev.conductor.server.queue.QueuedMessage;
import dev.conductor.server.queue.QueuedMessageEvent;
import dev.conductor.server.queue.Urgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AdditionalEventWebSocketHandler}.
 *
 * <p>Uses a {@link StubWebSocketSession} to avoid Mockito interface mocking
 * issues on JDK 25+. The stub records sent messages for assertion.
 */
class AdditionalEventWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private AdditionalEventWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        handler = new AdditionalEventWebSocketHandler(objectMapper);
    }

    // ─── Connection Management ────────────────────────────────────────

    @Test
    @DisplayName("Connected client count starts at 0")
    void initialCountIsZero() {
        assertEquals(0, handler.getConnectedClientCount());
    }

    @Test
    @DisplayName("afterConnectionEstablished adds session")
    void connectionEstablished() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession();
        handler.afterConnectionEstablished(session);

        assertEquals(1, handler.getConnectedClientCount());
    }

    @Test
    @DisplayName("afterConnectionClosed removes session")
    void connectionClosed() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession();
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(0, handler.getConnectedClientCount());
    }

    @Test
    @DisplayName("handleTransportError removes session")
    void transportError() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession();
        handler.afterConnectionEstablished(session);
        handler.handleTransportError(session, new RuntimeException("connection lost"));

        assertEquals(0, handler.getConnectedClientCount());
    }

    // ─── HumanInputNeededEvent ────────────────────────────────────────

    @Test
    @DisplayName("HumanInputNeeded event is broadcast with correct type")
    void humanInputNeededBroadcast() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession();
        handler.afterConnectionEstablished(session);

        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(
                agentId, "test-agent", "Which approach?", "PATTERN_MATCH", 0.8);
        HumanInputNeededEvent event = new HumanInputNeededEvent(request);

        handler.onHumanInputNeeded(event);

        assertEquals(1, session.sentMessages.size());
        String json = session.sentMessages.get(0).getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(json, Map.class);
        assertEquals("human_input_needed", payload.get("type"));
        assertNotNull(payload.get("request"));
    }

    @Test
    @DisplayName("HumanInputNeeded is not broadcast when no sessions")
    void humanInputNeededNoSessions() {
        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(
                agentId, "test-agent", "question", "PATTERN_MATCH", 0.8);
        HumanInputNeededEvent event = new HumanInputNeededEvent(request);

        // Should not throw
        handler.onHumanInputNeeded(event);
    }

    // ─── QueuedMessageEvent ───────────────────────────────────────────

    @Test
    @DisplayName("QueuedMessage event is broadcast with correct type")
    void queuedMessageBroadcast() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession();
        handler.afterConnectionEstablished(session);

        QueuedMessage message = new QueuedMessage(
                UUID.randomUUID(), "test-agent", "Tool use: bash",
                Urgency.MEDIUM, "tool_use", Instant.now(), "abc123", null);
        QueuedMessageEvent event = new QueuedMessageEvent(message);

        handler.onQueuedMessage(event);

        assertEquals(1, session.sentMessages.size());
        String json = session.sentMessages.get(0).getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(json, Map.class);
        assertEquals("queued_message", payload.get("type"));
        assertNotNull(payload.get("message"));
    }

    @Test
    @DisplayName("QueuedMessage is not broadcast when no sessions")
    void queuedMessageNoSessions() {
        QueuedMessage message = new QueuedMessage(
                UUID.randomUUID(), "test-agent", "text",
                Urgency.LOW, "text", Instant.now(), "hash", null);
        QueuedMessageEvent event = new QueuedMessageEvent(message);

        // Should not throw
        handler.onQueuedMessage(event);
    }

    // ─── Multi-Session Broadcast ──────────────────────────────────────

    @Test
    @DisplayName("Events are broadcast to all connected sessions")
    void broadcastToMultipleSessions() throws Exception {
        StubWebSocketSession session1 = new StubWebSocketSession();
        StubWebSocketSession session2 = new StubWebSocketSession();
        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);

        QueuedMessage message = new QueuedMessage(
                UUID.randomUUID(), "test-agent", "text",
                Urgency.HIGH, "text", Instant.now(), "hash", null);
        handler.onQueuedMessage(new QueuedMessageEvent(message));

        assertEquals(1, session1.sentMessages.size());
        assertEquals(1, session2.sentMessages.size());
    }

    @Test
    @DisplayName("Closed sessions are skipped during broadcast")
    void closedSessionSkipped() throws Exception {
        StubWebSocketSession openSession = new StubWebSocketSession();
        StubWebSocketSession closedSession = new StubWebSocketSession();
        closedSession.setOpen(false);

        handler.afterConnectionEstablished(openSession);
        handler.afterConnectionEstablished(closedSession);

        QueuedMessage message = new QueuedMessage(
                UUID.randomUUID(), "test-agent", "text",
                Urgency.HIGH, "text", Instant.now(), "hash", null);
        handler.onQueuedMessage(new QueuedMessageEvent(message));

        assertEquals(1, openSession.sentMessages.size());
        assertTrue(closedSession.sentMessages.isEmpty());
    }

    // ─── Test Double ──────────────────────────────────────────────────

    /**
     * Minimal WebSocketSession stub that records sent messages.
     * Avoids Mockito interface mocking on JDK 25+.
     */
    static class StubWebSocketSession implements WebSocketSession {

        final List<TextMessage> sentMessages = new ArrayList<>();
        private final String id = UUID.randomUUID().toString();
        private boolean open = true;

        void setOpen(boolean open) {
            this.open = open;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage tm) {
                sentMessages.add(tm);
            }
        }

        @Override public java.net.URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return new org.springframework.http.HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return Map.of(); }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<org.springframework.web.socket.WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
