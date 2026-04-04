package dev.conductor.server.brain.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for decision record types: {@link BrainDecision}, {@link BrainResponseEvent},
 * and {@link BrainEscalationEvent}. Validates compact constructor defaults and field
 * clamping behavior.
 */
class BrainDecisionRecordTest {

    // ─── BrainDecision ───────────────────────────────────────────────

    @Test
    @DisplayName("BrainDecision: null action defaults to ESCALATE")
    void brainDecision_nullAction_defaultsToEscalate() {
        BrainDecision decision = new BrainDecision(null, "response", 0.5, "reason");
        assertEquals("ESCALATE", decision.action());
    }

    @Test
    @DisplayName("BrainDecision: blank action defaults to ESCALATE")
    void brainDecision_blankAction_defaultsToEscalate() {
        BrainDecision decision = new BrainDecision("   ", "response", 0.5, "reason");
        assertEquals("ESCALATE", decision.action());
    }

    @Test
    @DisplayName("BrainDecision: confidence below 0.0 clamped to 0.0")
    void brainDecision_negativConfidence_clampedToZero() {
        BrainDecision decision = new BrainDecision("RESPOND", "yes", -0.5, "reason");
        assertEquals(0.0, decision.confidence());
    }

    @Test
    @DisplayName("BrainDecision: confidence above 1.0 clamped to 1.0")
    void brainDecision_overConfidence_clampedToOne() {
        BrainDecision decision = new BrainDecision("RESPOND", "yes", 1.5, "reason");
        assertEquals(1.0, decision.confidence());
    }

    @Test
    @DisplayName("BrainDecision: valid confidence is preserved")
    void brainDecision_validConfidence_preserved() {
        BrainDecision decision = new BrainDecision("RESPOND", "yes", 0.85, "pattern match");
        assertEquals("RESPOND", decision.action());
        assertEquals("yes", decision.response());
        assertEquals(0.85, decision.confidence());
        assertEquals("pattern match", decision.reasoning());
    }

    // ─── BrainResponseEvent ──────────────────────────────────────────

    @Test
    @DisplayName("BrainResponseEvent: null decidedAt defaults to Instant.now()")
    void brainResponseEvent_nullDecidedAt_defaultsToNow() {
        Instant before = Instant.now();
        BrainResponseEvent event = new BrainResponseEvent(
                "req-1", "agent-1", "yes", 0.9, "pattern match", null
        );
        Instant after = Instant.now();

        assertNotNull(event.decidedAt());
        assertFalse(event.decidedAt().isBefore(before));
        assertFalse(event.decidedAt().isAfter(after));
    }

    @Test
    @DisplayName("BrainResponseEvent: all fields preserved when non-null")
    void brainResponseEvent_allFieldsPreserved() {
        Instant ts = Instant.parse("2026-04-01T12:00:00Z");
        BrainResponseEvent event = new BrainResponseEvent(
                "req-1", "agent-1", "yes proceed", 0.95, "behavior model match", ts
        );

        assertEquals("req-1", event.requestId());
        assertEquals("agent-1", event.agentId());
        assertEquals("yes proceed", event.response());
        assertEquals(0.95, event.confidence());
        assertEquals("behavior model match", event.reasoning());
        assertEquals(ts, event.decidedAt());
    }

    // ─── BrainEscalationEvent ────────────────────────────────────────

    @Test
    @DisplayName("BrainEscalationEvent: null decidedAt defaults to Instant.now()")
    void brainEscalationEvent_nullDecidedAt_defaultsToNow() {
        Instant before = Instant.now();
        BrainEscalationEvent event = new BrainEscalationEvent(
                "req-1", "agent-1", "no match", null, 0.0, null
        );
        Instant after = Instant.now();

        assertNotNull(event.decidedAt());
        assertFalse(event.decidedAt().isBefore(before));
        assertFalse(event.decidedAt().isAfter(after));
    }

    @Test
    @DisplayName("BrainEscalationEvent: all fields preserved when non-null")
    void brainEscalationEvent_allFieldsPreserved() {
        Instant ts = Instant.parse("2026-04-01T12:00:00Z");
        BrainEscalationEvent event = new BrainEscalationEvent(
                "req-1", "agent-1", "low confidence", "maybe yes", 0.4, ts
        );

        assertEquals("req-1", event.requestId());
        assertEquals("agent-1", event.agentId());
        assertEquals("low confidence", event.reason());
        assertEquals("maybe yes", event.recommendation());
        assertEquals(0.4, event.confidence());
        assertEquals(ts, event.decidedAt());
    }

    @Test
    @DisplayName("BrainEscalationEvent: nullable recommendation allowed")
    void brainEscalationEvent_nullRecommendation_allowed() {
        BrainEscalationEvent event = new BrainEscalationEvent(
                "req-1", "agent-1", "no match", null, 0.0, Instant.now()
        );
        assertNull(event.recommendation());
    }
}
