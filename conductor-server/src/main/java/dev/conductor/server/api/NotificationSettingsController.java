package dev.conductor.server.api;

import dev.conductor.server.notification.DndManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for notification settings management.
 *
 * <p>Provides endpoints for controlling Do Not Disturb (DND) mode.
 * When DND is active, only CRITICAL notifications are delivered;
 * all other urgency levels are suppressed by the
 * {@link dev.conductor.server.notification.NotificationRouter}.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationSettingsController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsController.class);

    private final DndManager dndManager;

    public NotificationSettingsController(DndManager dndManager) {
        this.dndManager = dndManager;
    }

    // ─── Get DND State ────────────────────────────────────────────────

    /**
     * Returns the current Do Not Disturb state.
     *
     * @return JSON with a single {@code enabled} boolean field
     */
    @GetMapping("/dnd")
    public ResponseEntity<?> getDndState() {
        return ResponseEntity.ok(Map.of("enabled", dndManager.isEnabled()));
    }

    // ─── Set DND State ────────────────────────────────────────────────

    /**
     * Enables or disables Do Not Disturb mode.
     *
     * <p>Request body:
     * <pre>
     * { "enabled": true }
     * </pre>
     *
     * <p>When enabled, only CRITICAL notifications are delivered.
     * When disabled, all urgency levels flow normally.
     *
     * @param request body containing the desired DND state
     * @return the resulting DND state
     */
    @PostMapping("/dnd")
    public ResponseEntity<?> setDndState(@RequestBody DndRequest request) {
        if (request.enabled()) {
            dndManager.enable();
            log.info("DND enabled via REST");
        } else {
            dndManager.disable();
            log.info("DND disabled via REST");
        }
        return ResponseEntity.ok(Map.of("enabled", dndManager.isEnabled()));
    }

    record DndRequest(boolean enabled) {}
}
