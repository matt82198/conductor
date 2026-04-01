package dev.conductor.server.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent mute state. Muted agents' messages are dropped before classification
 * in the {@link QueueManager} pipeline.
 *
 * <p>All state is in-memory (future: database persistence). Thread-safe via
 * {@link ConcurrentHashMap}.
 */
@Service
public class MuteRegistry {

    private static final Logger log = LoggerFactory.getLogger(MuteRegistry.class);

    /**
     * Stores agentId -> muted state. Only muted agents are present in the map.
     * If an agent is not in the map, it is considered unmuted.
     */
    private final ConcurrentHashMap<UUID, Boolean> muteState = new ConcurrentHashMap<>();

    /**
     * Mutes an agent. All messages from this agent will be dropped.
     *
     * @param agentId the agent to mute
     */
    public void mute(UUID agentId) {
        muteState.put(agentId, Boolean.TRUE);
        log.info("Agent muted: {}", agentId);
    }

    /**
     * Unmutes an agent. Messages from this agent will flow through the pipeline.
     *
     * @param agentId the agent to unmute
     */
    public void unmute(UUID agentId) {
        Boolean previous = muteState.remove(agentId);
        if (previous != null) {
            log.info("Agent unmuted: {}", agentId);
        }
    }

    /**
     * Returns true if the given agent is currently muted.
     *
     * @param agentId the agent to check
     * @return true if muted, false otherwise
     */
    public boolean isMuted(UUID agentId) {
        return muteState.getOrDefault(agentId, Boolean.FALSE);
    }

    /**
     * Toggles the mute state for an agent.
     *
     * @param agentId the agent to toggle
     * @return the new mute state (true = muted, false = unmuted)
     */
    public boolean toggle(UUID agentId) {
        Boolean wasMuted = muteState.remove(agentId);
        if (wasMuted == null || !wasMuted) {
            muteState.put(agentId, Boolean.TRUE);
            log.info("Agent muted (toggle): {}", agentId);
            return true;
        }
        log.info("Agent unmuted (toggle): {}", agentId);
        return false;
    }

    /**
     * Returns the set of currently muted agent IDs.
     * The returned set is an unmodifiable snapshot.
     */
    public Set<UUID> getMutedAgents() {
        return Set.copyOf(muteState.keySet());
    }

    /**
     * Returns the number of currently muted agents.
     */
    public int mutedCount() {
        return muteState.size();
    }

    /**
     * Clears all mute state. Visible for testing.
     */
    public void clearAll() {
        muteState.clear();
        log.info("All mute state cleared");
    }
}
