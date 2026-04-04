package dev.conductor.server.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of all known projects.
 *
 * <p>Uses ConcurrentHashMap for lock-free reads. Writes (register, remove, increment/decrement)
 * replace the entire ProjectRecord atomically since records are immutable.
 *
 * <p>This is the single source of truth for project state within the server process.
 * Projects are registered by absolute path — Conductor discovers projects, it doesn't create them.
 */
@Service
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final ConcurrentHashMap<String, ProjectRecord> projects = new ConcurrentHashMap<>();

    /**
     * Registers a new project by scanning the given absolute path.
     *
     * <p>If a project with the same normalized path is already registered, returns the
     * existing record without creating a duplicate.
     *
     * @param absolutePath absolute filesystem path to the project directory
     * @return the registered ProjectRecord (new or existing)
     */
    public ProjectRecord register(String absolutePath) {
        // Check if already registered by path (avoid duplicates)
        Optional<ProjectRecord> existing = getByPath(absolutePath);
        if (existing.isPresent()) {
            log.debug("Project already registered at path: {}", absolutePath);
            return existing.get();
        }

        ProjectRecord project = ProjectRecord.create(absolutePath);
        projects.put(project.id(), project);
        log.info("Registered project: {} [{}] path={} remote={}",
                project.name(), project.id(), project.path(), project.gitRemote());
        return project;
    }

    /**
     * Removes a project from the registry.
     *
     * @param id the project's unique identifier
     * @return true if the project was found and removed, false otherwise
     */
    public boolean remove(String id) {
        ProjectRecord removed = projects.remove(id);
        if (removed != null) {
            log.info("Removed project: {} [{}]", removed.name(), id);
            return true;
        }
        return false;
    }

    /**
     * Returns the project record for the given ID, or empty if not found.
     */
    public Optional<ProjectRecord> get(String id) {
        return Optional.ofNullable(projects.get(id));
    }

    /**
     * Returns the project record whose normalized path matches the given path.
     *
     * <p>Path comparison uses {@link java.nio.file.Path#normalize()} to handle
     * trailing slashes, redundant separators, and {@code .} / {@code ..} components.
     */
    public Optional<ProjectRecord> getByPath(String path) {
        String normalized = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        return projects.values().stream()
                .filter(p -> p.path().equals(normalized))
                .findFirst();
    }

    /**
     * Returns an unmodifiable snapshot of all registered projects.
     */
    public Collection<ProjectRecord> listAll() {
        return List.copyOf(projects.values());
    }

    /**
     * Atomically increments the agent count for the given project.
     *
     * @param id the project's unique identifier
     * @return the updated record, or empty if the project was not found
     */
    public Optional<ProjectRecord> incrementAgentCount(String id) {
        ProjectRecord updated = projects.computeIfPresent(id,
                (key, current) -> current.withIncrementedAgentCount());
        if (updated != null) {
            log.debug("Project {} agent count -> {}", id, updated.agentCount());
        }
        return Optional.ofNullable(updated);
    }

    /**
     * Atomically decrements the agent count for the given project (floored at zero).
     *
     * @param id the project's unique identifier
     * @return the updated record, or empty if the project was not found
     */
    public Optional<ProjectRecord> decrementAgentCount(String id) {
        ProjectRecord updated = projects.computeIfPresent(id,
                (key, current) -> current.withDecrementedAgentCount());
        if (updated != null) {
            log.debug("Project {} agent count -> {}", id, updated.agentCount());
        }
        return Optional.ofNullable(updated);
    }

    /**
     * Returns the project record whose name matches (case-insensitive).
     * If multiple projects share a name, returns the first match.
     */
    public Optional<ProjectRecord> getByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String lower = name.toLowerCase();
        return projects.values().stream()
                .filter(p -> p.name().toLowerCase().equals(lower))
                .findFirst();
    }

    /**
     * Returns the number of currently registered projects.
     */
    public int size() {
        return projects.size();
    }
}
