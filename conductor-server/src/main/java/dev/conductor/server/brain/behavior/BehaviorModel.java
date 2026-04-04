package dev.conductor.server.brain.behavior;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregated model of the user's interaction patterns, built from the behavior log.
 *
 * <p>This is a snapshot — it's rebuilt periodically (cached for 60 seconds) from the
 * raw behavior log entries. The model drives the Brain's pattern-matching auto-response
 * capability in Phase 4A.
 *
 * @param responsePatterns       question category (normalized first 50 chars) mapped to past response texts
 * @param overallApprovalRate    ratio of RESPONDED events to (RESPONDED + DISMISSED) events
 * @param approvalRateByTool     per-tool approval rate (tool name to rate 0.0-1.0)
 * @param averageResponseWordCount average number of words in human responses
 * @param dismissalRate          ratio of DISMISSED events to total decision events
 * @param autoApprovePatterns    question patterns the user has responded to 3+ times with similar answers
 * @param alwaysEscalatePatterns question patterns the user always wants to see (populated by explicit feedback)
 * @param lastUpdatedAt          when this model was last computed
 */
public record BehaviorModel(
        Map<String, List<String>> responsePatterns,
        double overallApprovalRate,
        Map<String, Double> approvalRateByTool,
        int averageResponseWordCount,
        double dismissalRate,
        Set<String> autoApprovePatterns,
        Set<String> alwaysEscalatePatterns,
        Instant lastUpdatedAt
) {

    public BehaviorModel {
        if (responsePatterns == null) {
            responsePatterns = Map.of();
        }
        if (approvalRateByTool == null) {
            approvalRateByTool = Map.of();
        }
        if (autoApprovePatterns == null) {
            autoApprovePatterns = Set.of();
        }
        if (alwaysEscalatePatterns == null) {
            alwaysEscalatePatterns = Set.of();
        }
        if (lastUpdatedAt == null) {
            lastUpdatedAt = Instant.now();
        }
    }
}
