package dev.conductor.server.humaninput;

import dev.conductor.common.AgentState;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors agent activity timestamps and flags stalls as potential
 * human-input-needed situations.
 *
 * <p>Runs on a 10-second scheduled tick. For each active agent with no
 * recent activity:
 * <ul>
 *   <li>&gt;30s stall → confidence 0.5</li>
 *   <li>&gt;45s stall → confidence 0.6</li>
 *   <li>&gt;60s stall → confidence 0.7</li>
 * </ul>
 *
 * <p>Listens to {@link ClaudeProcessManager.AgentStreamEvent} to update
 * last-activity timestamps. When activity resumes, any pending stall-based
 * requests for that agent are automatically resolved from the queue.
 *
 * <p>Thread safety: uses ConcurrentHashMap for the activity map. The scheduled
 * tick runs on a Spring-managed thread; event listeners run on the publishing
 * thread (virtual thread from the agent reader loop).
 */
@Service
public class StallDetector {

    private static final Logger log = LoggerFactory.getLogger(StallDetector.class);

    /** Minimum silence duration before we flag a stall. */
    private static final Duration STALL_THRESHOLD = Duration.ofSeconds(30);

    /** Elevated stall — higher confidence. */
    private static final Duration ELEVATED_STALL_THRESHOLD = Duration.ofSeconds(45);

    /** Severe stall — highest confidence for stall-based detection. */
    private static final Duration SEVERE_STALL_THRESHOLD = Duration.ofSeconds(60);

    private final AgentRegistry registry;
    private final HumanInputQueue queue;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Last-activity timestamps per agent. Updated on every AgentStreamEvent.
     * Entries are removed when agents reach a terminal state.
     */
    private final ConcurrentHashMap<UUID, Instant> lastActivity = new ConcurrentHashMap<>();

    /**
     * Tracks which agents already have a stall-based request queued,
     * to avoid spamming duplicate requests every 10 seconds.
     */
    private final ConcurrentHashMap<UUID, String> activeStallRequests = new ConcurrentHashMap<>();

    public StallDetector(
            AgentRegistry registry,
            HumanInputQueue queue,
            ApplicationEventPublisher eventPublisher
    ) {
        this.registry = registry;
        this.queue = queue;
        this.eventPublisher = eventPublisher;
    }

    // ─── Event listener: update activity timestamps ───────────────────

    /**
     * Updates the last-activity timestamp for the agent that emitted this event.
     * Also clears any pending stall-based request if activity resumes.
     */
    @EventListener
    public void onAgentStreamEvent(ClaudeProcessManager.AgentStreamEvent event) {
        UUID agentId = event.agentId();
        lastActivity.put(agentId, Instant.now());

        // If we had flagged a stall for this agent, resolve it — they're active again
        String existingRequestId = activeStallRequests.remove(agentId);
        if (existingRequestId != null) {
            queue.resolve(existingRequestId);
            log.debug("Agent {} resumed activity — resolved stall request {}", agentId, existingRequestId);
        }
    }

    // ─── Scheduled tick: check all agents for stalls ──────────────────

    /**
     * Runs every 10 seconds. Checks all active agents for activity stalls.
     * If an agent has been silent beyond the threshold, creates a
     * {@link HumanInputRequest} with stall-appropriate confidence.
     */
    @Scheduled(fixedRate = 10_000)
    public void checkForStalls() {
        Instant now = Instant.now();

        for (AgentRecord agent : registry.listAll()) {
            // Only check agents that are alive and not in a terminal state
            if (!agent.state().isAlive()) {
                cleanupAgent(agent.id());
                continue;
            }

            // Only check ACTIVE or BLOCKED agents — THINKING/USING_TOOL are expected silences
            if (agent.state() != AgentState.ACTIVE && agent.state() != AgentState.BLOCKED) {
                continue;
            }

            // Skip if we already have a stall request queued for this agent
            if (activeStallRequests.containsKey(agent.id())) {
                continue;
            }

            Instant lastSeen = lastActivity.getOrDefault(agent.id(), agent.spawnedAt());
            Duration silence = Duration.between(lastSeen, now);

            if (silence.compareTo(STALL_THRESHOLD) > 0) {
                double confidence = computeStallConfidence(silence);
                flagStall(agent, silence, confidence);
            }
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private double computeStallConfidence(Duration silence) {
        if (silence.compareTo(SEVERE_STALL_THRESHOLD) >= 0) {
            return 0.7;
        } else if (silence.compareTo(ELEVATED_STALL_THRESHOLD) >= 0) {
            return 0.6;
        } else {
            return 0.5;
        }
    }

    private void flagStall(AgentRecord agent, Duration silence, double confidence) {
        String question = String.format(
                "Agent '%s' has been silent for %d seconds. It may be waiting for input.",
                agent.name(), silence.getSeconds()
        );

        HumanInputRequest request = new HumanInputRequest(
                UUID.randomUUID().toString(),
                agent.id(),
                agent.name(),
                question,
                java.util.List.of(),
                "Agent state: " + agent.state() + ", last activity: " + silence.getSeconds() + "s ago",
                confidence >= 0.7 ? "HIGH" : "NORMAL",
                Instant.now(),
                "STALL",
                confidence
        );

        // Only add to queue if confidence meets the threshold (0.6 for stalls >45s)
        // Stalls 30-45s get 0.5 confidence — below the 0.6 threshold, but we still
        // track them to avoid duplicate creation if the stall persists.
        if (confidence >= 0.6) {
            queue.add(request);
            eventPublisher.publishEvent(new HumanInputNeededEvent(request));
            log.info("Stall detected for agent {} [{}]: {}s silent, confidence={}",
                    agent.name(), agent.id(), silence.getSeconds(),
                    String.format("%.2f", confidence));
        } else {
            log.debug("Sub-threshold stall for agent {} [{}]: {}s silent, confidence={}",
                    agent.name(), agent.id(), silence.getSeconds(),
                    String.format("%.2f", confidence));
        }

        // Track regardless of threshold to prevent duplicate checks
        activeStallRequests.put(agent.id(), request.requestId());
    }

    /**
     * Cleans up tracking state for agents that have reached terminal state.
     */
    private void cleanupAgent(UUID agentId) {
        lastActivity.remove(agentId);
        String requestId = activeStallRequests.remove(agentId);
        if (requestId != null) {
            queue.resolve(requestId);
        }
    }

    /**
     * Returns the number of agents currently being tracked for stalls.
     * Exposed for testing and monitoring.
     */
    public int trackedAgentCount() {
        return lastActivity.size();
    }
}
