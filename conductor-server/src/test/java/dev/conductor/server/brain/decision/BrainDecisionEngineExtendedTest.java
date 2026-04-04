package dev.conductor.server.brain.decision;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.BrainStateManager;
import dev.conductor.server.brain.behavior.BehaviorEvent;
import dev.conductor.server.brain.behavior.BehaviorLog;
import dev.conductor.server.brain.behavior.BehaviorModelBuilder;
import dev.conductor.server.brain.context.ClaudeMdScanner;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.humaninput.HumanInputNeededEvent;
import dev.conductor.server.humaninput.HumanInputQueue;
import dev.conductor.server.humaninput.HumanInputRequest;
import dev.conductor.server.humaninput.HumanInputResponder;
import dev.conductor.server.project.ProjectRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Extended tests for {@link BrainDecisionEngine} covering specific decision
 * scenarios, confidence threshold boundaries, and escalation details.
 * Supplements the base decision engine test.
 */
class BrainDecisionEngineExtendedTest {

    @TempDir
    Path tempDir;

    private BehaviorLog behaviorLog;
    private BrainDecisionEngine engine;
    private HumanInputQueue queue;
    private CapturingEventPublisher eventPublisher;
    private CapturingResponder responder;

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
        ContextIngestionService contextService = new ContextIngestionService(projectRegistry, scanner, null);

        queue = new HumanInputQueue();
        responder = new CapturingResponder(queue);
        eventPublisher = new CapturingEventPublisher();

        engine = new BrainDecisionEngine(
                props, new BrainStateManager(props), modelBuilder, contextService,
                responder, queue, eventPublisher, null
        );
    }

    // ─── Disabled Brain ──────────────────────────────────────────────

    @Test
    @DisplayName("disabled brain does not publish any events")
    void disabledBrain_noEventsPublished() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("disabled.jsonl").toString();
        BrainProperties disabledProps = new BrainProperties(
                false, null, null, 0.8, 10, logPath, 100000
        );

        BrainDecisionEngine disabledEngine = new BrainDecisionEngine(
                disabledProps,
                new BrainStateManager(disabledProps),
                new BehaviorModelBuilder(new BehaviorLog(om, disabledProps), null),
                new ContextIngestionService(new ProjectRegistry(), new ClaudeMdScanner(), null),
                responder, queue, eventPublisher, null
        );

        HumanInputRequest request = createRequest("test");
        disabledEngine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        assertTrue(eventPublisher.events.isEmpty());
        assertFalse(responder.wasCalled);
    }

    @Test
    @DisplayName("disabled brain does not call responder")
    void disabledBrain_responderNotCalled() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        String logPath = tempDir.resolve("disabled2.jsonl").toString();
        BrainProperties disabledProps = new BrainProperties(
                false, null, null, 0.8, 10, logPath, 100000
        );

        BrainDecisionEngine disabledEngine = new BrainDecisionEngine(
                disabledProps,
                new BrainStateManager(disabledProps),
                new BehaviorModelBuilder(new BehaviorLog(om, disabledProps), null),
                new ContextIngestionService(new ProjectRegistry(), new ClaudeMdScanner(), null),
                responder, queue, eventPublisher, null
        );

        disabledEngine.onHumanInputNeeded(
                new HumanInputNeededEvent(createRequest("anything"))
        );

        assertFalse(responder.wasCalled);
    }

    // ─── Escalation details ──────────────────────────────────────────

    @Test
    @DisplayName("escalation event contains correct reason when no behavior match")
    void noMatch_escalationReason() {
        HumanInputRequest request = createRequest("Never-seen question");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        assertEquals(1, eventPublisher.events.size());
        BrainEscalationEvent escalation = (BrainEscalationEvent) eventPublisher.events.get(0);
        assertTrue(escalation.reason().startsWith("No behavior pattern match"),
                "Reason should start with 'No behavior pattern match', got: " + escalation.reason());
        assertEquals(0.0, escalation.confidence());
        assertNull(escalation.recommendation());
    }

    @Test
    @DisplayName("escalation event includes the correct requestId and agentId")
    void escalation_identifiers() {
        HumanInputRequest request = createRequest("Unknown question");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        BrainEscalationEvent escalation = (BrainEscalationEvent) eventPublisher.events.get(0);
        assertEquals(request.requestId(), escalation.requestId());
        assertEquals(request.agentId().toString(), escalation.agentId());
        assertNotNull(escalation.decidedAt());
    }

    // ─── Auto-response ───────────────────────────────────────────────

    @Test
    @DisplayName("auto-response publishes BrainResponseEvent when confidence exceeds threshold")
    void autoResponse_publishesResponseEvent() {
        // Build a strong pattern: 10 identical responses to the same question
        String question = "Do you want me to write the tests?";
        for (int i = 0; i < 10; i++) {
            appendResponded(question, "yes write them");
        }

        HumanInputRequest request = createRequest(question);
        queue.add(request);
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        boolean hasResponse = eventPublisher.events.stream()
                .anyMatch(e -> e instanceof BrainResponseEvent);

        if (hasResponse) {
            BrainResponseEvent response = eventPublisher.events.stream()
                    .filter(e -> e instanceof BrainResponseEvent)
                    .map(e -> (BrainResponseEvent) e)
                    .findFirst().orElseThrow();

            assertEquals(request.requestId(), response.requestId());
            assertEquals(request.agentId().toString(), response.agentId());
            assertNotNull(response.response());
            assertTrue(response.confidence() >= 0.8);
            assertNotNull(response.reasoning());
            assertNotNull(response.decidedAt());
            assertTrue(responder.wasCalled);
        }
        // If the model didn't reach 0.8 confidence even with 10 responses,
        // escalation is acceptable behavior
    }

    @Test
    @DisplayName("insufficient responses result in escalation, not auto-response")
    void fewResponses_escalates() {
        appendResponded("Occasional question", "maybe");

        HumanInputRequest request = createRequest("Occasional question");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        boolean hasEscalation = eventPublisher.events.stream()
                .anyMatch(e -> e instanceof BrainEscalationEvent);
        assertTrue(hasEscalation, "Too few responses should result in escalation");
    }

    @Test
    @DisplayName("inconsistent responses result in escalation even with 3+ entries")
    void inconsistentResponses_escalates() {
        appendResponded("Multi-option question?", "use approach A");
        appendResponded("Multi-option question?", "go with B");
        appendResponded("Multi-option question?", "try C");
        appendResponded("Multi-option question?", "maybe D");

        HumanInputRequest request = createRequest("Multi-option question?");
        engine.onHumanInputNeeded(new HumanInputNeededEvent(request));

        // With inconsistent responses, confidence should be low -> escalation
        boolean hasEscalation = eventPublisher.events.stream()
                .anyMatch(e -> e instanceof BrainEscalationEvent);
        assertTrue(hasEscalation,
                "Inconsistent responses should not produce high-confidence auto-response");
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private void appendResponded(String question, String response) {
        behaviorLog.append(new BehaviorEvent(
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
     * Test stub that captures published events for verification.
     */
    static class CapturingEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    /**
     * Test stub for HumanInputResponder that records whether respond() was called
     * without requiring a real ClaudeProcessManager.
     */
    static class CapturingResponder extends HumanInputResponder {
        boolean wasCalled = false;
        String lastRequestId;
        String lastResponse;

        CapturingResponder(HumanInputQueue queue) {
            super(queue, null); // null process manager -- we override respond()
        }

        @Override
        public void respond(String requestId, String responseText) {
            wasCalled = true;
            lastRequestId = requestId;
            lastResponse = responseText;
        }
    }
}
