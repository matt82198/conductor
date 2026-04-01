package dev.conductor.server.notification;

/**
 * Urgency classification for queued messages, used to determine
 * notification routing and DND behavior.
 *
 * <p>Matches the urgency levels defined in the queue/ domain's
 * classification rules. This enum is local to the notification
 * domain to avoid coupling; the queue domain may define its own
 * identical enum. The router maps between them at the boundary.
 *
 * @see NotificationRouter
 */
public enum Urgency {

    /** Agent blocked, needs human input. Desktop notification + sound. */
    CRITICAL,

    /** Agent error, task complete. Badge + feed highlight. */
    HIGH,

    /** Tool use, progress update. Feed only. */
    MEDIUM,

    /** Thinking indicator, debug info. Collapsed in feed. */
    LOW,

    /** Heartbeats, rate limit checks. Dropped unless verbose mode. */
    NOISE;

    /**
     * Returns true if this urgency level should always be delivered,
     * even when Do Not Disturb is active.
     */
    public boolean bypassesDnd() {
        return this == CRITICAL;
    }

    /**
     * Returns true if this urgency level should be silently dropped.
     */
    public boolean isDropped() {
        return this == NOISE;
    }
}
