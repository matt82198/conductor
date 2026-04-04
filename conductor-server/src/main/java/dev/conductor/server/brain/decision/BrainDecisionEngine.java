package dev.conductor.server.brain.decision;

import dev.conductor.server.brain.BrainProperties;
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
    private final BehaviorModelBuilder behaviorModelBuilder;
    private final ContextIngestionService contextIngestionService;
    private final HumanInputResponder humanInputResponder;
    private final HumanInputQueue humanInputQueue;
    private final ApplicationEventPublisher eventPublisher;
    private final BrainApiClient brainApiClient; // nullable — only present when API key is set

    public BrainDecisionEngine(
            BrainProperties brainProperties,
            BehaviorModelBuilder behaviorModelBuilder,
            ContextIngestionService contextIngestionService,
            HumanInputResponder humanInputResponder,
            HumanInputQueue humanInputQueue,
            ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) BrainApiClient brainApiClient
    ) {
        this.brainProperties = brainProperties;
        this.behaviorModelBuilder = behaviorModelBuilder;
        this.contextIngestionService = contextIngestionService;
        this.humanInputResponder = humanInputResponder;
        this.humanInputQueue = humanInputQueue;
        this.eventPublisher = eventPublisher;
        this.brainApiClient = brainApiClient;

        log.info("Brain decision engine initialized (enabled={}, confidenceThreshold={}, apiClient={})",
                brainProperties.enabled(), brainProperties.confidenceThreshold(),
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
        if (!brainProperties.enabled()) {
            return;
        }

        HumanInputRequest request = event.request();

        // Step 1: Build/get current behavior model (cached internally)
        BehaviorModel model = behaviorModelBuilder.build();

        // Step 2: Check behavior model for pattern match
        BehaviorMatch match = behaviorModelBuilder.findMatch(request, model);

        if (match != null && match.confidence() >= brainProperties.confidenceThreshold()) {
            // Auto-respond from behavior model
            try {
                humanInputResponder.respond(request.requestId(), match.suggestedResponse());
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

        // Step 3: Call Claude API if available
        if (brainApiClient != null) {
            try {
                ContextIndex contextIndex = contextIngestionService.buildIndex();
                String contextPrompt = contextIngestionService.renderForPrompt(
                        contextIndex, null, brainProperties.contextWindowBudget());
                Optional<BrainDecision> apiDecision = brainApiClient.evaluate(request, contextPrompt, model);

                if (apiDecision.isPresent()) {
                    BrainDecision decision = apiDecision.get();
                    if ("RESPOND".equals(decision.action())
                            && decision.response() != null
                            && decision.confidence() >= brainProperties.confidenceThreshold()) {
                        try {
                            humanInputResponder.respond(request.requestId(), decision.response());
                            eventPublisher.publishEvent(new BrainResponseEvent(
                                    request.requestId(),
                                    request.agentId().toString(),
                                    decision.response(),
                                    decision.confidence(),
                                    "API decision: " + decision.reasoning(),
                                    Instant.now()));
                            log.info("Brain auto-responded via API to {} (confidence={})",
                                    request.requestId(), decision.confidence());
                            return;
                        } catch (Exception e) {
                            log.error("Brain failed to send API response to {}: {}",
                                    request.requestId(), e.getMessage());
                        }
                    }
                    // API decided to escalate or confidence too low
                    log.debug("Brain API decided to escalate: {} (confidence={}, reasoning={})",
                            request.requestId(), decision.confidence(), decision.reasoning());
                    eventPublisher.publishEvent(new BrainEscalationEvent(
                            request.requestId(),
                            request.agentId().toString(),
                            decision.reasoning() != null ? decision.reasoning() : "API decided to escalate",
                            decision.response(),
                            decision.confidence(),
                            Instant.now()));
                    return;
                }
            } catch (Exception e) {
                log.error("Brain API call failed for {}: {}", request.requestId(), e.getMessage());
            }
        }

        // Step 4: No behavior match, no API — escalate to human
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
        if (!brainProperties.enabled()) {
            return;
        }

        log.debug("Brain observed agent {} event: {}",
                event.agentId(), event.event().getClass().getSimpleName());
    }
}
