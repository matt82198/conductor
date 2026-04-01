package dev.conductor.server.humaninput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe priority queue of pending human input requests.
 *
 * <p>Requests are stored in a ConcurrentHashMap for O(1) lookup and removal.
 * The {@link #getPending()} method returns a snapshot sorted by confidence
 * descending, so the most urgent/certain requests surface first in the UI.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link HumanInputDetector} or {@link StallDetector} adds a request</li>
 *   <li>UI displays pending requests via REST or WebSocket</li>
 *   <li>User responds → {@link HumanInputResponder} calls {@link #resolve(String)}</li>
 * </ol>
 */
@Service
public class HumanInputQueue {

    private static final Logger log = LoggerFactory.getLogger(HumanInputQueue.class);

    /**
     * All pending requests indexed by requestId.
     * ConcurrentHashMap for thread-safe access from event listeners and REST threads.
     */
    private final ConcurrentHashMap<String, HumanInputRequest> pending = new ConcurrentHashMap<>();

    /**
     * Adds a new human input request to the queue.
     *
     * <p>If a request with the same ID already exists, it is replaced (idempotent).
     * If a request for the same agent already exists with lower confidence,
     * the higher-confidence request replaces it.
     *
     * @param request the request to enqueue
     */
    public void add(HumanInputRequest request) {
        if (request == null) {
            return;
        }

        // Check if this agent already has a pending request
        HumanInputRequest existing = findByAgentId(request.agentId());
        if (existing != null) {
            if (request.confidenceScore() > existing.confidenceScore()) {
                // Higher confidence replaces lower
                pending.remove(existing.requestId());
                pending.put(request.requestId(), request);
                log.info("Replaced lower-confidence request for agent {} ({}→{})",
                        request.agentId(),
                        String.format("%.2f", existing.confidenceScore()),
                        String.format("%.2f", request.confidenceScore()));
            } else {
                log.debug("Skipping lower-confidence request for agent {} (existing={}, new={})",
                        request.agentId(),
                        String.format("%.2f", existing.confidenceScore()),
                        String.format("%.2f", request.confidenceScore()));
                return;
            }
        } else {
            pending.put(request.requestId(), request);
        }

        log.info("Queued human input request: {} for agent {} [{}] confidence={}",
                request.requestId(), request.agentName(), request.agentId(),
                String.format("%.2f", request.confidenceScore()));
    }

    /**
     * Returns a snapshot of all pending requests, sorted by confidence descending.
     * The returned list is a copy — modifications do not affect the queue.
     *
     * @return pending requests, highest confidence first
     */
    public List<HumanInputRequest> getPending() {
        List<HumanInputRequest> snapshot = new ArrayList<>(pending.values());
        snapshot.sort(Comparator.comparingDouble(HumanInputRequest::confidenceScore).reversed());
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Resolves (removes) a request from the queue.
     *
     * @param requestId the request to resolve
     * @return the resolved request, or empty if not found
     */
    public Optional<HumanInputRequest> resolve(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        HumanInputRequest removed = pending.remove(requestId);
        if (removed != null) {
            log.info("Resolved human input request: {} for agent {}",
                    requestId, removed.agentName());
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Looks up a specific request by ID without removing it.
     *
     * @param requestId the request ID
     * @return the request, or empty if not found
     */
    public Optional<HumanInputRequest> getById(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.get(requestId));
    }

    /**
     * Resolves all pending requests for a given agent.
     * Used when an agent resumes activity (stall clears).
     *
     * @param agentId the agent whose requests should be cleared
     * @return the number of requests removed
     */
    public int resolveByAgent(UUID agentId) {
        if (agentId == null) {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<String, HumanInputRequest>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, HumanInputRequest> entry = it.next();
            if (agentId.equals(entry.getValue().agentId())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleared {} pending requests for agent {}", removed, agentId);
        }
        return removed;
    }

    /**
     * Returns the number of pending requests.
     */
    public int size() {
        return pending.size();
    }

    /**
     * Returns true if there are no pending requests.
     */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private HumanInputRequest findByAgentId(UUID agentId) {
        for (HumanInputRequest req : pending.values()) {
            if (agentId.equals(req.agentId())) {
                return req;
            }
        }
        return null;
    }
}
