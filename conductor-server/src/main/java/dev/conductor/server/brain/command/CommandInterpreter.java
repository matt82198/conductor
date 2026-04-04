package dev.conductor.server.brain.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.brain.BrainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets natural language user input into structured {@link CommandIntent}s.
 *
 * <p>Uses a two-tier approach:
 * <ol>
 *   <li><b>Tier 1: Pattern matching</b> — fast, local keyword/regex matching
 *       that handles common phrasing without an API call. If confidence >= 0.8,
 *       the result is returned immediately.</li>
 *   <li><b>Tier 2: Claude API</b> — falls back to the Claude API for ambiguous
 *       or complex commands. Only called when an API key is configured.</li>
 * </ol>
 *
 * <p>This service has no state and is thread-safe.
 */
@Service
public class CommandInterpreter {

    private static final Logger log = LoggerFactory.getLogger(CommandInterpreter.class);

    // ─── Pattern matching constants ──────────────────────────────────

    private static final Pattern SPAWN_PATTERN = Pattern.compile(
            "\\b(spawn|create|start|launch)\\b.*\\bagent\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DECOMPOSE_PATTERN = Pattern.compile(
            "\\b(decompose|break\\s*down|plan)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern REGISTER_PATTERN = Pattern.compile(
            "\\b(register|add|open)\\b.*\\bproject\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern REGISTER_PATH_PATTERN = Pattern.compile(
            "\\b(register|add|open)\\b.*?([A-Za-z]:[/\\\\][^\\s]+|/[^\\s]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCAN_PATTERN = Pattern.compile(
            "\\bscan\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ANALYZE_PATTERN = Pattern.compile(
            "\\b(analyze|study|learn)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "\\b(status|what|how\\s+many|show|list)\\b.*\\bagent", Pattern.CASE_INSENSITIVE);

    private static final Pattern KILL_PATTERN = Pattern.compile(
            "\\b(kill|stop|cancel)\\b.*\\bagent\\b", Pattern.CASE_INSENSITIVE);

    /** Matches Windows paths (C:\..., C:/...) and Unix absolute paths (/...). */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "([A-Za-z]:[/\\\\][^\\s\"]+|/[^\\s\"]+)");

