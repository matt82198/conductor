package dev.conductor.server.brain.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link ProjectKnowledge} records as JSON files under
 * {@code ~/.conductor/project-knowledge/}.
 *
 * <p>One file per project, named {@code {projectName}-{first-8-chars-of-id}.json}.
 * Thread-safe: all file operations synchronize on an internal lock.
 */
@Service
public class ProjectKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(ProjectKnowledgeStore.class);

    private final ObjectMapper objectMapper;
    private final Path storePath;
    private final Object lock = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectKnowledgeStore(ObjectMapper objectMapper) {
        this(objectMapper, Path.of(System.getProperty("user.home"), ".conductor", "project-knowledge"));
    }

    /**
     * Constructor allowing a custom store path (used in tests).
     */
    ProjectKnowledgeStore(ObjectMapper objectMapper, Path storePath) {
        this.objectMapper = objectMapper;
        this.storePath = storePath;
        log.info("ProjectKnowledgeStore initialized — path: {}", storePath);
    }

    /**
     * Saves knowledge for a project. Overwrites if the file already exists.
     *
     * @param knowledge the project knowledge to persist
     */
    public void save(ProjectKnowledge knowledge) {
        synchronized (lock) {
            try {
                Files.createDirectories(storePath);
                Path file = storePath.resolve(fileName(knowledge.projectName(), knowledge.projectId()));
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), knowledge);
                log.info("Saved project knowledge: {} -> {}", knowledge.projectName(), file.getFileName());
            } catch (IOException e) {
                log.error("Failed to save project knowledge for {}: {}", knowledge.projectName(), e.getMessage());
            }
        }
    }

    /**
     * Loads knowledge for a project by projectId.
     *
     * @param projectId the project's unique identifier
     * @return the knowledge, or empty if the project has not been analyzed
     */
    public Optional<ProjectKnowledge> load(String projectId) {
        synchronized (lock) {
            if (!Files.isDirectory(storePath)) {
                return Optional.empty();
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storePath, "*.json")) {
                for (Path file : stream) {
                    try {
                        ProjectKnowledge knowledge = objectMapper.readValue(file.toFile(), ProjectKnowledge.class);
                        if (projectId.equals(knowledge.projectId())) {
                            return Optional.of(knowledge);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read knowledge file {}: {}", file.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("Failed to list knowledge directory: {}", e.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * Loads all project knowledge files.
     *
     * @return list of all persisted project knowledge records
     */
    public List<ProjectKnowledge> loadAll() {
        synchronized (lock) {
            List<ProjectKnowledge> results = new ArrayList<>();
            if (!Files.isDirectory(storePath)) {
                return results;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storePath, "*.json")) {
                for (Path file : stream) {
                    try {
                        ProjectKnowledge knowledge = objectMapper.readValue(file.toFile(), ProjectKnowledge.class);
                        results.add(knowledge);
                    } catch (IOException e) {
                        log.warn("Failed to read knowledge file {}: {}", file.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("Failed to list knowledge directory: {}", e.getMessage());
            }
            return results;
        }
    }

    /**
     * Checks whether knowledge has been persisted for the given project.
     *
     * @param projectId the project's unique identifier
     * @return true if a knowledge file exists for this project
     */
    public boolean hasKnowledge(String projectId) {
        return load(projectId).isPresent();
    }

    /**
     * Deletes the knowledge file for the given project.
     *
     * @param projectId the project's unique identifier
     * @return true if a file was found and deleted, false otherwise
     */
    public boolean delete(String projectId) {
        synchronized (lock) {
            if (!Files.isDirectory(storePath)) {
                return false;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storePath, "*.json")) {
                for (Path file : stream) {
                    try {
                        ProjectKnowledge knowledge = objectMapper.readValue(file.toFile(), ProjectKnowledge.class);
                        if (projectId.equals(knowledge.projectId())) {
                            Files.delete(file);
                            log.info("Deleted project knowledge for projectId={}", projectId);
                            return true;
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read/delete knowledge file {}: {}", file.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("Failed to list knowledge directory: {}", e.getMessage());
            }
            return false;
        }
    }

    /**
     * Renders all project knowledge as a human-readable string suitable for
     * inclusion in Brain prompts.
     *
     * <p>Format: one section per project with tech stack, architecture summary,
     * patterns, and key files. Truncated to {@code maxChars}.
     *
     * @param maxChars maximum character budget for the rendered output
     * @return the rendered knowledge string
     */
    public String renderForPrompt(int maxChars) {
        List<ProjectKnowledge> allKnowledge = loadAll();
        if (allKnowledge.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (ProjectKnowledge pk : allKnowledge) {
            if (sb.length() >= maxChars) break;

            sb.append("### ").append(pk.projectName()).append("\n");
            if (pk.techStack() != null && !pk.techStack().isBlank()) {
                sb.append("**Tech Stack:** ").append(pk.techStack()).append("\n");
            }
            if (pk.architectureSummary() != null && !pk.architectureSummary().isBlank()) {
                sb.append("**Architecture:** ").append(pk.architectureSummary()).append("\n");
            }

            if (!pk.patterns().isEmpty()) {
                sb.append("**Patterns:**\n");
                for (ProjectKnowledge.PatternEntry pattern : pk.patterns()) {
                    if (sb.length() >= maxChars) break;
                    sb.append("- **").append(pattern.name()).append("**: ")
                            .append(pattern.description());
                    if (pattern.sourceFile() != null && !pattern.sourceFile().isBlank()) {
                        sb.append(" (ref: ").append(pattern.sourceFile()).append(")");
                    }
                    sb.append("\n");
                }
            }

            if (!pk.keyFiles().isEmpty()) {
                sb.append("**Key Files:** ").append(String.join(", ", pk.keyFiles())).append("\n");
            }

            sb.append("\n");
        }

        if (sb.length() > maxChars) {
            return sb.substring(0, maxChars);
        }
        return sb.toString();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    /**
     * Generates the file name for a project's knowledge file.
     * Format: {projectName}-{first-8-chars-of-id}.json
     */
    private String fileName(String projectName, String projectId) {
        String safeName = (projectName != null ? projectName : "unknown")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        String idPrefix = (projectId != null && projectId.length() >= 8)
                ? projectId.substring(0, 8) : (projectId != null ? projectId : "unknown");
        return safeName + "-" + idPrefix + ".json";
    }
}
