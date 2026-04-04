package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bootstrap behavior and feedback integration in {@link BehaviorModelBuilder}.
 * Validates that first-run scenarios produce a sensible bootstrap model, and that
 * non-empty logs use the standard model-building logic instead.
 */
class BehaviorModelBuilderBootstrapTest {

    @TempDir
    Path tempDir;

    private BehaviorLog behaviorLog;
    private BrainFeedbackStore feedbackStore;
    private BehaviorModelBuilder builder;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("behavior-log.jsonl").toString();
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, logPath, 100000);
        behaviorLog = new BehaviorLog(objectMapper, props);

        Path feedbackPath = tempDir.resolve("brain-feedback.jsonl");
        feedbackStore = new BrainFeedbackStore(objectMapper, feedbackPath);

        builder = new BehaviorModelBuilder(behaviorLog, feedbackStore);
    }

    // ─── Bootstrap model from empty log ──────────────────────────────

    @Test
    void emptyLog_returnsBootstrapModel() {
        BehaviorModel model = builder.build();

        // Bootstrap model should have non-zero defaults
        assertEquals(0.8, model.overallApprovalRate(), 0.01);
        assertEquals(15, model.averageResponseWordCount());
        assertEquals(0.1, model.dismissalRate(), 0.01);
        assertNotNull(model.lastUpdatedAt());
        assertTrue(model.responsePatterns().isEmpty());
    }

    @Test
    void bootstrap_hasAutoApprovePatterns() {
        BehaviorModel model = builder.build();

        assertFalse(model.autoApprovePatterns().isEmpty());
        assertTrue(model.autoApprovePatterns().contains("should i proceed"));
        assertTrue(model.autoApprovePatterns().contains("should i continue"));
        assertTrue(model.autoApprovePatterns().contains("shall i go ahead"));
        assertTrue(model.autoApprovePatterns().contains("which approach"));
        assertTrue(model.autoApprovePatterns().contains("can i proceed"));
        assertTrue(model.autoApprovePatterns().contains("should i create"));
        assertTrue(model.autoApprovePatterns().contains("should i add"));
        assertTrue(model.autoApprovePatterns().contains("should i use"));
        assertTrue(model.autoApprovePatterns().contains("should i implement"));
    }

    @Test
    void bootstrap_hasAlwaysEscalatePatterns() {
        BehaviorModel model = builder.build();

        assertFalse(model.alwaysEscalatePatterns().isEmpty());
        assertTrue(model.alwaysEscalatePatterns().contains("delete"));
        assertTrue(model.alwaysEscalatePatterns().contains("remove"));
        assertTrue(model.alwaysEscalatePatterns().contains("git push"));
        assertTrue(model.alwaysEscalatePatterns().contains("force push"));
        assertTrue(model.alwaysEscalatePatterns().contains("drop table"));
        assertTrue(model.alwaysEscalatePatterns().contains("rm -rf"));
        assertTrue(model.alwaysEscalatePatterns().contains("deploy"));
        assertTrue(model.alwaysEscalatePatterns().contains("publish"));
        assertTrue(model.alwaysEscalatePatterns().contains("release"));
        assertTrue(model.alwaysEscalatePatterns().contains("production"));
    }

    // ─── Non-empty log uses standard model ───────────────────────────

    @Test
    void nonEmptyLog_doesNotBootstrap() {
        // Add some behavior events to the log
        appendEvent("RESPONDED", "Should I refactor?", "yes");
        appendEvent("RESPONDED", "Should I refactor?", "yes");
        appendEvent("RESPONDED", "Should I refactor?", "yes");
        appendEvent("DISMISSED", "Should I delete?", null);

        // Invalidate the cache to force rebuild
        builder.invalidateCache();

        BehaviorModel model = builder.build();

        // Non-empty log should compute rates from actual data (3 responded, 1 dismissed)
        assertEquals(0.75, model.overallApprovalRate(), 0.01);
        assertEquals(0.25, model.dismissalRate(), 0.01);
        // Bootstrap auto-approve patterns should NOT be present
        assertFalse(model.autoApprovePatterns().contains("should i proceed"));
        assertFalse(model.autoApprovePatterns().contains("shall i go ahead"));
    }

    @Test
    void nonEmptyLog_hasResponsePatterns() {
        appendEvent("RESPONDED", "Should I refactor?", "yes");
        appendEvent("RESPONDED", "Should I refactor?", "yes");
        appendEvent("RESPONDED", "Should I refactor?", "yes");

        builder.invalidateCache();
        BehaviorModel model = builder.build();

        // The model should have response patterns from the log
        assertFalse(model.responsePatterns().isEmpty());
    }

    // ─── Feedback integration ────────────────────────────────────────

    @Test
    void feedback_bad_addsToAlwaysEscalate() {
        // Use non-empty log to avoid bootstrap
        appendEvent("RESPONDED", "general question", "yes");

        // Add BAD feedback
        feedbackStore.append(new BrainFeedback(
                null, "req-1", "RESPOND", "Delete the old module",
                "BAD", "Should have escalated", null
        ));

        builder.invalidateCache();
        BehaviorModel model = builder.build();

        // The normalized pattern should be in alwaysEscalate
        assertTrue(model.alwaysEscalatePatterns().contains("delete the old module"));
    }

    @Test
    void feedback_good_addsToAutoApprove() {
        // Use non-empty log to avoid bootstrap
        appendEvent("RESPONDED", "general question", "yes");

        // Add GOOD feedback
        feedbackStore.append(new BrainFeedback(
                null, "req-1", "RESPOND", "Yes proceed with refactoring",
                "GOOD", null, null
        ));

        builder.invalidateCache();
        BehaviorModel model = builder.build();

        // The normalized pattern should be in autoApprove
        assertTrue(model.autoApprovePatterns().contains("yes proceed with refactoring"));
    }

    @Test
    void feedback_neutral_noEffect() {
        appendEvent("RESPONDED", "general question", "yes");

        feedbackStore.append(new BrainFeedback(
                null, "req-1", "RESPOND", "Some response",
                "NEUTRAL", null, null
        ));

        builder.invalidateCache();
        BehaviorModel model = builder.build();

        // NEUTRAL should not add to either set
        assertFalse(model.autoApprovePatterns().contains("some response"));
        assertFalse(model.alwaysEscalatePatterns().contains("some response"));
    }

    @Test
    void feedback_bad_overridesGood() {
        appendEvent("RESPONDED", "general question", "yes");

        // First GOOD feedback
        feedbackStore.append(new BrainFeedback(
                null, "req-1", "RESPOND", "Auto delete backup files",
                "GOOD", null, null
        ));
        // Then BAD feedback for same pattern
        feedbackStore.append(new BrainFeedback(
                null, "req-2", "RESPOND", "Auto delete backup files",
                "BAD", "Never auto-delete", null
        ));

        builder.invalidateCache();
        BehaviorModel model = builder.build();

        // BAD should win (processed in order, BAD comes after GOOD)
        assertTrue(model.alwaysEscalatePatterns().contains("auto delete backup files"));
        assertFalse(model.autoApprovePatterns().contains("auto delete backup files"));
    }

    @Test
    void noFeedbackStore_buildStillWorks() {
        // Create builder without feedback store
        BehaviorModelBuilder builderNoFeedback =
                new BehaviorModelBuilder(behaviorLog, null);

        // Empty log returns bootstrap
        BehaviorModel model = builderNoFeedback.build();
        assertNotNull(model);
        assertTrue(model.autoApprovePatterns().contains("should i proceed"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private void appendEvent(String type, String question, String response) {
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), type, "agent-1", "FEATURE_ENGINEER",
                "/home/project", question, response, 5000L,
                Map.of("requestId", "req-1", "detectionMethod", "PATTERN_MATCH",
                        "urgency", "NORMAL")
        ));
    }
}
