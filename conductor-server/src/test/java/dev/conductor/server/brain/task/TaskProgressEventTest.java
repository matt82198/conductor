package dev.conductor.server.brain.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TaskProgressEvent} record.
 */
class TaskProgressEventTest {

    @Test
    void timestampDefaultsToNowWhenNull() {
        TaskProgressEvent event = new TaskProgressEvent("plan-1", 2, 5, "wave-1", null);
        assertNotNull(event.timestamp());
    }

    @Test
    void explicitTimestampIsPreserved() {
        Instant explicit = Instant.parse("2026-04-01T00:00:00Z");
        TaskProgressEvent event = new TaskProgressEvent("plan-1", 2, 5, "wave-1", explicit);
        assertEquals(explicit, event.timestamp());
    }

    @Test
    void fieldsAreAccessible() {
        TaskProgressEvent event = new TaskProgressEvent("plan-1", 3, 7, "wave-2", null);
        assertEquals("plan-1", event.planId());
        assertEquals(3, event.completed());
        assertEquals(7, event.total());
        assertEquals("wave-2", event.currentPhase());
    }
}
