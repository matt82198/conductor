package dev.conductor.server.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Notification channel for desktop (Electron) notifications.
 *
 * <p><b>Phase 1:</b> Logs the notification at INFO level. This is a placeholder
 * implementation that proves the routing works end-to-end.
 *
 * <p><b>Future:</b> Will POST to the Electron app's local notification API
 * (e.g., {@code http://localhost:PORT/api/notify}) to trigger native OS
 * notifications with sound and system-tray integration.
 *
 * <p>Thread-safe: stateless — all state is in the log call.
 */
@Component
public class DesktopChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(DesktopChannel.class);

    @Override
    public void send(NotificationPayload payload) {
        try {
            log.info("DESKTOP NOTIFICATION: [{}] agent={} | {}",
                    payload.urgency(),
                    payload.agentId(),
                    payload.text());
        } catch (Exception e) {
            // Even a log call can fail (e.g., appender error). Never propagate.
            log.error("Failed to log desktop notification for agent {}: {}",
                    payload.agentId(), e.getMessage());
        }
    }

    @Override
    public String channelName() {
        return "desktop";
    }
}
