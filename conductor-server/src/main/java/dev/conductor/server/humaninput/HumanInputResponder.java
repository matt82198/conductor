package dev.conductor.server.humaninput;

import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/**
 * Pipes human responses to blocked agents via stdin.
 *
 * <p>When a user responds to a {@link HumanInputRequest} through the UI,
 * this service:
 * <ol>
 *   <li>Looks up the request in {@link HumanInputQueue}</li>
 *   <li>Sends the response text to the agent's stdin via
 *       {@link ClaudeProcessManager#sendMessage}</li>
 *   <li>Resolves (removes) the request from the queue</li>
 * </ol>
 *
 * <p>Thread safety: relies on the thread-safe queue and process manager.
 * The respond() method can be called from any thread (typically from a
 * REST controller or WebSocket handler).
 */
@Service
public class HumanInputResponder {

    private static final Logger log = LoggerFactory.getLogger(HumanInputResponder.class);

    private final HumanInputQueue queue;
    private final ClaudeProcessManager processManager;

    public HumanInputResponder(
            HumanInputQueue queue,
            ClaudeProcessManager processManager
    ) {
        this.queue = queue;
        this.processManager = processManager;
    }

    /**
     * Sends a human response to the agent that requested input.
     *
     * @param requestId    the ID of the HumanInputRequest to respond to
     * @param responseText the human's response text
     * @throws IllegalArgumentException if the requestId is not found in the queue
     * @throws IllegalStateException    if the agent process is no longer active
     * @throws IOException              if writing to the agent's stdin fails
     */
    public void respond(String requestId, String responseText) throws IOException {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalArgumentException("responseText must not be blank");
        }

        // Look up the pending request
        Optional<HumanInputRequest> requestOpt = queue.getById(requestId);
        if (requestOpt.isEmpty()) {
            throw new IllegalArgumentException("No pending request found with ID: " + requestId);
        }

        HumanInputRequest request = requestOpt.get();

        // Verify the agent process is still active
        if (!processManager.hasActiveProcess(request.agentId())) {
            // Resolve the stale request and report
            queue.resolve(requestId);
            throw new IllegalStateException(
                    "Agent " + request.agentId() + " is no longer active. Request resolved as stale.");
        }

        // Send the response to the agent's stdin
        try {
            processManager.sendMessage(request.agentId(), responseText);
            log.info("Sent human response to agent {} [{}]: {} chars",
                    request.agentName(), request.agentId(), responseText.length());
        } catch (IOException e) {
            log.error("Failed to send response to agent {} [{}]: {}",
                    request.agentName(), request.agentId(), e.getMessage());
            throw e;
        }

        // Resolve the request from the queue
        queue.resolve(requestId);
    }

    /**
     * Resolves a request without sending a response.
     * Used when the user dismisses a false-positive detection.
     *
     * @param requestId the ID of the request to dismiss
     * @return the dismissed request, or empty if not found
     */
    public Optional<HumanInputRequest> dismiss(String requestId) {
        Optional<HumanInputRequest> resolved = queue.resolve(requestId);
        resolved.ifPresent(r ->
                log.info("Dismissed human input request: {} for agent {}",
                        requestId, r.agentName()));
        return resolved;
    }
}
