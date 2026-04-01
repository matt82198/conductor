package dev.conductor.server.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * A classified, filtered message ready for notification and UI display.
 *
 * <p>This record is the output of the queue pipeline: raw {@code AgentStreamEvent}s
 * enter, and {@code QueuedMessage}s exit after classification, deduplication,
 * batching, and noise filtering.
 *
 * @param agentId    the originating agent's UUID
 * @param agentName  human-readable agent name (resolved from the registry)
 * @param text       display text for the UI (tool name, error summary, thinking snippet, etc.)
 * @param urgency    classified urgency level
 * @param category   free-form category string (e.g., "tool_use", "text", "error", "task_complete")
 * @param timestamp  when the original event was received
 * @param dedupHash  SHA-256 hash used for deduplication
 * @param batchId    non-null if this message is a batch digest; null for individual messages
 */
public record QueuedMessage(
        UUID agentId,
        String agentName,
        String text,
        Urgency urgency,
        String category,
        Instant timestamp,
        String dedupHash,
        String batchId
) {

    /**
     * Compact constructor that defaults timestamp to now if null,
     * and ensures agentName is never null.
     */
    public QueuedMessage {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (agentName == null) {
            agentName = "unknown-agent";
        }
        if (category == null) {
            category = "unknown";
        }
    }
}
