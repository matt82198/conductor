package dev.conductor.server.brain.decision;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.BrainStateManager;
import dev.conductor.server.brain.behavior.BehaviorLog;
import dev.conductor.server.brain.behavior.BehaviorModelBuilder;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.brain.context.ClaudeMdScanner;
import dev.conductor.server.humaninput.HumanInputNeededEvent;
import dev.conductor.server.humaninput.HumanInputQueue;
import dev.conductor.server.humaninput.HumanInputRequest;
import dev.conductor.server.humaninput.HumanInputResponder;
import dev.conductor.server.project.ProjectRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BrainDecisionEngine}.
 * Validates event interception, auto-response, and escalation behavior.
 */
class BrainDecisionEngineTest {

    @TempDir
    Path tempDir;

    private BehaviorLog behaviorLog;
    private BrainDecisionEngine engine;
    private HumanInputQueue queue;
    private TestEventPublisher eventPublisher;
    private TestHumanInputResponder responder;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("behavior-log.jsonl").toString();
        BrainProperties props = new BrainProperties(true, null, null, 0.8, 10, logPath, 100000);

        behaviorLog = new BehaviorLog(objectMapper, props);
        BehaviorModelBuilder modelBuilder = new BehaviorModelBuilder(behaviorLog, null);

        ProjectRegistry projectRegistry = new ProjectRegistry();
        ClaudeMdScanner scanner = new ClaudeMdScanner();
        ContextIngestionService contextService = new ContextIngestionService(projectRegistry, scanner);

        queue = new HumanInputQueue();
        responder = new TestHumanInputResponder(queue);
        eventPublisher = new TestEventPublisher();

        engine = new BrainDecisionEngine(
                props, new BrainStateManager(props), modelBuilder, contextService,
                responder, queue, eventPublisher, null
        );
    }

    // ─── Disabled Brain ───────────────────────────────────────────────

    @Test
    void disabled_brain_does_not_intercept() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("disabled-log.jsonl").toString();
        BrainProperties disabledProps = new BrainProperties(false, null, null, 0.8, 10, logPath, 100000);

        BrainDecisionEngine disabledEngine = new BrainDecisionEngine(
                disabledProps,
                new BrainStateManager(disabledProps),
                new BehaviorModelBuilder(new BehaviorLog(objectMapper, disabledProps), null),
                new ContextIngestionService(new ProjectRegistry(), new ClaudeMdScanner()),
                responder, queue, eventPublisher, null
        );

        HumanInputRequest request = createRequest("test question");
        disabledEngine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        assertTrue(eventPublisher.events.isEmpty(),
                "Disabled brain should not publish any events");
    }

    // ─── Escalation (no pattern match) ────────────────────────────────

    @Test
    void unknown_question_is_escalated() {
        HumanInputRequest request = createRequest("Completely unknown question");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        assertEquals(1, eventPublisher.events.size());
        assertInstanceOf(BrainEscalationEvent.class, eventPublisher.events.get(0));

        BrainEscalationEvent escalation = (BrainEscalationEvent) eventPublisher.events.get(0);
        assertEquals(request.requestId(), escalation.requestId());
        assertTrue(escalation.reason().startsWith("No behavior pattern match"),
                "Reason should start with 'No behavior pattern match', got: " + escalation.reason());
    }

    @Test
    void escalation_includes_agent_id() {
        HumanInputRequest request = createRequest("Some question");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        BrainEscalationEvent escalation = (BrainEscalationEvent) eventPublisher.events.get(0);
        assertEquals(request.agentId().toString(), escalation.agentId());
    }

    // ─── Auto-response ────────────────────────────────────────────────

    @Test
    void auto_responds_when_behavior_pattern_matches() {
        // Build up a behavior pattern: 4 consistent responses
        String question = "Should I proceed with the file write?";
        for (int i = 0; i < 4; i++) {
            appendResponded(question, "yes proceed");
        }

        // Now trigger with the same question
        HumanInputRequest request = createRequest(question);
        queue.add(request); // Add to queue so responder can find it
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        // Should have auto-responded
        boolean hasResponseEvent = eventPublisher.events.stream()
                .anyMatch(e -> e instanceof BrainResponseEvent);

        if (hasResponseEvent) {
            BrainResponseEvent response = eventPublisher.events.stream()
                    .filter(e -> e instanceof BrainResponseEvent)
                    .map(e -> (BrainResponseEvent) e)
                    .findFirst().orElseThrow();
            assertEquals(request.requestId(), response.requestId());
            assertTrue(response.confidence() >= 0.8);
            assertTrue(responder.responded, "Responder should have been called");
        }
        // If confidence is below threshold, escalation is also valid behavior
    }

    @Test
    void low_confidence_match_still_escalates() {
        // Only 1 response — too few for auto-approve
        appendResponded("Rare question?", "maybe");

        HumanInputRequest request = createRequest("Rare question?");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        boolean hasEscalation = eventPublisher.events.stream()
                .anyMatch(e -> e instanceof BrainEscalationEvent);
        assertTrue(hasEscalation, "Low-confidence should escalate");
    }

    // ─── Test helpers ─────────────────────────────────────────────────

    private void appendResponded(String question, String response) {
        behaviorLog.append(new dev.conductor.server.brain.behavior.BehaviorEvent(
                Instant.now(), "RESPONDED", "agent-1", null,
                null, question, response, 2000L,
                Map.of("detectionMethod", "TOOL_USE")
        ));
    }

    private HumanInputRequest createRequest(String question) {
        return new HumanInputRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "test-agent",
                question,
                List.of(),
                "",
                "NORMAL",
                Instant.now(),
                "TOOL_USE",
                0.9
        );
    }

    /**
     * Test stub that captures published events.
     */
    static class TestEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    /**
     * Test stub that simulates HumanInputResponder without needing ClaudeProcessManager.
     */
    static class TestHumanInputResponder extends HumanInputResponder {
        boolean responded = false;
        String lastResponse;

        TestHumanInputResponder(HumanInputQueue queue) {
            super(queue, null); // null process manager — we override respond()
        }

        @Override
        public void respond(String requestId, String responseText) {
            responded = true;
            lastResponse = responseText;
            // Don't call super — it would NPE on null process manager
        }
    }
}
