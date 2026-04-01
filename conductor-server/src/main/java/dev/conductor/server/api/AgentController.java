package dev.conductor.server.api;

import dev.conductor.common.AgentRole;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing Claude agent lifecycles.
 *
 * <p>Provides CRUD-style endpoints for spawning, listing, inspecting,
 * killing, and messaging agents.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRegistry registry;
    private final ClaudeProcessManager processManager;

    public AgentController(AgentRegistry registry, ClaudeProcessManager processManager) {
        this.registry = registry;
        this.processManager = processManager;
    }

    // ─── Spawn ─────────────────────────────────────────────────────────

    /**
     * Spawns a new Claude agent process.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "name": "feature-auth-module",
     *   "role": "FEATURE_ENGINEER",
     *   "projectPath": "C:/Users/matt8/projects/myapp",
     *   "prompt": "Build the authentication module..."
     * }
     * </pre>
     */
    @PostMapping("/spawn")
    public ResponseEntity<?> spawnAgent(@RequestBody SpawnRequest request) {
        try {
            AgentRole role = parseRole(request.role());
            AgentRecord agent = processManager.spawnAgent(
                    request.name(), role, request.projectPath(), request.prompt());
            return ResponseEntity.status(HttpStatus.CREATED).body(agent);
        } catch (IllegalStateException e) {
            log.warn("Spawn rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Failed to spawn agent: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start Claude process: " + e.getMessage()));
        }
    }

    record SpawnRequest(String name, String role, String projectPath, String prompt) {}


    // ─── List ──────────────────────────────────────────────────────────

    /**
     * Returns all registered agents (alive and terminal).
     */
    @GetMapping
    public List<AgentRecord> listAgents() {
        return registry.listAll();
    }


    // ─── Get One ───────────────────────────────────────────────────────

    /**
     * Returns a single agent by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AgentRecord> getAgent(@PathVariable UUID id) {
        return registry.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    // ─── Kill ──────────────────────────────────────────────────────────

    /**
     * Forcibly kills an agent's process and marks it as FAILED.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> killAgent(@PathVariable UUID id) {
        return processManager.killAgent(id)
                .map(agent -> ResponseEntity.ok(agent))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    // ─── Send Message ──────────────────────────────────────────────────

    /**
     * Sends a follow-up message to an agent's stdin.
     *
     * <p>Request body:
     * <pre>
     * { "text": "Now add error handling to that module." }
     * </pre>
     */
    @PostMapping("/{id}/message")
    public ResponseEntity<?> sendMessage(@PathVariable UUID id, @RequestBody MessageRequest request) {
        try {
            processManager.sendMessage(id, request.text());
            return ResponseEntity.ok(Map.of("status", "sent"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Failed to send message to agent {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to write to agent stdin: " + e.getMessage()));
        }
    }

    record MessageRequest(String text) {}


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
