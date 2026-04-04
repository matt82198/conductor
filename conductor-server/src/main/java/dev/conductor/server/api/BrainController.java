package dev.conductor.server.api;

import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.behavior.BehaviorLog;
import dev.conductor.server.brain.behavior.BehaviorModelBuilder;
import dev.conductor.server.brain.behavior.BrainFeedback;
import dev.conductor.server.brain.behavior.BrainFeedbackStore;
import dev.conductor.server.brain.command.CommandExecutor;
import dev.conductor.server.brain.command.CommandIntent;
import dev.conductor.server.brain.command.CommandInterpreter;
import dev.conductor.server.brain.command.CommandResult;
import dev.conductor.server.brain.context.ContextIndex;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.brain.context.ProjectKnowledge;
import dev.conductor.server.brain.context.ProjectKnowledgeExtractor;
import dev.conductor.server.brain.context.ProjectKnowledgeStore;
import dev.conductor.server.brain.task.DecompositionPlan;
import dev.conductor.server.brain.task.TaskDecomposer;
import dev.conductor.server.brain.task.TaskExecutor;
import dev.conductor.server.project.ProjectRecord;
import dev.conductor.server.project.ProjectRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

/**
 * Unified REST controller for all Brain module endpoints.
 *
 * <p>Consolidates status, behavior model, context index, project knowledge,
 * feedback, command bar, and task decomposition into a single controller
 * mounted at {@code /api/brain}.
 */
@RestController
@RequestMapping("/api/brain")
public class BrainController {

    private static final Logger log = LoggerFactory.getLogger(BrainController.class);

    private final BrainProperties brainProperties;
    private final BehaviorModelBuilder behaviorModelBuilder;
    private final BehaviorLog behaviorLog;
    private final ContextIngestionService contextIngestionService;
    private final ProjectRegistry projectRegistry;
    private final ProjectKnowledgeExtractor projectKnowledgeExtractor;
    private final ProjectKnowledgeStore projectKnowledgeStore;
    private final BrainFeedbackStore brainFeedbackStore;
    private final CommandInterpreter commandInterpreter;
    private final CommandExecutor commandExecutor;
    private final TaskDecomposer taskDecomposer;
    private final TaskExecutor taskExecutor;

    public BrainController(
            BrainProperties brainProperties,
            BehaviorModelBuilder behaviorModelBuilder,
            BehaviorLog behaviorLog,
            ContextIngestionService contextIngestionService,
            ProjectRegistry projectRegistry,
            BrainFeedbackStore brainFeedbackStore,
            CommandInterpreter commandInterpreter,
            CommandExecutor commandExecutor,
            TaskDecomposer taskDecomposer,
            TaskExecutor taskExecutor,
            @Autowired(required = false) ProjectKnowledgeExtractor projectKnowledgeExtractor,
            @Autowired(required = false) ProjectKnowledgeStore projectKnowledgeStore
    ) {
        this.brainProperties = brainProperties;
        this.behaviorModelBuilder = behaviorModelBuilder;
        this.behaviorLog = behaviorLog;
        this.contextIngestionService = contextIngestionService;
        this.projectRegistry = projectRegistry;
        this.brainFeedbackStore = brainFeedbackStore;
        this.commandInterpreter = commandInterpreter;
        this.commandExecutor = commandExecutor;
        this.taskDecomposer = taskDecomposer;
        this.taskExecutor = taskExecutor;
        this.projectKnowledgeExtractor = projectKnowledgeExtractor;
        this.projectKnowledgeStore = projectKnowledgeStore;
    }

    // ─── Status ────────────────────────────────────────────────────────

    /**
     * Returns the current Brain module status, including configuration,
     * behavior log size, and number of indexed projects.
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "enabled": true,
     *   "model": "claude-sonnet-4-6",
     *   "confidenceThreshold": 0.8,
     *   "behaviorLogSize": 247,
     *   "projectsIndexed": 3
     * }
     * </pre>
     *
     * @return brain status as a map (values may be null)
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", brainProperties.enabled());
        status.put("model", brainProperties.model());
        status.put("confidenceThreshold", brainProperties.confidenceThreshold());
        status.put("behaviorLogSize", behaviorLog.size());
        status.put("projectsIndexed", contextIngestionService.buildIndex().projects().size());
        return ResponseEntity.ok(status);
    }

    // ─── Behavior ──────────────────────────────────────────────────────

    /**
     * Returns the current behavior model built from accumulated behavior logs.
     *
     * <p>The model captures observed patterns in how the user interacts
     * with agents — response styles, timing, common decisions — so the
     * Brain can predict appropriate automated responses.
     *
     * @return the current {@code BehaviorModel} (Jackson-serialized record)
     */
    @GetMapping("/behavior")
    public ResponseEntity<?> getBehaviorModel() {
        return ResponseEntity.ok(behaviorModelBuilder.build());
    }

