package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.humaninput.HumanInputRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BehaviorModelBuilder}.
 * Validates model building, pattern matching, confidence scoring, and caching.
 */
class BehaviorModelBuilderTest {

    @TempDir
    Path tempDir;

    private BehaviorLog behaviorLog;
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
        builder = new BehaviorModelBuilder(behaviorLog);
    }

    // ─── Empty model ──────────────────────────────────────────────────

    @Test
    void empty_log_produces_empty_model() {
        BehaviorModel model = builder.build();

        assertTrue(model.responsePatterns().isEmpty());
        assertEquals(0.0, model.overallApprovalRate());
        assertEquals(0.0, model.dismissalRate());
        assertEquals(0, model.averageResponseWordCount());
        assertTrue(model.autoApprovePatterns().isEmpty());
        assertTrue(model.alwaysEscalatePatterns().isEmpty());
        assertNotNull(model.lastUpdatedAt());
    }

    // ─── Approval rate ────────────────────────────────────────────────

    @Test
    void approval_rate_computed_correctly() {
        appendEvent("RESPONDED", "q1", "answer1");
        appendEvent("RESPONDED", "q2", "answer2");
        appendEvent("RESPONDED", "q3", "answer3");
        appendEvent("DISMISSED", "q4", null);

        BehaviorModel model = builder.build();

        assertEquals(0.75, model.overallApprovalRate(), 0.01);
        assertEquals(0.25, model.dismissalRate(), 0.01);
    }

    @Test
    void all_responded_gives_approval_rate_one() {
        appendEvent("RESPONDED", "q1", "yes");
        appendEvent("RESPONDED", "q2", "yes");

        BehaviorModel model = builder.build();

        assertEquals(1.0, model.overallApprovalRate(), 0.01);
        assertEquals(0.0, model.dismissalRate(), 0.01);
    }

    @Test
    void all_dismissed_gives_approval_rate_zero() {
        appendEvent("DISMISSED", "q1", null);
        appendEvent("DISMISSED", "q2", null);

        BehaviorModel model = builder.build();

        assertEquals(0.0, model.overallApprovalRate(), 0.01);
        assertEquals(1.0, model.dismissalRate(), 0.01);
    }

    // ─── Response patterns ────────────────────────────────────────────

    @Test
    void response_patterns_group_by_normalized_key() {
        appendEvent("RESPONDED", "Should I use approach A?", "yes, approach A");
        appendEvent("RESPONDED", "Should I use approach A?", "yes, go with A");

        BehaviorModel model = builder.build();

        String key = BehaviorModelBuilder.normalizeKey("Should I use approach A?");
        assertTrue(model.responsePatterns().containsKey(key));
        assertEquals(2, model.responsePatterns().get(key).size());
    }

    @Test
    void normalize_key_truncates_to_50_chars() {
        String longQuestion = "a".repeat(100);
        String key = BehaviorModelBuilder.normalizeKey(longQuestion);
        assertEquals(50, key.length());
    }

    @Test
    void normalize_key_lowercases() {
        String key = BehaviorModelBuilder.normalizeKey("UPPERCASE Question");
        assertEquals("uppercase question", key);
    }

    @Test
    void normalize_key_null_returns_empty() {
        assertEquals("", BehaviorModelBuilder.normalizeKey(null));
        assertEquals("", BehaviorModelBuilder.normalizeKey(""));
        assertEquals("", BehaviorModelBuilder.normalizeKey("   "));
    }

    // ─── Auto-approve patterns ────────────────────────────────────────

    @Test
    void auto_approve_detected_after_three_consistent_responses() {
        String question = "Should I proceed with the deployment?";
        appendEvent("RESPONDED", question, "yes proceed");
        appendEvent("RESPONDED", question, "yes go ahead");
        appendEvent("RESPONDED", question, "yes");

        BehaviorModel model = builder.build();

        String key = BehaviorModelBuilder.normalizeKey(question);
        assertTrue(model.autoApprovePatterns().contains(key),
                "Expected auto-approve pattern for consistently answered question");
    }

    @Test
    void no_auto_approve_with_inconsistent_responses() {
        String question = "Which approach should I take?";
        appendEvent("RESPONDED", question, "use approach A");
        appendEvent("RESPONDED", question, "go with B");
        appendEvent("RESPONDED", question, "try C this time");

        BehaviorModel model = builder.build();

        String key = BehaviorModelBuilder.normalizeKey(question);
        assertFalse(model.autoApprovePatterns().contains(key),
                "Should not auto-approve pattern with inconsistent responses");
    }

    @Test
    void no_auto_approve_with_fewer_than_three_responses() {
        appendEvent("RESPONDED", "Some question?", "yes");
        appendEvent("RESPONDED", "Some question?", "yes");

        BehaviorModel model = builder.build();

        assertTrue(model.autoApprovePatterns().isEmpty(),
                "Should not have auto-approve patterns with fewer than 3 responses");
    }

    // ─── Pattern matching ─────────────────────────────────────────────

    @Test
    void findMatch_returns_null_for_unknown_question() {
        appendEvent("RESPONDED", "Known question?", "known answer");

        BehaviorModel model = builder.build();
        HumanInputRequest request = createRequest("Completely different question");

        BehaviorMatch match = builder.findMatch(request, model);
        assertNull(match);
    }

    @Test
    void findMatch_returns_match_for_auto_approve_pattern() {
        String question = "Should I continue?";
        appendEvent("RESPONDED", question, "yes continue");
        appendEvent("RESPONDED", question, "yes go");
        appendEvent("RESPONDED", question, "yes");

        BehaviorModel model = builder.build();
        HumanInputRequest request = createRequest(question);

        BehaviorMatch match = builder.findMatch(request, model);
        assertNotNull(match, "Should find a match for auto-approve pattern");
        assertNotNull(match.suggestedResponse());
        assertTrue(match.confidence() > 0.0);
    }

    @Test
    void findMatch_returns_null_for_null_request() {
        BehaviorModel model = builder.build();
        assertNull(builder.findMatch(null, model));
    }

    @Test
    void findMatch_returns_null_for_null_model() {
        HumanInputRequest request = createRequest("test");
        assertNull(builder.findMatch(request, null));
    }

    // ─── Average word count ───────────────────────────────────────────

    @Test
    void average_word_count_computed_correctly() {
        appendEvent("RESPONDED", "q1", "yes please do that");        // 4 words
        appendEvent("RESPONDED", "q2", "no");                         // 1 word
        appendEvent("RESPONDED", "q3", "try the simpler approach");   // 4 words

        BehaviorModel model = builder.build();

        assertEquals(3, model.averageResponseWordCount()); // (4+1+4)/3 = 3
    }

    // ─── Non-decision events ignored ──────────────────────────────────

    @Test
    void spawned_and_killed_events_do_not_affect_approval_rate() {
        appendEvent("RESPONDED", "q1", "yes");
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "SPAWNED", "agent-1", "EXPLORER",
                "/project", null, "initial prompt", 0L, Map.of()
        ));
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "KILLED", "agent-2", null,
                null, null, null, 0L, Map.of()
        ));

        BehaviorModel model = builder.build();

        assertEquals(1.0, model.overallApprovalRate(), 0.01);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private void appendEvent(String eventType, String question, String response) {
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), eventType, "agent-1", null,
                null, question, response, 2000L,
                Map.of("detectionMethod", "TOOL_USE")
        ));
    }

    private HumanInputRequest createRequest(String question) {
        return new HumanInputRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "test-agent",
                question,
                List.of(),
                "",
                "NORMAL",
                Instant.now(),
                "TOOL_USE",
                0.9
        );
    }
}
