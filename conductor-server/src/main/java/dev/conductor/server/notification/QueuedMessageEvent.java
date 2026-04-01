package dev.conductor.server.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Local representation of the event published by the queue/ domain when a
 * classified, filtered message is ready for notification routing.
 *
 * <p>The queue/ domain is being built in parallel. When it publishes its own
 * {@code QueuedMessageEvent}, the {@link NotificationRouter} will listen for
 * that event type. This local record serves as the contract definition so the
 * notification domain can be built and tested independently.
 *
 * <p><b>Contract synchronization:</b> When queue/ is built, either:
 * <ul>
 *   <li>Queue publishes this exact type (if it imports from notification/), or</li>
 *   <li>This class is replaced with an import from queue/, or</li>
 *   <li>A shared type is extracted to conductor-common/</li>
 * </ul>
 *
 * @param agentId   the agent that produced the message
 * @param text      the message text content
 * @param urgency   the classified urgency level
 * @param category  the message category (e.g., "tool_use", "error", "thinking")
 * @param timestamp when the message was originally produced
 */
public record QueuedMessageEvent(
        UUID agentId,
        String text,
        Urgency urgency,
        String category,
        Instant timestamp
) {

    /**
     * Compact constructor — enforces non-null invariants.
     */
    public QueuedMessageEvent {
        if (agentId == null) throw new IllegalArgumentException("agentId must not be null");
        if (text == null) text = "";
        if (urgency == null) urgency = Urgency.MEDIUM;
        if (category == null) category = "unknown";
        if (timestamp == null) timestamp = Instant.now();
    }

    /**
     * Converts this event into a {@link NotificationPayload} for channel delivery.
     */
    public NotificationPayload toPayload() {
        return new NotificationPayload(agentId, text, urgency, timestamp);
    }
}
