package dev.conductor.server.api;

import dev.conductor.common.AgentRole;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentTemplate;
import dev.conductor.server.agent.AgentTemplateRegistry;
import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST controller for managing agent templates. Users save common agent
 * configurations and reuse them with one click.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/templates — list all templates (sorted by usage)</li>
 *   <li>GET /api/templates/search?q=query — search templates</li>
 *   <li>GET /api/templates/{id} — get one template</li>
 *   <li>POST /api/templates — create a new template</li>
 *   <li>PUT /api/templates/{id} — update a template</li>
 *   <li>DELETE /api/templates/{id} — delete a template</li>
 *   <li>POST /api/templates/{id}/use — use a template to spawn an agent</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/templates")
public class AgentTemplateController {

    private static final Logger log = LoggerFactory.getLogger(AgentTemplateController.class);

    private final AgentTemplateRegistry templateRegistry;
    private final ClaudeProcessManager processManager;

    public AgentTemplateController(AgentTemplateRegistry templateRegistry,
                                   ClaudeProcessManager processManager) {
        this.templateRegistry = templateRegistry;
        this.processManager = processManager;
    }

    // ─── List ──────────────────────────────────────────────────────────

    /**
     * Returns all templates, sorted by usage count descending.
     */
    @GetMapping
    public List<AgentTemplate> listTemplates() {
        return templateRegistry.listAll();
    }

    // ─── Search ────────────────────────────────────────────────────────

    /**
     * Searches templates by name, description, and tags.
     */
    @GetMapping("/search")
    public List<AgentTemplate> searchTemplates(@RequestParam("q") String query) {
        return templateRegistry.search(query);
    }

    // ─── Get One ───────────────────────────────────────────────────────

    /**
     * Returns a single template by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AgentTemplate> getTemplate(@PathVariable String id) {
        return templateRegistry.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Create ────────────────────────────────────────────────────────

    /**
     * Creates a new template.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "name": "Java Test Writer",
     *   "description": "Write tests for Java modules",
     *   "role": "TESTER",
     *   "defaultPrompt": "Write tests for {module}",
     *   "tags": ["java", "testing"]
     * }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<?> createTemplate(@RequestBody CreateTemplateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Template name is required"));
        }

        AgentRole role = parseRole(request.role());
        AgentTemplate template = new AgentTemplate(
                null, request.name(), request.description(), role,
                request.defaultPrompt(), request.tags(), 0, null, null);

        AgentTemplate saved = templateRegistry.save(template);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    record CreateTemplateRequest(String name, String description, String role,
                                 String defaultPrompt, List<String> tags) {}

    // ─── Update ────────────────────────────────────────────────────────

    /**
     * Updates an existing template.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable String id,
                                            @RequestBody UpdateTemplateRequest request) {
        return templateRegistry.get(id)
                .map(existing -> {
                    AgentRole role = request.role() != null ? parseRole(request.role()) : existing.role();
                    String name = request.name() != null ? request.name() : existing.name();
                    String description = request.description() != null ? request.description() : existing.description();
                    String defaultPrompt = request.defaultPrompt() != null ? request.defaultPrompt() : existing.defaultPrompt();
                    List<String> tags = request.tags() != null ? request.tags() : existing.tags();

                    AgentTemplate updated = existing.withUpdatedFields(name, description, role, defaultPrompt, tags);
                    templateRegistry.save(updated);
                    return ResponseEntity.ok(updated);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record UpdateTemplateRequest(String name, String description, String role,
                                 String defaultPrompt, List<String> tags) {}

    // ─── Delete ────────────────────────────────────────────────────────

    /**
     * Deletes a template by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable String id) {
        if (templateRegistry.delete(id)) {
            return ResponseEntity.ok(Map.of("deleted", id));
        }
        return ResponseEntity.notFound().build();
    }

    // ─── Use (Spawn from Template) ─────────────────────────────────────

    /**
     * Uses a template to spawn an agent. Records the usage and increments the
     * template's usage count.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "projectPath": "C:/Users/matt8/projects/myapp",
     *   "promptOverride": "Write tests for the auth module"
     * }
     * </pre>
     *
     * <p>If {@code promptOverride} is provided, it replaces all {@code {placeholder}}
     * tokens in the template's defaultPrompt. If no override is given, the raw
     * defaultPrompt is used.
     */
    @PostMapping("/{id}/use")
    public ResponseEntity<?> useTemplate(@PathVariable String id,
                                         @RequestBody UseTemplateRequest request) {
        return templateRegistry.get(id)
                .map(template -> {
                    try {
                        // Record usage
                        AgentTemplate updated = templateRegistry.recordUsage(id);

                        // Determine the prompt — use override if provided, else default
                        String prompt = (request.promptOverride() != null && !request.promptOverride().isBlank())
                                ? request.promptOverride()
                                : template.defaultPrompt();

                        // Generate a name from the template
                        String agentName = template.name().toLowerCase().replaceAll("\\s+", "-")
                                + "-" + System.currentTimeMillis() % 10000;

                        // Spawn the agent
                        AgentRecord agent = processManager.spawnAgent(
                                agentName, template.role(), request.projectPath(), prompt);

                        Map<String, Object> response = new HashMap<>();
                        response.put("agent", agent);
                        response.put("template", updated);
                        return ResponseEntity.status(HttpStatus.CREATED).body(response);

                    } catch (IllegalStateException e) {
                        log.warn("Template use rejected (agent limit): {}", e.getMessage());
                        Map<String, String> error = new HashMap<>();
                        error.put("error", e.getMessage());
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
                    } catch (IOException e) {
                        log.error("Failed to spawn agent from template {}: {}", id, e.getMessage(), e);
                        Map<String, String> error = new HashMap<>();
                        error.put("error", "Failed to start Claude process: " + e.getMessage());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record UseTemplateRequest(String projectPath, String promptOverride) {}

    // ─── Helpers ───────────────────────────────────────────────────────

    private AgentRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return AgentRole.GENERAL;
        }
        try {
            return AgentRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown role '{}', defaulting to GENERAL", role);
            return AgentRole.GENERAL;
        }
    }
}
