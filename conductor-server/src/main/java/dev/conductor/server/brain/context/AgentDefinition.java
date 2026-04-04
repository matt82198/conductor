package dev.conductor.server.brain.context;

/**
 * Parsed agent definition from {@code ~/.claude/agents/*.md}.
 *
 * @param name         agent name from frontmatter
 * @param description  usage description from frontmatter
 * @param model        preferred model (e.g., "opus")
 * @param systemPrompt full body content (the agent's system prompt)
 * @param filePath     absolute path to the source file
 */
public record AgentDefinition(
        String name,
        String description,
        String model,
        String systemPrompt,
        String filePath
) {

    public AgentDefinition {
        if (name == null) name = "unknown";
        if (description == null) description = "";
        if (systemPrompt == null) systemPrompt = "";
        if (filePath == null) filePath = "";
    }
}
