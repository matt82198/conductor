package dev.conductor.server.brain.behavior;

/**
 * Result of matching a human input request against the behavior model.
 *
 * <p>If the model finds a pattern that matches the incoming request, this record
 * holds the suggested response and a confidence score indicating how certain the
 * match is.
 *
 * @param matchedPattern    description of which pattern matched (for logging/audit)
 * @param suggestedResponse the response text the model recommends sending
 * @param confidence        0.0-1.0 confidence that this is the right response
 */
public record BehaviorMatch(
        String matchedPattern,
        String suggestedResponse,
        double confidence
) {

    public BehaviorMatch {
        if (confidence < 0.0) {
            confidence = 0.0;
        } else if (confidence > 1.0) {
            confidence = 1.0;
        }
    }
}
