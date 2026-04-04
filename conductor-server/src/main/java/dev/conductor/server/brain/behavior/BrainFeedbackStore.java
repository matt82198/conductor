package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL store for Brain decision feedback.
 *
 * <p>Each line in the file is a single JSON-encoded {@link BrainFeedback} record.
 * The store follows the same pattern as {@link BehaviorLog}: append-only JSONL,
 * synchronized writes, and snapshot reads.
 *
 * <p>The feedback file is stored at {@code ~/.conductor/brain-feedback.jsonl}.
 *
 * <p>Thread safety: all writes are synchronized on the service instance. Reads take
 * a snapshot and do not block writes for longer than necessary.
 */
@Service
public class BrainFeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(BrainFeedbackStore.class);

    private final ObjectMapper objectMapper;
    private final Path feedbackPath;

    /**
     * Constructs the feedback store with the default path under the user home directory.
     *
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     */
    public BrainFeedbackStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.feedbackPath = Path.of(System.getProperty("user.home"), ".conductor", "brain-feedback.jsonl");
        ensureParentDirectories();
    }

    /**
     * Package-private constructor for testing with a custom path.
     *
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     * @param feedbackPath explicit path to the feedback JSONL file
     */
    BrainFeedbackStore(ObjectMapper objectMapper, Path feedbackPath) {
        this.objectMapper = objectMapper;
        this.feedbackPath = feedbackPath;
        ensureParentDirectories();
    }

    /**
     * Appends a single feedback entry as a JSON line.
     *
     * @param feedback the feedback to record
     */
    public synchronized void append(BrainFeedback feedback) {
        if (feedback == null) {
            return;
        }

        try {
            ensureParentDirectories();
            String json = objectMapper.writeValueAsString(feedback);

            try (BufferedWriter writer = Files.newBufferedWriter(feedbackPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }

            log.debug("Recorded brain feedback: {} rating={} for request {}",
                    feedback.feedbackId(), feedback.rating(), feedback.requestId());

        } catch (IOException e) {
            log.error("Failed to append brain feedback: {}", e.getMessage());
        }
    }

    /**
     * Reads all feedback entries from the store.
     *
     * @return all feedback entries in chronological order, or empty list on error
     */
    public synchronized List<BrainFeedback> readAll() {
        return readAllInternal();
    }

    /**
     * Reads the last N feedback entries.
     *
     * @param count the maximum number of recent entries to return
     * @return the most recent entries, chronologically ordered
     */
    public synchronized List<BrainFeedback> readRecent(int count) {
        List<BrainFeedback> all = readAllInternal();
        if (count <= 0) {
            return List.of();
        }
        if (count >= all.size()) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - count, all.size()));
    }

    /**
     * Returns the total number of feedback entries in the store.
     *
     * @return entry count, or 0 if the file doesn't exist or can't be read
     */
    public synchronized int size() {
        return readAllInternal().size();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private List<BrainFeedback> readAllInternal() {
        if (!Files.exists(feedbackPath)) {
            return new ArrayList<>();
        }

        List<BrainFeedback> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(feedbackPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    BrainFeedback feedback = objectMapper.readValue(trimmed, BrainFeedback.class);
                    entries.add(feedback);
                } catch (IOException e) {
                    log.warn("Skipping malformed brain feedback line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read brain feedback: {}", e.getMessage());
        }

        return entries;
    }

    private void ensureParentDirectories() {
        try {
            Path parent = feedbackPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.warn("Failed to create brain feedback directories: {}", e.getMessage());
        }
    }
}
