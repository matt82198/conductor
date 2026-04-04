package dev.conductor.server.agent;

import dev.conductor.common.AgentState;
import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import dev.conductor.server.process.ClaudeProcessManager.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures and stores full agent output per agent.
 *
 * <p>Listens for all {@link AgentStreamEvent}s published by
 * {@link dev.conductor.server.process.ClaudeProcessManager} and stores the full
 * content (not truncated) as {@link OutputEntry} records, indexed by agent ID.
 *
 * <p>Output is capped at {@value #MAX_ENTRIES_PER_AGENT} entries per agent to
 * prevent unbounded memory growth. When the cap is exceeded, the oldest entries
 * are discarded.
 *
 * <p>Thread safety: the per-agent list is synchronized on access. The
 * ConcurrentHashMap provides safe concurrent reads across agents.
 */
@Service
public class AgentOutputStore {

    private static final Logger log = LoggerFactory.getLogger(AgentOutputStore.class);

    /** Maximum number of output entries stored per agent. */
    static final int MAX_ENTRIES_PER_AGENT = 500;

    /** How long to keep output for terminal agents before cleanup (30 minutes). */
    private static final long TERMINAL_RETENTION_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<UUID, List<OutputEntry>> outputs = new ConcurrentHashMap<>();

    /** Tracks when agents entered a terminal state, for delayed cleanup. */
    private final ConcurrentHashMap<UUID, Instant> terminalAgentTimestamps = new ConcurrentHashMap<>();

    private final AgentRegistry agentRegistry;

    public AgentOutputStore(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * Periodically cleans up output for agents that have been in a terminal
     * state (COMPLETED/FAILED) for more than 30 minutes. Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000L)
    void cleanupTerminalAgentOutput() {
        // First, detect newly terminal agents
        for (UUID agentId : outputs.keySet()) {
            if (terminalAgentTimestamps.containsKey(agentId)) continue;

            agentRegistry.get(agentId).ifPresent(agent -> {
                AgentState state = agent.state();
                if (state == AgentState.COMPLETED || state == AgentState.FAILED) {
                    terminalAgentTimestamps.put(agentId, Instant.now());
                }
            });
        }

        // Then, clean up agents past retention
        long now = System.currentTimeMillis();
        List<UUID> toRemove = new ArrayList<>();

        terminalAgentTimestamps.forEach((agentId, terminatedAt) -> {
            if (now - terminatedAt.toEpochMilli() > TERMINAL_RETENTION_MS) {
                toRemove.add(agentId);
            }
        });

        for (UUID agentId : toRemove) {
            outputs.remove(agentId);
            terminalAgentTimestamps.remove(agentId);
            log.debug("Cleaned up output for terminal agent {}", agentId);
        }

        if (!toRemove.isEmpty()) {
            log.info("Cleaned up output for {} terminal agent(s)", toRemove.size());
        }
    }

    /**
     * Immutable snapshot of a single output entry from an agent.
     *
     * @param timestamp when this output was captured
     * @param type      output type: "thinking", "text", "tool_use", "tool_result", "error", "system", "rate_limit", "result"
     * @param content   the actual content (not truncated)
     * @param toolName  for tool_use events, the name of the tool (nullable)
     * @param isError   true if this entry represents an error condition
     */
    public record OutputEntry(
            Instant timestamp,
            String type,
            String content,
            String toolName,
            boolean isError
    ) {
        public OutputEntry {
            if (timestamp == null) timestamp = Instant.now();
            if (type == null) type = "unknown";
            if (content == null) content = "";
        }
    }

    /**
     * Listens for ALL agent stream events and stores the full content.
     *
     * <p>Parses the {@link StreamJsonEvent} sealed interface to extract the
     * appropriate content and type for each event kind.
     *
     * @param event the agent stream event from the process manager
     */
    @EventListener
    public void onAgentStreamEvent(AgentStreamEvent event) {
        UUID agentId = event.agentId();
        StreamJsonEvent streamEvent = event.event();

        OutputEntry entry = parseToEntry(streamEvent);
        if (entry == null) {
            return;
        }

        List<OutputEntry> agentOutputs = outputs.computeIfAbsent(agentId,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (agentOutputs) {
            agentOutputs.add(entry);
            // Evict oldest entries if over the cap
            while (agentOutputs.size() > MAX_ENTRIES_PER_AGENT) {
                agentOutputs.remove(0);
            }
        }

        log.trace("Stored output entry for agent {}: type={}", agentId, entry.type());
    }

    /**
     * Get all output for an agent.
     *
     * @param agentId the agent UUID
     * @return all stored output entries, or empty list if no output exists
     */
    public List<OutputEntry> getOutput(UUID agentId) {
        List<OutputEntry> agentOutputs = outputs.get(agentId);
        if (agentOutputs == null) {
            return List.of();
        }
        synchronized (agentOutputs) {
            return List.copyOf(agentOutputs);
        }
    }

    /**
     * Get output for an agent, paginated.
     *
     * @param agentId the agent UUID
     * @param offset  the starting index (0-based)
     * @param limit   maximum number of entries to return
     * @return a sublist of output entries, or empty list if out of range
     */
    public List<OutputEntry> getOutput(UUID agentId, int offset, int limit) {
        List<OutputEntry> agentOutputs = outputs.get(agentId);
        if (agentOutputs == null) {
            return List.of();
        }
        synchronized (agentOutputs) {
            if (offset < 0) offset = 0;
            if (limit < 0) limit = 0;
            if (offset >= agentOutputs.size()) {
                return List.of();
            }
            int end = Math.min(offset + limit, agentOutputs.size());
            return List.copyOf(agentOutputs.subList(offset, end));
        }
    }

    /**
     * Returns the total number of output entries stored for an agent.
     *
     * @param agentId the agent UUID
     * @return entry count, or 0 if no output exists
     */
    public int size(UUID agentId) {
        List<OutputEntry> agentOutputs = outputs.get(agentId);
        if (agentOutputs == null) {
            return 0;
        }
        synchronized (agentOutputs) {
            return agentOutputs.size();
        }
    }

    /**
     * Clear output for a terminated agent (to free memory).
     *
     * @param agentId the agent UUID
     */
    public void clear(UUID agentId) {
        outputs.remove(agentId);
        log.debug("Cleared output store for agent {}", agentId);
    }

    // ─── Internal ─────────────────────────────────────────────────────

    /**
     * Parses a StreamJsonEvent into an OutputEntry by extracting the relevant
     * content from each sealed interface variant.
     */
    private OutputEntry parseToEntry(StreamJsonEvent streamEvent) {
        return switch (streamEvent) {
            case SystemInitEvent init -> new OutputEntry(
                    Instant.now(),
                    "system",
                    String.format("Session started: model=%s, tools=%d, version=%s",
                            init.model(),
                            init.tools() != null ? init.tools().size() : 0,
                            init.claudeCodeVersion()),
                    null,
                    false
            );

            case AssistantEvent assistant -> {
                if (assistant.isThinking()) {
                    yield new OutputEntry(
                            Instant.now(),
                            "thinking",
                            assistant.textContent() != null ? assistant.textContent() : "",
                            null,
                            false
                    );
                } else if (assistant.isToolUse()) {
                    String content = String.format("Tool: %s\nInput: %s",
                            assistant.toolName(),
                            assistant.toolInput() != null ? assistant.toolInput().toString() : "{}");
                    yield new OutputEntry(
                            Instant.now(),
                            "tool_use",
                            content,
                            assistant.toolName(),
                            false
                    );
                } else {
                    // text content
                    yield new OutputEntry(
                            Instant.now(),
                            "text",
                            assistant.textContent() != null ? assistant.textContent() : "",
                            null,
                            false
                    );
                }
            }

            case UserEvent user -> new OutputEntry(
                    Instant.now(),
                    "tool_result",
                    user.content() != null ? user.content() : "",
                    null,
                    user.isError()
            );

            case ResultEvent result -> new OutputEntry(
                    Instant.now(),
                    "result",
                    String.format("Result: %s (cost=$%.6f, turns=%d, duration=%dms)",
                            result.resultText() != null ? result.resultText() : "",
                            result.totalCostUsd(),
                            result.numTurns(),
                            result.durationMs()),
                    null,
                    result.isError()
            );

            case RateLimitEvent rateLimit -> new OutputEntry(
                    Instant.now(),
                    "rate_limit",
                    String.format("Rate limit: status=%s, type=%s, overage=%s",
                            rateLimit.status(), rateLimit.rateLimitType(), rateLimit.isUsingOverage()),
                    null,
                    !rateLimit.isAllowed()
            );

            case ParseErrorEvent error -> new OutputEntry(
                    Instant.now(),
                    "error",
                    error.errorMessage() != null ? error.errorMessage() : "Parse error",
                    null,
                    true
            );
        };
    }
}
