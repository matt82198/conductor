package dev.conductor.server.brain.context;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated context for a single registered project, including all its CLAUDE.md files.
 *
 * @param projectId      the project's unique identifier from the registry
 * @param projectName    human-readable project name
 * @param projectPath    absolute filesystem path to the project root
 * @param rootClaudeMd   contents of the root-level CLAUDE.md (nullable if missing)
 * @param domainClaudeMds all CLAUDE.md files found within the project
 * @param lastScannedAt  when this project was last scanned
 */
public record ProjectContext(
        String projectId,
        String projectName,
        String projectPath,
        String rootClaudeMd,
        List<DomainClaudeMd> domainClaudeMds,
        Instant lastScannedAt
) {

    public ProjectContext {
        if (domainClaudeMds == null) {
            domainClaudeMds = List.of();
        }
        if (lastScannedAt == null) {
            lastScannedAt = Instant.now();
        }
    }
}
