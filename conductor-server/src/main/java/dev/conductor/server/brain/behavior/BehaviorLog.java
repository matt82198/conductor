package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.brain.BrainProperties;
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
import java.util.Collections;
import java.util.List;

/**
 * Append-only JSONL file for recording user behavior events.
 *
 * <p>Each line in the file is a single JSON-encoded {@link BehaviorEvent}. The log
 * is capped at {@value #MAX_ENTRIES} entries — when the limit is exceeded, the oldest
 * entries are evicted by rewriting the file.
 *
 * <p>Thread safety: all writes are synchronized on the service instance. Reads take
 * a snapshot and do not block writes for longer than necessary.
 */
@Service
public class BehaviorLog {

    private static final Logger log = LoggerFactory.getLogger(BehaviorLog.class);
    private static final int MAX_ENTRIES = 10_000;

    private final ObjectMapper objectMapper;
    private final Path logPath;

    public BehaviorLog(ObjectMapper objectMapper, BrainProperties brainProperties) {
        this.objectMapper = objectMapper;
        this.logPath = Path.of(brainProperties.behaviorLogPath());
        ensureParentDirectories();
    }

    /**
     * Appends a single behavior event as a JSON line. If the log exceeds
     * {@value #MAX_ENTRIES} entries after the append, the oldest entries are evicted.
     *
     * @param event the behavior event to record
     */
    public synchronized void append(BehaviorEvent event) {
        if (event == null) {
            return;
        }

        try {
            ensureParentDirectories();
            String json = objectMapper.writeValueAsString(event);

            try (BufferedWriter writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }

            // Check if eviction is needed
            List<BehaviorEvent> all = readAllInternal();
            if (all.size() > MAX_ENTRIES) {
                evict(all);
            }

            log.debug("Recorded behavior event: {} for agent {}", event.eventType(), event.agentId());

        } catch (IOException e) {
            log.error("Failed to append behavior event: {}", e.getMessage());
        }
    }

    /**
     * Reads all entries from the behavior log.
     *
     * @return all behavior events in chronological order, or empty list on error
     */
    public synchronized List<BehaviorEvent> readAll() {
        return readAllInternal();
    }

    /**
     * Reads the last N entries from the behavior log.
     *
     * @param count the maximum number of recent entries to return
     * @return the most recent entries, chronologically ordered
     */
    public synchronized List<BehaviorEvent> readRecent(int count) {
        List<BehaviorEvent> all = readAllInternal();
        if (count <= 0) {
            return List.of();
        }
        if (count >= all.size()) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - count, all.size()));
    }

    /**
     * Returns the total number of entries in the behavior log.
     *
     * @return entry count, or 0 if the file doesn't exist or can't be read
     */
    public synchronized int size() {
        return readAllInternal().size();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private List<BehaviorEvent> readAllInternal() {
        if (!Files.exists(logPath)) {
            return new ArrayList<>();
        }

        List<BehaviorEvent> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    BehaviorEvent event = objectMapper.readValue(trimmed, BehaviorEvent.class);
                    events.add(event);
                } catch (IOException e) {
                    log.warn("Skipping malformed behavior log line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read behavior log: {}", e.getMessage());
        }

        return events;
    }

    /**
     * Evicts the oldest entries so the log does not exceed {@value #MAX_ENTRIES}.
     * Rewrites the file with only the most recent entries.
     */
    private void evict(List<BehaviorEvent> all) {
        if (all.size() <= MAX_ENTRIES) {
            return;
        }

        List<BehaviorEvent> retained = all.subList(all.size() - MAX_ENTRIES, all.size());
        try (BufferedWriter writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (BehaviorEvent event : retained) {
                writer.write(objectMapper.writeValueAsString(event));
                writer.newLine();
            }
            log.info("Evicted {} oldest behavior log entries (retained {})",
                    all.size() - MAX_ENTRIES, MAX_ENTRIES);
        } catch (IOException e) {
            log.error("Failed to evict behavior log entries: {}", e.getMessage());
        }
    }

    private void ensureParentDirectories() {
        try {
            Path parent = logPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.warn("Failed to create behavior log directories: {}", e.getMessage());
        }
    }
}
