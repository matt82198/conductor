package dev.conductor.server.brain.task;

import dev.conductor.common.AgentRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DecompositionPlan} record.
 */
class DecompositionPlanTest {

    @Test
    void defaultsAreAppliedWhenNull() {
        DecompositionPlan plan = new DecompositionPlan(null, "prompt", "/path", null, null, null);
        assertNotNull(plan.planId());
        assertEquals(List.of(), plan.subtasks());
        assertNotNull(plan.createdAt());
        assertEquals(DecompositionPlan.STATUS_CREATED, plan.status());
    }

    @Test
    void explicitValuesArePreserved() {
        Instant now = Instant.now();
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                List.of(), now, DecompositionPlan.STATUS_EXECUTING);
        assertEquals("plan-1", plan.planId());
        assertEquals("prompt", plan.originalPrompt());
        assertEquals("/path", plan.projectPath());
        assertEquals(now, plan.createdAt());
        assertEquals(DecompositionPlan.STATUS_EXECUTING, plan.status());
    }

    @Test
    void withStatusReturnsNewInstance() {
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                List.of(), null, DecompositionPlan.STATUS_CREATED);
        DecompositionPlan executing = plan.withStatus(DecompositionPlan.STATUS_EXECUTING);
        assertNotSame(plan, executing);
        assertEquals(DecompositionPlan.STATUS_CREATED, plan.status());
        assertEquals(DecompositionPlan.STATUS_EXECUTING, executing.status());
    }

    @Test
    void withSubtaskReplacesMatchingSubtask() {
        Subtask s1 = new Subtask("s1", "explore", "desc", AgentRole.EXPLORER,
                null, null, "/path", "prompt", null, SubtaskStatus.PENDING,
                null, null, null, null);
        Subtask s2 = new Subtask("s2", "build", "desc", AgentRole.FEATURE_ENGINEER,
                null, null, "/path", "prompt", null, SubtaskStatus.PENDING,
                null, null, null, null);
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                List.of(s1, s2), null, null);

        Subtask s1Updated = s1.withStatus(SubtaskStatus.COMPLETED);
        DecompositionPlan updated = plan.withSubtask("s1", s1Updated);

        assertEquals(SubtaskStatus.COMPLETED, updated.subtasks().get(0).status());
        assertEquals(SubtaskStatus.PENDING, updated.subtasks().get(1).status());
        // Original unchanged
        assertEquals(SubtaskStatus.PENDING, plan.subtasks().get(0).status());
    }

    @Test
    void completedCountMatchesCompletedSubtasks() {
        List<Subtask> subtasks = List.of(
                makeSubtask("s1", SubtaskStatus.COMPLETED),
                makeSubtask("s2", SubtaskStatus.COMPLETED),
                makeSubtask("s3", SubtaskStatus.RUNNING),
                makeSubtask("s4", SubtaskStatus.PENDING)
        );
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                subtasks, null, null);
        assertEquals(2, plan.completedCount());
    }

    @Test
    void failedCountMatchesFailedSubtasks() {
        List<Subtask> subtasks = List.of(
                makeSubtask("s1", SubtaskStatus.COMPLETED),
                makeSubtask("s2", SubtaskStatus.FAILED),
                makeSubtask("s3", SubtaskStatus.FAILED)
        );
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                subtasks, null, null);
        assertEquals(2, plan.failedCount());
    }

    @Test
    void isCompleteWhenAllTerminal() {
        List<Subtask> subtasks = List.of(
                makeSubtask("s1", SubtaskStatus.COMPLETED),
                makeSubtask("s2", SubtaskStatus.FAILED),
                makeSubtask("s3", SubtaskStatus.CANCELLED)
        );
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                subtasks, null, null);
        assertTrue(plan.isComplete());
    }

    @Test
    void isNotCompleteWhenSomeStillRunning() {
        List<Subtask> subtasks = List.of(
                makeSubtask("s1", SubtaskStatus.COMPLETED),
                makeSubtask("s2", SubtaskStatus.RUNNING)
        );
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                subtasks, null, null);
        assertFalse(plan.isComplete());
    }

    @Test
    void isNotCompleteWhenEmpty() {
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                List.of(), null, null);
        assertFalse(plan.isComplete());
    }

    @Test
    void isNotCompleteWhenSomePending() {
        List<Subtask> subtasks = List.of(
                makeSubtask("s1", SubtaskStatus.COMPLETED),
                makeSubtask("s2", SubtaskStatus.PENDING)
        );
        DecompositionPlan plan = new DecompositionPlan("plan-1", "prompt", "/path",
                subtasks, null, null);
        assertFalse(plan.isComplete());
    }

    private Subtask makeSubtask(String id, SubtaskStatus status) {
        return new Subtask(id, "name-" + id, "desc", AgentRole.EXPLORER,
                null, null, "/path", "prompt", null, status,
                null, null, null, null);
    }
}
