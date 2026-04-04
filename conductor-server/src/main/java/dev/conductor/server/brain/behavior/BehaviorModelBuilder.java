package dev.conductor.server.brain.behavior;

import dev.conductor.server.humaninput.HumanInputRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the {@link BehaviorModel} from raw behavior log entries.
 *
 * <p>The model is cached for 60 seconds to avoid expensive recomputation on every
 * incoming event. Pattern matching normalizes question text by taking the first
 * 50 characters and lowercasing — a simple but effective approach for Phase 4A.
 *
 * <p>Auto-approve detection: if the user has responded to the same normalized
 * question pattern 3+ times with similar answers (all responses start with the
 * same first word), the pattern is flagged for auto-approval.
 */
@Service
public class BehaviorModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(BehaviorModelBuilder.class);
    private static final int PATTERN_KEY_LENGTH = 50;
    private static final int AUTO_APPROVE_THRESHOLD = 3;
    private static final long CACHE_TTL_MS = 60_000L;

    private final BehaviorLog behaviorLog;

    private volatile BehaviorModel cachedModel;
    private volatile long cachedAt;

    public BehaviorModelBuilder(BehaviorLog behaviorLog) {
        this.behaviorLog = behaviorLog;
    }

    /**
     * Builds or returns the cached behavior model. The model is rebuilt if the cache
     * is older than 60 seconds.
     *
     * @return the current behavior model
     */
    public BehaviorModel build() {
        long now = System.currentTimeMillis();
        BehaviorModel snapshot = cachedModel;
        if (snapshot != null && (now - cachedAt) < CACHE_TTL_MS) {
            return snapshot;
        }

        synchronized (this) {
            // Double-check after acquiring lock
            if (cachedModel != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
                return cachedModel;
            }

            BehaviorModel freshModel = buildInternal();
            cachedModel = freshModel;
            cachedAt = System.currentTimeMillis();
            return freshModel;
        }
    }

    /**
     * Checks if the given human input request matches a known behavior pattern.
     *
     * @param request the incoming human input request
     * @param model   the current behavior model
     * @return a {@link BehaviorMatch} if a pattern matches, or null if no match found
     */
    public BehaviorMatch findMatch(HumanInputRequest request, BehaviorModel model) {
        if (request == null || model == null) {
            return null;
        }

        String questionKey = normalizeKey(request.question());

        // Check always-escalate patterns first — these override auto-approve
        for (String escalatePattern : model.alwaysEscalatePatterns()) {
            if (questionKey.startsWith(escalatePattern)) {
                return null; // Force escalation
            }
        }

        // Check auto-approve patterns
        for (String approvePattern : model.autoApprovePatterns()) {
            if (questionKey.startsWith(approvePattern)) {
                List<String> pastResponses = model.responsePatterns().get(approvePattern);
                if (pastResponses != null && !pastResponses.isEmpty()) {
                    String suggestedResponse = findMostCommonResponse(pastResponses);
                    double confidence = computeConfidence(pastResponses);
                    return new BehaviorMatch(approvePattern, suggestedResponse, confidence);
                }
            }
        }

        // Check response patterns for a direct match
        List<String> responses = model.responsePatterns().get(questionKey);
        if (responses != null && responses.size() >= AUTO_APPROVE_THRESHOLD) {
            String suggestedResponse = findMostCommonResponse(responses);
            double confidence = computeConfidence(responses);
            if (confidence >= 0.5) {
                return new BehaviorMatch(questionKey, suggestedResponse, confidence);
            }
        }

        return null;
    }

    // ─── Internal Model Building ──────────────────────────────────────

    private BehaviorModel buildInternal() {
        List<BehaviorEvent> events = behaviorLog.readAll();

        if (events.isEmpty()) {
            return emptyModel();
        }

        // Separate events by type
        List<BehaviorEvent> responded = filterByType(events, "RESPONDED");
        List<BehaviorEvent> dismissed = filterByType(events, "DISMISSED");

        // Build response patterns: normalized question key -> list of responses
        Map<String, List<String>> responsePatterns = new HashMap<>();
        for (BehaviorEvent event : responded) {
            if (event.questionText() != null && event.responseText() != null) {
                String key = normalizeKey(event.questionText());
                responsePatterns.computeIfAbsent(key, k -> new ArrayList<>()).add(event.responseText());
            }
        }

        // Overall approval rate
        int totalDecisions = responded.size() + dismissed.size();
        double overallApprovalRate = totalDecisions > 0
                ? (double) responded.size() / totalDecisions
                : 0.0;

        // Dismissal rate
        double dismissalRate = totalDecisions > 0
                ? (double) dismissed.size() / totalDecisions
                : 0.0;

        // Approval rate by tool (from metadata detectionMethod)
        Map<String, Double> approvalRateByTool = computeApprovalRateByTool(responded, dismissed);

        // Average response word count
        int averageWordCount = computeAverageWordCount(responded);

        // Auto-approve patterns: responded 3+ times with similar answers
        Set<String> autoApprovePatterns = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : responsePatterns.entrySet()) {
            if (entry.getValue().size() >= AUTO_APPROVE_THRESHOLD && hasConsistentResponses(entry.getValue())) {
                autoApprovePatterns.add(entry.getKey());
            }
        }

        // Always-escalate: empty initially — populated by explicit user feedback in Phase 4B+
        Set<String> alwaysEscalatePatterns = Set.of();

        log.debug("Built behavior model: {} response patterns, {} auto-approve, approval rate={}",
                responsePatterns.size(), autoApprovePatterns.size(),
                String.format("%.2f", overallApprovalRate));

        return new BehaviorModel(
                Map.copyOf(responsePatterns),
                overallApprovalRate,
                Map.copyOf(approvalRateByTool),
                averageWordCount,
                dismissalRate,
                Set.copyOf(autoApprovePatterns),
                alwaysEscalatePatterns,
                Instant.now()
        );
    }

    private BehaviorModel emptyModel() {
        return new BehaviorModel(
                Map.of(), 0.0, Map.of(), 0, 0.0, Set.of(), Set.of(), Instant.now()
        );
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Normalizes a question string to a pattern key: lowercase, trimmed, first 50 chars.
     */
    static String normalizeKey(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > PATTERN_KEY_LENGTH
                ? normalized.substring(0, PATTERN_KEY_LENGTH)
                : normalized;
    }

    private List<BehaviorEvent> filterByType(List<BehaviorEvent> events, String type) {
        return events.stream()
                .filter(e -> type.equals(e.eventType()))
                .collect(Collectors.toList());
    }

    private Map<String, Double> computeApprovalRateByTool(
            List<BehaviorEvent> responded, List<BehaviorEvent> dismissed) {

        Map<String, int[]> toolCounts = new HashMap<>(); // tool -> [responded, dismissed]

        for (BehaviorEvent event : responded) {
            String method = event.metadata().getOrDefault("detectionMethod", "UNKNOWN");
            toolCounts.computeIfAbsent(method, k -> new int[2])[0]++;
        }
        for (BehaviorEvent event : dismissed) {
            String method = event.metadata().getOrDefault("detectionMethod", "UNKNOWN");
            toolCounts.computeIfAbsent(method, k -> new int[2])[1]++;
        }

        Map<String, Double> rates = new HashMap<>();
        for (Map.Entry<String, int[]> entry : toolCounts.entrySet()) {
            int total = entry.getValue()[0] + entry.getValue()[1];
            if (total > 0) {
                rates.put(entry.getKey(), (double) entry.getValue()[0] / total);
            }
        }
        return rates;
    }

    private int computeAverageWordCount(List<BehaviorEvent> responded) {
        if (responded.isEmpty()) {
            return 0;
        }
        long totalWords = 0;
        int counted = 0;
        for (BehaviorEvent event : responded) {
            if (event.responseText() != null && !event.responseText().isBlank()) {
                totalWords += event.responseText().trim().split("\\s+").length;
                counted++;
            }
        }
        return counted > 0 ? (int) (totalWords / counted) : 0;
    }

    /**
     * Checks if the responses are consistent — all start with the same first word
     * (normalized to lowercase). This is the Phase 4A heuristic for detecting
     * "the user always says the same thing."
     */
    private boolean hasConsistentResponses(List<String> responses) {
        if (responses.size() < AUTO_APPROVE_THRESHOLD) {
            return false;
        }

        Set<String> firstWords = new HashSet<>();
        for (String response : responses) {
            String trimmed = response.trim().toLowerCase(Locale.ROOT);
            String firstWord = trimmed.contains(" ") ? trimmed.substring(0, trimmed.indexOf(' ')) : trimmed;
            firstWords.add(firstWord);
        }
        // Consistent if most responses start with the same word
        return firstWords.size() <= Math.max(1, responses.size() / 3);
    }

    /**
     * Computes confidence based on response consistency and count.
     * More responses + more consistency = higher confidence.
     */
    private double computeConfidence(List<String> responses) {
        if (responses.isEmpty()) {
            return 0.0;
        }

        // Count frequency of most common response
        Map<String, Long> freq = responses.stream()
                .map(r -> r.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        long maxFreq = freq.values().stream().mapToLong(Long::longValue).max().orElse(0);
        double consistency = (double) maxFreq / responses.size();

        // Scale confidence with count: 3 responses = base, 10+ = max bonus
        double countBonus = Math.min(1.0, responses.size() / 10.0);
        return Math.min(1.0, consistency * 0.7 + countBonus * 0.3);
    }

    /**
     * Finds the most common response text from a list of past responses.
     */
    private String findMostCommonResponse(List<String> responses) {
        Map<String, Long> freq = responses.stream()
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        return freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(responses.get(0));
    }
}
