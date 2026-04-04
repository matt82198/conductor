package dev.conductor.server.brain.behavior;

import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.humaninput.HumanInputRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * High-level logging API for recording user interactions with the Conductor system.
 *
 * <p>Called by controllers and event handlers when the user takes an action
 * (responds to a question, dismisses a request, spawns/kills an agent, etc.).
 * Each method constructs a {@link BehaviorEvent} and appends it to the
 * {@link BehaviorLog} for later analysis by the behavior model.
 */
@Service
public class BehaviorLogger {

    private static final Logger log = LoggerFactory.getLogger(BehaviorLogger.class);

    private final BehaviorLog behaviorLog;

    public BehaviorLogger(BehaviorLog behaviorLog) {
        this.behaviorLog = behaviorLog;
    }

    /**
     * Records that the user responded to an agent's question.
     *
     * @param request        the original human input request
     * @param responseText   the user's response
     * @param responseTimeMs time in milliseconds from detection to response
     */
    public void logResponse(HumanInputRequest request, String responseText, long responseTimeMs) {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(),
                "RESPONDED",
                request.agentId().toString(),
                null,
                null,
                request.question(),
                responseText,
                responseTimeMs,
                Map.of(
                        "requestId", request.requestId(),
                        "detectionMethod", request.detectionMethod(),
                        "urgency", request.urgency(),
                        "confidenceScore", String.format("%.2f", request.confidenceScore())
                )
        );
        behaviorLog.append(event);
        log.debug("Logged RESPONDED event for agent {}", request.agentId());
    }

    /**
     * Records that the user dismissed (ignored) an agent's question.
     *
     * @param request the original human input request
     */
    public void logDismissal(HumanInputRequest request) {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(),
                "DISMISSED",
                request.agentId().toString(),
                null,
                null,
                request.question(),
                null,
                0L,
                Map.of(
                        "requestId", request.requestId(),
                        "detectionMethod", request.detectionMethod(),
                        "urgency", request.urgency()
                )
        );
        behaviorLog.append(event);
        log.debug("Logged DISMISSED event for agent {}", request.agentId());
    }

    /**
     * Records that the user spawned a new agent.
     *
     * @param agent  the spawned agent record
     * @param prompt the initial prompt given to the agent
     */
    public void logSpawn(AgentRecord agent, String prompt) {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(),
                "SPAWNED",
                agent.id().toString(),
                agent.role() != null ? agent.role().name() : null,
                agent.projectPath(),
                null,
                prompt,
                0L,
                Map.of("agentName", agent.name())
        );
        behaviorLog.append(event);
        log.debug("Logged SPAWNED event for agent {} [{}]", agent.name(), agent.id());
    }

    /**
     * Records that the user killed an agent.
     *
     * @param agent the killed agent record
     */
    public void logKill(AgentRecord agent) {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(),
                "KILLED",
                agent.id().toString(),
                agent.role() != null ? agent.role().name() : null,
                agent.projectPath(),
                null,
                null,
                0L,
                Map.of("agentName", agent.name())
        );
        behaviorLog.append(event);
        log.debug("Logged KILLED event for agent {} [{}]", agent.name(), agent.id());
    }

    /**
     * Records that the user sent a follow-up message to an agent.
     *
     * @param agentId the target agent's UUID
     * @param text    the message text
     */
    public void logMessage(UUID agentId, String text) {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(),
                "MESSAGED",
                agentId.toString(),
                null,
                null,
                null,
                text,
                0L,
                Map.of()
        );
        behaviorLog.append(event);
        log.debug("Logged MESSAGED event for agent {}", agentId);
    }

    /**
     * Records that the user muted or unmuted an agent.
     *
     * @param agentId the target agent's UUID
     * @param muted   true if the agent was muted, false if unmuted
     */
    public void logMute(UUID agentId, boolean muted) {
        BehaviorEvent event = new BehaviorEvent(
                Instant.now(),
                "MUTED",
                agentId.toString(),
                null,
                null,
                null,
                null,
                0L,
                Map.of("muted", String.valueOf(muted))
        );
        behaviorLog.append(event);
        log.debug("Logged MUTED event for agent {} (muted={})", agentId, muted);
    }
}
