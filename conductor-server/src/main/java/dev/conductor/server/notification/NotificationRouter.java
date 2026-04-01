package dev.conductor.server.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes classified messages to the appropriate notification channel(s)
 * based on urgency level and DND state.
 *
 * <p>Listens for {@link QueuedMessageEvent}s published by the queue/ domain
 * (via Spring's {@code ApplicationEventPublisher}) and delivers them to
 * one or more {@link NotificationChannel} implementations.
 *
 * <h3>Routing Rules</h3>
 * <pre>
 * | Urgency  | Channels              | DND Behavior       |
 * |----------|-----------------------|--------------------|
 * | CRITICAL | WebSocket + Desktop   | Always delivered   |
 * | HIGH     | WebSocket + Desktop   | Suppressed         |
 * | MEDIUM   | WebSocket only        | Suppressed         |
 * | LOW      | WebSocket only        | Suppressed         |
 * | NOISE    | Dropped               | Dropped            |
 * </pre>
 *
 * <p>Channel discovery is automatic: all Spring beans implementing
 * {@link NotificationChannel} are injected. The router identifies channels
 * by their {@link NotificationChannel#channelName()} return value.
 *
 * <p>Thread safety: this class is stateless beyond the injected dependencies.
 * The {@link DndManager} is thread-safe. Channel implementations must also
 * be thread-safe.
 */
@Service
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

    private final DndManager dndManager;
    private final Map<String, NotificationChannel> channelsByName;
    private final List<NotificationChannel> allChannels;

    /**
     * @param dndManager  manages Do Not Disturb state
     * @param allChannels all discovered notification channel beans
     */
    public NotificationRouter(DndManager dndManager, List<NotificationChannel> allChannels) {
        this.dndManager = dndManager;
        this.allChannels = allChannels;

        // Build a name-indexed lookup for routing rules
        Map<String, NotificationChannel> map = new HashMap<>();
        for (NotificationChannel channel : allChannels) {
            map.put(channel.channelName(), channel);
        }
        this.channelsByName = Map.copyOf(map);

        log.info("NotificationRouter initialized with {} channels: {}",
                allChannels.size(), channelsByName.keySet());
    }

    /**
     * Spring event listener that receives classified messages from the queue/
     * domain and routes them to the appropriate channels.
     *
     * @param event the queued message event to route
     */
    @EventListener
    public void onQueuedMessage(QueuedMessageEvent event) {
        route(event.toPayload());
    }

    /**
     * Routes a notification payload to the appropriate channel(s) based on
     * urgency and DND state.
     *
     * <p>This method is also exposed for direct invocation (e.g., from tests
     * or future REST endpoints that bypass the event bus).
     *
     * @param payload the notification to route
     */
    public void route(NotificationPayload payload) {
        Urgency urgency = payload.urgency();

        // NOISE is always dropped
        if (urgency.isDropped()) {
            log.trace("Dropped NOISE notification from agent {}", payload.agentId());
            return;
        }

        // Check DND — only CRITICAL bypasses
        boolean dndActive = dndManager.isEnabled();
        if (dndActive && !urgency.bypassesDnd()) {
            log.debug("DND active — suppressed {} notification from agent {}",
                    urgency, payload.agentId());
            return;
        }

        // Determine target channels based on urgency
        List<String> targetChannels = resolveChannels(urgency);

        // Deliver to each target channel
        int delivered = 0;
        for (String channelName : targetChannels) {
            NotificationChannel channel = channelsByName.get(channelName);
            if (channel != null) {
                try {
                    channel.send(payload);
                    delivered++;
                } catch (Exception e) {
                    log.error("Channel '{}' failed to deliver notification for agent {}: {}",
                            channelName, payload.agentId(), e.getMessage(), e);
                }
            } else {
                log.warn("Configured channel '{}' not found in registered channels", channelName);
            }
        }

        log.debug("Routed {} notification from agent {} to {}/{} channels (dnd={})",
                urgency, payload.agentId(), delivered, targetChannels.size(), dndActive);
    }

    /**
     * Resolves the list of channel names that should receive a notification
     * at the given urgency level.
     *
     * @param urgency the notification urgency
     * @return ordered list of channel names to deliver to
     */
    private List<String> resolveChannels(Urgency urgency) {
        return switch (urgency) {
            case CRITICAL -> List.of("websocket", "desktop");
            case HIGH     -> List.of("websocket", "desktop");
            case MEDIUM   -> List.of("websocket");
            case LOW      -> List.of("websocket");
            case NOISE    -> List.of();  // should not reach here (dropped above)
        };
    }
}
