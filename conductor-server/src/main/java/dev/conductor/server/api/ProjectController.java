package dev.conductor.server.api;

import dev.conductor.server.project.ProjectRecord;
import dev.conductor.server.project.ProjectRegistry;
import dev.conductor.server.project.ProjectScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * REST controller for multi-project registration and management.
 *
 * <p>Provides CRUD-style endpoints for registering, listing, scanning,
 * and removing projects. Projects are directories containing
 * {@code .git} or {@code CLAUDE.md}.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRegistry registry;
    private final ProjectScanner scanner;

    public ProjectController(ProjectRegistry registry, ProjectScanner scanner) {
        this.registry = registry;
        this.scanner = scanner;
    }

    // ─── List All ─────────────────────────────────────────────────────

    /**
     * Returns all registered projects.
     *
     * @return collection of all {@link ProjectRecord}s
     */
    @GetMapping
    public Collection<ProjectRecord> listProjects() {
        return registry.listAll();
    }

    // ─── Register ─────────────────────────────────────────────────────

    /**
     * Registers a project by its absolute filesystem path.
     *
     * <p>If a project with the same normalized path is already registered,
     * returns the existing record (idempotent).
     *
     * <p>Request body:
     * <pre>
     * { "path": "C:/Users/matt8/projects/myapp" }
     * </pre>
     *
     * @param request body containing the absolute path to register
     * @return 201 with the new or existing {@link ProjectRecord}
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerProject(@RequestBody RegisterRequest request) {
        try {
            ProjectRecord project = registry.register(request.path());
            return ResponseEntity.status(HttpStatus.CREATED).body(project);
        } catch (IllegalArgumentException e) {
            log.warn("Register rejected: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    record RegisterRequest(String path) {}

    // ─── Scan ─────────────────────────────────────────────────────────

    /**
     * Scans a root directory for project subdirectories.
     *
     * <p>Each immediate child of the root that contains {@code .git} or
     * {@code CLAUDE.md} is registered and returned. Already-registered
     * projects are returned as-is (no duplicates created).
     *
     * <p>Request body:
     * <pre>
     * { "rootPath": "C:/Users/matt8/projects" }
     * </pre>
     *
     * @param request body containing the root path to scan
     * @return list of discovered (and now registered) {@link ProjectRecord}s
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@RequestBody ScanRequest request) {
        try {
            List<ProjectRecord> discovered = scanner.scanDirectory(request.rootPath());
            return ResponseEntity.ok(discovered);
        } catch (IllegalArgumentException e) {
            log.warn("Scan rejected: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    record ScanRequest(String rootPath) {}

    // ─── Lookup by Name ────────────────────────────────────────────────

    /**
     * Looks up a project by name (case-insensitive) or ID.
     *
     * @param nameOrId the project name (e.g., "billing") or UUID
     * @return the project record, or 404
     */
    @GetMapping("/{nameOrId}")
    public ResponseEntity<?> getProject(@PathVariable String nameOrId) {
        // Try by ID first, then by name
        return registry.get(nameOrId)
                .or(() -> registry.getByName(nameOrId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── Remove ───────────────────────────────────────────────────────

    /**
     * Removes a project from the registry by its ID.
     *
     * @param id the project's unique identifier
     * @return 200 if removed, 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> removeProject(@PathVariable String id) {
        boolean removed = registry.remove(id);
        if (removed) {
            return ResponseEntity.ok(Map.of("status", "removed"));
        }
        return ResponseEntity.notFound().build();
    }
}
