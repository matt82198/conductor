package dev.conductor.server.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DndManager} — thread-safe DND toggle.
 */
class DndManagerTest {

    private DndManager dndManager;

    @BeforeEach
    void setUp() {
        dndManager = new DndManager();
    }

    @Test
    @DisplayName("DND is disabled by default")
    void defaultState() {
        assertFalse(dndManager.isEnabled());
    }

    @Test
    @DisplayName("enable() turns DND on")
    void enableTurnsDndOn() {
        dndManager.enable();
        assertTrue(dndManager.isEnabled());
    }

    @Test
    @DisplayName("disable() turns DND off after enable")
    void disableTurnsDndOff() {
        dndManager.enable();
        dndManager.disable();
        assertFalse(dndManager.isEnabled());
    }

    @Test
    @DisplayName("double enable is idempotent")
    void doubleEnableIsIdempotent() {
        dndManager.enable();
        dndManager.enable();
        assertTrue(dndManager.isEnabled());
    }

    @Test
    @DisplayName("double disable is idempotent")
    void doubleDisableIsIdempotent() {
        dndManager.disable();
        dndManager.disable();
        assertFalse(dndManager.isEnabled());
    }

    @Test
    @DisplayName("enable/disable cycle works repeatedly")
    void toggleCycle() {
        for (int i = 0; i < 10; i++) {
            dndManager.enable();
            assertTrue(dndManager.isEnabled(), "Should be enabled on iteration " + i);
            dndManager.disable();
            assertFalse(dndManager.isEnabled(), "Should be disabled on iteration " + i);
        }
    }
}
