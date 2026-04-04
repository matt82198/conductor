package dev.conductor.server.brain.decision;

import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.BrainStateManager;
import dev.conductor.server.brain.api.BrainApiClient;
import dev.conductor.server.brain.behavior.BehaviorMatch;
import dev.conductor.server.brain.behavior.BehaviorModel;
import dev.conductor.server.brain.behavior.BehaviorModelBuilder;
import dev.conductor.server.brain.context.ContextIndex;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.humaninput.HumanInputNeededEvent;
import dev.conductor.server.humaninput.HumanInputQueue;
import dev.conductor.server.humaninput.HumanInputRequest;
import dev.conductor.server.humaninput.HumanInputResponder;
import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core decision engine for the Conductor Brain (Phase 4A).
 *
 * <p>Listens for {@link HumanInputNeededEvent} at highest precedence. If the behavior
 * model has a confident pattern match, the Brain auto-responds to the agent immediately.
 * Otherwise, it escalates the request to the human by publishing a
 * {@link BrainEscalationEvent}.
 *
 * <p>The event interception strategy is zero-modification to existing domains: the Brain
 * handles the event before notification/ sees it. If the Brain responds, it calls
 * {@link HumanInputResponder#respond} which resolves the request in the queue. By the
 * time downstream listeners process the event, the request is already gone.
 *
 * <p>Also listens for {@link ClaudeProcessManager.AgentStreamEvent} for context awareness
 * (debug logging only in Phase 4A — Phase 4E will add context sharing).
 */
@Service
public class BrainDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(BrainDecisionEngine.class);

    private final BrainProperties brainProperties;
    private final BrainStateManager brainStateManager;
    private final BehaviorModelBuilder behaviorModelBuilder;
    private final ContextIngestionService contextIngestionService;
    private final HumanInputResponder humanInputResponder;
    private final HumanInputQueue humanInputQueue;
    private final ApplicationEventPublisher eventPublisher;
    private final BrainApiClient brainApiClient; // nullable — only present when API key is set

    /** Rate limiting: tracks auto-responses per minute window. */
    private final AtomicInteger autoResponseCount = new AtomicInteger(0);
    private volatile long lastResetTime = System.currentTimeMillis();

    public BrainDecisionEngine(
            BrainProperties brainProperties,
            BrainStateManager brainStateManager,
            BehaviorModelBuilder behaviorModelBuilder,
            ContextIngestionService contextIngestionService,
            HumanInputResponder humanInputResponder,
            HumanInputQueue humanInputQueue,
            ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) BrainApiClient brainApiClient
    ) {
        this.brainProperties = brainProperties;
        this.brainStateManager = brainStateManager;
        this.behaviorModelBuilder = behaviorModelBuilder;
        this.contextIngestionService = contextIngestionService;
        this.humanInputResponder = humanInputResponder;
        this.humanInputQueue = humanInputQueue;
        this.eventPublisher = eventPublisher;
        this.brainApiClient = brainApiClient;

        log.info("Brain decision engine initialized (enabled={}, confidenceThreshold={}, apiClient={})",
                brainStateManager.isEnabled(), brainProperties.confidenceThreshold(),
                brainApiClient != null ? "present" : "absent");
    }

    /**
     * Intercepts human input needed events at highest precedence.
     *
     * <p>If the Brain is enabled and the behavior model has a confident match, the
     * Brain auto-responds to the agent. Otherwise, it escalates to the human.
     *
     * @param event the human input needed event
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onHumanInputNeeded(HumanInputNeededEvent event) {
        if (!brainStateManager.isEnabled()) {
            return;
        }

        HumanInputRequest request = event.request();

        // Step 1: Build/get current behavior model (cached internally)
        BehaviorModel model = behaviorModelBuilder.build();

        // Step 2: Check behavior model for pattern match
        BehaviorMatch match = behaviorModelBuilder.findMatch(request, model);

        if (match != null && match.confidence() >= brainProperties.confidenceThreshold()) {
            // Rate limit check: reset counter if more than 60s have elapsed
            long now = System.currentTimeMillis();
            if (now - lastResetTime > 60_000L) {
                autoResponseCount.set(0);
                lastResetTime = now;
            }
            if (autoResponseCount.get() >= brainProperties.maxAutoResponsesPerMinute()) {
                log.warn("Brain rate limit exceeded ({}/min) — escalating request {} to human",
                        brainProperties.maxAutoResponsesPerMinute(), request.requestId());
                eventPublisher.publishEvent(new BrainEscalationEvent(
                        request.requestId(),
                        request.agentId().toString(),
                        "Rate limit exceeded (" + brainProperties.maxAutoResponsesPerMinute() + "/min)",
                        match.suggestedResponse(),
                        match.confidence(),
                        Instant.now()));
                return;
            }

            // Auto-respond from behavior model
            try {
                humanInputResponder.respond(request.requestId(), match.suggestedResponse());
                autoResponseCount.incrementAndGet();
                eventPublisher.publishEvent(new BrainResponseEvent(
                        request.requestId(),
                        request.agentId().toString(),
                        match.suggestedResponse(),
                        match.confidence(),
                        "Behavior model match: " + match.matchedPattern(),
                        Instant.now()));
                log.info("Brain auto-responded to {} (confidence={}, pattern={})",
                        request.requestId(), match.confidence(), match.matchedPattern());
            } catch (Exception e) {
                log.error("Brain failed to auto-respond to {}: {}",
                        request.requestId(), e.getMessage());
            }
            return;
        }

        // Step 3: Escalate to human — the local agent has the context, not the Brain API.
        // Brain API is reserved for orchestration (task decomposition, project analysis,
        // command interpretation) — NOT for answering routine agent questions.
        // The human's response gets logged → behavior model learns → next time Brain handles it.
        log.debug("Brain escalating to human: {} (no behavior match, no API)", request.requestId());
        eventPublisher.publishEvent(new BrainEscalationEvent(
                request.requestId(),
                request.agentId().toString(),
                "No behavior pattern match" + (brainApiClient == null ? " (API not configured)" : " (API unavailable)"),
                match != null ? match.suggestedResponse() : null,
                match != null ? match.confidence() : 0.0,
                Instant.now()));
    }

    /**
     * Monitors agent stream events for context awareness.
     *
     * <p>In Phase 4A this is debug logging only. Phase 4E will add intelligent
     * context sharing between agents based on what they discover.
     *
     * @param event the agent stream event
     */
    @EventListener
    public void onAgentStreamEvent(ClaudeProcessManager.AgentStreamEvent event) {
        if (!brainStateManager.isEnabled()) {
            return;
        }

        log.debug("Brain observed agent {} event: {}",
                event.agentId(), event.event().getClass().getSimpleName());
    }
}
