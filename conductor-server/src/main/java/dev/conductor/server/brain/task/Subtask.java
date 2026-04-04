package dev.conductor.server.brain.task;

import dev.conductor.common.AgentRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single unit of work within a {@link DecompositionPlan}.
 *
 * <p>Each subtask maps to one Claude CLI agent. Subtasks form a directed
 * acyclic graph (DAG) via {@code dependsOn} relationships, and can share
 * output context via {@code contextFrom} declarations.
 *
 * <p>Immutable record — state changes produce new instances via
 * {@code with*()} methods, consistent with the AgentRecord pattern.
 *
 * @param subtaskId       unique identifier for this subtask
 * @param name            short human-readable label (e.g., "Explore auth setup")
 * @param description     detailed description of what this subtask does
 * @param role            the AgentRole to assign when spawning the agent
 * @param dependsOn       subtaskIds that must complete before this can start
 * @param contextFrom     subtaskIds whose output should be shared as context
 * @param projectPath     absolute path to the project working directory
 * @param prompt          specific prompt text for the spawned agent
 * @param successCriteria how to know the subtask is done (nullable, informational)
 * @param status          current lifecycle status
 * @param agentId         UUID of the spawned agent (null until RUNNING)
 * @param result          output summary when completed (null until terminal)
 * @param startedAt       when the agent was spawned (null until RUNNING)
 * @param completedAt     when the subtask reached a terminal status (null until terminal)
 */
public record Subtask(
        String subtaskId,
        String name,
        String description,
        AgentRole role,
        List<String> dependsOn,
        List<String> contextFrom,
        String projectPath,
        String prompt,
        String successCriteria,
        SubtaskStatus status,
        UUID agentId,
        String result,
        Instant startedAt,
        Instant completedAt
) {

    public Subtask {
        if (subtaskId == null) subtaskId = UUID.randomUUID().toString();
        if (dependsOn == null) dependsOn = List.of();
        if (contextFrom == null) contextFrom = List.of();
        if (status == null) status = SubtaskStatus.PENDING;
    }

    /**
     * Returns a copy with the given status.
     */
    public Subtask withStatus(SubtaskStatus newStatus) {
        return new Subtask(subtaskId, name, description, role, dependsOn, contextFrom,
                projectPath, prompt, successCriteria, newStatus, agentId, result, startedAt, completedAt);
    }

    /**
     * Returns a copy with the given agent ID and a startedAt timestamp of now.
     */
    public Subtask withAgent(UUID newAgentId) {
        return new Subtask(subtaskId, name, description, role, dependsOn, contextFrom,
                projectPath, prompt, successCriteria, status, newAgentId, result, Instant.now(), completedAt);
    }

    /**
     * Returns a copy with the given result text, new status, and a completedAt timestamp of now.
     */
    public Subtask withResult(String newResult, SubtaskStatus newStatus) {
        return new Subtask(subtaskId, name, description, role, dependsOn, contextFrom,
                projectPath, prompt, successCriteria, newStatus, agentId, newResult, startedAt, Instant.now());
    }
}
