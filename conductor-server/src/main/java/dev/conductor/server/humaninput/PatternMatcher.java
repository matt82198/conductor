package dev.conductor.server.humaninput;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regex-based detection of question patterns in agent output text.
 *
 * <p>Stateless utility — all methods are static, no Spring annotation needed.
 * Patterns are compiled once at class load time for performance.
 *
 * <p>Each pattern has an associated confidence score reflecting how likely
 * the matched text represents an actual request for human input:
 * <ul>
 *   <li>0.90 — explicit requests ("please choose", "please confirm")</li>
 *   <li>0.85 — tentative proposals ("should I", "shall I")</li>
 *   <li>0.80 — option-seeking ("which approach", "what do you think")</li>
 *   <li>0.75 — choice presentation ("A or B", "option 1 or")</li>
 *   <li>0.60 — generic long questions (ends with "?" and >20 chars)</li>
 * </ul>
 */
public final class PatternMatcher {

    private PatternMatcher() {
        // Utility class — no instances
    }

    /**
     * A matched pattern with its regex description and confidence score.
     *
     * @param matchedPattern human-readable description of what matched
     * @param confidence     0.0-1.0 confidence that this is a human-input request
     */
    public record PatternMatch(String matchedPattern, double confidence) {}

    // ─── Compiled patterns (highest confidence first for early exit) ───

    private static final List<PatternEntry> PATTERNS = List.of(
            new PatternEntry(
                    Pattern.compile("(?i)please\\s+(choose|decide|confirm|select|pick)"),
                    "explicit_request_please",
                    0.90
            ),
            new PatternEntry(
                    Pattern.compile("(?i)\\b(should|shall)\\s+I\\b"),
                    "tentative_proposal_should_shall",
                    0.85
            ),
            new PatternEntry(
                    Pattern.compile("(?i)\\bwhich\\s+(approach|option|method|way|path|strategy|alternative)\\b"),
                    "option_seeking_which",
                    0.80
            ),
            new PatternEntry(
                    Pattern.compile("(?i)\\b(what\\s+do\\s+you\\s+think|your\\s+preference|your\\s+thoughts|your\\s+input)\\b"),
                    "opinion_seeking",
                    0.80
            ),
            new PatternEntry(
                    Pattern.compile("(?i)\\b(option\\s+\\d+\\s+or|\\w+\\s+or\\s+\\w+\\?)"),
                    "choice_presentation_or",
                    0.75
            ),
            new PatternEntry(
                    Pattern.compile("(?i)\\bA\\s+or\\s+B\\b"),
                    "choice_presentation_a_or_b",
                    0.75
            )
    );

    /**
     * The fallback pattern: any text ending with "?" that is longer than 20 characters.
     * Only checked if no higher-confidence pattern matched.
     */
    private static final Pattern GENERIC_QUESTION = Pattern.compile("^.{20,}\\?\\s*$", Pattern.DOTALL);
    private static final double GENERIC_QUESTION_CONFIDENCE = 0.60;

    /**
     * Evaluates the given text against all known question patterns.
     *
     * <p>Returns the highest-confidence match, or empty if no pattern matches
     * with sufficient confidence.
     *
     * @param text the agent output text to evaluate
     * @return the best {@link PatternMatch}, or empty if no match
     */
    public static Optional<PatternMatch> evaluate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        // Check high-confidence patterns first (list is ordered by confidence desc)
        for (PatternEntry entry : PATTERNS) {
            if (entry.pattern.matcher(text).find()) {
                return Optional.of(new PatternMatch(entry.name, entry.confidence));
            }
        }

        // Fallback: generic long question
        if (GENERIC_QUESTION.matcher(text).matches()) {
            return Optional.of(new PatternMatch("generic_long_question", GENERIC_QUESTION_CONFIDENCE));
        }

        return Optional.empty();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private record PatternEntry(Pattern pattern, String name, double confidence) {}
}
