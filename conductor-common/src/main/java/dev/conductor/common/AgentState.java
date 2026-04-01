package dev.conductor.common;

/**
 * Lifecycle states for a managed Claude agent process.
 *
 * <pre>
 * LAUNCHING ──► ACTIVE ──► THINKING ──► USING_TOOL ──► ACTIVE ──► ...
 *                  │                                        │
 *                  ├──► BLOCKED ──► ACTIVE (after human input)
 *                  │
 *                  ├──► COMPLETED (normal exit)
 *                  └──► FAILED    (crash / kill / error)
 * </pre>
 */
public enum AgentState {

    /** Process spawned, waiting for first system(init) event. */
    LAUNCHING,

    /** Agent is actively processing (between tool calls). */
    ACTIVE,

    /** Agent is in extended thinking mode. */
    THINKING,

    /** Agent is executing a tool (Bash, Read, Write, etc.). */
    USING_TOOL,

    /** Agent is blocked waiting for human input or permission. */
    BLOCKED,

    /** Agent completed its task successfully. */
    COMPLETED,

    /** Agent process crashed, was killed, or encountered an unrecoverable error. */
    FAILED;

    /**
     * Returns true if this state represents a terminal condition
     * (the agent will not produce further output).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * Returns true if the agent is alive and potentially doing work.
     */
    public boolean isAlive() {
        return !isTerminal();
    }
}
