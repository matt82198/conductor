package dev.conductor.server.notification;

import dev.conductor.server.queue.QueuedMessage;
import dev.conductor.server.queue.QueuedMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges the queue/ domain's {@link QueuedMessageEvent} into the notification
 * domain's own event type and routing pipeline.
 *
 * <p>The notification domain defines its own
 * {@link dev.conductor.server.notification.QueuedMessageEvent} to stay decoupled
 * from the queue domain's internals. This bridge listens for the queue's event
 * type and translates it into a direct {@link NotificationRouter#route} call,
 * mapping the queue's {@link dev.conductor.server.queue.Urgency} to the
 * notification domain's {@link Urgency}.
 *
 * <p>This adapter exists because both domains were built in parallel with their
 * own event types. The bridge is the single point of coupling between them.
 *
 * <p>Thread-safe: stateless — delegates to thread-safe services.
 */
@Component
public class QueueEventBridge {

    private static final Logger log = LoggerFactory.getLogger(QueueEventBridge.class);

    private final NotificationRouter router;

    public QueueEventBridge(NotificationRouter router) {
        this.router = router;
    }

    /**
     * Listens for the queue domain's {@link QueuedMessageEvent} and routes
     * the message through the notification pipeline.
     *
     * <p>Maps the queue's urgency enum to the notification domain's urgency
     * enum by name, then constructs a {@link NotificationPayload} for routing.
     *
     * @param event the queue domain's event carrying a classified message
     */
    @EventListener
    public void onQueueEvent(QueuedMessageEvent event) {
        QueuedMessage message = event.message();

        try {
            // Map queue urgency -> notification urgency by name
            Urgency notificationUrgency = mapUrgency(message.urgency());

            NotificationPayload payload = new NotificationPayload(
                    message.agentId(),
                    message.text(),
                    notificationUrgency,
                    message.timestamp()
            );

            router.route(payload);

            log.trace("Bridged queue event to notification: agent={} urgency={}",
                    message.agentId(), notificationUrgency);
        } catch (Exception e) {
            log.error("Failed to bridge queue event for agent {}: {}",
                    message.agentId(), e.getMessage(), e);
        }
    }

    /**
     * Maps a queue-domain urgency to a notification-domain urgency by enum name.
     *
     * <p>Both enums define the same constants (CRITICAL, HIGH, MEDIUM, LOW, NOISE),
     * so the mapping is a simple name lookup. If a new queue urgency is added that
     * the notification domain doesn't know about, this defaults to MEDIUM
     * (fail-safe: deliver rather than drop, but don't over-alert).
     *
     * @param queueUrgency the queue domain's urgency classification
     * @return the corresponding notification domain urgency
     */
    private static Urgency mapUrgency(dev.conductor.server.queue.Urgency queueUrgency) {
        try {
            return Urgency.valueOf(queueUrgency.name());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown queue urgency '{}' — defaulting to MEDIUM", queueUrgency);
            return Urgency.MEDIUM;
        }
    }
}
