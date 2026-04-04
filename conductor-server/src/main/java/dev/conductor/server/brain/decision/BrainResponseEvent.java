package dev.conductor.server.brain.decision;

import java.time.Instant;

/**
 * Spring application event published when the Brain auto-responds to an agent's request.
 *
 * <p>Consumed by:
 * <ul>
 *   <li>api/ — broadcasts to WebSocket clients for UI display</li>
 *   <li>notification/ — informational alert that the Brain acted</li>
 * </ul>
 *
 * @param requestId  the human input request ID that was auto-responded
 * @param agentId    the agent that received the response
 * @param response   the response text that was sent
 * @param confidence the confidence score of the decision
 * @param reasoning  explanation of why the Brain chose this response
 * @param decidedAt  when the decision was made
 */
public record BrainResponseEvent(
        String requestId,
        String agentId,
        String response,
        double confidence,
        String reasoning,
        Instant decidedAt
) {

    public BrainResponseEvent {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }
}
