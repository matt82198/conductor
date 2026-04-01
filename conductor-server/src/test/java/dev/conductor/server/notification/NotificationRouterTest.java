package dev.conductor.server.notification;

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
 * Tests for {@link NotificationRouter} — urgency-based routing and DND suppression.
 *
 * <p>Uses a {@link RecordingChannel} stub to verify which channels receive
 * notifications without requiring Spring context or real WebSocket connections.
 */
class NotificationRouterTest {

    private DndManager dndManager;
    private RecordingChannel websocketChannel;
    private RecordingChannel desktopChannel;
    private NotificationRouter router;

    @BeforeEach
    void setUp() {
        dndManager = new DndManager();
        websocketChannel = new RecordingChannel("websocket");
        desktopChannel = new RecordingChannel("desktop");
        router = new NotificationRouter(dndManager, List.of(websocketChannel, desktopChannel));
    }

    // ─── Routing by urgency ───────────────────────────────────────────

    @Test
    @DisplayName("CRITICAL routes to websocket + desktop")
    void criticalRoutesToBothChannels() {
        router.route(payload(Urgency.CRITICAL));

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
    }

    @Test
    @DisplayName("HIGH routes to websocket + desktop")
    void highRoutesToBothChannels() {
        router.route(payload(Urgency.HIGH));

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
    }

    @Test
    @DisplayName("MEDIUM routes to websocket only")
    void mediumRoutesToWebsocketOnly() {
        router.route(payload(Urgency.MEDIUM));

        assertEquals(1, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("LOW routes to websocket only")
    void lowRoutesToWebsocketOnly() {
        router.route(payload(Urgency.LOW));

        assertEquals(1, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("NOISE is dropped entirely")
    void noiseIsDropped() {
        router.route(payload(Urgency.NOISE));

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    // ─── DND behavior ─────────────────────────────────────────────────

    @Test
    @DisplayName("DND suppresses HIGH notifications")
    void dndSuppressesHigh() {
        dndManager.enable();
        router.route(payload(Urgency.HIGH));

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("DND suppresses MEDIUM notifications")
    void dndSuppressesMedium() {
        dndManager.enable();
        router.route(payload(Urgency.MEDIUM));

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("DND suppresses LOW notifications")
    void dndSuppressesLow() {
        dndManager.enable();
        router.route(payload(Urgency.LOW));

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    @Test
    @DisplayName("CRITICAL always delivered even during DND")
    void criticalBypassesDnd() {
        dndManager.enable();
        router.route(payload(Urgency.CRITICAL));

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
    }

    @Test
    @DisplayName("NOISE is still dropped during DND")
    void noiseStillDroppedDuringDnd() {
        dndManager.enable();
        router.route(payload(Urgency.NOISE));

        assertEquals(0, websocketChannel.received.size());
        assertEquals(0, desktopChannel.received.size());
    }

    // ─── DND toggle ───────────────────────────────────────────────────

    @Test
    @DisplayName("Disabling DND restores normal routing")
    void disablingDndRestoresRouting() {
        dndManager.enable();
        router.route(payload(Urgency.HIGH));
        assertEquals(0, websocketChannel.received.size(), "Should be suppressed during DND");

        dndManager.disable();
        router.route(payload(Urgency.HIGH));
        assertEquals(1, websocketChannel.received.size(), "Should be delivered after DND off");
        assertEquals(1, desktopChannel.received.size());
    }

    // ─── Event listener integration ───────────────────────────────────

    @Test
    @DisplayName("onQueuedMessage converts event and routes it")
    void eventListenerDelegatesToRoute() {
        QueuedMessageEvent event = new QueuedMessageEvent(
                UUID.randomUUID(), "Test message", Urgency.HIGH, "error", Instant.now());

        router.onQueuedMessage(event);

        assertEquals(1, websocketChannel.received.size());
        assertEquals(1, desktopChannel.received.size());
        assertEquals("Test message", websocketChannel.received.get(0).text());
    }

    // ─── Payload preservation ─────────────────────────────────────────

    @Test
    @DisplayName("Channel receives the exact payload that was routed")
    void payloadIsPreservedThroughRouting() {
        UUID agentId = UUID.randomUUID();
        Instant now = Instant.now();
        NotificationPayload payload = new NotificationPayload(agentId, "hello", Urgency.CRITICAL, now);

        router.route(payload);

        NotificationPayload received = websocketChannel.received.get(0);
        assertEquals(agentId, received.agentId());
        assertEquals("hello", received.text());
        assertEquals(Urgency.CRITICAL, received.urgency());
        assertEquals(now, received.timestamp());
    }

    // ─── Channel error isolation ──────────────────────────────────────

    @Test
    @DisplayName("One channel failure does not prevent delivery to other channels")
    void channelErrorIsolation() {
        FailingChannel failingWebsocket = new FailingChannel("websocket");
        NotificationRouter isolationRouter = new NotificationRouter(
                dndManager, List.of(failingWebsocket, desktopChannel));

        // Should not throw, and desktop should still receive
        assertDoesNotThrow(() -> isolationRouter.route(payload(Urgency.CRITICAL)));
        assertEquals(1, desktopChannel.received.size());
    }

    // ─── Urgency enum properties ──────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"HIGH", "MEDIUM", "LOW", "NOISE"})
    @DisplayName("Non-CRITICAL urgencies do not bypass DND")
    void nonCriticalDoNotBypassDnd(Urgency urgency) {
        assertFalse(urgency.bypassesDnd());
    }

    @Test
    @DisplayName("Only CRITICAL bypasses DND")
    void onlyCriticalBypassesDnd() {
        assertTrue(Urgency.CRITICAL.bypassesDnd());
    }

    @Test
    @DisplayName("Only NOISE is dropped")
    void onlyNoiseIsDropped() {
        assertTrue(Urgency.NOISE.isDropped());
        assertFalse(Urgency.CRITICAL.isDropped());
        assertFalse(Urgency.HIGH.isDropped());
        assertFalse(Urgency.MEDIUM.isDropped());
        assertFalse(Urgency.LOW.isDropped());
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static NotificationPayload payload(Urgency urgency) {
        return new NotificationPayload(UUID.randomUUID(), "Test notification", urgency, Instant.now());
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

    /**
     * Test double that always throws on send.
     */
    static class FailingChannel implements NotificationChannel {
        final String name;

        FailingChannel(String name) {
            this.name = name;
        }

        @Override
        public void send(NotificationPayload payload) {
            throw new RuntimeException("Simulated channel failure");
        }

        @Override
        public String channelName() {
            return name;
        }
    }
}
