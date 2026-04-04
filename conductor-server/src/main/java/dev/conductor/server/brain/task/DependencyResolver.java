package dev.conductor.server.brain.task;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class that resolves subtask dependencies into execution waves
 * using Kahn's algorithm for topological sorting.
 *
 * <p>Wave 0 contains subtasks with no dependencies. Wave N contains
 * subtasks whose dependencies all appear in waves 0..N-1. This enables
 * maximum parallelism — all subtasks within a wave can run concurrently.
 *
 * <p>This class is stateless and has no Spring annotations. It is a pure
 * utility used by {@link TaskExecutor}.
 */
public final class DependencyResolver {

    private DependencyResolver() {
        // utility class — not instantiable
    }

    /**
     * Groups subtasks into execution waves based on their dependency graph.
     *
     * <p>Uses Kahn's algorithm for topological sort. Within each wave, all
     * subtasks are independent of each other and can execute in parallel.
     *
     * @param subtasks the list of subtasks to resolve
     * @return a list of waves, where each wave is a list of subtaskIds;
     *         wave index 0 runs first, then 1, etc.
     * @throws IllegalArgumentException if the dependency graph contains a cycle
     *         or references a non-existent subtaskId
     */
    public static List<List<String>> resolveWaves(List<Subtask> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) {
            return List.of();
        }

        // Build the set of known subtask IDs for validation
        Set<String> knownIds = subtasks.stream()
                .map(Subtask::subtaskId)
                .collect(Collectors.toSet());

        // Validate that all dependency references point to known subtask IDs
        for (Subtask subtask : subtasks) {
            for (String depId : subtask.dependsOn()) {
                if (!knownIds.contains(depId)) {
                    throw new IllegalArgumentException(
                            "Subtask '" + subtask.subtaskId() + "' depends on unknown subtask '" + depId + "'");
                }
            }
        }

        // Build in-degree map and adjacency list (dependency -> dependents)
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (Subtask subtask : subtasks) {
            String id = subtask.subtaskId();
            inDegree.put(id, subtask.dependsOn().size());
            dependents.putIfAbsent(id, new ArrayList<>());
        }

        // For each dependency edge: dep -> dependent, record in adjacency list
        for (Subtask subtask : subtasks) {
            for (String depId : subtask.dependsOn()) {
                dependents.computeIfAbsent(depId, k -> new ArrayList<>()).add(subtask.subtaskId());
            }
        }

        // Kahn's algorithm: process nodes with in-degree 0 in waves
        List<List<String>> waves = new ArrayList<>();
        int processedCount = 0;

        // Seed the first wave with all zero-dependency subtasks
        List<String> currentWave = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                currentWave.add(entry.getKey());
            }
        }

        while (!currentWave.isEmpty()) {
            waves.add(List.copyOf(currentWave));
            processedCount += currentWave.size();

            List<String> nextWave = new ArrayList<>();

            // For each node in the current wave, decrement in-degree of its dependents
            for (String nodeId : currentWave) {
                for (String dependentId : dependents.getOrDefault(nodeId, List.of())) {
                    int newDegree = inDegree.get(dependentId) - 1;
                    inDegree.put(dependentId, newDegree);
                    if (newDegree == 0) {
                        nextWave.add(dependentId);
                    }
                }
            }

            currentWave = nextWave;
        }

        // If we didn't process all nodes, there's a cycle
        if (processedCount != subtasks.size()) {
            // Find the cycle participants for a helpful error message
            List<String> cycleNodes = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            throw new IllegalArgumentException(
                    "Dependency cycle detected among subtasks: " + cycleNodes);
        }

        return List.copyOf(waves);
    }
}
