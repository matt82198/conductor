package dev.conductor.server.api;

import dev.conductor.server.brain.BrainProperties;
import dev.conductor.server.brain.behavior.BehaviorLog;
import dev.conductor.server.brain.behavior.BehaviorModelBuilder;
import dev.conductor.server.brain.context.ContextIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for Brain module introspection and management.
 *
 * <p>Provides read-only status endpoints for the Brain's configuration,
 * behavior model, and context index, plus a refresh trigger for
 * re-scanning project context.
 */
@RestController
@RequestMapping("/api/brain")
public class BrainController {

    private static final Logger log = LoggerFactory.getLogger(BrainController.class);

    private final BrainProperties brainProperties;
    private final BehaviorModelBuilder behaviorModelBuilder;
    private final BehaviorLog behaviorLog;
    private final ContextIngestionService contextIngestionService;

    public BrainController(BrainProperties brainProperties,
                           BehaviorModelBuilder behaviorModelBuilder,
                           BehaviorLog behaviorLog,
                           ContextIngestionService contextIngestionService) {
        this.brainProperties = brainProperties;
        this.behaviorModelBuilder = behaviorModelBuilder;
        this.behaviorLog = behaviorLog;
        this.contextIngestionService = contextIngestionService;
    }

    // ─── Status ───────────────────────────────────────────────────────

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

    // ─── Behavior Model ───────────────────────────────────────────────

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

    // ─── Context Index ────────────────────────────────────────────────

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

    // ─── Context Refresh ──────────────────────────────────────────────

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
}
