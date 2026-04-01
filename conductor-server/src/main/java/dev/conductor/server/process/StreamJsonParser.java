package dev.conductor.server.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * Parses a single line of JSON from Claude CLI's {@code --output-format stream-json}
 * output into a typed {@link StreamJsonEvent}.
 *
 * <p>Each line is a complete JSON object. The parser switches on the {@code "type"} field
 * to determine which record to construct. Unknown types or malformed JSON produce a
 * {@link ParseErrorEvent} rather than throwing, ensuring the reader loop never breaks.
 *
 * <p>Thread-safe: uses a shared ObjectMapper (Jackson ObjectMapper is thread-safe
 * after configuration).
 */
@Component
public class StreamJsonParser {

    private static final Logger log = LoggerFactory.getLogger(StreamJsonParser.class);

    private final ObjectMapper mapper;

    public StreamJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses a single JSON line into a StreamJsonEvent.
     *
     * @param line a complete JSON object string (one line from stdout)
     * @return the parsed event, or a ParseErrorEvent if parsing fails
     */
    public StreamJsonEvent parse(String line) {
        if (line == null || line.isBlank()) {
            return new ParseErrorEvent(null, null, "Empty or null line", "");
        }

        try {
            JsonNode root = mapper.readTree(line);
            String type = textOrNull(root, "type");

            if (type == null) {
                return new ParseErrorEvent(null, null, "Missing 'type' field", line);
            }

            return switch (type) {
                case "system" -> parseSystemInit(root, line);
                case "assistant" -> parseAssistant(root, line);
                case "user" -> parseUser(root, line);
                case "rate_limit_event" -> parseRateLimit(root, line);
                case "result" -> parseResult(root, line);
                default -> new ParseErrorEvent(
                        textOrNull(root, "session_id"),
                        textOrNull(root, "uuid"),
                        "Unknown event type: " + type,
                        line
                );
            };
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stream-json line: {}", e.getMessage());
            return new ParseErrorEvent(null, null, "JSON parse error: " + e.getMessage(), line);
        }
    }


    // ─── Type-specific parsers ─────────────────────────────────────────

    private SystemInitEvent parseSystemInit(JsonNode root, String rawJson) {
        return new SystemInitEvent(
                textOrNull(root, "session_id"),
                textOrNull(root, "uuid"),
                textOrNull(root, "model"),
                listOfStrings(root, "tools"),
                textOrNull(root, "claude_code_version"),
                textOrNull(root, "cwd"),
                textOrNull(root, "permissionMode"),
                rawJson
        );
    }

    private AssistantEvent parseAssistant(JsonNode root, String rawJson) {
        JsonNode message = root.path("message");
        String messageId = textOrNull(message, "id");
        String model = textOrNull(message, "model");
        String stopReason = textOrNull(message, "stop_reason");

        // Usage from message.usage
        JsonNode usage = message.path("usage");
        int inputTokens = intOrZero(usage, "input_tokens");
        int outputTokens = intOrZero(usage, "output_tokens");

        // Content block -- always exactly one entry in content[]
        JsonNode contentArray = message.path("content");
        if (!contentArray.isArray() || contentArray.isEmpty()) {
            return new AssistantEvent(
                    textOrNull(root, "session_id"),
                    textOrNull(root, "uuid"),
                    messageId, model, "unknown", null, null, null, null,
                    stopReason, inputTokens, outputTokens, rawJson
            );
        }

        JsonNode block = contentArray.get(0);
        String contentType = textOrNull(block, "type");

        String textContent = null;
        String toolName = null;
        String toolUseId = null;
        Map<String, Object> toolInput = null;

        if ("thinking".equals(contentType)) {
            textContent = textOrNull(block, "thinking");
        } else if ("text".equals(contentType)) {
            textContent = textOrNull(block, "text");
        } else if ("tool_use".equals(contentType)) {
            toolName = textOrNull(block, "name");
            toolUseId = textOrNull(block, "id");
            JsonNode inputNode = block.path("input");
            if (inputNode.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.convertValue(inputNode, Map.class);
                toolInput = parsed;
            }
        }

        return new AssistantEvent(
                textOrNull(root, "session_id"),
                textOrNull(root, "uuid"),
                messageId, model, contentType, textContent, toolName, toolUseId, toolInput,
                stopReason, inputTokens, outputTokens, rawJson
        );
    }

    private UserEvent parseUser(JsonNode root, String rawJson) {
        JsonNode message = root.path("message");
        JsonNode contentArray = message.path("content");

        String toolUseId = null;
        String content = null;
        boolean isError = false;

        if (contentArray.isArray() && !contentArray.isEmpty()) {
            JsonNode block = contentArray.get(0);
            toolUseId = textOrNull(block, "tool_use_id");
            content = textOrNull(block, "content");
            isError = block.path("is_error").asBoolean(false);
        }

        return new UserEvent(
                textOrNull(root, "session_id"),
                textOrNull(root, "uuid"),
                toolUseId,
                content,
                isError,
                textOrNull(root, "timestamp"),
                rawJson
        );
    }

    private RateLimitEvent parseRateLimit(JsonNode root, String rawJson) {
        JsonNode info = root.path("rate_limit_info");
        return new RateLimitEvent(
                textOrNull(root, "session_id"),
                textOrNull(root, "uuid"),
                textOrNull(info, "status"),
                info.path("resetsAt").asLong(0),
                textOrNull(info, "rateLimitType"),
                info.path("isUsingOverage").asBoolean(false),
                rawJson
        );
    }

    private ResultEvent parseResult(JsonNode root, String rawJson) {
        // Aggregate usage from the top-level usage object
        JsonNode usage = root.path("usage");
        int inputTokens = intOrZero(usage, "input_tokens");
        int outputTokens = intOrZero(usage, "output_tokens");

        return new ResultEvent(
                textOrNull(root, "session_id"),
                textOrNull(root, "uuid"),
                textOrNull(root, "subtype"),
                root.path("is_error").asBoolean(false),
                root.path("total_cost_usd").asDouble(0.0),
                root.path("duration_ms").asLong(0),
                root.path("duration_api_ms").asLong(0),
                root.path("num_turns").asInt(0),
                textOrNull(root, "result"),
                textOrNull(root, "stop_reason"),
                inputTokens,
                outputTokens,
                rawJson
        );
    }


    // ─── Helpers ───────────────────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isTextual() ? child.asText() : null;
    }

    private static int intOrZero(JsonNode node, String field) {
        return node.path(field).asInt(0);
    }

    private static java.util.List<String> listOfStrings(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return Collections.emptyList();
        }
        java.util.List<String> result = new java.util.ArrayList<>(array.size());
        for (JsonNode elem : array) {
            if (elem.isTextual()) {
                result.add(elem.asText());
            }
        }
        return Collections.unmodifiableList(result);
    }
}
