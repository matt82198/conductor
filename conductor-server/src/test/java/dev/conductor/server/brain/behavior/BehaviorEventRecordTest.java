package dev.conductor.server.brain.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BehaviorEvent} record — validates compact constructor defaults
 * and immutability guarantees.
 */
class BehaviorEventRecordTest {

    @Test
    @DisplayName("null timestamp defaults to Instant.now()")
    void nullTimestamp_defaultsToNow() {
        Instant before = Instant.now();
        BehaviorEvent event = new BehaviorEvent(
                null, "RESPONDED", "agent-1", null,
                null, null, null, 0L, null
        );
        Instant after = Instant.now();

        assertNotNull(event.timestamp());
        assertFalse(event.timestamp().isBefore(before));
        assertFalse(event.timestamp().isAfter(after));
    }

    @Test
    @DisplayName("null eventType defaults to UNKNOWN")
    void nullEventType_defaultsToUnknown() {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(), null, "agent-1", null,
                null, null, null, 0L, null
        );

        assertEquals("UNKNOWN", event.eventType());
    }

    @Test
    @DisplayName("blank eventType defaults to UNKNOWN")
    void blankEventType_defaultsToUnknown() {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(), "   ", "agent-1", null,
                null, null, null, 0L, null
        );

        assertEquals("UNKNOWN", event.eventType());
    }

    @Test
    @DisplayName("null metadata defaults to empty map")
    void nullMetadata_defaultsToEmptyMap() {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(), "RESPONDED", "agent-1", null,
                null, null, null, 0L, null
        );

        assertNotNull(event.metadata());
        assertTrue(event.metadata().isEmpty());
    }

    @Test
    @DisplayName("all fields preserved when non-null")
    void allFieldsPreserved() {
        Instant ts = Instant.parse("2026-04-01T12:00:00Z");
        Map<String, String> meta = Map.of("key", "value");

        BehaviorEvent event = new BehaviorEvent(
                ts, "RESPONDED", "agent-1", "EXPLORER",
                "/home/project", "question?", "answer!", 5000L, meta
        );

        assertEquals(ts, event.timestamp());
        assertEquals("RESPONDED", event.eventType());
        assertEquals("agent-1", event.agentId());
        assertEquals("EXPLORER", event.agentRole());
        assertEquals("/home/project", event.projectPath());
        assertEquals("question?", event.questionText());
        assertEquals("answer!", event.responseText());
        assertEquals(5000L, event.responseTimeMs());
        assertEquals("value", event.metadata().get("key"));
    }
}
