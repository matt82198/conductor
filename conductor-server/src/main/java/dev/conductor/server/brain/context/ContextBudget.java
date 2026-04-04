package dev.conductor.server.brain.context;

import java.util.List;
import java.util.Locale;

/**
 * Tracks how the character budget is allocated across context categories.
 *
 * <p>The context manager builds a budget before rendering, allocating
 * characters to four categories: target project (60%), memories (20%),
 * agent definitions (10%), and cross-project knowledge (10%).
 *
 * <p>Each category contains a list of {@link ScoredEntry} items ranked
 * by relevance. Only entries that fit within their category's budget
 * are included in the final rendered prompt.
 *
 * @param totalChars         the total character budget
 * @param targetProjectChars budget for the target project's CLAUDE.md content
 * @param memoriesChars      budget for user memories
 * @param agentDefsChars     budget for agent definitions
 * @param crossProjectChars  budget for cross-project knowledge summaries
 * @param rankedEntries      all scored entries across categories, sorted by relevance descending
 */
public record ContextBudget(
        int totalChars,
        int targetProjectChars,
        int memoriesChars,
        int agentDefsChars,
        int crossProjectChars,
        List<ScoredEntry> rankedEntries
) {

    /**
     * Default budget allocation percentages.
     */
    public static final double TARGET_PROJECT_RATIO = 0.60;
    public static final double MEMORIES_RATIO = 0.20;
    public static final double AGENT_DEFS_RATIO = 0.10;
    public static final double CROSS_PROJECT_RATIO = 0.10;

    public ContextBudget {
        if (totalChars < 0) totalChars = 0;
        if (targetProjectChars < 0) targetProjectChars = 0;
        if (memoriesChars < 0) memoriesChars = 0;
        if (agentDefsChars < 0) agentDefsChars = 0;
        if (crossProjectChars < 0) crossProjectChars = 0;
        if (rankedEntries == null) rankedEntries = List.of();
    }

    /**
     * Creates a ContextBudget from a total character limit using default ratios.
     *
     * @param totalChars the total character budget
     * @return a new ContextBudget with default ratio allocation
     */
    public static ContextBudget withDefaults(int totalChars) {
        return withDefaults(totalChars, List.of());
    }

    /**
     * Creates a ContextBudget from a total character limit with scored entries.
     *
     * @param totalChars    the total character budget
     * @param rankedEntries all scored entries, sorted by relevance descending
     * @return a new ContextBudget with default ratio allocation
     */
    public static ContextBudget withDefaults(int totalChars, List<ScoredEntry> rankedEntries) {
        return new ContextBudget(
                totalChars,
                (int) (totalChars * TARGET_PROJECT_RATIO),
                (int) (totalChars * MEMORIES_RATIO),
                (int) (totalChars * AGENT_DEFS_RATIO),
                (int) (totalChars * CROSS_PROJECT_RATIO),
                rankedEntries
        );
    }

    /**
     * Returns the total allocated budget across all categories.
     * May be slightly less than totalChars due to integer rounding.
     */
    public int allocatedChars() {
        return targetProjectChars + memoriesChars + agentDefsChars + crossProjectChars;
    }

    /**
     * Returns the number of entries in a given category.
     */
    public long entriesInCategory(String category) {
        return rankedEntries.stream()
                .filter(e -> category.equals(e.source()))
                .count();
    }

    /**
     * Returns the total character size of entries in a given category.
     */
    public int charsInCategory(String category) {
        return rankedEntries.stream()
                .filter(e -> category.equals(e.source()))
                .mapToInt(ScoredEntry::charSize)
                .sum();
    }

    /**
     * A single scored context entry considered for inclusion in the prompt.
     *
     * @param source    category: "target-project", "memory", "agent-def", "cross-project"
     * @param key       identifier within the category (e.g., memory name, agent name, project name)
     * @param relevance relevance score [0.0, 1.0]
     * @param charSize  character size of this entry's rendered content
     */
    public record ScoredEntry(
            String source,
            String key,
            double relevance,
            int charSize
    ) {

        public ScoredEntry {
            if (source == null) source = "unknown";
            if (key == null) key = "unknown";
            if (relevance < 0.0) relevance = 0.0;
            if (relevance > 1.0) relevance = 1.0;
            if (charSize < 0) charSize = 0;
        }

        /**
         * Returns a human-readable summary for debugging/API display.
         */
        public String summary() {
            return String.format(Locale.ROOT, "[%s] %s (relevance=%.2f, %d chars)",
                    source, key, relevance, charSize);
        }
    }
}
