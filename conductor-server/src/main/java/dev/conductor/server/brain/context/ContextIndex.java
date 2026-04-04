package dev.conductor.server.brain.context;

import java.time.Instant;
import java.util.List;

/**
 * Complete context index assembled by the Brain's context ingestion pipeline.
 *
 * <p>Contains project-level and global context gathered from CLAUDE.md files,
 * user memories, and Claude Code configuration. Used by the decision engine
 * when constructing prompts for the Claude API (Phase 4B) or rendering
 * context for pattern matching.
 *
 * @param projects    context for each registered project
 * @param global      global user context from ~/.claude/
 * @param lastUpdated when this index was last built
 */
public record ContextIndex(
        List<ProjectContext> projects,
        GlobalContext global,
        Instant lastUpdated
) {

    public ContextIndex {
        if (projects == null) {
            projects = List.of();
        }
        if (lastUpdated == null) {
            lastUpdated = Instant.now();
        }
    }

    /**
     * Returns the number of projects in this index.
     */
    public int projectCount() {
        return projects.size();
    }
}
