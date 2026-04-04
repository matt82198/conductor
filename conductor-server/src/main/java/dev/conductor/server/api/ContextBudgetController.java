package dev.conductor.server.api;

import dev.conductor.server.brain.context.ContextBudget;
import dev.conductor.server.brain.context.ContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing the context budget analysis endpoint.
 *
 * <p>Mounted at {@code /api/brain/context/budget}, this controller allows
 * inspection of how the {@link ContextManager} would allocate its character
 * budget for a given task prompt and project. Useful for debugging context
 * selection and understanding why certain entries are included or excluded.
 *
 * <p>This is a read-only endpoint that does not modify state.
 */
@RestController
@RequestMapping("/api/brain/context")
public class ContextBudgetController {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetController.class);

    /** Default character budget when none is specified (200K chars). */
    private static final int DEFAULT_MAX_CHARS = 200_000;

    private final ContextManager contextManager;

    public ContextBudgetController(
            @Autowired(required = false) ContextManager contextManager
    ) {
        this.contextManager = contextManager;
    }

    /**
     * Returns the context budget allocation for a given task prompt and project.
     *
     * <p>Shows which context sources would be selected, their relevance scores,
     * character sizes, and how the budget would be divided across categories.
     *
     * <p>Example request:
     * <pre>
     * GET /api/brain/context/budget?prompt=Add%20OAuth2%20to%20the%20API&amp;project=/path/to/project
     * </pre>
     *
     * <p>Example response:
     * <pre>
     * {
     *   "totalChars": 200000,
     *   "targetProjectChars": 120000,
     *   "memoriesChars": 40000,
     *   "agentDefsChars": 20000,
     *   "crossProjectChars": 20000,
     *   "rankedEntries": [
     *     { "source": "target-project", "key": "conductor/CLAUDE.md", "relevance": 1.0, "charSize": 4521 },
     *     { "source": "memory", "key": "auth_patterns [feedback]", "relevance": 0.85, "charSize": 312 },
     *     ...
     *   ]
     * }
     * </pre>
     *
     * @param prompt   the task prompt to analyze
     * @param project  the target project path (optional; defaults to empty)
     * @param maxChars the total character budget (optional; defaults to 200000)
     * @return the computed budget with all scored entries
     */
    @GetMapping("/budget")
    public ResponseEntity<?> getBudget(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "") String project,
            @RequestParam(defaultValue = "200000") int maxChars
    ) {
        if (contextManager == null) {
            return ResponseEntity.status(503).body(
                    Map.of("error", "Context manager not available")
            );
        }

        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Prompt parameter is required")
            );
        }

        if (maxChars < 100) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "maxChars must be at least 100")
            );
        }

        log.info("Context budget requested: prompt='{}' ({}chars), project='{}', maxChars={}",
                prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt,
                prompt.length(), project, maxChars);

        ContextBudget budget = contextManager.buildBudget(prompt, project, maxChars);

        log.info("Budget computed: {} total entries, {} allocated chars",
                budget.rankedEntries().size(), budget.allocatedChars());

        return ResponseEntity.ok(budget);
    }

    /**
     * Returns a rendered context preview showing exactly what would be included
     * in the prompt for a given task.
     *
     * <p>Example request:
     * <pre>
     * GET /api/brain/context/preview?prompt=Add%20OAuth2&amp;project=/path/to/project&amp;maxChars=50000
     * </pre>
     *
     * @param prompt   the task prompt
     * @param project  the target project path
     * @param maxChars the total character budget (optional; defaults to 200000)
     * @return the rendered context string
     */
    @GetMapping("/preview")
    public ResponseEntity<?> getPreview(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "") String project,
            @RequestParam(defaultValue = "200000") int maxChars
    ) {
        if (contextManager == null) {
            return ResponseEntity.status(503).body(
                    Map.of("error", "Context manager not available")
            );
        }

        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Prompt parameter is required")
            );
        }

        String rendered = contextManager.renderForPrompt(prompt, project, maxChars);

        return ResponseEntity.ok(Map.of(
                "prompt", prompt,
                "project", project,
                "maxChars", maxChars,
                "renderedChars", rendered.length(),
                "content", rendered
        ));
    }
}
