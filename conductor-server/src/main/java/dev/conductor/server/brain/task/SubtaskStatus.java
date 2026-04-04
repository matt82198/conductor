package dev.conductor.server.brain.task;

/**
 * Lifecycle status of a subtask within a decomposition plan.
 *
 * <p>Transitions:
 * <pre>
 * PENDING -> RUNNING -> COMPLETED
 *                    -> FAILED
 *         -> CANCELLED
 * </pre>
 */
public enum SubtaskStatus {

    /** Not yet started — waiting for dependencies. */
    PENDING,

    /** An agent has been spawned and is executing this subtask. */
    RUNNING,

    /** The agent completed the subtask successfully. */
    COMPLETED,

    /** The agent failed to complete the subtask. */
    FAILED,

    /** The subtask was cancelled before completion (e.g., plan was cancelled). */
    CANCELLED;

    /**
     * Returns true if this status is terminal — no further transitions expected.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