    // ─── Context ───────────────────────────────────────────────────────

    /**
     * Returns the current context index representing all ingested project data.
     *
     * <p>The index contains project structures, file summaries, and
     * relationships the Brain uses when making decisions about agent prompts.
     *
     * @return the current {@code ContextIndex} (Jackson-serialized record)
     */
    @GetMapping("/context")
    public ResponseEntity<?> getContextIndex() {
        return ResponseEntity.ok(contextIngestionService.buildIndex());
    }

    /**
     * Forces a full re-scan of all registered project contexts.
     *
     * <p>Triggers {@link ContextIngestionService#buildIndex()} to re-read
     * project files and rebuild the context index from scratch. Useful
     * after significant file changes or new project registration.
     *
     * @return the freshly rebuilt {@code ContextIndex}
     */
    @PostMapping("/context/refresh")
    public ResponseEntity<?> refreshContext() {
        log.info("Context refresh triggered via REST");
        return ResponseEntity.ok(contextIngestionService.buildIndex());
    }

    // ─── Knowledge ─────────────────────────────────────────────────────

    /**
     * Triggers deep analysis of a registered project via the Claude API.
     *
     * <p>Looks up the project in the registry, analyzes it using the
     * {@link ProjectKnowledgeExtractor}, saves the result, and returns it.
     *
     * @param projectId the project's unique identifier
     * @return 201 with the extracted knowledge, 404 if project not found,
     *         503 if the extractor or store is unavailable
     */
    @PostMapping("/analyze/{projectId}")
    public ResponseEntity<?> analyzeProject(@PathVariable String projectId) {
        if (projectKnowledgeExtractor == null || projectKnowledgeStore == null) {
            log.warn("Knowledge extractor or store not available — Brain may not be configured");
            return ResponseEntity.status(503).body("Project knowledge services not available");
        }

        var projectOpt = projectRegistry.get(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ProjectRecord project = projectOpt.get();
        log.info("Triggering analysis for project: {} [{}]", project.name(), project.id());

        ProjectKnowledge knowledge = projectKnowledgeExtractor.analyze(
                project.path(), project.id(), project.name()
        );
        projectKnowledgeStore.save(knowledge);

        return ResponseEntity.status(201).body(knowledge);
    }

    /**
     * Returns all stored project knowledge.
     *
     * @return 200 with list of project knowledge records, or 503 if store unavailable
     */
    @GetMapping("/knowledge")
    public ResponseEntity<?> getAllKnowledge() {
        if (projectKnowledgeStore == null) {
            return ResponseEntity.status(503).body("Project knowledge store not available");
        }
        List<ProjectKnowledge> allKnowledge = projectKnowledgeStore.loadAll();
        return ResponseEntity.ok(allKnowledge);
    }

    /**
     * Returns stored knowledge for a specific project.
     *
     * @param projectId the project's unique identifier
     * @return 200 with the knowledge, 404 if not found, 503 if store unavailable
     */
    @GetMapping("/knowledge/{projectId}")
    public ResponseEntity<?> getKnowledge(@PathVariable String projectId) {
        if (projectKnowledgeStore == null) {
            return ResponseEntity.status(503).body("Project knowledge store not available");
        }
        return projectKnowledgeStore.load(projectId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Feedback ──────────────────────────────────────────────────────

    /**
     * Submit feedback on a Brain decision.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "requestId": "req-123",
     *   "decision": "RESPOND",
     *   "brainResponse": "Yes, proceed with option A",
     *   "rating": "GOOD",
     *   "correction": null
     * }
     * </pre>
     *
     * <p>Rating values: "GOOD", "BAD", "NEUTRAL".
     * For "BAD" ratings, include a correction string describing what the Brain
     * should have done.
     *
     * @param request the feedback submission
     * @return status acknowledgement with the assigned feedbackId
     */
    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestBody FeedbackRequest request) {
        BrainFeedback feedback = new BrainFeedback(
                null,
                request.requestId(),
                request.decision(),
                request.brainResponse(),
                request.rating(),
                request.correction(),
                null
        );
        brainFeedbackStore.append(feedback);
        log.info("Brain feedback recorded: {} rating={} for request {}",
                feedback.feedbackId(), feedback.rating(), request.requestId());
        return ResponseEntity.ok(Map.of(
                "status", "recorded",
                "feedbackId", feedback.feedbackId()
        ));
    }

    /**
     * Get recent Brain feedback entries.
     *
     * @param limit maximum number of recent entries to return (default 50)
     * @return list of recent feedback entries, most recent last
     */
    @GetMapping("/feedback")
    public List<BrainFeedback> getFeedback(@RequestParam(defaultValue = "50") int limit) {
        return brainFeedbackStore.readRecent(limit);
    }

    // ─── Command ───────────────────────────────────────────────────────

    /**
     * Interprets and executes a natural language command.
     *
     * <p>Request body:
     * <pre>
     * { "text": "spawn an agent to add tests to myapp" }
     * </pre>
     *
     * <p>Response:
     * <pre>
     * {
     *   "intent": { "action": "SPAWN_AGENT", "parameters": {...}, "confidence": 0.9, ... },
     *   "result": { "success": true, "message": "Spawned agent...", "data": {...} }
     * }
     * </pre>
     *
     * @param request the command text
     * @return the interpreted intent and execution result
     */
    @PostMapping("/command")
    public ResponseEntity<CommandResponse> executeCommand(@RequestBody CommandRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new CommandResponse(
                            new CommandIntent("UNKNOWN", "", Map.of(), 0.0, "Empty command"),
                            new CommandResult(false, "Please enter a command.")
                    )
            );
        }

        log.info("Command bar input: '{}'", truncate(request.text(), 100));

        // Step 1: Interpret the natural language
        CommandIntent intent = commandInterpreter.interpret(request.text());

        log.info("Interpreted as: action={}, confidence={}, params={}",
                intent.action(), intent.confidence(), intent.parameters());

        // Step 2: Execute the command
        CommandResult result = commandExecutor.execute(intent);

        log.info("Command result: success={}, message='{}'",
                result.success(), truncate(result.message(), 100));

        return ResponseEntity.ok(new CommandResponse(intent, result));
    }

