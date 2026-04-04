package dev.conductor.server.brain.context;

import java.time.Instant;
import java.util.List;

/**
 * Aggregate of all personal knowledge scanned from {@code ~/.claude/}.
 *
 * @param agents   custom agent definitions
 * @param memories all memory entries across projects and agents
 * @param commands sub-agent command definitions
 * @param plans    active plan files
 * @param scannedAt when this knowledge was last scanned
 */
public record PersonalKnowledge(
        List<AgentDefinition> agents,
        List<MemoryEntry> memories,
        List<CommandDefinition> commands,
        List<PlanEntry> plans,
        Instant scannedAt
) {

    public PersonalKnowledge {
        if (agents == null) agents = List.of();
        if (memories == null) memories = List.of();
        if (commands == null) commands = List.of();
        if (plans == null) plans = List.of();
        if (scannedAt == null) scannedAt = Instant.now();
    }
}
