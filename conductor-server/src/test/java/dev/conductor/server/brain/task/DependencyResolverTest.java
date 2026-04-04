package dev.conductor.server.brain.task;

import dev.conductor.common.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DependencyResolver}.
 * Validates Kahn's algorithm for topological sorting into waves.
 */
class DependencyResolverTest {

    @Test
    void emptyListReturnsEmptyWaves() {
        List<List<String>> waves = DependencyResolver.resolveWaves(List.of());
        assertTrue(waves.isEmpty());
    }

    @Test
    void nullListReturnsEmptyWaves() {
        List<List<String>> waves = DependencyResolver.resolveWaves(null);
        assertTrue(waves.isEmpty());
    }

    @Test
    void singleTaskWithNoDependencies() {
        Subtask s = makeSubtask("s1", List.of());
        List<List<String>> waves = DependencyResolver.resolveWaves(List.of(s));
        assertEquals(1, waves.size());
        assertEquals(List.of("s1"), waves.get(0));
    }

    @Test
    void linearChainProducesOneTaskPerWave() {
        Subtask s1 = makeSubtask("s1", List.of());
        Subtask s2 = makeSubtask("s2", List.of("s1"));
        Subtask s3 = makeSubtask("s3", List.of("s2"));

        List<List<String>> waves = DependencyResolver.resolveWaves(List.of(s1, s2, s3));

        assertEquals(3, waves.size());
        assertEquals(List.of("s1"), waves.get(0));
        assertEquals(List.of("s2"), waves.get(1));
        assertEquals(List.of("s3"), waves.get(2));
    }

    @Test
    void parallelTasksInSameWave() {
        Subtask s1 = makeSubtask("s1", List.of());
        Subtask s2 = makeSubtask("s2", List.of());
        Subtask s3 = makeSubtask("s3", List.of("s1", "s2"));

        List<List<String>> waves = DependencyResolver.resolveWaves(List.of(s1, s2, s3));

        assertEquals(2, waves.size());
        // Wave 0: s1 and s2 (order within wave doesn't matter)
        assertTrue(waves.get(0).containsAll(List.of("s1", "s2")));
        assertEquals(2, waves.get(0).size());
        // Wave 1: s3
        assertEquals(List.of("s3"), waves.get(1));
    }

    @Test
    void diamondDependencyPattern() {
        //   s1
        //  / \
        // s2  s3
        //  \ /
        //   s4
        Subtask s1 = makeSubtask("s1", List.of());
        Subtask s2 = makeSubtask("s2", List.of("s1"));
        Subtask s3 = makeSubtask("s3", List.of("s1"));
        Subtask s4 = makeSubtask("s4", List.of("s2", "s3"));

        List<List<String>> waves = DependencyResolver.resolveWaves(List.of(s1, s2, s3, s4));

        assertEquals(3, waves.size());
        assertEquals(List.of("s1"), waves.get(0));
        assertTrue(waves.get(1).containsAll(List.of("s2", "s3")));
        assertEquals(2, waves.get(1).size());
        assertEquals(List.of("s4"), waves.get(2));
    }

    @Test
    void standardTemplateDecomposition() {
        // The default 3-step template: explore -> implement -> review
        Subtask explore = makeSubtask("explore", List.of());
        Subtask implement = makeSubtask("implement", List.of("explore"));
        Subtask review = makeSubtask("review", List.of("implement"));

        List<List<String>> waves = DependencyResolver.resolveWaves(
                List.of(explore, implement, review));

        assertEquals(3, waves.size());
        assertEquals(List.of("explore"), waves.get(0));
        assertEquals(List.of("implement"), waves.get(1));
        assertEquals(List.of("review"), waves.get(2));
    }

    @Test
    void cycleBetweenTwoThrows() {
        Subtask s1 = makeSubtask("s1", List.of("s2"));
        Subtask s2 = makeSubtask("s2", List.of("s1"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DependencyResolver.resolveWaves(List.of(s1, s2)));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void cycleAmongThreeThrows() {
        Subtask s1 = makeSubtask("s1", List.of("s3"));
        Subtask s2 = makeSubtask("s2", List.of("s1"));
        Subtask s3 = makeSubtask("s3", List.of("s2"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DependencyResolver.resolveWaves(List.of(s1, s2, s3)));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void selfDependencyThrows() {
        Subtask s1 = makeSubtask("s1", List.of("s1"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DependencyResolver.resolveWaves(List.of(s1)));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void unknownDependencyThrows() {
        Subtask s1 = makeSubtask("s1", List.of("nonexistent"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DependencyResolver.resolveWaves(List.of(s1)));
        assertTrue(ex.getMessage().contains("unknown"));
    }

    @Test
    void allIndependentTasksInOneWave() {
        Subtask s1 = makeSubtask("s1", List.of());
        Subtask s2 = makeSubtask("s2", List.of());
        Subtask s3 = makeSubtask("s3", List.of());
        Subtask s4 = makeSubtask("s4", List.of());

        List<List<String>> waves = DependencyResolver.resolveWaves(List.of(s1, s2, s3, s4));

        assertEquals(1, waves.size());
        assertEquals(4, waves.get(0).size());
        assertTrue(waves.get(0).containsAll(List.of("s1", "s2", "s3", "s4")));
    }

    @Test
    void complexDagWithMultipleWaves() {
        //   s1    s2
        //   |    / |
        //   s3  s4 |
        //    \  |  |
        //     s5   |
        //      \  /
        //       s6
        Subtask s1 = makeSubtask("s1", List.of());
        Subtask s2 = makeSubtask("s2", List.of());
        Subtask s3 = makeSubtask("s3", List.of("s1"));
        Subtask s4 = makeSubtask("s4", List.of("s2"));
        Subtask s5 = makeSubtask("s5", List.of("s3", "s4"));
        Subtask s6 = makeSubtask("s6", List.of("s5", "s2"));

        List<List<String>> waves = DependencyResolver.resolveWaves(
                List.of(s1, s2, s3, s4, s5, s6));

        assertEquals(4, waves.size());
        // Wave 0: s1, s2
        assertTrue(waves.get(0).containsAll(List.of("s1", "s2")));
        // Wave 1: s3, s4
        assertTrue(waves.get(1).containsAll(List.of("s3", "s4")));
        // Wave 2: s5
        assertEquals(List.of("s5"), waves.get(2));
        // Wave 3: s6
        assertEquals(List.of("s6"), waves.get(3));
    }

    @Test
    void resultListsAreImmutable() {
        Subtask s1 = makeSubtask("s1", List.of());
        List<List<String>> waves = DependencyResolver.resolveWaves(List.of(s1));
        assertThrows(UnsupportedOperationException.class,
                () -> waves.add(List.of("new")));
        assertThrows(UnsupportedOperationException.class,
                () -> waves.get(0).add("new"));
    }

    private Subtask makeSubtask(String id, List<String> dependsOn) {
        return new Subtask(id, "name-" + id, "desc", AgentRole.EXPLORER,
                dependsOn, null, "/path", "prompt", null, SubtaskStatus.PENDING,
                null, null, null, null);
    }
}
