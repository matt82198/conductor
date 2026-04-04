package dev.conductor.server.agent;

import dev.conductor.common.AgentRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable template for reusable agent configurations. Users save common
 * agent setups (role, prompt, tags) and reuse them with one click.
 *
 * <p>The {@code defaultPrompt} may contain {@code {placeholders}} that are
 * substituted at spawn time (e.g., {@code {module}}, {@code {description}}).
 *
 * @param templateId   unique identifier (auto-generated if null)
 * @param name         human-readable template name, e.g. "Java Test Writer"
 * @param description  what this template does
 * @param role         the agent role to assign when spawning from this template
 * @param defaultPrompt pre-filled prompt, may include {placeholder} tokens
 * @param tags         search/filter tags, e.g. "java", "testing", "react"
 * @param usageCount   how many times this template has been used to spawn agents
 * @param createdAt    when the template was first created
 * @param lastUsedAt   when the template was last used to spawn an agent
 */
public record AgentTemplate(
        String templateId,
        String name,
        String description,
        AgentRole role,
        String defaultPrompt,
        List<String> tags,
        int usageCount,
        Instant createdAt,
        Instant lastUsedAt
) {

    public AgentTemplate {
        if (templateId == null) templateId = UUID.randomUUID().toString();
        if (tags == null) tags = List.of();
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * Returns a copy with the usage count incremented by one and lastUsedAt set to now.
     */
    public AgentTemplate withIncrementedUsage() {
        return new AgentTemplate(templateId, name, description, role, defaultPrompt, tags,
                usageCount + 1, createdAt, Instant.now());
    }

    /**
     * Returns a copy with the given fields updated (for PUT/edit operations).
     */
    public AgentTemplate withUpdatedFields(String name, String description, AgentRole role,
                                           String defaultPrompt, List<String> tags) {
        return new AgentTemplate(templateId, name, description, role, defaultPrompt, tags,
                usageCount, createdAt, lastUsedAt);
    }
}
