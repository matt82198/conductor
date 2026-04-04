package dev.conductor.server.brain.task;

import dev.conductor.common.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Subtask} record.
 */
class SubtaskTest {

    @Test
    void defaultsAreAppliedWhenNull() {
        Subtask s = new Subtask(null, "test", "desc", AgentRole.EXPLORER,
                null, null, "/path", "prompt", null, null, null, null, null, null);
        assertNotNull(s.subtaskId());
        assertEquals(List.of(), s.dependsOn());
        assertEquals(List.of(), s.contextFrom());
        assertEquals(SubtaskStatus.PENDING, s.status());
    }

    @Test
    void explicitValuesArePreserved() {
        List<String> deps = List.of("dep1", "dep2");
        List<String> ctx = List.of("ctx1");
        Subtask s = new Subtask("id-1", "test", "desc", AgentRole.FEATURE_ENGINEER,
                deps, ctx, "/path", "prompt", "criteria", SubtaskStatus.RUNNING,
                null, null, null, null);
        assertEquals("id-1", s.subtaskId());
        assertEquals(deps, s.dependsOn());
        assertEquals(ctx, s.contextFrom());
        assertEquals(SubtaskStatus.RUNNING, s.status());
        assertEquals("criteria", s.successCriteria());
    }

    @Test
    void withStatusReturnsNewInstance() {
        Subtask s = new Subtask("id-1", "test", "desc", AgentRole.EXPLORER,
                null, null, "/path", "prompt", null, SubtaskStatus.PENDING,
                null, null, null, null);
        Subtask running = s.withStatus(SubtaskStatus.RUNNING);
        assertNotSame(s, running);
        assertEquals(SubtaskStatus.PENDING, s.status());
        assertEquals(SubtaskStatus.RUNNING, running.status());
        assertEquals(s.subtaskId(), running.subtaskId());
    }

    @Test
    void withAgentSetsAgentIdAndStartedAt() {
        Subtask s = new Subtask("id-1", "test", "desc", AgentRole.EXPLORER,
                null, null, "/path", "prompt", null, SubtaskStatus.PENDING,
                null, null, null, null);
        UUID agentId = UUID.randomUUID();
        Subtask withAgent = s.withAgent(agentId);
        assertEquals(agentId, withAgent.agentId());
        assertNotNull(withAgent.startedAt());
        assertNull(s.startedAt());
    }

    @Test
    void withResultSetsResultAndCompletedAt() {
        Subtask s = new Subtask("id-1", "test", "desc", AgentRole.EXPLORER,
                null, null, "/path", "prompt", null, SubtaskStatus.RUNNING,
                null, null, null, null);
        Subtask completed = s.withResult("done!", SubtaskStatus.COMPLETED);
        assertEquals("done!", completed.result());
        assertEquals(SubtaskStatus.COMPLETED, completed.status());
        assertNotNull(completed.completedAt());
        assertNull(s.result());
    }

    @Test
    void withResultPreservesOtherFields() {
        UUID agentId = UUID.randomUUID();
        Subtask s = new Subtask("id-1", "test", "desc", AgentRole.REVIEWER,
                List.of("dep1"), List.of("ctx1"), "/path", "prompt", "criteria",
                SubtaskStatus.RUNNING, agentId, null, null, null);
        Subtask completed = s.withResult("result", SubtaskStatus.COMPLETED);
        assertEquals("id-1", completed.subtaskId());
        assertEquals("test", completed.name());
        assertEquals(AgentRole.REVIEWER, completed.role());
        assertEquals(List.of("dep1"), completed.dependsOn());
        assertEquals(List.of("ctx1"), completed.contextFrom());
        assertEquals(agentId, completed.agentId());
    }
}
