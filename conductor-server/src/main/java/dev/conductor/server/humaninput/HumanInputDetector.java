package dev.conductor.server.humaninput;

import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Main detection pipeline for human-input-needed situations.
 *
 * <p>Listens to every {@link ClaudeProcessManager.AgentStreamEvent} and runs
 * a multi-layer detection pipeline:
 *
 * <ol>
 *   <li><b>Layer 1 — Explicit tool use:</b> AssistantEvent with
 *       toolName="AskUserQuestion" → confidence 1.0</li>
 *   <li><b>Layer 2 — Pattern matching:</b> Regex detection on text content
 *       → confidence 0.60-0.90 (via {@link PatternMatcher})</li>
 *   <li><b>Layer 3 — Stall detection:</b> Handled separately by
 *       {@link StallDetector} on a scheduled tick</li>
 *   <li><b>Layer 4 — Permission denial:</b> ResultEvent with permission
 *       denial indicators → confidence 0.90</li>
 * </ol>
 *
 * <p>If the combined confidence (via {@link ConfidenceScorer}) is &ge; 0.6,
 * the detector creates a {@link HumanInputRequest}, adds it to the
 * {@link HumanInputQueue}, and publishes a {@link HumanInputNeededEvent}
 * via Spring's ApplicationEventPublisher.
 *
 * <p>Thread safety: event listeners run on the publishing thread (virtual
 * thread from the agent reader loop). All shared state is in thread-safe
 * structures managed by the injected services.
 */
@Service
public class HumanInputDetector {

    private static final Logger log = LoggerFactory.getLogger(HumanInputDetector.class);

    /** Minimum confidence to surface a request to the UI. */
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    private static final String ASK_USER_TOOL = "AskUserQuestion";

    private final AgentRegistry registry;
    private final HumanInputQueue queue;
    private final ApplicationEventPublisher eventPublisher;

    public HumanInputDetector(
            AgentRegistry registry,
            HumanInputQueue queue,
            ApplicationEventPublisher eventPublisher
    ) {
        this.registry = registry;
        this.queue = queue;
        this.eventPublisher = eventPublisher;
    }

    // ─── Event listener ───────────────────────────────────────────────

    /**
     * Processes every agent stream event through the detection pipeline.
     * Lightweight — returns quickly for events that don't match any layer.
     */
    @EventListener
    public void onAgentStreamEvent(ClaudeProcessManager.AgentStreamEvent event) {
        UUID agentId = event.agentId();
        StreamJsonEvent streamEvent = event.event();

        Optional<HumanInputRequest> detected = evaluate(agentId, streamEvent);
        detected.ifPresent(this::surfaceRequest);
    }

    // ─── Public evaluation entry point ────────────────────────────────

    /**
     * Evaluates a stream event for human-input signals.
     *
     * <p>Runs Layers 1, 2, and 4 of the detection pipeline.
     * Layer 3 (stall detection) is handled by {@link StallDetector}.
     *
     * @param agentId     the agent that produced the event
     * @param streamEvent the raw stream event
     * @return a HumanInputRequest if confidence &ge; threshold, empty otherwise
     */
    public Optional<HumanInputRequest> evaluate(UUID agentId, StreamJsonEvent streamEvent) {
        List<ConfidenceScorer.Signal> signals = new ArrayList<>();

        // ── Layer 1: Explicit tool use (AskUserQuestion) ──
        if (streamEvent instanceof AssistantEvent assistant && assistant.isToolUse()) {
            if (ASK_USER_TOOL.equals(assistant.toolName())) {
                String question = extractQuestionFromToolInput(assistant);
                signals.add(new ConfidenceScorer.Signal("TOOL_USE", 1.0));

                // Tool use is definitive — skip other layers, create request immediately
                return Optional.of(buildRequest(agentId, question, "TOOL_USE", 1.0));
            }
        }

        // ── Layer 2: Pattern matching on text content ──
        String textContent = extractTextContent(streamEvent);
        if (textContent != null && !textContent.isBlank()) {
            Optional<PatternMatcher.PatternMatch> match = PatternMatcher.evaluate(textContent);
            match.ifPresent(m -> signals.add(new ConfidenceScorer.Signal("PATTERN_MATCH", m.confidence())));
        }

        // ── Layer 4: Permission denial ──
        if (streamEvent instanceof ResultEvent result && result.isError()) {
            String resultText = result.resultText();
            if (resultText != null && (resultText.contains("permission") || resultText.contains("denied"))) {
                signals.add(new ConfidenceScorer.Signal("PERMISSION_DENIAL", 0.90));
            }
        }

        // ── Combine signals and check threshold ──
        if (signals.isEmpty()) {
            return Optional.empty();
        }

        double combined = ConfidenceScorer.combine(signals);
        if (combined < CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }

        ConfidenceScorer.Signal dominant = ConfidenceScorer.dominant(signals);
        String detectionMethod = dominant != null ? dominant.source() : "UNKNOWN";
        String question = textContent != null ? textContent : "Agent may need input";

        return Optional.of(buildRequest(agentId, question, detectionMethod, combined));
    }

    // ─── Internal ─────────────────────────────────────────────────────

    /**
     * Builds a HumanInputRequest with agent context from the registry.
     */
    private HumanInputRequest buildRequest(UUID agentId, String question, String method, double confidence) {
        AgentRecord agent = registry.get(agentId).orElse(null);
        String agentName = agent != null ? agent.name() : "unknown-" + agentId.toString().substring(0, 8);
        String context = agent != null
                ? "Agent state: " + agent.state() + ", role: " + agent.role()
                : "Agent not found in registry";

        return new HumanInputRequest(
                UUID.randomUUID().toString(),
                agentId,
                agentName,
                truncate(question, 500),
                List.of(),
                context,
                confidence >= 0.9 ? "CRITICAL" : confidence >= 0.7 ? "HIGH" : "NORMAL",
                Instant.now(),
                method,
                confidence
        );
    }

    /**
     * Surfaces a request: adds to queue and publishes a Spring event.
     */
    private void surfaceRequest(HumanInputRequest request) {
        queue.add(request);
        eventPublisher.publishEvent(new HumanInputNeededEvent(request));
        log.info("Human input detected for agent {} [{}] via {} confidence={}",
                request.agentName(), request.agentId(), request.detectionMethod(),
                String.format("%.2f", request.confidenceScore()));
    }

    /**
     * Extracts the question text from an AskUserQuestion tool_use event.
     * The tool input typically has a "question" field.
     */
    private String extractQuestionFromToolInput(AssistantEvent assistant) {
        if (assistant.toolInput() == null) {
            return "Agent is asking for user input";
        }
        Object question = assistant.toolInput().get("question");
        if (question != null) {
            return question.toString();
        }
        Object content = assistant.toolInput().get("content");
        if (content != null) {
            return content.toString();
        }
        // Fallback: serialize all input as the question
        return assistant.toolInput().toString();
    }

    /**
     * Extracts text content from any stream event that carries it.
     */
    private String extractTextContent(StreamJsonEvent event) {
        if (event instanceof AssistantEvent assistant) {
            return assistant.textContent();
        }
        if (event instanceof UserEvent user) {
            return user.content();
        }
        if (event instanceof ResultEvent result) {
            return result.resultText();
        }
        return null;
    }

    /**
     * Truncates text to a maximum length, appending "..." if truncated.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
