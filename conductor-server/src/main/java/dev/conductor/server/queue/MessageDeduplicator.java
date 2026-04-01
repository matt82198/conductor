package dev.conductor.server.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content-hash deduplication within a sliding time window.
 *
 * <p>Uses SHA-256 of {@code (agentId + contentType + first 200 chars of text)}
 * as the dedup key. If the same key is seen within a 60-second window,
 * the message is considered a duplicate and should be dropped.
 *
 * <p>Expired entries are cleaned up every 30 seconds by a scheduled task.
 *
 * <p>Thread-safe: all state is in a {@link ConcurrentHashMap}.
 */
@Service
public class MessageDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(MessageDeduplicator.class);

    /** Duration in milliseconds after which a dedup entry expires. */
    private static final long WINDOW_MS = 60_000L;

    /** Max chars of text used in the dedup hash input. */
    private static final int TEXT_PREFIX_LENGTH = 200;

    /** Stores dedup hash -> timestamp of first occurrence. */
    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * Computes the dedup hash for a message and checks if it is a duplicate.
     *
     * @param agentId  the originating agent's UUID
     * @param category the message category (e.g., "tool_use", "text", "thinking")
     * @param text     the display text of the message
     * @return true if this message is a duplicate (should be dropped); false if it is new
     */
    public boolean isDuplicate(UUID agentId, String category, String text) {
        String hash = computeHash(agentId, category, text);
        Instant now = Instant.now();

        Instant previous = seen.putIfAbsent(hash, now);
        if (previous == null) {
            // First time seeing this hash -- not a duplicate
            return false;
        }

        // Check if the previous entry is still within the window
        long ageMs = now.toEpochMilli() - previous.toEpochMilli();
        if (ageMs <= WINDOW_MS) {
            log.trace("Duplicate detected (age={}ms): hash={}", ageMs, hash.substring(0, 12));
            return true;
        }

        // Previous entry expired -- replace it and allow this one through
        seen.put(hash, now);
        return false;
    }

    /**
     * Computes the dedup hash and returns it (for storing in the QueuedMessage).
     *
     * @param agentId  the originating agent's UUID
     * @param category the message category
     * @param text     the display text
     * @return the SHA-256 hex string
     */
    public String computeHash(UUID agentId, String category, String text) {
        String safeText = text != null ? text : "";
        String prefix = safeText.length() > TEXT_PREFIX_LENGTH
                ? safeText.substring(0, TEXT_PREFIX_LENGTH)
                : safeText;

        String input = agentId.toString() + "|" + (category != null ? category : "") + "|" + prefix;
        return sha256(input);
    }

    /**
     * Periodically removes expired entries from the dedup map.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedRate = 30_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minusMillis(WINDOW_MS);
        int before = seen.size();
        seen.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        int removed = before - seen.size();
        if (removed > 0) {
            log.debug("Dedup cleanup: removed {} expired entries ({} remaining)", removed, seen.size());
        }
    }

    /**
     * Returns the number of entries currently in the dedup map.
     * Visible for testing.
     */
    public int size() {
        return seen.size();
    }

    /**
     * Clears all dedup state. Visible for testing.
     */
    public void clear() {
        seen.clear();
    }

    // ─── Internal ──────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JVM spec -- should never happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
