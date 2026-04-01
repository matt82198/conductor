package dev.conductor.server.queue;

import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageClassifier}.
 * Validates all classification rules from the CLAUDE.md spec.
 */
class MessageClassifierTest {

    private MessageClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new MessageClassifier();
    }

    // ─── CRITICAL ──────────────────────────────────────────────────────

    @Test
    void askUserQuestion_tool_is_critical() {
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "tool_use", null, "AskUserQuestion", "tool1",
                Map.of("question", "What should I do?"),
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.CRITICAL, result.urgency());
        assertEquals("ask_user", result.category());
        assertTrue(result.text().contains("What should I do?"));
    }

    @Test
    void askUserQuestion_without_question_field_still_critical() {
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "tool_use", null, "AskUserQuestion", "tool1",
                Map.of(),
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.CRITICAL, result.urgency());
        assertEquals("ask_user", result.category());
        assertTrue(result.text().contains("AskUserQuestion"));
    }

    // ─── HIGH ──────────────────────────────────────────────────────────

    @Test
    void resultEvent_with_error_is_high() {
        ResultEvent event = new ResultEvent(
                "session1", "uuid1", "error", true,
                0.05, 1000, 800, 5, "Something broke", "error",
                100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.HIGH, result.urgency());
        assertEquals("error", result.category());
        assertTrue(result.text().contains("Task failed"));
    }

    @Test
    void resultEvent_success_is_high_task_complete() {
        ResultEvent event = new ResultEvent(
                "session1", "uuid1", "success", false,
                0.12, 5000, 4000, 10, "Done!", "end_turn",
                500, 200, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.HIGH, result.urgency());
        assertEquals("task_complete", result.category());
        assertTrue(result.text().contains("Task completed"));
    }

    @Test
    void resultEvent_success_without_text_shows_cost() {
        ResultEvent event = new ResultEvent(
                "session1", "uuid1", "success", false,
                0.1234, 5000, 4000, 10, null, "end_turn",
                500, 200, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.HIGH, result.urgency());
        assertTrue(result.text().contains("$0.1234"));
    }

    // ─── MEDIUM ────────────────────────────────────────────────────────

    @Test
    void assistant_tool_use_is_medium() {
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "tool_use", null, "Read", "tool1",
                Map.of("path", "/some/file.txt"),
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.MEDIUM, result.urgency());
        assertEquals("tool_use", result.category());
        assertTrue(result.text().contains("Read"));
    }

    @Test
    void assistant_text_is_medium() {
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "text", "Here is my analysis of the code.", null, null, null,
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.MEDIUM, result.urgency());
        assertEquals("text", result.category());
        assertTrue(result.text().contains("analysis"));
    }

    // ─── LOW ───────────────────────────────────────────────────────────

    @Test
    void assistant_thinking_is_low() {
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "thinking", "Let me analyze this...", null, null, null,
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.LOW, result.urgency());
        assertEquals("thinking", result.category());
        assertTrue(result.text().contains("Thinking"));
    }

    @Test
    void parseErrorEvent_is_low() {
        ParseErrorEvent event = new ParseErrorEvent(
                "session1", "uuid1", "Unexpected token", "{bad json}"
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.LOW, result.urgency());
        assertEquals("parse_error", result.category());
    }

    @Test
    void userEvent_tool_result_is_low() {
        UserEvent event = new UserEvent(
                "session1", "uuid1", "tool1", "file contents here",
                false, "2026-03-31T00:00:00Z", ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.LOW, result.urgency());
        assertEquals("tool_result", result.category());
    }

    // ─── NOISE ─────────────────────────────────────────────────────────

    @Test
    void rateLimitEvent_is_noise() {
        RateLimitEvent event = new RateLimitEvent(
                "session1", "uuid1", "allowed", 0, "api", false, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.NOISE, result.urgency());
        assertEquals("rate_limit", result.category());
    }

    @Test
    void systemInitEvent_is_noise() {
        SystemInitEvent event = new SystemInitEvent(
                "session1", "uuid1", "claude-opus-4",
                java.util.List.of("Read", "Write", "Bash"),
                "1.0.0", "/home/user", "auto", ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals(Urgency.NOISE, result.urgency());
        assertEquals("system_init", result.category());
    }

    // ─── Edge cases ────────────────────────────────────────────────────

    @Test
    void long_text_is_truncated() {
        String longText = "x".repeat(500);
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "text", longText, null, null, null,
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertTrue(result.text().length() <= 203); // 200 + "..."
        assertTrue(result.text().endsWith("..."));
    }

    @Test
    void null_text_shows_empty_marker() {
        AssistantEvent event = new AssistantEvent(
                "session1", "uuid1", "msg1", "claude-4",
                "text", null, null, null, null,
                null, 100, 50, ""
        );

        MessageClassifier.Classification result = classifier.classify(event);

        assertEquals("(empty)", result.text());
    }
}
