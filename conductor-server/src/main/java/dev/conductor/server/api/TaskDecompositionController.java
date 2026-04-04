package dev.conductor.server.api;

import dev.conductor.server.brain.context.ContextIndex;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.brain.task.DecompositionPlan;
import dev.conductor.server.brain.task.TaskDecomposer;
import dev.conductor.server.brain.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Collection;
import java.util.Optional;

/**
 * REST controller for task decomposition endpoints (Phase 4C).
 *
 * <p>Provides CRUD operations for decomposition plans: create (decompose + execute),
 * list active plans, get plan details, and cancel running plans.
 *
 * <p>Mounted at {@code /api/brain/tasks} alongside the existing Brain status
 * endpoints in {@link BrainController}. Uses a separate controller to keep
 * the task decomposition surface decoupled from the core Brain introspection API.
 */
@RestController
@RequestMapping("/api/brain/tasks")
public class TaskDecompositionController {

    private static final Logger log = LoggerFactory.getLogger(TaskDecompositionController.class);

    private final TaskDecomposer taskDecomposer;
    private final TaskExecutor taskExecutor;
    private final ContextIngestionService contextIngestionService;

    public TaskDecompositionController(
            TaskDecomposer taskDecomposer,
            TaskExecutor taskExecutor,
            ContextIngestionService contextIngestionService
    ) {
        this.taskDecomposer = taskDecomposer;
        this.taskExecutor = taskExecutor;
        this.contextIngestionService = contextIngestionService;
    }

    // ─── Create & Execute ─────────────────────────────────────────────

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
    @PostMapping
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

    // ─── List ─────────────────────────────────────────────────────────

    /**
     * Returns all active plans (including recently completed/failed ones).
     *
     * @return collection of all plans tracked by the executor
     */
    @GetMapping
    public ResponseEntity<Collection<DecompositionPlan>> listTasks() {
        return ResponseEntity.ok(taskExecutor.getActivePlans());
    }

    // ─── Get ──────────────────────────────────────────────────────────

    /**
     * Returns a specific plan by ID.
     *
     * @param planId the plan identifier
     * @return the plan if found, 404 otherwise
     */
    @GetMapping("/{planId}")
    public ResponseEntity<DecompositionPlan> getTask(@PathVariable String planId) {
        Optional<DecompositionPlan> plan = taskExecutor.getPlan(planId);
        return plan.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Cancel ───────────────────────────────────────────────────────

    /**
     * Cancels an executing plan. Kills all running agents and marks
     * remaining subtasks as CANCELLED.
     *
     * @param planId the plan to cancel
     * @return the cancelled plan if found, 404 otherwise
     */
    @DeleteMapping("/{planId}")
    public ResponseEntity<DecompositionPlan> cancelTask(@PathVariable String planId) {
        Optional<DecompositionPlan> cancelled = taskExecutor.cancel(planId);
        return cancelled.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // ─── Request DTO ──────────────────────────────────────────────────

    /**
     * Request body for task creation.
     *
     * @param prompt      the high-level prompt to decompose
     * @param projectPath absolute path to the target project
     */
    record TaskRequest(String prompt, String projectPath) {}
}
