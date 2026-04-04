package dev.conductor.server.brain.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BehaviorModel} record — validates compact constructor defaults.
 */
class BehaviorModelRecordTest {

    @Test
    @DisplayName("null responsePatterns defaults to empty map")
    void nullResponsePatterns_defaultsToEmptyMap() {
        BehaviorModel model = new BehaviorModel(
                null, 0.5, null, 3, 0.5, null, null, null
        );
        assertNotNull(model.responsePatterns());
        assertTrue(model.responsePatterns().isEmpty());
    }

    @Test
    @DisplayName("null approvalRateByTool defaults to empty map")
    void nullApprovalRateByTool_defaultsToEmptyMap() {
        BehaviorModel model = new BehaviorModel(
                Map.of(), 0.5, null, 3, 0.5, Set.of(), Set.of(), Instant.now()
        );
        assertNotNull(model.approvalRateByTool());
        assertTrue(model.approvalRateByTool().isEmpty());
    }

    @Test
    @DisplayName("null autoApprovePatterns defaults to empty set")
    void nullAutoApprovePatterns_defaultsToEmptySet() {
        BehaviorModel model = new BehaviorModel(
                Map.of(), 0.5, Map.of(), 3, 0.5, null, Set.of(), Instant.now()
        );
        assertNotNull(model.autoApprovePatterns());
        assertTrue(model.autoApprovePatterns().isEmpty());
    }

    @Test
    @DisplayName("null alwaysEscalatePatterns defaults to empty set")
    void nullAlwaysEscalatePatterns_defaultsToEmptySet() {
        BehaviorModel model = new BehaviorModel(
                Map.of(), 0.5, Map.of(), 3, 0.5, Set.of(), null, Instant.now()
        );
        assertNotNull(model.alwaysEscalatePatterns());
        assertTrue(model.alwaysEscalatePatterns().isEmpty());
    }

    @Test
    @DisplayName("null lastUpdatedAt defaults to Instant.now()")
    void nullLastUpdatedAt_defaultsToNow() {
        Instant before = Instant.now();
        BehaviorModel model = new BehaviorModel(
                Map.of(), 0.5, Map.of(), 3, 0.5, Set.of(), Set.of(), null
        );
        Instant after = Instant.now();

        assertNotNull(model.lastUpdatedAt());
        assertFalse(model.lastUpdatedAt().isBefore(before));
        assertFalse(model.lastUpdatedAt().isAfter(after));
    }

    @Test
    @DisplayName("all non-null fields are preserved")
    void allFieldsPreserved() {
        Instant ts = Instant.parse("2026-04-01T12:00:00Z");
        Map<String, List<String>> patterns = Map.of("key", List.of("yes", "no"));
        Map<String, Double> toolRates = Map.of("TOOL_USE", 0.8);
        Set<String> autoApprove = Set.of("pattern1");
        Set<String> alwaysEscalate = Set.of("pattern2");

        BehaviorModel model = new BehaviorModel(
                patterns, 0.75, toolRates, 5, 0.25, autoApprove, alwaysEscalate, ts
        );

        assertEquals(patterns, model.responsePatterns());
        assertEquals(0.75, model.overallApprovalRate());
        assertEquals(toolRates, model.approvalRateByTool());
        assertEquals(5, model.averageResponseWordCount());
        assertEquals(0.25, model.dismissalRate());
        assertEquals(autoApprove, model.autoApprovePatterns());
        assertEquals(alwaysEscalate, model.alwaysEscalatePatterns());
        assertEquals(ts, model.lastUpdatedAt());
    }
}
