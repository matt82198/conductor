package dev.conductor.server.brain.context;

/**
 * Parsed command definition from {@code ~/.claude/commands/*.md}.
 *
 * @param name     command name derived from filename
 * @param content  full file content
 * @param filePath absolute path to the source file
 */
public record CommandDefinition(
        String name,
        String content,
        String filePath
) {

    public CommandDefinition {
        if (name == null) name = "unknown";
        if (content == null) content = "";
        if (filePath == null) filePath = "";
    }
}
