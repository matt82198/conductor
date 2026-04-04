package dev.conductor.server.brain.task;

import dev.conductor.common.AgentRole;
import dev.conductor.server.brain.context.ContextIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TaskDecomposer}.
 * Validates the template-based decomposition strategy for Phase 4C.
 */
class TaskDecomposerTest {

    private TaskDecomposer decomposer;
    private ContextIndex emptyContext;

    @BeforeEach
    void setUp() {
        decomposer = new TaskDecomposer();
        emptyContext = new ContextIndex(List.of(), null, null);
    }

    @Test
    void decomposesIntoThreeSubtasks() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        assertEquals(3, plan.subtasks().size());
    }

    @Test
    void planHasCreatedStatus() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        assertEquals(DecompositionPlan.STATUS_CREATED, plan.status());
    }

    @Test
    void planPreservesPromptAndPath() {
        DecompositionPlan plan = decomposer.decompose("Add OAuth2", "/my/project", emptyContext);
        assertEquals("Add OAuth2", plan.originalPrompt());
        assertEquals("/my/project", plan.projectPath());
    }

    @Test
    void firstSubtaskIsExplorer() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask first = plan.subtasks().get(0);
        assertEquals(AgentRole.EXPLORER, first.role());
        assertTrue(first.dependsOn().isEmpty());
    }

    @Test
    void secondSubtaskIsFeatureEngineer() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask second = plan.subtasks().get(1);
        assertEquals(AgentRole.FEATURE_ENGINEER, second.role());
    }

    @Test
    void thirdSubtaskIsReviewer() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask third = plan.subtasks().get(2);
        assertEquals(AgentRole.REVIEWER, third.role());
    }

    @Test
    void implementDependsOnExplore() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask explore = plan.subtasks().get(0);
        Subtask implement = plan.subtasks().get(1);
        assertTrue(implement.dependsOn().contains(explore.subtaskId()));
    }

    @Test
    void reviewDependsOnImplement() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask implement = plan.subtasks().get(1);
        Subtask review = plan.subtasks().get(2);
        assertTrue(review.dependsOn().contains(implement.subtaskId()));
    }

    @Test
    void implementReceivesContextFromExplore() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask explore = plan.subtasks().get(0);
        Subtask implement = plan.subtasks().get(1);
        assertTrue(implement.contextFrom().contains(explore.subtaskId()));
    }

    @Test
    void reviewReceivesContextFromBothPrior() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        Subtask explore = plan.subtasks().get(0);
        Subtask implement = plan.subtasks().get(1);
        Subtask review = plan.subtasks().get(2);
        assertTrue(review.contextFrom().contains(explore.subtaskId()));
        assertTrue(review.contextFrom().contains(implement.subtaskId()));
    }

    @Test
    void allSubtasksStartPending() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        for (Subtask s : plan.subtasks()) {
            assertEquals(SubtaskStatus.PENDING, s.status());
            assertNull(s.agentId());
            assertNull(s.result());
        }
    }

    @Test
    void promptIsEmbeddedInSubtaskPrompts() {
        DecompositionPlan plan = decomposer.decompose("Add OAuth2 auth", "/project", emptyContext);
        for (Subtask s : plan.subtasks()) {
            assertTrue(s.prompt().contains("Add OAuth2 auth"),
                    "Subtask prompt should contain the original prompt text");
        }
    }

    @Test
    void allSubtasksHaveProjectPath() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/my/path", emptyContext);
        for (Subtask s : plan.subtasks()) {
            assertEquals("/my/path", s.projectPath());
        }
    }

    @Test
    void dagIsValidForDependencyResolver() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        // Should not throw — the decomposition must produce a valid DAG
        List<List<String>> waves = DependencyResolver.resolveWaves(plan.subtasks());
        assertEquals(3, waves.size(), "Template decomposition should produce 3 sequential waves");
    }

    @Test
    void blankPromptThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> decomposer.decompose("", "/project", emptyContext));
        assertThrows(IllegalArgumentException.class,
                () -> decomposer.decompose("   ", "/project", emptyContext));
    }

    @Test
    void nullPromptThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> decomposer.decompose(null, "/project", emptyContext));
    }

    @Test
    void blankProjectPathThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> decomposer.decompose("Add a feature", "", emptyContext));
    }

    @Test
    void nullProjectPathThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> decomposer.decompose("Add a feature", null, emptyContext));
    }

    @Test
    void eachPlanHasUniqueId() {
        DecompositionPlan p1 = decomposer.decompose("task 1", "/project", emptyContext);
        DecompositionPlan p2 = decomposer.decompose("task 2", "/project", emptyContext);
        assertNotEquals(p1.planId(), p2.planId());
    }

    @Test
    void eachSubtaskHasUniqueId() {
        DecompositionPlan plan = decomposer.decompose("Add a feature", "/project", emptyContext);
        long distinctIds = plan.subtasks().stream().map(Subtask::subtaskId).distinct().count();
        assertEquals(plan.subtasks().size(), distinctIds);
    }
}
