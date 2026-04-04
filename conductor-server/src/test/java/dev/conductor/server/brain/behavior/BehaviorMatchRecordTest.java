package dev.conductor.server.brain.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BehaviorMatch} record — validates confidence clamping.
 */
class BehaviorMatchRecordTest {

    @Test
    @DisplayName("confidence below 0.0 is clamped to 0.0")
    void confidenceBelowZero_clampedToZero() {
        BehaviorMatch match = new BehaviorMatch("pattern", "response", -0.5);
        assertEquals(0.0, match.confidence());
    }

    @Test
    @DisplayName("confidence above 1.0 is clamped to 1.0")
    void confidenceAboveOne_clampedToOne() {
        BehaviorMatch match = new BehaviorMatch("pattern", "response", 1.5);
        assertEquals(1.0, match.confidence());
    }

    @Test
    @DisplayName("confidence within range is preserved")
    void confidenceInRange_preserved() {
        BehaviorMatch match = new BehaviorMatch("pattern", "response", 0.75);
        assertEquals(0.75, match.confidence());
    }

    @Test
    @DisplayName("all fields are accessible")
    void allFieldsAccessible() {
        BehaviorMatch match = new BehaviorMatch("my-pattern", "yes proceed", 0.9);
        assertEquals("my-pattern", match.matchedPattern());
        assertEquals("yes proceed", match.suggestedResponse());
        assertEquals(0.9, match.confidence());
    }
}
