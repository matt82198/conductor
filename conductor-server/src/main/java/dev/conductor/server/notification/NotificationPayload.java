package dev.conductor.server.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable carrier for a notification message routed to one or more channels.
 *
 * <p>This record captures everything a channel needs to deliver the
 * notification: the originating agent, the text content, the urgency
 * classification, and the timestamp of the original message.
 *
 * @param agentId   the agent that produced the original message
 * @param text      the human-readable notification content
 * @param urgency   classification that drives routing decisions
 * @param timestamp when the original message was produced
 */
public record NotificationPayload(
        UUID agentId,
        String text,
        Urgency urgency,
        Instant timestamp
) {

    /**
     * Compact constructor — enforces non-null invariants.
     */
    public NotificationPayload {
        if (agentId == null) throw new IllegalArgumentException("agentId must not be null");
        if (text == null) throw new IllegalArgumentException("text must not be null");
        if (urgency == null) throw new IllegalArgumentException("urgency must not be null");
        if (timestamp == null) timestamp = Instant.now();
    }
}
