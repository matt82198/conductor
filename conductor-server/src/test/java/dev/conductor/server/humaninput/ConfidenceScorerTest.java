package dev.conductor.server.humaninput;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfidenceScorer} — signal combination logic.
 */
class ConfidenceScorerTest {

    @Test
    void combine_emptyList_returnsZero() {
        assertEquals(0.0, ConfidenceScorer.combine(List.of()));
    }

    @Test
    void combine_nullList_returnsZero() {
        assertEquals(0.0, ConfidenceScorer.combine(null));
    }

    @Test
    void combine_singleSignal_returnsItsConfidence() {
        var signals = List.of(new ConfidenceScorer.Signal("TOOL_USE", 0.85));
        assertEquals(0.85, ConfidenceScorer.combine(signals), 0.001);
    }

    @Test
    void combine_multipleSignals_returnsMax() {
        var signals = List.of(
                new ConfidenceScorer.Signal("STALL", 0.5),
                new ConfidenceScorer.Signal("PATTERN_MATCH", 0.8),
                new ConfidenceScorer.Signal("PERMISSION_DENIAL", 0.7)
        );
        assertEquals(0.8, ConfidenceScorer.combine(signals), 0.001);
    }

    @Test
    void combine_twoSignals_returnsMax() {
        var a = new ConfidenceScorer.Signal("A", 0.3);
        var b = new ConfidenceScorer.Signal("B", 0.9);
        assertEquals(0.9, ConfidenceScorer.combine(a, b), 0.001);
    }

    @Test
    void dominant_returnsHighestConfidenceSignal() {
        var signals = List.of(
                new ConfidenceScorer.Signal("LOW", 0.2),
                new ConfidenceScorer.Signal("HIGH", 0.95),
                new ConfidenceScorer.Signal("MID", 0.5)
        );
        var dominant = ConfidenceScorer.dominant(signals);
        assertNotNull(dominant);
        assertEquals("HIGH", dominant.source());
        assertEquals(0.95, dominant.confidence(), 0.001);
    }

    @Test
    void dominant_emptyList_returnsNull() {
        assertNull(ConfidenceScorer.dominant(List.of()));
    }

    @Test
    void dominant_nullList_returnsNull() {
        assertNull(ConfidenceScorer.dominant(null));
    }

    @Test
    void signal_clampsConfidenceToRange() {
        var tooHigh = new ConfidenceScorer.Signal("X", 1.5);
        assertEquals(1.0, tooHigh.confidence(), 0.001);

        var tooLow = new ConfidenceScorer.Signal("Y", -0.3);
        assertEquals(0.0, tooLow.confidence(), 0.001);
    }

    @Test
    void signal_rejectsBlankSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceScorer.Signal("", 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceScorer.Signal("  ", 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceScorer.Signal(null, 0.5));
    }
}
