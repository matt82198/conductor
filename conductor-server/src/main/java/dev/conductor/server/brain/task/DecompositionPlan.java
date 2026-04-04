package dev.conductor.server.brain.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A complete task decomposition plan — a set of {@link Subtask}s with dependency
 * relationships that form a DAG, produced by the {@link TaskDecomposer}.
 *
 * <p>Plans are executed by the {@link TaskExecutor} in waves: subtasks whose
 * dependencies are all satisfied run in parallel as a single wave.
 *
 * <p>Immutable record — state changes produce new instances via {@code with*()}
 * methods, consistent with the project's record conventions.
 *
 * @param planId         unique identifier for this plan
 * @param originalPrompt the user's original high-level prompt
 * @param projectPath    absolute path to the target project
 * @param subtasks       the ordered list of subtasks in this plan
 * @param createdAt      when the plan was created
 * @param status         plan lifecycle: CREATED, EXECUTING, COMPLETED, FAILED, CANCELLED
 */
public record DecompositionPlan(
        String planId,
        String originalPrompt,
        String projectPath,
        List<Subtask> subtasks,
        Instant createdAt,
        String status
) {

    /** Plan has been created but execution has not started. */
    public static final String STATUS_CREATED = "CREATED";

    /** Plan is actively executing — agents are running. */
    public static final String STATUS_EXECUTING = "EXECUTING";

    /** All subtasks completed successfully. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** One or more subtasks failed and the plan cannot continue. */
    public static final String STATUS_FAILED = "FAILED";

    /** The plan was cancelled by the user. */
    public static final String STATUS_CANCELLED = "CANCELLED";

    public DecompositionPlan {
        if (planId == null) planId = UUID.randomUUID().toString();
        if (subtasks == null) subtasks = List.of();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = STATUS_CREATED;
    }

    /**
     * Returns a copy with the given subtask replaced (matched by subtaskId).
     */
    public DecompositionPlan withSubtask(String subtaskId, Subtask updated) {
        List<Subtask> newList = subtasks.stream()
                .map(s -> s.subtaskId().equals(subtaskId) ? updated : s)
                .toList();
        return new DecompositionPlan(planId, originalPrompt, projectPath, newList, createdAt, status);
    }

    /**
     * Returns a copy with the given plan status.
     */
    public DecompositionPlan withStatus(String newStatus) {
        return new DecompositionPlan(planId, originalPrompt, projectPath, subtasks, createdAt, newStatus);
    }

    /**
     * Returns the number of subtasks that have completed successfully.
     */
    public long completedCount() {
        return subtasks.stream().filter(s -> s.status() == SubtaskStatus.COMPLETED).count();
    }

    /**
     * Returns the number of subtasks that have failed.
     */
    public long failedCount() {
        return subtasks.stream().filter(s -> s.status() == SubtaskStatus.FAILED).count();
    }

    /**
     * Returns true if all subtasks are in a terminal state.
     */
    public boolean isComplete() {
        return !subtasks.isEmpty() && subtasks.stream().allMatch(s -> s.status().isTerminal());
    }
}
