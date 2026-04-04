package dev.conductor.server.brain.context;

import java.time.Instant;
import java.util.List;

/**
 * Global context from the user's Claude Code configuration and memory.
 *
 * <p>Scanned from {@code ~/.claude/} — includes the global CLAUDE.md (if it exists)
 * and summaries of project-specific memory files.
 *
 * @param globalClaudeMd      contents of {@code ~/.claude/CLAUDE.md}, or null if it doesn't exist
 * @param userMemoryDir       path to {@code ~/.claude/projects/} memory directory
 * @param memoryFileSummaries one-line summary of each memory file found
 * @param scannedAt           when this context was last scanned
 */
public record GlobalContext(
        String globalClaudeMd,
        String userMemoryDir,
        List<String> memoryFileSummaries,
        Instant scannedAt
) {

    public GlobalContext {
        if (memoryFileSummaries == null) {
            memoryFileSummaries = List.of();
        }
        if (scannedAt == null) {
            scannedAt = Instant.now();
        }
    }
}
