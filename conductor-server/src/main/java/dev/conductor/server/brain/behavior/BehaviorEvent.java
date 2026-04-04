package dev.conductor.server.brain.behavior;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record of a single user interaction with the Conductor system.
 *
 * <p>Stored as JSON lines in the behavior log. Each event captures what happened,
 * who was involved, and how long it took — building the training data for the
 * behavior model.
 *
 * @param timestamp      when the interaction occurred
 * @param eventType      action type: RESPONDED, DISMISSED, SPAWNED, KILLED, MESSAGED, MUTED
 * @param agentId        the UUID string of the agent involved (nullable for global actions)
 * @param agentRole      the agent's role at time of event (nullable)
 * @param projectPath    the project the agent was working in (nullable)
 * @param questionText   what the agent asked, if applicable (nullable)
 * @param responseText   what the human said, if applicable (nullable)
 * @param responseTimeMs how long the human took to respond in milliseconds
 * @param metadata       additional context key-value pairs
 */
public record BehaviorEvent(
        Instant timestamp,
        String eventType,
        String agentId,
        String agentRole,
        String projectPath,
        String questionText,
        String responseText,
        long responseTimeMs,
        Map<String, String> metadata
) {

    public BehaviorEvent {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (eventType == null || eventType.isBlank()) {
            eventType = "UNKNOWN";
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
