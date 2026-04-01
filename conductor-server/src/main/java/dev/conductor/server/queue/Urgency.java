package dev.conductor.server.queue;

/**
 * Message urgency levels, ordered from highest to lowest priority.
 *
 * <p>These levels drive UI behavior: CRITICAL triggers desktop notifications,
 * HIGH highlights in the feed, MEDIUM and LOW appear in the feed with
 * different visibility, and NOISE is dropped unless verbose mode is on.
 */
public enum Urgency {

    /**
     * Agent is blocked and needs human input (e.g., AskUserQuestion tool).
     * Triggers desktop notification + sound.
     */
    CRITICAL,

    /**
     * Agent error or task completion.
     * Triggers badge + feed highlight.
     */
    HIGH,

    /**
     * Tool use, progress update, text output.
     * Shown in feed only.
     */
    MEDIUM,

    /**
     * Thinking indicator, debug info, parse errors.
     * Collapsed in feed.
     */
    LOW,

    /**
     * Heartbeats, rate limit checks, system init.
     * Dropped unless verbose mode is enabled.
     */
    NOISE
}
