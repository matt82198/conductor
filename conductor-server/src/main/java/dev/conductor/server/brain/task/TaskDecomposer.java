package dev.conductor.server.brain.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.common.AgentRole;
import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.context.AgentDefinition;
import dev.conductor.server.brain.context.ContextIndex;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.brain.context.PersonalKnowledge;
import dev.conductor.server.brain.context.PersonalKnowledgeScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Creates {@link DecompositionPlan}s from high-level user prompts.
 *
 * <p>Uses a two-tier strategy:
 * <ol>
 *   <li><b>Claude API</b> — When an API key is configured, sends the prompt and
 *       project context to the Claude API for intelligent, prompt-specific decomposition.</li>
 *   <li><b>Template fallback</b> — If no API key is set, the API call fails, or the
 *       response cannot be parsed, falls back to the standard 3-step template
 *       (EXPLORER &rarr; FEATURE_ENGINEER &rarr; REVIEWER).</li>
 * </ol>
 *
 * <p>The interface is stable — callers always get back a {@link DecompositionPlan}
 * regardless of which strategy produced it.
 */
@Service
public class TaskDecomposer {

    private static final Logger log = LoggerFactory.getLogger(TaskDecomposer.class);

    private static final String SYSTEM_PROMPT_BASE = """
            You are a task decomposition engine for Conductor, an agent orchestration platform.
            Given a high-level prompt and project context, break it into subtasks that can be executed by specialized agents.

            Available agent roles:
            - EXPLORER: Analyze codebases, find files, understand architecture
            - FEATURE_ENGINEER: Write new code, implement features
            - TESTER: Write tests
            - REFACTORER: Improve existing code
            - REVIEWER: Review changes for quality and security
            - GENERAL: Any task that doesn't fit the above

            %s

            Rules:
            - First subtask should usually be EXPLORER to understand the codebase
            - Last subtask should usually be REVIEWER or TESTER
            - Each subtask gets its own agent — keep scopes narrow
            - If a custom agent is a better fit than a generic role, use it by setting "customAgent" to its name
            - Set dependsOn for tasks that need previous tasks' output
            - Set contextFrom for tasks that benefit from another task's discoveries
            - 2-7 subtasks is ideal. Don't over-decompose.

            Respond in this exact JSON format:
            {
              "subtasks": [
                {
                  "name": "short-name",
                  "description": "What this agent should do",
                  "role": "EXPLORER|FEATURE_ENGINEER|TESTER|REFACTORER|REVIEWER|GENERAL",
                  "customAgent": "agent-name-if-applicable-or-null",
                  "prompt": "The specific prompt for this agent",
                  "dependsOn": ["name-of-dependency"],
                  "contextFrom": ["name-of-context-source"],
                  "successCriteria": "How to know this is done"
                }
              ]
            }""";

    @Nullable
    private final BrainProperties brainProperties;

    @Nullable
    private final ObjectMapper objectMapper;

    @Nullable
    private final RestClient restClient;

    @Nullable
    private final ContextIngestionService contextIngestionService;

    @Nullable
    private final PersonalKnowledgeScanner personalKnowledgeScanner;

    /**
     * Full constructor for production use — Spring will inject all dependencies.
     */
    public TaskDecomposer(
            @Nullable BrainProperties brainProperties,
            @Nullable ObjectMapper objectMapper,
            @Nullable ContextIngestionService contextIngestionService,
            @Nullable PersonalKnowledgeScanner personalKnowledgeScanner
    ) {
        this.brainProperties = brainProperties;
        this.objectMapper = objectMapper;
        this.contextIngestionService = contextIngestionService;
        this.personalKnowledgeScanner = personalKnowledgeScanner;

        if (brainProperties != null
                && brainProperties.apiKey() != null
                && !brainProperties.apiKey().isBlank()
                && objectMapper != null) {
            this.restClient = RestClient.builder()
                    .baseUrl("https://api.anthropic.com")
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .build();
            log.info("TaskDecomposer initialized with Claude API — model: {}, apiKeyLength: {}",
                    brainProperties.model(), brainProperties.apiKey().length());
        } else {
            this.restClient = null;
            log.info("TaskDecomposer initialized in template-only mode (apiKey null={}, blank={}, objectMapper null={})",
                    brainProperties == null || brainProperties.apiKey() == null,
                    brainProperties != null && brainProperties.apiKey() != null && brainProperties.apiKey().isBlank(),
                    objectMapper == null);
        }
    }

