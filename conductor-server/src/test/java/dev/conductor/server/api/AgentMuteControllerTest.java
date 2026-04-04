package dev.conductor.server.api;

import dev.conductor.server.queue.MuteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentMuteController}.
 *
 * <p>Uses a real {@link MuteRegistry} (it's a simple in-memory service
 * with no external dependencies), which gives more confidence than mocking.
 */
class AgentMuteControllerTest {

    private MuteRegistry muteRegistry;
    private AgentMuteController controller;

    @BeforeEach
    void setUp() {
        muteRegistry = new MuteRegistry();
        controller = new AgentMuteController(muteRegistry, null);
    }

    @Test
    @DisplayName("POST mute=true mutes the agent")
    void muteAgent() {
        UUID agentId = UUID.randomUUID();

        ResponseEntity<?> response = controller.setMuteState(agentId, new AgentMuteController.MuteRequest(true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(agentId.toString(), body.get("agentId"));
        assertEquals(true, body.get("muted"));
        assertTrue(muteRegistry.isMuted(agentId));
    }

    @Test
    @DisplayName("POST mute=false unmutes the agent")
    void unmuteAgent() {
        UUID agentId = UUID.randomUUID();
        muteRegistry.mute(agentId);

        ResponseEntity<?> response = controller.setMuteState(agentId, new AgentMuteController.MuteRequest(false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(agentId.toString(), body.get("agentId"));
        assertEquals(false, body.get("muted"));
        assertFalse(muteRegistry.isMuted(agentId));
    }

    @Test
    @DisplayName("GET muted returns false for unmuted agent")
    void getMuteStateUnmuted() {
        UUID agentId = UUID.randomUUID();

        ResponseEntity<?> response = controller.getMuteState(agentId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("muted"));
    }

    @Test
    @DisplayName("GET muted returns true for muted agent")
    void getMuteStateMuted() {
        UUID agentId = UUID.randomUUID();
        muteRegistry.mute(agentId);

        ResponseEntity<?> response = controller.getMuteState(agentId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("muted"));
    }

    @Test
    @DisplayName("Muting twice is idempotent")
    void muteIdempotent() {
        UUID agentId = UUID.randomUUID();

        controller.setMuteState(agentId, new AgentMuteController.MuteRequest(true));
        ResponseEntity<?> response = controller.setMuteState(agentId, new AgentMuteController.MuteRequest(true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(muteRegistry.isMuted(agentId));
    }

    @Test
    @DisplayName("Unmuting an already-unmuted agent is safe")
    void unmuteIdempotent() {
        UUID agentId = UUID.randomUUID();

        ResponseEntity<?> response = controller.setMuteState(agentId, new AgentMuteController.MuteRequest(false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(muteRegistry.isMuted(agentId));
    }
}
