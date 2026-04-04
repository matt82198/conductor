package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.humaninput.HumanInputRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supplementary tests for {@link BehaviorModelBuilder} covering caching behavior,
 * edge cases in pattern matching, and confidence scoring that are not covered
 * by the base test class.
 */
class BehaviorModelBuilderCacheTest {

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
        builder = new BehaviorModelBuilder(behaviorLog, null);
    }

    // ─── Caching ─────────────────────────────────────────────────────

    @Test
    @DisplayName("build() returns cached model within 60-second TTL window")
    void build_caches60Seconds_returnsSameModel() {
        appendEvent("RESPONDED", "question", "answer");

        BehaviorModel first = builder.build();
        BehaviorModel second = builder.build();

        // Within the cache window, the same object should be returned
        assertSame(first, second, "Second call within cache TTL should return the same object");
        assertEquals(first.lastUpdatedAt(), second.lastUpdatedAt(),
                "lastUpdatedAt should be identical for cached model");
    }

    @Test
    @DisplayName("build() after data change within cache TTL still returns cached model")
    void build_cachedEvenAfterNewData() {
        appendEvent("RESPONDED", "q1", "a1");
        BehaviorModel first = builder.build();

        // Add more data
        appendEvent("RESPONDED", "q2", "a2");
        BehaviorModel second = builder.build();

        // Still within cache window, so same object
        assertSame(first, second,
                "Should still return cached model even after new data is added within TTL");
    }

    // ─── High-confidence exact match ─────────────────────────────────

    @Test
    @DisplayName("findMatch returns high confidence for frequently and identically answered question")
    void findMatch_exactIdenticalResponses_highConfidence() {
        String question = "Should I proceed with the file write?";
        // 10 identical responses should produce very high confidence
        for (int i = 0; i < 10; i++) {
            appendEvent("RESPONDED", question, "yes proceed");
        }

        BehaviorModel model = builder.build();
        HumanInputRequest request = createRequest(question);
        BehaviorMatch match = builder.findMatch(request, model);

        assertNotNull(match, "Should find a match for 10 identical responses");
        assertEquals("yes proceed", match.suggestedResponse());
        assertTrue(match.confidence() >= 0.9,
                "10 identical responses should yield confidence >= 0.9, got: " + match.confidence());
    }

    // ─── Always-escalate overrides auto-approve ──────────────────────

    @Test
    @DisplayName("findMatch returns null for questions matching always-escalate patterns")
    void findMatch_alwaysEscalatePattern_overridesAutoApprove() {
        String question = "Should I delete the database?";
        String key = BehaviorModelBuilder.normalizeKey(question);

        // Build a model with the question in both auto-approve and always-escalate
        BehaviorModel model = new BehaviorModel(
                Map.of(key, List.of("yes", "yes", "yes")),
                1.0,
                Map.of(),
                1,
                0.0,
                Set.of(key),
                Set.of(key), // always-escalate should override
                Instant.now()
        );

        HumanInputRequest request = createRequest(question);
        BehaviorMatch match = builder.findMatch(request, model);

        assertNull(match, "Always-escalate should override auto-approve and return null");
    }

    // ─── Direct response pattern match (3+ responses, not in auto-approve) ─

    @Test
    @DisplayName("findMatch returns match from direct response patterns when 3+ consistent responses exist")
    void findMatch_directResponsePatternMatch() {
        // Add 3+ consistent responses with same first word
        String question = "Should I refactor this method?";
        for (int i = 0; i < 4; i++) {
            appendEvent("RESPONDED", question, "yes refactor it");
        }

        BehaviorModel model = builder.build();
        HumanInputRequest request = createRequest(question);
        BehaviorMatch match = builder.findMatch(request, model);

        assertNotNull(match, "Should find a match for 4 consistent responses");
        assertTrue(match.confidence() >= 0.5);
    }

    // ─── Approval rate by tool ───────────────────────────────────────

    @Test
    @DisplayName("build() computes per-tool approval rates from metadata detectionMethod")
    void build_computesApprovalRateByTool() {
        // 2 TOOL_USE responses, 1 TOOL_USE dismissal
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "a1", null, null, "q1", "yes", 1000L,
                Map.of("detectionMethod", "TOOL_USE")
        ));
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "a2", null, null, "q2", "yes", 1000L,
                Map.of("detectionMethod", "TOOL_USE")
        ));
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "DISMISSED", "a3", null, null, "q3", null, 0L,
                Map.of("detectionMethod", "TOOL_USE")
        ));
        // 1 PATTERN_MATCH response
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "a4", null, null, "q4", "ok", 1000L,
                Map.of("detectionMethod", "PATTERN_MATCH")
        ));

        BehaviorModel model = builder.build();

        assertTrue(model.approvalRateByTool().containsKey("TOOL_USE"));
        assertEquals(2.0 / 3.0, model.approvalRateByTool().get("TOOL_USE"), 0.01);
        assertTrue(model.approvalRateByTool().containsKey("PATTERN_MATCH"));
        assertEquals(1.0, model.approvalRateByTool().get("PATTERN_MATCH"), 0.01);
    }

    // ─── Empty and null response text ────────────────────────────────

    @Test
    @DisplayName("build() handles events with null response text gracefully")
    void build_nullResponseText_handledGracefully() {
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "a1", null, null, "question", null, 1000L,
                Map.of("detectionMethod", "UNKNOWN")
        ));

        BehaviorModel model = builder.build();

        // Should still count as responded for approval rate
        assertEquals(1.0, model.overallApprovalRate(), 0.01);
        // But should not crash on null response text in word count
        assertEquals(0, model.averageResponseWordCount());
    }

    @Test
    @DisplayName("build() handles events with blank response text in word count")
    void build_blankResponseText_handledInWordCount() {
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "a1", null, null, "question", "   ", 1000L,
                Map.of("detectionMethod", "UNKNOWN")
        ));

        BehaviorModel model = builder.build();

        assertEquals(1.0, model.overallApprovalRate(), 0.01);
        assertEquals(0, model.averageResponseWordCount());
    }

    // ─── Helpers ─────────────────────────────────────────────────────

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