    /**
     * No-arg constructor for backward compatibility and tests that don't need API.
     * Always uses the template fallback.
     */
    public TaskDecomposer() {
        this(null, null, null, null);
    }

    /**
     * Decomposes a high-level prompt into a structured execution plan.
     *
     * <p>Tries the Claude API first if configured, then falls back to the
     * template strategy if the API is unavailable or returns an unparseable response.
     *
     * @param prompt      the user's high-level prompt (e.g., "Add OAuth2 authentication")
     * @param projectPath absolute path to the target project directory
     * @param context     the current context index for intelligent decomposition
     * @return a new DecompositionPlan in CREATED status
     */
    public DecompositionPlan decompose(String prompt, String projectPath, ContextIndex context) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be blank");
        }
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("Project path must not be blank");
        }

        // Try Claude API first if configured
        if (restClient != null && objectMapper != null && brainProperties != null) {
            try {
                DecompositionPlan apiPlan = decomposeViaApi(prompt, projectPath, context);
                if (apiPlan != null) {
                    return apiPlan;
                }
                log.warn("API decomposition returned null — falling back to template");
            } catch (Exception e) {
                log.warn("API decomposition failed — falling back to template: {} — {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }

        return decomposeFromTemplate(prompt, projectPath);
    }

    // ─── Template Decomposition ──────────────────────────────────────

    /**
     * Template-based decomposition: produces a standard 3-step plan
     * (EXPLORER &rarr; FEATURE_ENGINEER &rarr; REVIEWER).
     *
     * <p>This is the fallback strategy used when the Claude API is unavailable
     * or returns an unparseable response.
     *
     * @param prompt      the user's high-level prompt
     * @param projectPath absolute path to the target project directory
     * @return a new DecompositionPlan in CREATED status
     */
    DecompositionPlan decomposeFromTemplate(String prompt, String projectPath) {
        String planId = UUID.randomUUID().toString();

        // Step 1: Explorer — codebase analysis
        String exploreId = UUID.randomUUID().toString();
        Subtask explore = new Subtask(
                exploreId,
                "Explore codebase",
                "Analyze the codebase and identify relevant files, patterns, and integration points for the task.",
                AgentRole.EXPLORER,
                List.of(),           // no dependencies — runs first
                List.of(),           // no context input
                projectPath,
                String.format(
                        "Analyze the codebase and identify all relevant files, patterns, and integration points for the following task. "
                        + "Report your findings clearly including file paths, existing patterns, and recommended approach.\n\n"
                        + "Task: %s", prompt),
                "Comprehensive analysis of relevant code areas with specific file paths and recommendations",
                SubtaskStatus.PENDING,
                null, null, null, null
        );

        // Step 2: Feature Engineer — implementation (depends on explore)
        String implementId = UUID.randomUUID().toString();
        Subtask implement = new Subtask(
                implementId,
                "Implement changes",
                "Implement the requested changes based on the exploration findings.",
                AgentRole.FEATURE_ENGINEER,
                List.of(exploreId),      // must wait for exploration
                List.of(exploreId),      // receive exploration context
                projectPath,
                String.format(
                        "Implement the following task. Use the context provided by the exploration step "
                        + "to understand the codebase structure and integration points.\n\n"
                        + "Task: %s", prompt),
                "Working implementation that fulfills the task requirements",
                SubtaskStatus.PENDING,
                null, null, null, null
        );

        // Step 3: Reviewer — code review (depends on implementation)
        String reviewId = UUID.randomUUID().toString();
        Subtask review = new Subtask(
                reviewId,
                "Review changes",
                "Review the implementation for correctness, code quality, and adherence to project conventions.",
                AgentRole.REVIEWER,
                List.of(implementId),              // must wait for implementation
                List.of(exploreId, implementId),   // receive both exploration and implementation context
                projectPath,
                String.format(
                        "Review the changes made for the following task. Check for correctness, code quality, "
                        + "edge cases, and adherence to project conventions. Use the exploration context to "
                        + "understand what the codebase looked like before and the implementation context to "
                        + "understand what was changed.\n\n"
                        + "Task: %s", prompt),
                "Code review with specific feedback and any issues identified",
                SubtaskStatus.PENDING,
                null, null, null, null
        );

        List<Subtask> subtasks = List.of(explore, implement, review);

        DecompositionPlan plan = new DecompositionPlan(
                planId, prompt, projectPath, subtasks, null, null
        );

        log.info("Template decomposition: {} subtasks (planId={}): {}",
                subtasks.size(), planId, subtasks.stream().map(Subtask::name).toList());

        return plan;
    }

    // ─── API Decomposition ───────────────────────────────────────────

    /**
     * Calls the Claude API to produce an intelligent, prompt-specific decomposition.
     *
     * @param prompt      the user's high-level prompt
     * @param projectPath absolute path to the target project directory
     * @param context     the current context index
     * @return a DecompositionPlan from the API response, or null if parsing fails
     */
    private DecompositionPlan decomposeViaApi(String prompt, String projectPath, ContextIndex context)
            throws Exception {

        String userMessage = buildUserMessage(prompt, projectPath, context);
        String systemPrompt = buildSystemPrompt();

        Map<String, Object> requestBody = Map.of(
                "model", brainProperties.model(),
                "max_tokens", 2048,
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", userMessage)
                )
        );

        String body = objectMapper.writeValueAsString(requestBody);
        log.info("Calling Claude API for task decomposition — model={}, body length={}",
                brainProperties.model(), body.length());

        String response = restClient.post()
                .uri("/v1/messages")
                .header("x-api-key", brainProperties.apiKey())
                .body(body)
                .retrieve()
                .body(String.class);

        log.debug("Claude API decomposition response received, length={}",
                response != null ? response.length() : "null");

        return parseApiResponse(response, prompt, projectPath);
    }

    /**
     * Builds the system prompt, injecting custom agent definitions when available.
     */
    private String buildSystemPrompt() {
        String agentSection = "";
        if (personalKnowledgeScanner != null) {
            PersonalKnowledge pk = personalKnowledgeScanner.scan();
            if (!pk.agents().isEmpty()) {
                StringBuilder sb = new StringBuilder("Custom agents available (use these when they fit better than generic roles):\n");
                for (AgentDefinition agent : pk.agents()) {
                    sb.append("- ").append(agent.name());
                    if (!agent.description().isBlank()) {
                        // Truncate long descriptions for the prompt
                        String desc = agent.description();
                        if (desc.length() > 200) desc = desc.substring(0, 200) + "...";
                        sb.append(": ").append(desc);
                    }
                    sb.append("\n");
                }
                agentSection = sb.toString();
            }
        }
        return String.format(SYSTEM_PROMPT_BASE, agentSection);
    }

    /**
     * Builds the user message for the Claude API call, including project context
     * from the {@link ContextIngestionService} when available.
     */
    private String buildUserMessage(String prompt, String projectPath, ContextIndex context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(projectPath).append("\n");
        sb.append("Task: ").append(prompt).append("\n");

        // Render project context if available
        if (contextIngestionService != null && context != null) {
            int budget = (brainProperties != null) ? brainProperties.contextWindowBudget() : 50000;
            String contextStr = contextIngestionService.renderForPrompt(context, projectPath, budget);
            if (contextStr != null && !contextStr.isBlank()) {
                sb.append("\n").append(contextStr);
            }
        }

        return sb.toString();
    }

    /**
     * Parses the Claude API response into a {@link DecompositionPlan}.
     *
     * <p>Extracts the text content from the API response envelope, parses the
     * JSON subtask array, converts names to IDs, and wires up dependency/context
     * relationships.
     *
     * @param response    raw API response JSON
     * @param prompt      the original prompt (for the plan record)
     * @param projectPath the project path (for the plan record and subtasks)
     * @return a DecompositionPlan, or null if the response is malformed
     */
    private DecompositionPlan parseApiResponse(String response, String prompt, String projectPath)
            throws Exception {

        if (response == null || response.isBlank()) {
            log.warn("Empty response from Claude API");
            return null;
        }

        // Extract text from Anthropic Messages API response envelope
        JsonNode root = objectMapper.readTree(response);
        JsonNode contentArray = root.get("content");
        if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
            log.warn("Unexpected Claude API response structure — missing content array");
            return null;
        }

        String text = contentArray.get(0).get("text").asText();
        log.debug("Claude decomposition raw text: {}", text);

        // Strip markdown code fences if present
        String jsonText = stripCodeFences(text.trim());

        // Parse the subtasks JSON
        JsonNode parsed = objectMapper.readTree(jsonText);
        JsonNode subtasksNode = parsed.get("subtasks");
        if (subtasksNode == null || !subtasksNode.isArray() || subtasksNode.isEmpty()) {
            log.warn("No subtasks array in Claude API response");
            return null;
        }

        // First pass: assign IDs and build name→ID mapping
        String planId = UUID.randomUUID().toString();
        Map<String, String> nameToId = new LinkedHashMap<>();
        List<JsonNode> subtaskNodes = new ArrayList<>();

        for (JsonNode node : subtasksNode) {
            String name = node.has("name") ? node.get("name").asText() : "step-" + (subtaskNodes.size() + 1);
            String id = UUID.randomUUID().toString();
            nameToId.put(name, id);
            subtaskNodes.add(node);
        }

        // Second pass: build Subtask records with resolved dependencies
        List<Subtask> subtasks = new ArrayList<>();
        List<String> names = new ArrayList<>(nameToId.keySet());

        for (int i = 0; i < subtaskNodes.size(); i++) {
            JsonNode node = subtaskNodes.get(i);
            String name = names.get(i);
            String id = nameToId.get(name);

            String description = node.has("description") ? node.get("description").asText() : name;
            String subtaskPrompt = node.has("prompt") ? node.get("prompt").asText() : description;
            String successCriteria = node.has("successCriteria") ? node.get("successCriteria").asText() : null;

            // Parse role — default to GENERAL if unrecognized
            AgentRole role = AgentRole.GENERAL;
            if (node.has("role")) {
                try {
                    role = AgentRole.valueOf(node.get("role").asText().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown role '{}' in API response — defaulting to GENERAL", node.get("role").asText());
                }
            }

            // Resolve dependsOn names to IDs
            List<String> dependsOn = resolveNames(node.get("dependsOn"), nameToId);

            // Resolve contextFrom names to IDs
            List<String> contextFrom = resolveNames(node.get("contextFrom"), nameToId);

            // Check for custom agent assignment
            String customAgentName = node.has("customAgent") && !node.get("customAgent").isNull()
                    ? node.get("customAgent").asText() : null;
            String agentSystemPrompt = null;
            if (customAgentName != null && personalKnowledgeScanner != null) {
                agentSystemPrompt = personalKnowledgeScanner.scan().agents().stream()
                        .filter(a -> a.name().equals(customAgentName))
                        .map(AgentDefinition::systemPrompt)
                        .findFirst().orElse(null);
                if (agentSystemPrompt != null) {
                    log.info("Subtask '{}' assigned custom agent '{}' with system prompt ({} chars)",
                            name, customAgentName, agentSystemPrompt.length());
                }
            }

            subtasks.add(new Subtask(
                    id, name, description, role,
                    dependsOn, contextFrom,
                    projectPath, subtaskPrompt, successCriteria,
                    SubtaskStatus.PENDING,
                    null, null, null, null,
                    customAgentName, agentSystemPrompt
            ));
        }

        DecompositionPlan plan = new DecompositionPlan(
                planId, prompt, projectPath, List.copyOf(subtasks), null, null
        );

        log.info("API decomposition: {} subtasks (planId={}): {}",
                subtasks.size(), planId, subtasks.stream().map(Subtask::name).toList());

        return plan;
    }

    /**
     * Resolves a JSON array of subtask names to their corresponding UUIDs.
     * Unknown names are silently dropped.
     */
    private List<String> resolveNames(JsonNode arrayNode, Map<String, String> nameToId) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (JsonNode nameNode : arrayNode) {
            String refName = nameNode.asText();
            String refId = nameToId.get(refName);
            if (refId != null) {
                resolved.add(refId);
            } else {
                log.debug("Dropping unknown dependency/context reference: '{}'", refName);
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * Strips markdown code fences (```json ... ``` or ``` ... ```) from API responses.
     */
    private String stripCodeFences(String text) {
        if (text.startsWith("```")) {
            // Remove opening fence (possibly with language hint)
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            // Remove closing fence
            int lastFence = text.lastIndexOf("```");
            if (lastFence > 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text.trim();
    }
}
