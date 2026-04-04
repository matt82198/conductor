package dev.conductor.server.brain.context;

/**
 * Parsed plan file from {@code ~/.claude/plans/*.md}.
 *
 * @param name     plan name derived from filename
 * @param content  full file content
 * @param filePath absolute path to the source file
 */
public record PlanEntry(
        String name,
        String content,
        String filePath
) {

    public PlanEntry {
        if (name == null) name = "unknown";
        if (content == null) content = "";
        if (filePath == null) filePath = "";
    }
}
