package dev.conductor.server.brain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.behavior.BehaviorModel;
import dev.conductor.server.brain.decision.BrainDecision;
import dev.conductor.server.humaninput.HumanInputRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BrainApiClient}.
 *
 * <p>These tests verify that the API client handles errors gracefully
 * without making real API calls (no valid API key = REST call fails
 * and evaluate returns empty).
 */
class BrainApiClientTest {

    private final ObjectMapper objectMapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return om;
    }

    @Test
    @DisplayName("evaluate() returns empty when API call fails (no real API key)")
    void evaluate_returnsEmptyOnApiFailure() {
        // Using a bogus API key -- the REST call will fail, and evaluate returns empty
        BrainProperties props = new BrainProperties(
                true, "sk-bogus-key", "claude-haiku-4-5-20251001", 0.8, 10,
                System.getProperty("java.io.tmpdir") + "/test-log.jsonl", 100000
        );
        BrainApiClient client = new BrainApiClient(props, objectMapper);

        HumanInputRequest request = new HumanInputRequest(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-agent",
                "Should I proceed?", List.of(), "", "NORMAL",
                Instant.now(), "TOOL_USE", 0.9
        );
        BehaviorModel model = new BehaviorModel(
                Map.of(), 0.5, Map.of(), 3, 0.5, Set.of(), Set.of(), Instant.now()
        );

        Optional<BrainDecision> result = client.evaluate(request, "context", model);

        // Should return empty because the API call fails with a bogus key
        assertTrue(result.isEmpty(), "Should return empty when API call fails");
    }

    @Test
    @DisplayName("evaluate() does not throw with null context or model")
    void evaluate_handlesNullsGracefully() {
        BrainProperties props = new BrainProperties(
                true, "sk-bogus-key", "claude-haiku-4-5-20251001", 0.8, 10,
                System.getProperty("java.io.tmpdir") + "/test-log.jsonl", 100000
        );
        BrainApiClient client = new BrainApiClient(props, objectMapper);

        HumanInputRequest request = new HumanInputRequest(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-agent",
                "question", List.of(), "", "NORMAL",
                Instant.now(), "TOOL_USE", 0.9
        );

        assertDoesNotThrow(() -> client.evaluate(request, null, null));
    }

    @Test
    @DisplayName("multiple evaluate() calls all return empty when API is unreachable")
    void evaluate_multipleCallsAllReturnEmpty() {
        BrainProperties props = new BrainProperties(
                true, "sk-bogus-key", "claude-haiku-4-5-20251001", 0.8, 10,
                System.getProperty("java.io.tmpdir") + "/test-log.jsonl", 100000
        );
        BrainApiClient client = new BrainApiClient(props, objectMapper);

        HumanInputRequest request = new HumanInputRequest(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-agent",
                "question", List.of(), "", "NORMAL",
                Instant.now(), "TOOL_USE", 0.9
        );
        BehaviorModel model = new BehaviorModel(
                Map.of(), 0.0, Map.of(), 0, 0.0, Set.of(), Set.of(), Instant.now()
        );

        for (int i = 0; i < 3; i++) {
            Optional<BrainDecision> result = client.evaluate(request, "context", model);
            assertTrue(result.isEmpty(), "Call " + i + " should return empty");
        }
    }
}
