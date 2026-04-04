package dev.conductor.server.brain.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContextBudget} and {@link ContextBudget.ScoredEntry} records,
 * covering budget allocation, entry filtering, and default handling.
 */
class ContextBudgetTest {

    // -- ContextBudget creation ------------------------------------------

    @Test
    @DisplayName("withDefaults: allocates 60/20/10/10 split")
    void withDefaults_correctRatios() {
        ContextBudget budget = ContextBudget.withDefaults(100_000);

        assertEquals(100_000, budget.totalChars());
        assertEquals(60_000, budget.targetProjectChars());
        assertEquals(20_000, budget.memoriesChars());
        assertEquals(10_000, budget.agentDefsChars());
        assertEquals(10_000, budget.crossProjectChars());
    }

    @Test
    @DisplayName("withDefaults: allocatedChars approximately equals total (minus rounding)")
    void withDefaults_allocatedCharsApproximatesTotal() {
        ContextBudget budget = ContextBudget.withDefaults(200_000);
        // 0.60 + 0.20 + 0.10 + 0.10 = 1.0, so allocated should be very close
        assertTrue(budget.allocatedChars() >= 199_990,
                "Allocated should be close to total: " + budget.allocatedChars());
    }

    @Test
    @DisplayName("withDefaults: handles zero budget")
    void withDefaults_zeroBudget() {
        ContextBudget budget = ContextBudget.withDefaults(0);
        assertEquals(0, budget.totalChars());
        assertEquals(0, budget.targetProjectChars());
        assertEquals(0, budget.allocatedChars());
    }

    @Test
    @DisplayName("withDefaults: empty entries list")
    void withDefaults_emptyEntries() {
        ContextBudget budget = ContextBudget.withDefaults(100_000);
        assertTrue(budget.rankedEntries().isEmpty());
    }

    @Test
    @DisplayName("withDefaults: accepts entries list")
    void withDefaults_withEntries() {
        List<ContextBudget.ScoredEntry> entries = List.of(
                new ContextBudget.ScoredEntry("target-project", "root/CLAUDE.md", 1.0, 5000),
                new ContextBudget.ScoredEntry("memory", "feedback_rules", 0.8, 200)
        );
        ContextBudget budget = ContextBudget.withDefaults(100_000, entries);
        assertEquals(2, budget.rankedEntries().size());
    }

    // -- Compact constructor defaults ------------------------------------

    @Test
    @DisplayName("compact constructor: negative values clamped to 0")
    void compactConstructor_negativesClamped() {
        ContextBudget budget = new ContextBudget(-1, -1, -1, -1, -1, null);
        assertEquals(0, budget.totalChars());
        assertEquals(0, budget.targetProjectChars());
        assertEquals(0, budget.memoriesChars());
        assertEquals(0, budget.agentDefsChars());
        assertEquals(0, budget.crossProjectChars());
        assertNotNull(budget.rankedEntries());
        assertTrue(budget.rankedEntries().isEmpty());
    }

    // -- Category queries ------------------------------------------------

    @Test
    @DisplayName("entriesInCategory: counts entries by source")
    void entriesInCategory_correctCount() {
        List<ContextBudget.ScoredEntry> entries = List.of(
                new ContextBudget.ScoredEntry("memory", "m1", 0.9, 100),
                new ContextBudget.ScoredEntry("memory", "m2", 0.5, 200),
                new ContextBudget.ScoredEntry("agent-def", "a1", 0.7, 150),
                new ContextBudget.ScoredEntry("target-project", "p1", 1.0, 5000)
        );
        ContextBudget budget = ContextBudget.withDefaults(100_000, entries);

        assertEquals(2, budget.entriesInCategory("memory"));
        assertEquals(1, budget.entriesInCategory("agent-def"));
        assertEquals(1, budget.entriesInCategory("target-project"));
        assertEquals(0, budget.entriesInCategory("cross-project"));
    }

    @Test
    @DisplayName("charsInCategory: sums char sizes by source")
    void charsInCategory_correctSum() {
        List<ContextBudget.ScoredEntry> entries = List.of(
                new ContextBudget.ScoredEntry("memory", "m1", 0.9, 100),
                new ContextBudget.ScoredEntry("memory", "m2", 0.5, 200),
                new ContextBudget.ScoredEntry("agent-def", "a1", 0.7, 150)
        );
        ContextBudget budget = ContextBudget.withDefaults(100_000, entries);

        assertEquals(300, budget.charsInCategory("memory"));
        assertEquals(150, budget.charsInCategory("agent-def"));
        assertEquals(0, budget.charsInCategory("cross-project"));
    }

    // -- ScoredEntry record ----------------------------------------------

    @Test
    @DisplayName("ScoredEntry: null source defaults to 'unknown'")
    void scoredEntry_nullSource_defaults() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry(null, "key", 0.5, 100);
        assertEquals("unknown", entry.source());
    }

    @Test
    @DisplayName("ScoredEntry: null key defaults to 'unknown'")
    void scoredEntry_nullKey_defaults() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry("memory", null, 0.5, 100);
        assertEquals("unknown", entry.key());
    }

    @Test
    @DisplayName("ScoredEntry: negative relevance clamped to 0.0")
    void scoredEntry_negativeRelevance_clamped() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry("memory", "key", -0.5, 100);
        assertEquals(0.0, entry.relevance());
    }

    @Test
    @DisplayName("ScoredEntry: relevance above 1.0 clamped to 1.0")
    void scoredEntry_highRelevance_clamped() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry("memory", "key", 1.5, 100);
        assertEquals(1.0, entry.relevance());
    }

    @Test
    @DisplayName("ScoredEntry: negative charSize clamped to 0")
    void scoredEntry_negativeCharSize_clamped() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry("memory", "key", 0.5, -100);
        assertEquals(0, entry.charSize());
    }

    @Test
    @DisplayName("ScoredEntry: summary includes all fields")
    void scoredEntry_summary_format() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry("memory", "test_rules", 0.85, 250);
        String summary = entry.summary();
        assertTrue(summary.contains("[memory]"));
        assertTrue(summary.contains("test_rules"));
        assertTrue(summary.contains("0.85"));
        assertTrue(summary.contains("250"));
    }

    @Test
    @DisplayName("ScoredEntry: all valid fields preserved")
    void scoredEntry_allFieldsPreserved() {
        ContextBudget.ScoredEntry entry = new ContextBudget.ScoredEntry("agent-def", "test-gen", 0.72, 1500);
        assertEquals("agent-def", entry.source());
        assertEquals("test-gen", entry.key());
        assertEquals(0.72, entry.relevance(), 0.001);
        assertEquals(1500, entry.charSize());
    }

    // -- Budget ratios ---------------------------------------------------

    @Test
    @DisplayName("budget ratios sum to 1.0")
    void ratiosSumToOne() {
        double sum = ContextBudget.TARGET_PROJECT_RATIO
                + ContextBudget.MEMORIES_RATIO
                + ContextBudget.AGENT_DEFS_RATIO
                + ContextBudget.CROSS_PROJECT_RATIO;
        assertEquals(1.0, sum, 0.001);
    }
}
