package dev.conductor.server.brain.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.behavior.BehaviorModel;
import dev.conductor.server.brain.decision.BrainDecision;
import dev.conductor.server.humaninput.HumanInputRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the Claude API, used by the Brain to evaluate requests that the
 * behavior model cannot handle with sufficient confidence.
 *
 * <p>Uses Spring's {@link RestClient} to call the Anthropic Messages API directly
 * (no SDK dependency). Pattern borrowed from the medallioGenAi project's
 * {@code GenerationService}.
 *
 * <p>Conditionally created only when {@code conductor.brain.api-key} is set.
 */
@Service
@ConditionalOnProperty(name = "conductor.brain.api-key")
public class BrainApiClient {

    private static final Logger log = LoggerFactory.getLogger(BrainApiClient.class);

    private final BrainProperties brainProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BrainApiClient(BrainProperties brainProperties, ObjectMapper objectMapper) {
        this.brainProperties = brainProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        log.info("Brain API client initialized — model: {}, key present: {}",
                brainProperties.model(), !brainProperties.apiKey().isBlank());
    }

    /**
     * Evaluates a human input request using the Claude API.
     *
     * <p>Constructs a prompt from the request context, project context, and
     * behavior model, calls the Anthropic Messages API, and parses the
     * response into a {@link BrainDecision}.
     *
     * @param request       the human input request to evaluate
     * @param contextPrompt pre-rendered project context for the system prompt
     * @param model         the current behavior model for style guidance
     * @return a decision, or empty if the API is unavailable or fails
     */
    public Optional<BrainDecision> evaluate(
            HumanInputRequest request,
            String contextPrompt,
            BehaviorModel model
    ) {
        try {
            String systemPrompt = buildSystemPrompt(contextPrompt, model);
            String userMessage = buildUserMessage(request);

            Map<String, Object> requestBody = Map.of(
                    "model", brainProperties.model(),
                    "max_tokens", 1024,
                    "system", systemPrompt,
                    "messages", List.of(
                            Map.of("role", "user", "content", userMessage)
                    )
            );

            String body = objectMapper.writeValueAsString(requestBody);
            log.info("Calling Claude API for request {} — model={}, body length={}",
                    request.requestId(), brainProperties.model(), body.length());

            String response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", brainProperties.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.debug("Claude API response received, length={}",
                    response != null ? response.length() : "null");

            return parseResponse(response);

        } catch (Exception e) {
            log.error("Claude API call failed for request {}: {} — {}",
                    request.requestId(), e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private String buildSystemPrompt(String contextPrompt, BehaviorModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are the Conductor Brain — a leader agent that orchestrates Claude Code agents. \
                You never write code. You make decisions about how to respond to agent questions.

                When an agent asks a question, you must decide:
                1. RESPOND — provide an answer and the agent will continue working
                2. ESCALATE — defer to the human because this requires their judgment

                Respond in this exact JSON format (no markdown, no code fences):
                {"action":"RESPOND","response":"your answer here","confidence":0.85,"reasoning":"why you chose this"}

                Or to escalate:
                {"action":"ESCALATE","response":null,"confidence":0.3,"reasoning":"why the human should decide"}

                Guidelines:
                - RESPOND to routine questions about implementation approach, tool use, file locations
                - ESCALATE for architecture decisions, destructive operations, ambiguous requirements
                - ESCALATE if you're not confident (below 0.7)
                - Keep responses concise and actionable
                """);

        if (contextPrompt != null && !contextPrompt.isBlank()) {
            sb.append("\n\nPROJECT CONTEXT:\n").append(contextPrompt);
        }

        if (model != null && model.averageResponseWordCount() > 0) {
            sb.append("\n\nUSER STYLE: The user typically responds with ~")
                    .append(model.averageResponseWordCount())
                    .append(" words. Match this brevity.");
        }

        return sb.toString();
    }

    private String buildUserMessage(HumanInputRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent \"").append(request.agentName()).append("\" is asking:\n\n");
        sb.append(request.question());

        if (request.suggestedOptions() != null && !request.suggestedOptions().isEmpty()) {
            sb.append("\n\nSuggested options: ").append(String.join(", ", request.suggestedOptions()));
        }

        if (request.context() != null && !request.context().isBlank()) {
            sb.append("\n\nAgent context: ").append(request.context());
        }

        sb.append("\n\nDetection method: ").append(request.detectionMethod());
        sb.append(" | Confidence: ").append(String.format("%.2f", request.confidenceScore()));

        return sb.toString();
    }

    private Optional<BrainDecision> parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArray = root.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                log.warn("Unexpected Claude API response structure");
                return Optional.empty();
            }

            String text = contentArray.get(0).get("text").asText();
            log.debug("Claude raw response text: {}", text);

            // Parse the JSON decision from the response
            JsonNode decision = objectMapper.readTree(text.trim());
            String action = decision.get("action").asText();
            String responseText = decision.has("response") && !decision.get("response").isNull()
                    ? decision.get("response").asText() : null;
            double confidence = decision.has("confidence")
                    ? decision.get("confidence").asDouble() : 0.5;
            String reasoning = decision.has("reasoning")
                    ? decision.get("reasoning").asText() : "";

            return Optional.of(new BrainDecision(action, responseText, confidence, reasoning));

        } catch (Exception e) {
            log.error("Failed to parse Claude API response: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
