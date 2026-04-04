package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.common.AgentRole;
import dev.conductor.common.AgentState;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.humaninput.HumanInputRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BehaviorLogger}.
 * Validates that each interaction type is correctly logged.
 */
class BehaviorLoggerTest {

    @TempDir
    Path tempDir;

    private BehaviorLog behaviorLog;
    private BehaviorLogger behaviorLogger;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("behavior-log.jsonl").toString();
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, logPath, 100000);
        behaviorLog = new BehaviorLog(objectMapper, props);
        behaviorLogger = new BehaviorLogger(behaviorLog);
    }

    @Test
    void logResponse_records_responded_event() {
        HumanInputRequest request = createRequest("Which approach?");

        behaviorLogger.logResponse(request, "Use approach A", 5000L);

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("RESPONDED", events.get(0).eventType());
        assertEquals("Which approach?", events.get(0).questionText());
        assertEquals("Use approach A", events.get(0).responseText());
        assertEquals(5000L, events.get(0).responseTimeMs());
    }

    @Test
    void logDismissal_records_dismissed_event() {
        HumanInputRequest request = createRequest("False positive?");

        behaviorLogger.logDismissal(request);

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("DISMISSED", events.get(0).eventType());
        assertEquals("False positive?", events.get(0).questionText());
        assertNull(events.get(0).responseText());
    }

    @Test
    void logSpawn_records_spawned_event() {
        AgentRecord agent = new AgentRecord(
                UUID.randomUUID(), "test-agent", AgentRole.FEATURE_ENGINEER,
                "/home/project", AgentState.LAUNCHING, null,
                Instant.now(), 0.0, Instant.now()
        );

        behaviorLogger.logSpawn(agent, "Build the auth module");

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("SPAWNED", events.get(0).eventType());
        assertEquals("Build the auth module", events.get(0).responseText());
        assertEquals("FEATURE_ENGINEER", events.get(0).agentRole());
        assertEquals("/home/project", events.get(0).projectPath());
    }

    @Test
    void logKill_records_killed_event() {
        AgentRecord agent = new AgentRecord(
                UUID.randomUUID(), "stuck-agent", AgentRole.EXPLORER,
                "/home/project", AgentState.ACTIVE, null,
                Instant.now(), 0.5, Instant.now()
        );

        behaviorLogger.logKill(agent);

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("KILLED", events.get(0).eventType());
        assertEquals("EXPLORER", events.get(0).agentRole());
    }

    @Test
    void logMessage_records_messaged_event() {
        UUID agentId = UUID.randomUUID();

        behaviorLogger.logMessage(agentId, "Please focus on the auth module");

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("MESSAGED", events.get(0).eventType());
        assertEquals(agentId.toString(), events.get(0).agentId());
        assertEquals("Please focus on the auth module", events.get(0).responseText());
    }

    @Test
    void logMute_records_muted_event() {
        UUID agentId = UUID.randomUUID();

        behaviorLogger.logMute(agentId, true);

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("MUTED", events.get(0).eventType());
        assertEquals("true", events.get(0).metadata().get("muted"));
    }

    @Test
    void logMute_records_unmuted_event() {
        UUID agentId = UUID.randomUUID();

        behaviorLogger.logMute(agentId, false);

        List<BehaviorEvent> events = behaviorLog.readAll();
        assertEquals(1, events.size());
        assertEquals("MUTED", events.get(0).eventType());
        assertEquals("false", events.get(0).metadata().get("muted"));
    }

    @Test
    void logResponse_includes_metadata() {
        HumanInputRequest request = createRequest("Should I proceed?");

        behaviorLogger.logResponse(request, "Yes", 1000L);

        BehaviorEvent event = behaviorLog.readAll().get(0);
        assertNotNull(event.metadata().get("requestId"));
        assertEquals("TOOL_USE", event.metadata().get("detectionMethod"));
        assertEquals("NORMAL", event.metadata().get("urgency"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private HumanInputRequest createRequest(String question) {
        return new HumanInputRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "test-agent",
                question,
                List.of(),
                "",
                "NORMAL",
                Instant.now(),
                "TOOL_USE",
                0.9
        );
    }
}
