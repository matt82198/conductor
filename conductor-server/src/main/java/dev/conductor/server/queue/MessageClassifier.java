package dev.conductor.server.queue;

import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Classifies a {@link StreamJsonEvent} into an {@link Urgency} level and
 * a human-readable category string.
 *
 * <p>Classification rules are pattern-based, ordered by specificity:
 * <ol>
 *   <li>AssistantEvent with toolName="AskUserQuestion" -> CRITICAL / "ask_user"</li>
 *   <li>ResultEvent with isError=true -> HIGH / "error"</li>
 *   <li>ResultEvent (success) -> HIGH / "task_complete"</li>
 *   <li>AssistantEvent TOOL_USE -> MEDIUM / "tool_use"</li>
 *   <li>AssistantEvent TEXT -> MEDIUM / "text"</li>
 *   <li>AssistantEvent THINKING -> LOW / "thinking"</li>
 *   <li>RateLimitEvent -> NOISE / "rate_limit"</li>
 *   <li>SystemInitEvent -> NOISE / "system_init"</li>
 *   <li>ParseErrorEvent -> LOW / "parse_error"</li>
 *   <li>UserEvent (tool result) -> LOW / "tool_result"</li>
 * </ol>
 *
 * <p>Thread-safe: stateless, no mutable fields.
 */
@Service
public class MessageClassifier {

    private static final Logger log = LoggerFactory.getLogger(MessageClassifier.class);

    /**
     * Result of classifying a single event.
     *
     * @param urgency  the assigned urgency level
     * @param category human-readable category for grouping and display
     * @param text     display text extracted from the event
     */
    public record Classification(Urgency urgency, String category, String text) {}

    /**
     * Classifies a {@link StreamJsonEvent} into urgency, category, and display text.
     *
     * @param event the raw event from the Claude CLI stream
     * @return a classification result; never null
     */
    public Classification classify(StreamJsonEvent event) {
        return switch (event) {
            case AssistantEvent assistant -> classifyAssistant(assistant);
            case ResultEvent result -> classifyResult(result);
            case RateLimitEvent rateLimit -> new Classification(
                    Urgency.NOISE,
                    "rate_limit",
                    rateLimit.isAllowed() ? "Rate limit check: allowed" : "Rate limited until " + rateLimit.resetsAt()
            );
            case SystemInitEvent init -> new Classification(
                    Urgency.NOISE,
                    "system_init",
                    "Session started: model=" + init.model()
            );
            case ParseErrorEvent error -> new Classification(
                    Urgency.LOW,
                    "parse_error",
                    "Parse error: " + error.errorMessage()
            );
            case UserEvent user -> new Classification(
                    Urgency.LOW,
                    "tool_result",
                    user.isError()
                            ? "Tool result (error): " + truncate(user.content(), 200)
                            : "Tool result: " + truncate(user.content(), 200)
            );
        };
    }

    /**
     * Classifies an AssistantEvent by checking for AskUserQuestion first
     * (CRITICAL), then by content type.
     */
    private Classification classifyAssistant(AssistantEvent assistant) {
        // AskUserQuestion tool is always CRITICAL -- agent is blocked
        if (assistant.isToolUse() && "AskUserQuestion".equals(assistant.toolName())) {
            String question = extractQuestionText(assistant);
            return new Classification(Urgency.CRITICAL, "ask_user", question);
        }

        // Other tool uses are MEDIUM
        if (assistant.isToolUse()) {
            String toolText = assistant.toolName() != null
                    ? "Using tool: " + assistant.toolName()
                    : "Using unknown tool";
            return new Classification(Urgency.MEDIUM, "tool_use", toolText);
        }

        // Text output is MEDIUM
        if (assistant.isText()) {
            return new Classification(
                    Urgency.MEDIUM,
                    "text",
                    truncate(assistant.textContent(), 200)
            );
        }

        // Thinking is LOW
        if (assistant.isThinking()) {
            return new Classification(
                    Urgency.LOW,
                    "thinking",
                    "Thinking..." + (assistant.textContent() != null
                            ? " " + truncate(assistant.textContent(), 100)
                            : "")
            );
        }

        // Unknown content type -- default to MEDIUM
        log.debug("Unknown assistant content type: {}", assistant.contentType());
        return new Classification(Urgency.MEDIUM, "assistant_unknown", "Assistant event: " + assistant.contentType());
    }

    /**
     * Classifies a ResultEvent: errors are HIGH, success is HIGH (task complete).
     */
    private Classification classifyResult(ResultEvent result) {
        if (result.isError()) {
            return new Classification(
                    Urgency.HIGH,
                    "error",
                    "Task failed: " + truncate(result.resultText(), 200)
            );
        }
        return new Classification(
                Urgency.HIGH,
                "task_complete",
                "Task completed" + (result.resultText() != null
                        ? ": " + truncate(result.resultText(), 200)
                        : " (cost=$" + String.format("%.4f", result.totalCostUsd()) + ")")
        );
    }

    /**
     * Extracts the question text from an AskUserQuestion tool_use event.
     * Falls back to a generic message if the input is missing.
     */
    private String extractQuestionText(AssistantEvent assistant) {
        if (assistant.toolInput() != null) {
            Object question = assistant.toolInput().get("question");
            if (question != null) {
                return truncate(question.toString(), 500);
            }
        }
        return "Agent needs input (AskUserQuestion)";
    }

    /**
     * Truncates a string to the given max length, appending "..." if truncated.
     * Returns "(empty)" for null or blank strings.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
