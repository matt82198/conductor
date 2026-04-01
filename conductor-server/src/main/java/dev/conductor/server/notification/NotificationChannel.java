package dev.conductor.server.notification;

/**
 * Abstraction for a notification delivery channel.
 *
 * <p>Each implementation pushes a {@link NotificationPayload} to a specific
 * destination (WebSocket, desktop, email, Slack, etc.). New channels are
 * added by creating a new {@code @Component} that implements this interface;
 * the {@link NotificationRouter} discovers them via Spring's dependency injection.
 *
 * <p>Implementations must be thread-safe. The router may invoke {@code send}
 * concurrently from multiple event-listener threads.
 */
public interface NotificationChannel {

    /**
     * Delivers a notification to this channel's destination.
     *
     * <p>Implementations should catch and log internal errors rather than
     * propagating them, so that one failed channel does not block others.
     *
     * @param payload the notification to deliver, never {@code null}
     */
    void send(NotificationPayload payload);

    /**
     * Returns a human-readable name for this channel (e.g., "websocket", "desktop").
     * Used in log messages and audit trails.
     */
    String channelName();
}
