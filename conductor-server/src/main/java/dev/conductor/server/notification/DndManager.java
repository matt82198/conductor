package dev.conductor.server.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Do Not Disturb (DND) state for the notification system.
 *
 * <p>When DND is active, only {@link Urgency#CRITICAL} notifications are
 * delivered. All other urgency levels are suppressed by the
 * {@link NotificationRouter}.
 *
 * <p>DND state is a simple boolean toggle, persisted in-memory only.
 * Thread-safe via {@link AtomicBoolean}.
 *
 * <p>DND is controlled via:
 * <ul>
 *   <li>Programmatic: {@link #enable()} / {@link #disable()}</li>
 *   <li>REST (future): POST /api/notifications/dnd with {enabled: boolean}</li>
 *   <li>UI (future): toggle button on the dashboard</li>
 * </ul>
 */
@Service
public class DndManager {

    private static final Logger log = LoggerFactory.getLogger(DndManager.class);

    private final AtomicBoolean dndActive = new AtomicBoolean(false);

    /**
     * Enables Do Not Disturb mode.
     * Only CRITICAL notifications will be delivered after this call.
     */
    public void enable() {
        boolean wasDisabled = dndActive.compareAndSet(false, true);
        if (wasDisabled) {
            log.info("DND enabled — only CRITICAL notifications will be delivered");
        }
    }

    /**
     * Disables Do Not Disturb mode.
     * All notification urgency levels will be delivered as normal.
     */
    public void disable() {
        boolean wasEnabled = dndActive.compareAndSet(true, false);
        if (wasEnabled) {
            log.info("DND disabled — all notifications will be delivered");
        }
    }

    /**
     * Returns {@code true} if Do Not Disturb is currently active.
     */
    public boolean isEnabled() {
        return dndActive.get();
    }
}
