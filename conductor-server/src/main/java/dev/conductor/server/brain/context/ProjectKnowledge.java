package dev.conductor.server.brain.context;

import java.time.Instant;
import java.util.List;

/**
 * Stores extracted knowledge about a project, including its technology stack,
 * reusable patterns, key files, and an architecture summary.
 *
 * <p>Produced by {@link ProjectKnowledgeExtractor} when a project is analyzed
 * via the Claude API. Persisted by {@link ProjectKnowledgeStore} as JSON files
 * under {@code ~/.conductor/project-knowledge/}.
 *
 * @param projectId           the project's unique identifier from the registry
 * @param projectName         human-readable project name
 * @param projectPath         absolute filesystem path to the project root
 * @param techStack           detected technology stack (e.g., "Java 21, Spring Boot 3.4, Maven")
 * @param patterns            reusable patterns found during analysis
 * @param keyFiles            important files identified during analysis (relative paths)
 * @param architectureSummary 2-3 sentence summary of the project's architecture
 * @param analyzedAt          when this project was last analyzed
 */
public record ProjectKnowledge(
        String projectId,
        String projectName,
        String projectPath,
        String techStack,
        List<PatternEntry> patterns,
        List<String> keyFiles,
        String architectureSummary,
        Instant analyzedAt
) {

    public ProjectKnowledge {
        if (patterns == null) patterns = List.of();
        if (keyFiles == null) keyFiles = List.of();
        if (analyzedAt == null) analyzedAt = Instant.now();
    }

    /**
     * A single reusable pattern discovered in a project.
     *
     * @param name        pattern name (e.g., "Claude API integration", "Event-driven architecture")
     * @param description how the pattern works and when to use it
     * @param sourceFile  relative path to the reference implementation
     * @param tags        searchable tags (e.g., "api", "claude", "rest-client")
     */
    public record PatternEntry(
            String name,
            String description,
            String sourceFile,
            List<String> tags
    ) {
        public PatternEntry {
            if (tags == null) tags = List.of();
        }
    }
}
