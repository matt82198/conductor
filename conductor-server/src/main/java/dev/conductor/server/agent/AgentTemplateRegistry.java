package dev.conductor.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.common.AgentRole;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory registry of agent templates, backed by a JSON file at
 * {@code ~/.conductor/agent-templates.json} for persistence across restarts.
 *
 * <p>On first run (when the file does not exist), seeds five useful default
 * templates. All mutations (save, delete, recordUsage) write-through to disk.
 *
 * <p>Thread safety: reads are lock-free via ConcurrentHashMap; disk writes
 * are synchronized to prevent interleaving.
 */
@Service
public class AgentTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentTemplateRegistry.class);

    private final ConcurrentHashMap<String, AgentTemplate> templates = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path storagePath;

    /**
     * Production constructor. Stores templates in {@code ~/.conductor/agent-templates.json}.
     */
    public AgentTemplateRegistry() {
        this(Path.of(System.getProperty("user.home"), ".conductor", "agent-templates.json"));
    }

    /**
     * Test-friendly constructor that accepts a custom storage path.
     */
    public AgentTemplateRegistry(Path storagePath) {
        this.storagePath = storagePath;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    void init() {
        loadFromDisk();
    }

    // ─── Public API ───────────────────────────────────────────────────

    /**
     * Saves (creates or updates) a template and persists to disk.
     *
     * @param template the template to save
     * @return the saved template
     */
    public AgentTemplate save(AgentTemplate template) {
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(template.name(), "template name must not be null");
        templates.put(template.templateId(), template);
        persistToDisk();
        log.info("Saved template: {} [{}]", template.name(), template.templateId());
        return template;
    }

    /**
     * Returns the template with the given ID, or empty if not found.
     */
    public Optional<AgentTemplate> get(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    /**
     * Returns all templates, sorted by usage count descending (most-used first).
     */
    public List<AgentTemplate> listAll() {
        return templates.values().stream()
                .sorted(Comparator.comparingInt(AgentTemplate::usageCount).reversed()
                        .thenComparing(AgentTemplate::name))
                .collect(Collectors.toList());
    }

    /**
     * Searches templates by name, description, and tags. Case-insensitive substring match.
     *
     * @param query the search query
     * @return matching templates, sorted by usage count descending
     */
    public List<AgentTemplate> search(String query) {
        if (query == null || query.isBlank()) {
            return listAll();
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        return templates.values().stream()
                .filter(t -> matchesQuery(t, lowerQuery))
                .sorted(Comparator.comparingInt(AgentTemplate::usageCount).reversed()
                        .thenComparing(AgentTemplate::name))
                .collect(Collectors.toList());
    }

    /**
     * Deletes a template by ID and persists to disk.
     *
     * @param templateId the template ID to delete
     * @return true if a template was removed, false if not found
     */
    public boolean delete(String templateId) {
        AgentTemplate removed = templates.remove(templateId);
        if (removed != null) {
            persistToDisk();
            log.info("Deleted template: {} [{}]", removed.name(), templateId);
            return true;
        }
        return false;
    }

    /**
     * Increments the usage count for a template and updates lastUsedAt.
     *
     * @param templateId the template whose usage to record
     * @return the updated template
     * @throws NoSuchElementException if the template is not found
     */
    public AgentTemplate recordUsage(String templateId) {
        AgentTemplate existing = templates.get(templateId);
        if (existing == null) {
            throw new NoSuchElementException("Template not found: " + templateId);
        }
        AgentTemplate updated = existing.withIncrementedUsage();
        templates.put(templateId, updated);
        persistToDisk();
        log.debug("Recorded usage for template {} — count now {}", templateId, updated.usageCount());
        return updated;
    }

    /**
     * Returns the number of templates in the registry.
     */
    public int size() {
        return templates.size();
    }

    // ─── Persistence ──────────────────────────────────────────────────

    /**
     * Loads templates from the JSON file on disk. If the file does not exist,
     * seeds default templates.
     */
    void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            log.info("No template file found at {} — seeding defaults", storagePath);
            seedDefaults();
            return;
        }

        try {
            List<AgentTemplate> loaded = objectMapper.readValue(
                    storagePath.toFile(), new TypeReference<List<AgentTemplate>>() {});
            templates.clear();
            for (AgentTemplate t : loaded) {
                templates.put(t.templateId(), t);
            }
            log.info("Loaded {} templates from {}", templates.size(), storagePath);
        } catch (IOException e) {
            log.error("Failed to load templates from {}: {}", storagePath, e.getMessage());
            if (templates.isEmpty()) {
                seedDefaults();
            }
        }
    }

    /**
     * Persists all templates to the JSON file. Synchronized to prevent concurrent writes.
     */
    synchronized void persistToDisk() {
        try {
            Files.createDirectories(storagePath.getParent());
            objectMapper.writeValue(storagePath.toFile(), listAll());
            log.debug("Persisted {} templates to {}", templates.size(), storagePath);
        } catch (IOException e) {
            log.error("Failed to persist templates to {}: {}", storagePath, e.getMessage());
        }
    }

    // ─── Defaults ─────────────────────────────────────────────────────

    /**
     * Seeds five useful default templates on first run.
     */
    void seedDefaults() {
        save(new AgentTemplate(null, "Test Writer",
                "Write comprehensive tests for the specified module",
                AgentRole.TESTER,
                "Write unit and integration tests for {module}. Follow existing test patterns.",
                List.of("testing", "java", "typescript"), 0, null, null));

        save(new AgentTemplate(null, "Code Reviewer",
                "Review code changes for quality and security",
                AgentRole.REVIEWER,
                "Review the recent changes in this project. Check for bugs, security issues, and code quality.",
                List.of("review", "security", "quality"), 0, null, null));

        save(new AgentTemplate(null, "Feature Builder",
                "Implement a new feature from description",
                AgentRole.FEATURE_ENGINEER,
                "Implement the following feature: {description}",
                List.of("feature", "implementation"), 0, null, null));

        save(new AgentTemplate(null, "Codebase Explorer",
                "Analyze and explain a codebase area",
                AgentRole.EXPLORER,
                "Explore and document the architecture of {area}. Identify key patterns and integration points.",
                List.of("exploration", "documentation", "architecture"), 0, null, null));

        save(new AgentTemplate(null, "Refactoring Agent",
                "Refactor code for readability and performance",
                AgentRole.REFACTORER,
                "Refactor {target} for improved readability, performance, and maintainability.",
                List.of("refactor", "cleanup", "performance"), 0, null, null));

        log.info("Seeded {} default templates", templates.size());
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private boolean matchesQuery(AgentTemplate template, String lowerQuery) {
        if (template.name() != null && template.name().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (template.description() != null && template.description().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (template.tags() != null) {
            for (String tag : template.tags()) {
                if (tag.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    return true;
                }
            }
        }
        if (template.role() != null && template.role().name().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        return false;
    }
}
