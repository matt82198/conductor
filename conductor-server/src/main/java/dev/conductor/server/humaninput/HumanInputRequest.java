package dev.conductor.server.humaninput;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a detected human-input-needed event.
 *
 * <p>Created by the detection pipeline when an agent appears to be waiting
 * for user input. Queued in {@link HumanInputQueue} until a human responds
 * or the request is resolved by other means.
 *
 * @param requestId        unique identifier for this request (UUID string)
 * @param agentId          the UUID of the agent that needs input
 * @param agentName        human-readable agent name for the UI
 * @param question         extracted text of the question the agent is asking
 * @param suggestedOptions possible answers extracted from the question (may be empty)
 * @param context          brief summary of what the agent was doing when it blocked
 * @param urgency          urgency level: LOW, NORMAL, HIGH, CRITICAL
 * @param detectedAt       wall-clock time when the need was detected
 * @param detectionMethod  how the need was detected (TOOL_USE, PATTERN_MATCH, STALL, PERMISSION_DENIAL)
 * @param confidenceScore  0.0-1.0 confidence that the agent truly needs human input
 */
public record HumanInputRequest(
        String requestId,
        UUID agentId,
        String agentName,
        String question,
        List<String> suggestedOptions,
        String context,
        String urgency,
        Instant detectedAt,
        String detectionMethod,
        double confidenceScore
) {

    /**
     * Compact constructor — enforces invariants and defaults.
     */
    public HumanInputRequest {
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        if (agentId == null) {
            throw new IllegalArgumentException("agentId must not be null");
        }
        if (agentName == null) {
            agentName = "unknown";
        }
        if (question == null) {
            question = "";
        }
        if (suggestedOptions == null) {
            suggestedOptions = List.of();
        } else {
            suggestedOptions = List.copyOf(suggestedOptions); // defensive copy, immutable
        }
        if (context == null) {
            context = "";
        }
        if (urgency == null) {
            urgency = "NORMAL";
        }
        if (detectedAt == null) {
            detectedAt = Instant.now();
        }
        if (detectionMethod == null) {
            detectionMethod = "UNKNOWN";
        }
        if (confidenceScore < 0.0) {
            confidenceScore = 0.0;
        } else if (confidenceScore > 1.0) {
            confidenceScore = 1.0;
        }
    }

    /**
     * Convenience factory for building requests with sane defaults.
     */
    public static HumanInputRequest of(
            UUID agentId,
            String agentName,
            String question,
            String detectionMethod,
            double confidenceScore
    ) {
        return new HumanInputRequest(
                UUID.randomUUID().toString(),
                agentId,
                agentName,
                question,
                List.of(),
                "",
                confidenceScore >= 0.9 ? "CRITICAL" : confidenceScore >= 0.7 ? "HIGH" : "NORMAL",
                Instant.now(),
                detectionMethod,
                confidenceScore
        );
    }
}
