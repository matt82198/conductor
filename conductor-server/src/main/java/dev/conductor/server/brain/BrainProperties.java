package dev.conductor.server.brain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for the Conductor Brain, bound from
 * {@code conductor.brain.*} in application.yml.
 *
 * <p>The Brain is opt-in — {@code enabled} defaults to false. When enabled,
 * the decision engine intercepts human input events and auto-responds when
 * the behavior model has sufficient confidence.
 *
 * @param enabled                  whether the Brain is active (default: false)
 * @param apiKey                   Anthropic API key for Claude API calls (Phase 4B, nullable)
 * @param model                    Claude model to use for API calls (default: claude-sonnet-4-6)
 * @param confidenceThreshold      minimum confidence to auto-respond (default: 0.8)
 * @param maxAutoResponsesPerMinute safety rate limit for auto-responses (default: 10)
 * @param behaviorLogPath          path to the behavior log JSONL file
 * @param contextWindowBudget      max chars for context in API prompts (default: 100000)
 */
@ConfigurationProperties(prefix = "conductor.brain")
public record BrainProperties(
        boolean enabled,
        String apiKey,
        String model,
        double confidenceThreshold,
        int maxAutoResponsesPerMinute,
        String behaviorLogPath,
        int contextWindowBudget
) {

    public BrainProperties {
        if (model == null) {
            model = "claude-sonnet-4-6";
        }
        if (confidenceThreshold <= 0) {
            confidenceThreshold = 0.8;
        }
        if (maxAutoResponsesPerMinute <= 0) {
            maxAutoResponsesPerMinute = 10;
        }
        if (behaviorLogPath == null) {
            behaviorLogPath = System.getProperty("user.home") + "/.conductor/behavior-log.jsonl";
        }
        if (contextWindowBudget <= 0) {
            contextWindowBudget = 100000;
        }
    }
}
