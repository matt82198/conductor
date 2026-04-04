package dev.conductor.server.api;

import dev.conductor.server.notification.DndManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NotificationSettingsController}.
 *
 * <p>Uses a real {@link DndManager} (simple in-memory toggle with no
 * external dependencies).
 */
class NotificationSettingsControllerTest {

    private DndManager dndManager;
    private NotificationSettingsController controller;

    @BeforeEach
    void setUp() {
        dndManager = new DndManager();
        controller = new NotificationSettingsController(dndManager);
    }

    // ─── GET /dnd ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET dnd returns false by default")
    void getDndDefaultFalse() {
        ResponseEntity<?> response = controller.getDndState();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("enabled"));
    }

    @Test
    @DisplayName("GET dnd returns true when enabled")
    void getDndEnabled() {
        dndManager.enable();

        ResponseEntity<?> response = controller.getDndState();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("enabled"));
    }

    // ─── POST /dnd ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST dnd enabled=true enables DND")
    void enableDnd() {
        ResponseEntity<?> response = controller.setDndState(
                new NotificationSettingsController.DndRequest(true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("enabled"));
        assertTrue(dndManager.isEnabled());
    }

    @Test
    @DisplayName("POST dnd enabled=false disables DND")
    void disableDnd() {
        dndManager.enable();

        ResponseEntity<?> response = controller.setDndState(
                new NotificationSettingsController.DndRequest(false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("enabled"));
        assertFalse(dndManager.isEnabled());
    }

    @Test
    @DisplayName("POST dnd enable is idempotent")
    void enableIdempotent() {
        controller.setDndState(new NotificationSettingsController.DndRequest(true));
        controller.setDndState(new NotificationSettingsController.DndRequest(true));

        assertTrue(dndManager.isEnabled());
    }

    @Test
    @DisplayName("POST dnd disable when already disabled is safe")
    void disableIdempotent() {
        controller.setDndState(new NotificationSettingsController.DndRequest(false));

        assertFalse(dndManager.isEnabled());
    }

    @Test
    @DisplayName("Toggle cycle works correctly through REST")
    void toggleCycle() {
        controller.setDndState(new NotificationSettingsController.DndRequest(true));
        assertTrue(dndManager.isEnabled());

        controller.setDndState(new NotificationSettingsController.DndRequest(false));
        assertFalse(dndManager.isEnabled());

        controller.setDndState(new NotificationSettingsController.DndRequest(true));
        assertTrue(dndManager.isEnabled());
    }
}
