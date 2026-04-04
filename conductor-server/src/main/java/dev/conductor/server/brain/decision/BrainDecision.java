package dev.conductor.server.brain.decision;

/**
 * The Brain's decision about how to handle an agent's request.
 *
 * @param action     what to do: "RESPOND" (auto-respond) or "ESCALATE" (surface to human)
 * @param response   the response text to send to the agent (null if escalating)
 * @param confidence 0.0-1.0 confidence in this decision
 * @param reasoning  human-readable explanation of why this decision was made
 */
public record BrainDecision(
        String action,
        String response,
        double confidence,
        String reasoning
) {

    public BrainDecision {
        if (action == null || action.isBlank()) {
            action = "ESCALATE";
        }
        if (confidence < 0.0) {
            confidence = 0.0;
        } else if (confidence > 1.0) {
            confidence = 1.0;
        }
    }
}
