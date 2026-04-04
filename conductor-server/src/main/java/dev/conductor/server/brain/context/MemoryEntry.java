package dev.conductor.server.brain.context;

/**
 * Parsed memory file from ~/.claude/projects/{project}/memory/{name}.md
 * or ~/.claude/agent-memory/{agent}/{name}.md.
 *
 * @param name         memory name from frontmatter
 * @param description  one-line description from frontmatter
 * @param type         memory type: user, feedback, project, reference
 * @param content      full body content after frontmatter
 * @param projectScope project directory name or "global" / agent name
 * @param filePath     absolute path to the source file
 */
public record MemoryEntry(
        String name,
        String description,
        String type,
        String content,
        String projectScope,
        String filePath
) {

    public MemoryEntry {
        if (name == null) name = "unknown";
        if (description == null) description = "";
        if (type == null) type = "unknown";
        if (content == null) content = "";
        if (projectScope == null) projectScope = "global";
        if (filePath == null) filePath = "";
    }
}
