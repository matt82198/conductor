package dev.conductor.server.agent;

import dev.conductor.common.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of all managed Claude agent processes.
 *
 * <p>Uses ConcurrentHashMap for lock-free reads. Writes (register, update, remove)
 * replace the entire AgentRecord atomically since records are immutable.
 *
 * <p>This is the single source of truth for agent state within the server process.
 * Future phases may add persistence (Postgres snapshots) but the registry
 * remains the authoritative in-memory view.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final ConcurrentHashMap<UUID, AgentRecord> agents = new ConcurrentHashMap<>();

    /**
     * Registers a new agent. Throws if an agent with the same ID already exists.
     */
    public AgentRecord register(AgentRecord agent) {
        AgentRecord existing = agents.putIfAbsent(agent.id(), agent);
        if (existing != null) {
            throw new IllegalStateException("Agent already registered: " + agent.id());
        }
        log.info("Registered agent: {} [{}] role={} path={}",
                agent.name(), agent.id(), agent.role(), agent.projectPath());
        return agent;
    }

    /**
     * Removes an agent from the registry. Returns the removed record, or null if not found.
     */
    public AgentRecord remove(UUID id) {
        AgentRecord removed = agents.remove(id);
        if (removed != null) {
            log.info("Removed agent: {} [{}]", removed.name(), id);
        }
        return removed;
    }

    /**
     * Returns the agent record for the given ID, or empty if not found.
     */
    public Optional<AgentRecord> get(UUID id) {
        return Optional.ofNullable(agents.get(id));
    }

    /**
     * Returns an unmodifiable snapshot of all registered agents.
     */
    public List<AgentRecord> listAll() {
        return List.copyOf(agents.values());
    }

    /**
     * Atomically updates an agent's state. Returns the updated record,
     * or empty if the agent was not found.
     */
    public Optional<AgentRecord> updateState(UUID id, AgentState state) {
        AgentRecord updated = agents.computeIfPresent(id, (key, current) -> current.withState(state));
        if (updated != null) {
            log.debug("Agent {} state -> {}", id, state);
        }
        return Optional.ofNullable(updated);
    }

    /**
     * Atomically updates an agent's cumulative cost. Returns the updated record,
     * or empty if the agent was not found.
     */
    public Optional<AgentRecord> updateCost(UUID id, double costUsd) {
        AgentRecord updated = agents.computeIfPresent(id, (key, current) -> current.withCost(costUsd));
        if (updated != null) {
            log.debug("Agent {} cost -> ${}", id, String.format("%.6f", costUsd));
        }
        return Optional.ofNullable(updated);
    }

    /**
     * Atomically sets the session ID on an agent. Returns the updated record,
     * or empty if the agent was not found.
     */
    public Optional<AgentRecord> updateSessionId(UUID id, String sessionId) {
        AgentRecord updated = agents.computeIfPresent(id, (key, current) -> current.withSessionId(sessionId));
        return Optional.ofNullable(updated);
    }

    /**
     * Atomically touches the agent's last activity timestamp.
     */
    public Optional<AgentRecord> touchActivity(UUID id) {
        AgentRecord updated = agents.computeIfPresent(id, (key, current) -> current.withActivity());
        return Optional.ofNullable(updated);
    }

    /**
     * Returns the number of currently registered agents.
     */
    public int size() {
        return agents.size();
    }

    /**
     * Returns the number of agents in a non-terminal state.
     */
    public long countAlive() {
        return agents.values().stream()
                .filter(a -> a.state().isAlive())
                .count();
    }
}
