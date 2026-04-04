package dev.conductor.server.brain.behavior;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of user feedback on a Brain decision.
 *
 * <p>When the Brain auto-responds or escalates a human input request, the user
 * can rate the decision. This feedback is stored in an append-only JSONL file
 * and fed back into the behavior model to improve future decisions.
 *
 * @param feedbackId    unique identifier for this feedback entry
 * @param requestId     the original HumanInputRequest ID the Brain acted on
 * @param decision      what the Brain decided: "RESPOND" or "ESCALATE"
 * @param brainResponse what the Brain said (if it responded; nullable for escalations)
 * @param rating        user's rating: "GOOD", "BAD", or "NEUTRAL"
 * @param correction    user's correction text for BAD ratings (nullable)
 * @param timestamp     when the feedback was submitted
 */
public record BrainFeedback(
        String feedbackId,
        String requestId,
        String decision,
        String brainResponse,
        String rating,
        String correction,
        Instant timestamp
) {

    /**
     * Compact constructor that assigns defaults for feedbackId and timestamp
     * when not provided.
     */
    public BrainFeedback {
        if (feedbackId == null) {
            feedbackId = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (rating == null) {
            rating = "NEUTRAL";
        }
        if (decision == null) {
            decision = "UNKNOWN";
        }
    }
}
