package dev.conductor.server.api;

import dev.conductor.server.humaninput.HumanInputQueue;
import dev.conductor.server.humaninput.HumanInputRequest;
import dev.conductor.server.humaninput.HumanInputResponder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HumanInputController}.
 *
 * <p>Uses a real {@link HumanInputQueue} and a {@link FakeHumanInputResponder}
 * stub to avoid Mockito concrete class mocking issues on JDK 25+.
 */
class HumanInputControllerTest {

    private HumanInputQueue queue;
    private FakeHumanInputResponder responder;
    private HumanInputController controller;

    @BeforeEach
    void setUp() {
        queue = new HumanInputQueue();
        responder = new FakeHumanInputResponder(queue);
        controller = new HumanInputController(queue, responder, null);
    }

    // ─── GET /pending ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET pending returns list from queue")
    void getPendingReturnsQueueContents() {
        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(agentId, "test-agent", "Which approach?", "PATTERN_MATCH", 0.8);
        queue.add(request);

        List<HumanInputRequest> result = controller.getPending();

        assertEquals(1, result.size());
        assertEquals(agentId, result.get(0).agentId());
    }

    @Test
    @DisplayName("GET pending returns empty list when no requests")
    void getPendingEmpty() {
        List<HumanInputRequest> result = controller.getPending();

        assertTrue(result.isEmpty());
    }

    // ─── POST /{requestId}/respond ────────────────────────────────────

    @Test
    @DisplayName("POST respond returns 200 on success")
    void respondSuccess() {
        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(agentId, "test-agent", "question", "PATTERN_MATCH", 0.8);
        queue.add(request);
        responder.setMode(FakeHumanInputResponder.Mode.SUCCESS);

        ResponseEntity<?> response = controller.respond(
                request.requestId(), new HumanInputController.RespondRequest("Yes, use approach A."));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("POST respond returns 404 when request not found")
    void respondNotFound() {
        responder.setMode(FakeHumanInputResponder.Mode.NOT_FOUND);

        ResponseEntity<?> response = controller.respond(
                UUID.randomUUID().toString(), new HumanInputController.RespondRequest("text"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("POST respond returns 409 when agent is no longer active")
    void respondConflict() {
        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(agentId, "test-agent", "question", "PATTERN_MATCH", 0.8);
        queue.add(request);
        responder.setMode(FakeHumanInputResponder.Mode.AGENT_INACTIVE);

        ResponseEntity<?> response = controller.respond(
                request.requestId(), new HumanInputController.RespondRequest("text"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    @DisplayName("POST respond returns 500 on IOException")
    void respondIOError() {
        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(agentId, "test-agent", "question", "PATTERN_MATCH", 0.8);
        queue.add(request);
        responder.setMode(FakeHumanInputResponder.Mode.IO_ERROR);

        ResponseEntity<?> response = controller.respond(
                request.requestId(), new HumanInputController.RespondRequest("text"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // ─── POST /{requestId}/dismiss ────────────────────────────────────

    @Test
    @DisplayName("POST dismiss returns 200 when request found")
    void dismissSuccess() {
        UUID agentId = UUID.randomUUID();
        HumanInputRequest request = HumanInputRequest.of(agentId, "test-agent", "question", "PATTERN_MATCH", 0.8);
        queue.add(request);

        ResponseEntity<?> response = controller.dismiss(request.requestId());

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("POST dismiss returns 404 when request not found")
    void dismissNotFound() {
        ResponseEntity<?> response = controller.dismiss(UUID.randomUUID().toString());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── GET /count ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET count returns queue size")
    void countReturnsSize() {
        for (int i = 0; i < 3; i++) {
            queue.add(HumanInputRequest.of(UUID.randomUUID(), "agent-" + i, "question", "PATTERN_MATCH", 0.8));
        }

        ResponseEntity<?> response = controller.count();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(3, body.get("count"));
    }

    @Test
    @DisplayName("GET count returns 0 when queue is empty")
    void countZero() {
        ResponseEntity<?> response = controller.count();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("count"));
    }

    // ─── Test Double ──────────────────────────────────────────────────

    /**
     * A test double for {@link HumanInputResponder} that avoids needing
     * a real {@link dev.conductor.server.process.ClaudeProcessManager}.
     *
     * <p>Extends the real class but overrides {@code respond()} to simulate
     * success, not-found, agent-inactive, and I/O error scenarios.
     * The {@code dismiss()} method delegates to the queue directly.
     */
    static class FakeHumanInputResponder extends HumanInputResponder {

        enum Mode { SUCCESS, NOT_FOUND, AGENT_INACTIVE, IO_ERROR }

        private final HumanInputQueue queue;
        private Mode mode = Mode.SUCCESS;

        FakeHumanInputResponder(HumanInputQueue queue) {
            // Pass null for processManager — we override all methods that use it
            super(queue, null);
            this.queue = queue;
        }

        void setMode(Mode mode) {
            this.mode = mode;
        }

        @Override
        public void respond(String requestId, String responseText) throws IOException {
            switch (mode) {
                case SUCCESS -> queue.resolve(requestId);
                case NOT_FOUND -> throw new IllegalArgumentException("No pending request found");
                case AGENT_INACTIVE -> throw new IllegalStateException("Agent is no longer active");
                case IO_ERROR -> throw new IOException("stdin broken");
            }
        }

        @Override
        public Optional<HumanInputRequest> dismiss(String requestId) {
            return queue.resolve(requestId);
        }
    }
}
