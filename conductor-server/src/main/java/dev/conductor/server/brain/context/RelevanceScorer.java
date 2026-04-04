package dev.conductor.server.brain.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Scores context sources against a task prompt using keyword overlap.
 *
 * <p>Extracts significant keywords from the task prompt, then scores each
 * context entry (memory, agent definition, CLAUDE.md section) by how many
 * of those keywords appear in the entry's text. Returns a normalized score
 * in the range [0.0, 1.0].
 *
 * <p>This is a lightweight, no-API-call approach to relevance scoring
 * designed for speed over sophistication. It avoids Claude API round-trips
 * that would add latency to every context build.
 */
@Service
public class RelevanceScorer {

    private static final Logger log = LoggerFactory.getLogger(RelevanceScorer.class);

    /**
     * Common English stop words that carry no relevance signal.
     * Excluded from keyword extraction to prevent noise matches.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "it", "this", "that", "are", "was",
            "be", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "can", "not", "no", "so", "if", "then",
            "than", "just", "about", "up", "out", "all", "also", "as", "its",
            "my", "we", "us", "our", "i", "me", "he", "she", "they", "them",
            "what", "which", "who", "when", "where", "how", "why",
            "need", "want", "make", "use", "get", "set", "new", "add",
            "please", "help", "like", "into", "very", "some", "any", "each",
            "only", "more", "most", "other", "been", "being", "these", "those"
    );

    /**
     * Technical terms that carry strong relevance signal when present.
     * These get a 2x weight boost during scoring.
     */
    private static final Set<String> HIGH_SIGNAL_TERMS = Set.of(
            "api", "rest", "controller", "service", "repository", "entity",
            "test", "tests", "testing", "bug", "fix", "error", "exception",
            "deploy", "build", "maven", "gradle", "docker", "kubernetes",
            "database", "sql", "migration", "schema", "query",
            "authentication", "auth", "oauth", "security", "cors",
            "websocket", "event", "queue", "notification", "agent",
            "brain", "context", "memory", "knowledge", "pattern",
            "refactor", "optimize", "performance", "cache", "logging",
            "spring", "boot", "java", "react", "electron", "typescript"
    );

    private static final Pattern WORD_BOUNDARY = Pattern.compile("[\\s/\\-_.,;:!?()\\[\\]{}\"'`|]+");

    /**
     * Extracts significant keywords from a task prompt.
     *
     * <p>Tokenizes the prompt, lowercases, removes stop words, and retains
     * words of 2+ characters. Returns a frequency map where each keyword
     * maps to its occurrence count.
     *
     * @param prompt the user's task prompt
     * @return map of keyword to occurrence count, never null
     */
    public Map<String, Integer> extractKeywords(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Map.of();
        }

        String[] tokens = WORD_BOUNDARY.split(prompt.toLowerCase(Locale.ROOT));
        Map<String, Integer> keywords = new LinkedHashMap<>();

        for (String token : tokens) {
            String cleaned = token.strip();
            if (cleaned.length() < 2) continue;
            if (STOP_WORDS.contains(cleaned)) continue;

            keywords.merge(cleaned, 1, Integer::sum);
        }

        log.debug("Extracted {} keywords from prompt ({} chars)", keywords.size(), prompt.length());
        return keywords;
    }

    /**
     * Scores a text block against a set of prompt keywords.
     *
     * <p>Calculates the fraction of prompt keywords that appear in the text.
     * High-signal technical terms receive a 2x weight. The score is normalized
     * to [0.0, 1.0] based on the total weighted keyword count.
     *
     * @param text     the content to score (memory content, agent description, etc.)
     * @param keywords keywords extracted from the task prompt via {@link #extractKeywords}
     * @return relevance score in [0.0, 1.0], or 0.0 if text/keywords are empty
     */
    public double score(String text, Map<String, Integer> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0.0;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        Set<String> textTokens = tokenize(lowerText);

        double matchedWeight = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, Integer> entry : keywords.entrySet()) {
            String keyword = entry.getKey();
            int frequency = entry.getValue();
            double weight = HIGH_SIGNAL_TERMS.contains(keyword) ? 2.0 : 1.0;
            double keywordWeight = weight * frequency;
            totalWeight += keywordWeight;

            // Check both token-level match and substring match for compound terms
            if (textTokens.contains(keyword) || lowerText.contains(keyword)) {
                matchedWeight += keywordWeight;
            }
        }

        if (totalWeight == 0.0) {
            return 0.0;
        }

        double raw = matchedWeight / totalWeight;
        return Math.min(1.0, raw);
    }

    /**
     * Scores a memory entry, applying type-based boosting.
     *
     * <p>Feedback memories get a baseline boost of 0.3 because they contain
     * operational rules that are broadly applicable. Project and reference
     * memories receive no boost. User memories get a small boost of 0.1.
     *
     * @param memory   the memory entry to score
     * @param keywords keywords extracted from the task prompt
     * @return relevance score in [0.0, 1.0]
     */
    public double scoreMemory(MemoryEntry memory, Map<String, Integer> keywords) {
        // Combine name + description + content for matching
        String combined = String.join(" ",
                memory.name(),
                memory.description(),
                memory.content()
        );

        double baseScore = score(combined, keywords);

        // Type-based boost
        double boost = switch (memory.type().toLowerCase(Locale.ROOT)) {
            case "feedback" -> 0.3;
            case "user" -> 0.1;
            default -> 0.0;
        };

        return Math.min(1.0, baseScore + boost);
    }

    /**
     * Scores an agent definition against the task prompt.
     *
     * <p>Uses the agent's name, description, and system prompt for matching.
     * The description is weighted more heavily since it captures the agent's
     * purpose in concise form.
     *
     * @param agent    the agent definition to score
     * @param keywords keywords extracted from the task prompt
     * @return relevance score in [0.0, 1.0]
     */
    public double scoreAgent(AgentDefinition agent, Map<String, Integer> keywords) {
        // Weight description more heavily than full prompt
        String focused = String.join(" ",
                agent.name(), agent.name(), // double-weight the name
                agent.description(), agent.description(), agent.description(), // triple-weight description
                agent.systemPrompt()
        );

        return score(focused, keywords);
    }

    /**
     * Scores a CLAUDE.md domain document against the task prompt.
     *
     * @param doc      the domain CLAUDE.md to score
     * @param keywords keywords extracted from the task prompt
     * @return relevance score in [0.0, 1.0]
     */
    public double scoreDomainDoc(DomainClaudeMd doc, Map<String, Integer> keywords) {
        String combined = String.join(" ",
                doc.domainName(),
                doc.relativePath(),
                doc.content()
        );
        return score(combined, keywords);
    }

    // -- Internal -------------------------------------------------------

    /**
     * Tokenizes text into a set of lowercase words.
     */
    private Set<String> tokenize(String text) {
        String[] tokens = WORD_BOUNDARY.split(text);
        Set<String> result = new HashSet<>();
        for (String token : tokens) {
            String cleaned = token.strip();
            if (cleaned.length() >= 2) {
                result.add(cleaned);
            }
        }
        return result;
    }
}
