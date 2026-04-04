package dev.conductor.server.brain.task;

import java.time.Instant;

/**
 * Spring application event published by the {@link TaskExecutor} whenever
 * a subtask changes state within an executing {@link DecompositionPlan}.
 *
 * <p>Consumed by the WebSocket layer to push real-time task progress
 * to the Conductor UI dashboard.
 *
 * @param planId       the plan this progress update belongs to
 * @param completed    number of subtasks that have completed successfully
 * @param total        total number of subtasks in the plan
 * @param currentPhase the current execution wave label (e.g., "wave-1", "wave-2")
 * @param timestamp    when this progress event was generated
 */
public record TaskProgressEvent(
        String planId,
        long completed,
        long total,
        String currentPhase,
        Instant timestamp
) {

    public TaskProgressEvent {
        if (timestamp == null) timestamp = Instant.now();
    }
}