    /** Matches a quoted name (single or double quotes) or "called/named X". */
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?:called|named)\\s+[\"']?([\\w.-]+)[\"']?|[\"']([\\w.-]+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** Matches role keywords. */
    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "\\b(FEATURE_ENGINEER|TESTER|REFACTORER|REVIEWER|EXPLORER|GENERAL)\\b",
            Pattern.CASE_INSENSITIVE);

    // ─── Dependencies ────────────────────────────────────────────────

    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public CommandInterpreter(BrainProperties brainProperties, ObjectMapper objectMapper) {
        this.brainProperties = brainProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Interprets a natural language command into a structured {@link CommandIntent}.
     *
     * <p>First tries local pattern matching. If the result has confidence >= 0.8,
     * it is returned immediately. Otherwise, falls back to the Claude API
     * (if an API key is configured). If neither produces a result, returns
     * an UNKNOWN intent.
     *
     * @param userInput the raw natural language command text
     * @return a {@link CommandIntent} with the identified action and parameters
     */
    public CommandIntent interpret(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return new CommandIntent("UNKNOWN", userInput, Map.of(), 0.0,
                    "Empty input");
        }

        String trimmed = userInput.trim();

        // Tier 1: Pattern matching
        CommandIntent patternResult = tryPatternMatch(trimmed);
        if (patternResult != null && patternResult.confidence() >= 0.8) {
            log.debug("Pattern match succeeded for '{}' -> {} (confidence={})",
                    truncate(trimmed, 60), patternResult.action(), patternResult.confidence());
            return patternResult;
        }

        // Tier 2: Claude API fallback
        if (brainProperties.apiKey() != null && !brainProperties.apiKey().isBlank()) {
            try {
                CommandIntent apiResult = callApiForInterpretation(trimmed);
                if (apiResult != null) {
                    log.debug("API interpretation for '{}' -> {} (confidence={})",
                            truncate(trimmed, 60), apiResult.action(), apiResult.confidence());
                    return apiResult;
                }
            } catch (Exception e) {
                log.warn("Claude API interpretation failed: {} — falling back to pattern result",
                        e.getMessage());
            }
        }

        // Fallback: return pattern result (even if low confidence) or UNKNOWN
        if (patternResult != null) {
            return patternResult;
        }
        return new CommandIntent("UNKNOWN", trimmed, Map.of(), 0.0,
                "Could not interpret command");
    }

    // ─── Tier 1: Pattern Matching ────────────────────────────────────

    /**
     * Attempts to interpret the command using keyword/regex pattern matching.
     * Returns null if no pattern matches at all.
     */
    CommandIntent tryPatternMatch(String input) {
        // Check KILL before SPAWN since "kill agent" also contains "agent"
        // and kill patterns are more specific
        if (KILL_PATTERN.matcher(input).find()) {
            return buildKillIntent(input);
        }

        if (SPAWN_PATTERN.matcher(input).find()) {
            return buildSpawnIntent(input);
        }

        if (DECOMPOSE_PATTERN.matcher(input).find()) {
            return buildDecomposeIntent(input);
        }

        if (REGISTER_PATTERN.matcher(input).find() || REGISTER_PATH_PATTERN.matcher(input).find()) {
            return buildRegisterIntent(input);
        }

        if (SCAN_PATTERN.matcher(input).find()) {
            return buildScanIntent(input);
        }

        if (ANALYZE_PATTERN.matcher(input).find()) {
            return buildAnalyzeIntent(input);
        }

        if (STATUS_PATTERN.matcher(input).find()) {
            return buildStatusIntent(input);
        }

        return null;
    }

    private CommandIntent buildSpawnIntent(String input) {
        Map<String, String> params = new HashMap<>();

        // Extract name
        Matcher nameMatcher = NAME_PATTERN.matcher(input);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
            if (name != null) {
                params.put("name", name);
            }
        }

        // Extract role
        Matcher roleMatcher = ROLE_PATTERN.matcher(input);
        if (roleMatcher.find()) {
            params.put("role", roleMatcher.group(1).toUpperCase());
        }

        // Extract path
        String path = extractPath(input);
        if (path != null) {
            params.put("projectPath", path);
        }

        // Extract prompt: everything after common spawn preambles
        String prompt = extractPromptText(input,
                "(?i)(spawn|create|start|launch)\\s+(?:an?\\s+)?(?:\\w+\\s+)?agent\\s*(?:called\\s+\\S+\\s*)?(?:to\\s+|that\\s+|for\\s+)?");
        if (prompt != null && !prompt.isBlank()) {
            params.put("prompt", prompt.trim());
        }

        return new CommandIntent("SPAWN_AGENT", input, Map.copyOf(params), 0.9,
                "Matched spawn/create/start/launch + agent keywords");
    }

    private CommandIntent buildDecomposeIntent(String input) {
        Map<String, String> params = new HashMap<>();

        // Extract path
        String path = extractPath(input);
        if (path != null) {
            params.put("projectPath", path);
        }

        // Extract the task description: everything after the decompose keyword
        String prompt = extractPromptText(input,
                "(?i)(decompose|break\\s*down|plan)\\s*:?\\s*");
        if (prompt != null && !prompt.isBlank()) {
            params.put("prompt", prompt.trim());
        } else {
            // Fall back to using the whole input as the prompt
            params.put("prompt", input);
        }

        return new CommandIntent("DECOMPOSE_TASK", input, Map.copyOf(params), 0.85,
                "Matched decompose/break down/plan keywords");
    }

    private CommandIntent buildRegisterIntent(String input) {
        Map<String, String> params = new HashMap<>();

        String path = extractPath(input);
        if (path != null) {
            params.put("path", path);
            return new CommandIntent("REGISTER_PROJECT", input, Map.copyOf(params), 0.9,
                    "Matched register/add/open + project with path");
        }

        return new CommandIntent("REGISTER_PROJECT", input, Map.copyOf(params), 0.6,
                "Matched register/add/open + project but no path found");
    }

    private CommandIntent buildScanIntent(String input) {
        Map<String, String> params = new HashMap<>();

        String path = extractPath(input);
        if (path != null) {
            params.put("rootPath", path);
            return new CommandIntent("SCAN_PROJECTS", input, Map.copyOf(params), 0.9,
                    "Matched scan keyword with path");
        }

        return new CommandIntent("SCAN_PROJECTS", input, Map.copyOf(params), 0.6,
                "Matched scan keyword but no path found");
    }

    private CommandIntent buildAnalyzeIntent(String input) {
        Map<String, String> params = new HashMap<>();

        String path = extractPath(input);
        if (path != null) {
            params.put("projectPath", path);
        }

        // Try to extract a project name or ID
        Matcher nameMatcher = NAME_PATTERN.matcher(input);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
            if (name != null) {
                params.put("projectName", name);
            }
        }

        return new CommandIntent("ANALYZE_PROJECT", input, Map.copyOf(params), 0.85,
                "Matched analyze/study/learn keywords");
    }

    private CommandIntent buildStatusIntent(String input) {
        return new CommandIntent("QUERY_STATUS", input, Map.of(), 0.9,
                "Matched status/what/how many/show/list + agent keywords");
    }

    private CommandIntent buildKillIntent(String input) {
        Map<String, String> params = new HashMap<>();

        // Try to extract agent name or ID
        Matcher nameMatcher = NAME_PATTERN.matcher(input);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
            if (name != null) {
                params.put("agentName", name);
            }
        }

        // Also try to extract the word after "agent"
        if (!params.containsKey("agentName")) {
            Pattern afterAgent = Pattern.compile("\\bagent\\s+([\\w.-]+)", Pattern.CASE_INSENSITIVE);
            Matcher afterMatcher = afterAgent.matcher(input);
            if (afterMatcher.find()) {
                params.put("agentName", afterMatcher.group(1));
            }
        }

        // Try UUID-like pattern
        Pattern uuidPattern = Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                Pattern.CASE_INSENSITIVE);
        Matcher uuidMatcher = uuidPattern.matcher(input);
        if (uuidMatcher.find()) {
            params.put("agentId", uuidMatcher.group());
        }

        return new CommandIntent("KILL_AGENT", input, Map.copyOf(params), 0.9,
                "Matched kill/stop/cancel + agent keywords");
    }

    // ─── Tier 2: Claude API ──────────────────────────────────────────

    /**
     * Sends the user input to the Claude API for interpretation.
     * Returns null if the API call fails or produces unparseable output.
     */
    CommandIntent callApiForInterpretation(String input) {
        String systemPrompt = """
                You are a command interpreter for Conductor, an agent orchestration platform.
                Parse the user's natural language into a structured command.

                Respond in this exact JSON format (no markdown, no code fences):
                {"action":"SPAWN_AGENT|DECOMPOSE_TASK|REGISTER_PROJECT|SCAN_PROJECTS|ANALYZE_PROJECT|QUERY_STATUS|KILL_AGENT|UNKNOWN","parameters":{"name":"agent name if specified","role":"FEATURE_ENGINEER|TESTER|REFACTORER|REVIEWER|EXPLORER|GENERAL","projectPath":"/path/if/mentioned","prompt":"the task/instruction for the agent","agentId":"if referencing a specific agent","agentName":"if referencing by name","rootPath":"for scan commands","path":"for register commands"},"confidence":0.9,"reasoning":"brief explanation"}

                Available roles: FEATURE_ENGINEER, TESTER, REFACTORER, REVIEWER, EXPLORER, GENERAL
                Default role is GENERAL if not specified.
                Only include parameters that are actually present in the user's input.
                """;

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", brainProperties.model(),
                    "max_tokens", 512,
                    "system", systemPrompt,
                    "messages", List.of(
                            Map.of("role", "user", "content", input)
                    )
            );

            String body = objectMapper.writeValueAsString(requestBody);

            log.debug("Calling Claude API for command interpretation — input length={}",
                    input.length());

            String response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", brainProperties.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseApiResponse(response, input);

        } catch (Exception e) {
            log.warn("Claude API command interpretation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses the Claude API response into a {@link CommandIntent}.
     */
    private CommandIntent parseApiResponse(String response, String originalInput) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArray = root.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                log.warn("Unexpected Claude API response structure for command interpretation");
                return null;
            }

            String text = contentArray.get(0).get("text").asText().trim();
            JsonNode parsed = objectMapper.readTree(text);

            String action = parsed.has("action") ? parsed.get("action").asText() : "UNKNOWN";
            double confidence = parsed.has("confidence") ? parsed.get("confidence").asDouble() : 0.5;
            String reasoning = parsed.has("reasoning") ? parsed.get("reasoning").asText() : "";

            Map<String, String> params = new HashMap<>();
            if (parsed.has("parameters") && parsed.get("parameters").isObject()) {
                JsonNode paramsNode = parsed.get("parameters");
                paramsNode.fieldNames().forEachRemaining(field -> {
                    JsonNode value = paramsNode.get(field);
                    if (value != null && !value.isNull() && !value.asText().isBlank()) {
                        params.put(field, value.asText());
                    }
                });
            }

            return new CommandIntent(action, originalInput, Map.copyOf(params),
                    confidence, "API: " + reasoning);

        } catch (Exception e) {
            log.warn("Failed to parse Claude API command response: {}", e.getMessage());
            return null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * Extracts the first path-like string from the input.
     */
    private String extractPath(String input) {
        Matcher pathMatcher = PATH_PATTERN.matcher(input);
        if (pathMatcher.find()) {
            return pathMatcher.group(1);
        }
        return null;
    }

    /**
     * Extracts the "prompt" portion of text by removing the command preamble.
     * Returns null if nothing meaningful remains.
     */
    private String extractPromptText(String input, String preambleRegex) {
        String result = input.replaceFirst(preambleRegex, "").trim();
        // Also strip leading path if present (it's already captured as a parameter)
        if (result.isEmpty()) {
            return null;
        }
        return result;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
