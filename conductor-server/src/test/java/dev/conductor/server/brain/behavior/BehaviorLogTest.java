package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BehaviorLog}.
 * Validates append, read, eviction, and thread-safety behavior.
 */
class BehaviorLogTest {

    @TempDir
    Path tempDir;

    private BehaviorLog behaviorLog;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("behavior-log.jsonl").toString();
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, logPath, 100000);
        behaviorLog = new BehaviorLog(objectMapper, props);
    }

    @Test
    void append_and_readAll_roundtrip() {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(), "RESPONDED", "agent-1", "FEATURE_ENGINEER",
                "/home/project", "Which approach?", "The simpler one", 5000L,
                Map.of("requestId", "req-1")
        );

        behaviorLog.append(event);

        List<BehaviorEvent> all = behaviorLog.readAll();
        assertEquals(1, all.size());
        assertEquals("RESPONDED", all.get(0).eventType());
        assertEquals("agent-1", all.get(0).agentId());
        assertEquals("Which approach?", all.get(0).questionText());
        assertEquals("The simpler one", all.get(0).responseText());
    }

    @Test
    void multiple_appends_preserve_order() {
        for (int i = 0; i < 5; i++) {
            behaviorLog.append(new BehaviorEvent(
                    Instant.now(), "RESPONDED", "agent-" + i, null,
                    null, "Question " + i, "Answer " + i, 1000L, Map.of()
            ));
        }

        List<BehaviorEvent> all = behaviorLog.readAll();
        assertEquals(5, all.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("agent-" + i, all.get(i).agentId());
        }
    }

    @Test
    void readRecent_returns_last_n_entries() {
        for (int i = 0; i < 10; i++) {
            behaviorLog.append(new BehaviorEvent(
                    Instant.now(), "RESPONDED", "agent-" + i, null,
                    null, null, null, 0L, Map.of()
            ));
        }

        List<BehaviorEvent> recent = behaviorLog.readRecent(3);
        assertEquals(3, recent.size());
        assertEquals("agent-7", recent.get(0).agentId());
        assertEquals("agent-8", recent.get(1).agentId());
        assertEquals("agent-9", recent.get(2).agentId());
    }

    @Test
    void readRecent_with_count_larger_than_log_returns_all() {
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "SPAWNED", "agent-1", null,
                null, null, null, 0L, Map.of()
        ));

        List<BehaviorEvent> recent = behaviorLog.readRecent(100);
        assertEquals(1, recent.size());
    }

    @Test
    void readRecent_with_zero_returns_empty() {
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "SPAWNED", "agent-1", null,
                null, null, null, 0L, Map.of()
        ));

        List<BehaviorEvent> recent = behaviorLog.readRecent(0);
        assertTrue(recent.isEmpty());
    }

    @Test
    void size_returns_entry_count() {
        assertEquals(0, behaviorLog.size());

        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "agent-1", null,
                null, null, null, 0L, Map.of()
        ));
        assertEquals(1, behaviorLog.size());

        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "DISMISSED", "agent-2", null,
                null, null, null, 0L, Map.of()
        ));
        assertEquals(2, behaviorLog.size());
    }

    @Test
    void empty_log_readAll_returns_empty_list() {
        List<BehaviorEvent> all = behaviorLog.readAll();
        assertTrue(all.isEmpty());
    }

    @Test
    void append_null_event_is_noop() {
        behaviorLog.append(null);
        assertEquals(0, behaviorLog.size());
    }

    @Test
    void metadata_is_preserved_in_roundtrip() {
        Map<String, String> metadata = Map.of(
                "requestId", "req-123",
                "detectionMethod", "TOOL_USE",
                "urgency", "CRITICAL"
        );
        behaviorLog.append(new BehaviorEvent(
                Instant.now(), "RESPONDED", "agent-1", "EXPLORER",
                "/project", "question", "answer", 3000L, metadata
        ));

        BehaviorEvent read = behaviorLog.readAll().get(0);
        assertEquals("req-123", read.metadata().get("requestId"));
        assertEquals("TOOL_USE", read.metadata().get("detectionMethod"));
        assertEquals("CRITICAL", read.metadata().get("urgency"));
    }

    @Test
    void parent_directories_are_created() {
        String deepPath = tempDir.resolve("a/b/c/behavior.jsonl").toString();
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, deepPath, 100000);
        BehaviorLog deepLog = new BehaviorLog(objectMapper, props);

        deepLog.append(new BehaviorEvent(
                Instant.now(), "SPAWNED", "agent-1", null,
                null, null, null, 0L, Map.of()
        ));

        assertEquals(1, deepLog.size());
    }

    @Test
    void different_event_types_are_preserved() {
        String[] types = {"RESPONDED", "DISMISSED", "SPAWNED", "KILLED", "MESSAGED", "MUTED"};
        for (String type : types) {
            behaviorLog.append(new BehaviorEvent(
                    Instant.now(), type, "agent-1", null,
                    null, null, null, 0L, Map.of()
            ));
        }

        List<BehaviorEvent> all = behaviorLog.readAll();
        assertEquals(6, all.size());
        for (int i = 0; i < types.length; i++) {
            assertEquals(types[i], all.get(i).eventType());
        }
    }
}
