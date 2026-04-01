package dev.conductor.server.notification;

import dev.conductor.server.queue.QueuedMessage;
import dev.conductor.server.queue.QueuedMessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QueueEventBridge} — the adapter between the queue domain's
 * event type and the notification domain's routing pipeline.
 */
class QueueEventBridgeTest {

    private DndManager dndManager;
    private RecordingChannel websocketChannel;
    private RecordingChannel desktopChannel;
    private NotificationRouter router;
    private QueueEventBridge bridge;

    @BeforeEach
    void setUp() {
        dndManager = new DndManager();
        websocketChannel = new RecordingChannel("websocket");
        desktopChannel = new RecordingChannel("desktop");
        router = new NotificationRouter(dndManager, List.of(websocketChannel, desktopChannel));
        bridge = new QueueEventBridge(router);
    }

    // ─── Urgency mapping ─────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(dev.conductor.server.queue.Urgency.class)
    @DisplayName("All queue urgency levels are mapped to notification urgency by name")
    void allUrgenciesAreMapped(dev.conductor.server.queue.Urgency queueUrgency) {
        QueuedMessage message = queueMessage(queueUrgency);
        QueuedMessageEvent event = new QueuedMessageEvent(message);

        // Should not throw for any known urgency
        assertDoesNotThrow(() -> bridge.onQueueEvent(event));
    }

    @Test
    @DisplayName("CRITICAL queue event routes to websocket + desktop")
    void criticalRoutesToBothChannels() {
        QueuedMessageEvent event = new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.CRITICAL));

        bridge.onQueueEvent(event);

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
    }

    @Test
    @DisplayName("HIGH queue event routes to websocket + desktop")
    void highRoutesToBothChannels() {
        QueuedMessageEvent event = new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.HIGH));

        bridge.onQueueEvent(event);

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
    }

    @Test
    @DisplayName("MEDIUM queue event routes to websocket only")
    void mediumRoutesToWebsocketOnly() {
        QueuedMessageEvent event = new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.MEDIUM));

        bridge.onQueueEvent(event);

        assertEquals(1, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("LOW queue event routes to websocket only")
    void lowRoutesToWebsocketOnly() {
        QueuedMessageEvent event = new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.LOW));

        bridge.onQueueEvent(event);

        assertEquals(1, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("NOISE queue event is dropped")
    void noiseIsDropped() {
        QueuedMessageEvent event = new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.NOISE));

        bridge.onQueueEvent(event);

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    // ─── DND integration through bridge ──────────────────────────────

    @Test
    @DisplayName("DND suppresses HIGH through bridge")
    void dndSuppressesHighThroughBridge() {
        dndManager.enable();

        bridge.onQueueEvent(new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.HIGH)));

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("DND does NOT suppress CRITICAL through bridge")
    void dndDoesNotSuppressCriticalThroughBridge() {
        dndManager.enable();

        bridge.onQueueEvent(new QueuedMessageEvent(
                queueMessage(dev.conductor.server.queue.Urgency.CRITICAL)));

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
    }

    // ─── Payload fidelity ────────────────────────────────────────────

    @Test
    @DisplayName("Bridge preserves agentId, text, and timestamp from queue message")
    void payloadFieldsArePreserved() {
        UUID agentId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-03-31T12:00:00Z");
        String text = "Agent needs human input";

        QueuedMessage message = new QueuedMessage(
                agentId, "test-agent", text,
                dev.conductor.server.queue.Urgency.CRITICAL,
                "tool_use", timestamp, "hash123", null);

        bridge.onQueueEvent(new QueuedMessageEvent(message));

        assertEquals(1, websocketChannel.received.size());
        NotificationPayload payload = websocketChannel.received.get(0);
        assertEquals(agentId, payload.agentId());
        assertEquals(text, payload.text());
        assertEquals(Urgency.CRITICAL, payload.urgency());
        assertEquals(timestamp, payload.timestamp());
    }

    // ─── Error isolation ─────────────────────────────────────────────

    @Test
    @DisplayName("Bridge does not propagate exceptions from router")
    void bridgeDoesNotPropagateExceptions() {
        // Router with a failing channel
        NotificationChannel failingChannel = new NotificationChannel() {
            @Override
            public void send(NotificationPayload payload) {
                throw new RuntimeException("Boom");
            }

            @Override
            public String channelName() {
                return "websocket";
            }
        };

        NotificationRouter failingRouter = new NotificationRouter(
                dndManager, List.of(failingChannel, desktopChannel));
        QueueEventBridge failingBridge = new QueueEventBridge(failingRouter);

        // The router catches per-channel exceptions, so this should still work
        // and desktop should still receive the notification
        assertDoesNotThrow(() -> failingBridge.onQueueEvent(
                new QueuedMessageEvent(queueMessage(dev.conductor.server.queue.Urgency.CRITICAL))));
        assertEquals(1, desktopChannel.received.size());
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private static QueuedMessage queueMessage(dev.conductor.server.queue.Urgency urgency) {
        return new QueuedMessage(
                UUID.randomUUID(),
                "test-agent",
                "Test message",
                urgency,
                "test_category",
                Instant.now(),
                "hash",
                null
        );
    }

    /**
     * Test double that records all payloads sent to it.
     */
    static class RecordingChannel implements NotificationChannel {
        final String name;
        final List<NotificationPayload> received = new ArrayList<>();

        RecordingChannel(String name) {
            this.name = name;
        }

        @Override
        public void send(NotificationPayload payload) {
            received.add(payload);
        }

        @Override
        public String channelName() {
            return name;
        }
    }
}
