package dev.conductor.server.api;

import dev.conductor.server.brain.behavior.BehaviorLogger;
import dev.conductor.server.humaninput.HumanInputQueue;
import dev.conductor.server.humaninput.HumanInputRequest;
import dev.conductor.server.humaninput.HumanInputResponder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST controller for human input request management.
 *
 * <p>Exposes the {@link HumanInputQueue} and {@link HumanInputResponder}
 * to the UI, allowing users to view pending input requests, respond to
 * blocked agents, and dismiss false-positive detections.
 */
@RestController
@RequestMapping("/api/humaninput")
public class HumanInputController {

    private static final Logger log = LoggerFactory.getLogger(HumanInputController.class);

    private final HumanInputQueue humanInputQueue;
    private final HumanInputResponder humanInputResponder;
    private final BehaviorLogger behaviorLogger; // nullable — brain module may not be enabled

    public HumanInputController(HumanInputQueue humanInputQueue, HumanInputResponder humanInputResponder,
                                @Autowired(required = false) BehaviorLogger behaviorLogger) {
        this.humanInputQueue = humanInputQueue;
        this.humanInputResponder = humanInputResponder;
        this.behaviorLogger = behaviorLogger;
    }

    // ─── List Pending ─────────────────────────────────────────────────

    /**
     * Returns all pending human input requests, ordered by confidence descending.
     *
     * <p>The most urgent/certain requests appear first in the list.
     *
     * @return list of pending {@link HumanInputRequest}s
     */
    @GetMapping("/pending")
    public List<HumanInputRequest> getPending() {
        return humanInputQueue.getPending();
    }

    // ─── Respond ──────────────────────────────────────────────────────

    /**
     * Sends a human response to a blocked agent.
     *
     * <p>The response text is piped to the agent's stdin via
     * {@link HumanInputResponder#respond}, and the request is removed
     * from the queue.
     *
     * <p>Request body:
     * <pre>
     * { "text": "Yes, use approach A." }
     * </pre>
     *
     * @param requestId the ID of the pending request to respond to
     * @param request   body containing the response text
     * @return 200 on success, 404 if request not found, 409 if agent
     *         is no longer active, 500 on stdin write failure
     */
    @PostMapping("/{requestId}/respond")
    public ResponseEntity<?> respond(@PathVariable String requestId, @RequestBody RespondRequest request) {
        try {
            // Capture the request before it's resolved (for behavior logging)
            HumanInputRequest inputRequest = humanInputQueue.getById(requestId).orElse(null);
            humanInputResponder.respond(requestId, request.text());
            if (behaviorLogger != null && inputRequest != null) {
                behaviorLogger.logResponse(inputRequest, request.text(), 0L);
            }
            return ResponseEntity.ok(Map.of("status", "sent"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Failed to send human response for request {}: {}", requestId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to write response to agent stdin: " + e.getMessage()));
        }
    }

    record RespondRequest(String text) {}

    // ─── Dismiss ──────────────────────────────────────────────────────

    /**
     * Dismisses a pending human input request without sending a response.
     *
     * <p>Used when the user determines the detection is a false positive
     * and the agent does not actually need input.
     *
     * @param requestId the ID of the pending request to dismiss
     * @return 200 if dismissed, 404 if not found
     */
    @PostMapping("/{requestId}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable String requestId) {
        // Capture the request before it's dismissed (for behavior logging)
        HumanInputRequest inputRequest = humanInputQueue.getById(requestId).orElse(null);
        return humanInputResponder.dismiss(requestId)
                .map(req -> {
                    if (behaviorLogger != null && inputRequest != null) {
                        behaviorLogger.logDismissal(inputRequest);
                    }
                    return ResponseEntity.ok(Map.of("status", "dismissed"));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Count ────────────────────────────────────────────────────────

    /**
     * Returns the number of pending human input requests.
     *
     * @return JSON with a single {@code count} integer field
     */
    @GetMapping("/count")
    public ResponseEntity<?> count() {
        return ResponseEntity.ok(Map.of("count", humanInputQueue.size()));
    }
}
