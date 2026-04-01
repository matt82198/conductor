package dev.conductor.server.humaninput;

import java.util.List;

/**
 * Combines multiple detection signals into a single confidence score.
 *
 * <p>Stateless utility — all methods are static, no Spring annotation needed.
 *
 * <p>When multiple detection layers fire for the same event (e.g., pattern match
 * AND stall detection), the scorer takes the maximum confidence across all sources.
 * This is intentionally simple — a more sophisticated weighted combination can
 * be swapped in later without changing the interface.
 */
public final class ConfidenceScorer {

    private ConfidenceScorer() {
        // Utility class — no instances
    }

    /**
     * A single detection signal with its source identifier and confidence.
     *
     * @param source     identifier for the detection layer (e.g., "TOOL_USE", "PATTERN_MATCH", "STALL")
     * @param confidence 0.0-1.0 confidence from that source
     */
    public record Signal(String source, double confidence) {

        public Signal {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("Signal source must not be blank");
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));
        }
    }

    /**
     * Combines multiple signals into a single confidence score.
     *
     * <p>Current strategy: returns the maximum confidence across all signals.
     * This prevents false negatives — if any single layer is highly confident,
     * the overall score reflects that.
     *
     * @param signals the detection signals to combine
     * @return the combined confidence score (0.0 if no signals provided)
     */
    public static double combine(List<Signal> signals) {
        if (signals == null || signals.isEmpty()) {
            return 0.0;
        }
        return signals.stream()
                .mapToDouble(Signal::confidence)
                .max()
                .orElse(0.0);
    }

    /**
     * Convenience method for combining exactly two signals.
     *
     * @param a first signal
     * @param b second signal
     * @return the higher of the two confidence scores
     */
    public static double combine(Signal a, Signal b) {
        return Math.max(a.confidence(), b.confidence());
    }

    /**
     * Returns the signal with the highest confidence, or null if the list is empty.
     *
     * @param signals the detection signals
     * @return the dominant signal, or null
     */
    public static Signal dominant(List<Signal> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        Signal best = signals.getFirst();
        for (int i = 1; i < signals.size(); i++) {
            if (signals.get(i).confidence() > best.confidence()) {
                best = signals.get(i);
            }
        }
        return best;
    }
}
