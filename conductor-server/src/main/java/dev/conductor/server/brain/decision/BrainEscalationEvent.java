package dev.conductor.server.brain.decision;

import java.time.Instant;

/**
 * Spring application event published when the Brain escalates a request to the human.
 *
 * <p>This means the Brain could not auto-respond with sufficient confidence and is
 * passing the request through for human review. The event includes the Brain's
 * recommendation (if any) and its confidence level.
 *
 * <p>Consumed by:
 * <ul>
 *   <li>api/ — broadcasts to WebSocket clients</li>
 *   <li>notification/ — may include the Brain's recommendation in the alert</li>
 * </ul>
 *
 * @param requestId      the human input request ID being escalated
 * @param agentId        the agent that needs human input
 * @param reason         why the Brain is escalating (e.g., "No behavior pattern match")
 * @param recommendation the Brain's best guess response, if any (nullable)
 * @param confidence     how confident the Brain was in its recommendation (0.0 if none)
 * @param decidedAt      when the escalation decision was made
 */
public record BrainEscalationEvent(
        String requestId,
        String agentId,
        String reason,
        String recommendation,
        double confidence,
        Instant decidedAt
) {

    public BrainEscalationEvent {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }
}