    // ─── Tasks ─────────────────────────────────────────────────────────

    /**
     * Decomposes a prompt into a plan and starts execution immediately.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "prompt": "Add OAuth2 authentication to the API",
     *   "projectPath": "/absolute/path/to/project"
     * }
     * </pre>
     *
     * <p>Returns the executing plan with HTTP 201.
     *
     * @param request the task request containing prompt and project path
     * @return the executing DecompositionPlan
     */
    @PostMapping("/tasks")
    public ResponseEntity<DecompositionPlan> createTask(@RequestBody TaskRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.projectPath() == null || request.projectPath().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Task decomposition requested: prompt='{}', projectPath='{}'",
                truncate(request.prompt(), 80), request.projectPath());

        // Build current context index for the decomposer
        ContextIndex context = contextIngestionService.buildIndex();

        // Decompose the prompt into a plan
        DecompositionPlan plan = taskDecomposer.decompose(
                request.prompt(), request.projectPath(), context);

        // Start execution
        DecompositionPlan executing = taskExecutor.execute(plan);

        return ResponseEntity
                .created(URI.create("/api/brain/tasks/" + executing.planId()))
                .body(executing);
    }

    /**
     * Returns all active plans (including recently completed/failed ones).
     *
     * @return collection of all plans tracked by the executor
     */
    @GetMapping("/tasks")
    public ResponseEntity<Collection<DecompositionPlan>> listTasks() {
        return ResponseEntity.ok(taskExecutor.getActivePlans());
    }

    /**
     * Returns a specific plan by ID.
     *
     * @param planId the plan identifier
     * @return the plan if found, 404 otherwise
     */
    @GetMapping("/tasks/{planId}")
    public ResponseEntity<DecompositionPlan> getTask(@PathVariable String planId) {
        Optional<DecompositionPlan> plan = taskExecutor.getPlan(planId);
        return plan.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cancels an executing plan. Kills all running agents and marks
     * remaining subtasks as CANCELLED.
     *
     * @param planId the plan to cancel
     * @return the cancelled plan if found, 404 otherwise
     */
    @DeleteMapping("/tasks/{planId}")
    public ResponseEntity<DecompositionPlan> cancelTask(@PathVariable String planId) {
        Optional<DecompositionPlan> cancelled = taskExecutor.cancel(planId);
        return cancelled.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── DTOs ──────────────────────────────────────────────────────────

    /** Request body for the feedback endpoint. */
    record FeedbackRequest(
            String requestId,
            String decision,
            String brainResponse,
            String rating,
            String correction
    ) {}

    /** Request body for the command endpoint. */
    record CommandRequest(String text) {}

    /** Response combining the interpreted intent and execution result. */
    record CommandResponse(CommandIntent intent, CommandResult result) {}

    /** Request body for task creation. */
    record TaskRequest(String prompt, String projectPath) {}

    // ─── Helpers ───────────────────────────────────────────────────────

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
