package dev.conductor.server.agent;

import dev.conductor.common.AgentRole;
import dev.conductor.common.AgentState;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a managed Claude agent's identity and current state.
 *
 * <p>This record is returned by the registry and REST API. Because agent state
 * changes frequently (state, cost, lastActivityAt), the registry creates new
 * instances on each update rather than mutating fields.
 *
 * @param id              unique identifier assigned at spawn time
 * @param name            human-readable name (e.g., "feature-auth-module")
 * @param role            the agent's assigned role
 * @param projectPath     absolute path to the working directory
 * @param state           current lifecycle state
 * @param sessionId       Claude CLI session ID (set after system init event)
 * @param spawnedAt       timestamp when the agent process was started
 * @param costUsd         cumulative cost in USD reported by the result event
 * @param lastActivityAt  timestamp of the most recent event from this agent
 */
public record AgentRecord(
        UUID id,
        String name,
        AgentRole role,
        String projectPath,
        AgentState state,
        String sessionId,
        Instant spawnedAt,
        double costUsd,
        Instant lastActivityAt
) {

    /**
     * Creates a new AgentRecord in the LAUNCHING state with zero cost.
     */
    public static AgentRecord create(String name, AgentRole role, String projectPath) {
        Instant now = Instant.now();
        return new AgentRecord(
                UUID.randomUUID(),
                name,
                role,
                projectPath,
                AgentState.LAUNCHING,
                null,
                now,
                0.0,
                now
        );
    }

    /**
     * Returns a copy with the given state and updated lastActivityAt.
     */
    public AgentRecord withState(AgentState newState) {
        return new AgentRecord(id, name, role, projectPath, newState, sessionId, spawnedAt, costUsd, Instant.now());
    }

    /**
     * Returns a copy with the given session ID.
     */
    public AgentRecord withSessionId(String newSessionId) {
        return new AgentRecord(id, name, role, projectPath, state, newSessionId, spawnedAt, costUsd, Instant.now());
    }

    /**
     * Returns a copy with the given cost.
     */
    public AgentRecord withCost(double newCostUsd) {
        return new AgentRecord(id, name, role, projectPath, state, sessionId, spawnedAt, newCostUsd, Instant.now());
    }

    /**
     * Returns a copy with updated lastActivityAt timestamp.
     */
    public AgentRecord withActivity() {
        return new AgentRecord(id, name, role, projectPath, state, sessionId, spawnedAt, costUsd, Instant.now());
    }
}
