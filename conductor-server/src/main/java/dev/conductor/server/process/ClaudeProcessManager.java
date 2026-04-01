package dev.conductor.server.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.common.AgentRole;
import dev.conductor.common.AgentState;
import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.config.ConductorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Claude CLI subprocesses. Each agent is a separate {@code claude} process
 * spawned with {@code --verbose --output-format stream-json --input-format stream-json}.
 *
 * <p>Stdout is read line-by-line on a dedicated virtual thread. Each parsed
 * {@link StreamJsonEvent} is published to the Spring application event bus for
 * consumption by controllers, WebSocket handlers, and other services.
 *
 * <p>Stdin is kept open for sending follow-up messages via {@link #sendMessage}.
 *
 * <p>Thread safety: all mutable state is in ConcurrentHashMap. Process I/O is
 * confined to per-agent virtual threads.
 */
@Service
public class ClaudeProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProcessManager.class);

    private final AgentRegistry registry;
    private final StreamJsonParser parser;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ConductorProperties properties;

    /** Active processes indexed by agent ID. */
    private final ConcurrentHashMap<UUID, ManagedProcess> processes = new ConcurrentHashMap<>();

    public ClaudeProcessManager(
            AgentRegistry registry,
            StreamJsonParser parser,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            ConductorProperties properties
    ) {
        this.registry = registry;
        this.parser = parser;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Spawns a new Claude CLI agent process.
     *
     * @param name        human-readable agent name
     * @param role        the agent's role
     * @param projectPath absolute path to the working directory
     * @param prompt      the initial prompt to send to the agent
     * @return the registered AgentRecord in LAUNCHING state
     * @throws IOException if the process cannot be started
     * @throws IllegalStateException if the max concurrent agent limit is reached
     */
    public AgentRecord spawnAgent(String name, AgentRole role, String projectPath, String prompt)
            throws IOException {

        long alive = registry.countAlive();
        if (alive >= properties.agents().maxConcurrent()) {
            throw new IllegalStateException(
                    "Max concurrent agents reached: " + alive + "/" + properties.agents().maxConcurrent());
        }

        // Create and register the agent
        AgentRecord agent = AgentRecord.create(name, role, projectPath);
        registry.register(agent);

        try {
            // Build the Claude CLI command
            // Note: -p (print mode) is incompatible with --input-format stream-json.
            // Use -p for one-shot prompts. For interactive multi-turn, drop -p and
            // send the prompt via stdin JSON instead.
            ProcessBuilder pb = new ProcessBuilder(
                    "claude",
                    "--verbose",
                    "--output-format", "stream-json",
                    "-p", prompt
            );
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(false);  // keep stderr separate for debugging

            Process process = pb.start();

            OutputStream stdin = process.getOutputStream();
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            ManagedProcess managed = new ManagedProcess(agent.id(), process, stdin, stdout);
            processes.put(agent.id(), managed);

            // Start a virtual thread to read stdout
            Thread.startVirtualThread(() -> readLoop(agent.id(), managed));

            // Start a virtual thread to read stderr (just for logging)
            Thread.startVirtualThread(() -> readStderr(agent.id(), process));

            log.info("Spawned agent process: {} [{}] pid={}",
                    name, agent.id(), process.pid());

            return agent;

        } catch (IOException e) {
            // Clean up the registry entry if process spawn failed
            registry.updateState(agent.id(), AgentState.FAILED);
            throw e;
        }
    }

    /**
     * Sends a follow-up message to an agent's stdin as a JSON object.
     *
     * @param agentId the agent to send to
     * @param text    the message text
     * @throws IOException if writing to stdin fails
     * @throws IllegalArgumentException if the agent is not found
     */
    public void sendMessage(UUID agentId, String text) throws IOException {
        ManagedProcess managed = processes.get(agentId);
        if (managed == null) {
            throw new IllegalArgumentException("No active process for agent: " + agentId);
        }
        if (!managed.isAlive()) {
            throw new IllegalStateException("Agent process is no longer alive: " + agentId);
        }

        // Write the message as a JSON object followed by newline
        Map<String, String> message = Map.of("type", "user", "content", text);
        String json = objectMapper.writeValueAsString(message);

        synchronized (managed.stdin()) {
            managed.stdin().write(json.getBytes(StandardCharsets.UTF_8));
            managed.stdin().write('\n');
            managed.stdin().flush();
        }

        registry.touchActivity(agentId);
        log.debug("Sent message to agent {}: {} chars", agentId, text.length());
    }

    /**
     * Forcibly kills an agent's process and marks it as FAILED.
     *
     * @param agentId the agent to kill
     * @return the final AgentRecord, or empty if not found
     */
    public Optional<AgentRecord> killAgent(UUID agentId) {
        ManagedProcess managed = processes.remove(agentId);
        if (managed != null) {
            managed.destroyForcibly();
            log.info("Killed agent process: {}", agentId);
        }
        return registry.updateState(agentId, AgentState.FAILED);
    }

    /**
     * Returns true if the given agent has an active (running) process.
     */
    public boolean hasActiveProcess(UUID agentId) {
        ManagedProcess managed = processes.get(agentId);
        return managed != null && managed.isAlive();
    }


    // ─── Internal: stdout reader loop ──────────────────────────────────

    /**
     * Reads stdout line-by-line, parses each line as a StreamJsonEvent,
     * updates agent state in the registry, and publishes events.
     * Runs on a dedicated virtual thread per agent.
     */
    private void readLoop(UUID agentId, ManagedProcess managed) {
        Thread.currentThread().setName("agent-reader-" + agentId.toString().substring(0, 8));

        try {
            String line;
            while ((line = managed.stdout().readLine()) != null) {
                StreamJsonEvent event = parser.parse(line);
                updateAgentState(agentId, event);
                publishAgentEvent(agentId, event);
            }
        } catch (IOException e) {
            log.warn("Stdout read error for agent {}: {}", agentId, e.getMessage());
        } finally {
            // Process has exited -- determine terminal state
            processes.remove(agentId);
            AgentRecord current = registry.get(agentId).orElse(null);
            if (current != null && current.state().isAlive()) {
                int exitCode = waitForExit(managed);
                AgentState terminalState = (exitCode == 0) ? AgentState.COMPLETED : AgentState.FAILED;
                registry.updateState(agentId, terminalState);
                log.info("Agent {} process exited with code {} -> {}",
                        agentId, exitCode, terminalState);
            }
        }
    }

    /**
     * Reads stderr for debug logging. Claude CLI writes diagnostic info here.
     */
    private void readStderr(UUID agentId, Process process) {
        Thread.currentThread().setName("agent-stderr-" + agentId.toString().substring(0, 8));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[stderr:{}] {}", agentId.toString().substring(0, 8), line);
            }
        } catch (IOException e) {
            log.trace("Stderr read ended for agent {}: {}", agentId, e.getMessage());
        }
    }

    /**
     * Updates agent state in the registry based on the event type and content.
     */
    private void updateAgentState(UUID agentId, StreamJsonEvent event) {
        registry.touchActivity(agentId);

        switch (event) {
            case SystemInitEvent init -> {
                registry.updateState(agentId, AgentState.ACTIVE);
                registry.updateSessionId(agentId, init.sessionId());
            }
            case AssistantEvent assistant -> {
                if (assistant.isThinking()) {
                    registry.updateState(agentId, AgentState.THINKING);
                } else if (assistant.isToolUse()) {
                    registry.updateState(agentId, AgentState.USING_TOOL);
                } else if (assistant.isText()) {
                    registry.updateState(agentId, AgentState.ACTIVE);
                }
            }
            case UserEvent user -> {
                // Tool result received -- agent is back to active
                registry.updateState(agentId, AgentState.ACTIVE);
            }
            case ResultEvent result -> {
                registry.updateCost(agentId, result.totalCostUsd());
                AgentState terminal = result.isSuccess() ? AgentState.COMPLETED : AgentState.FAILED;
                registry.updateState(agentId, terminal);
            }
            case RateLimitEvent rateLimit -> {
                if (!rateLimit.isAllowed()) {
                    registry.updateState(agentId, AgentState.BLOCKED);
                }
            }
            case ParseErrorEvent error -> {
                log.warn("Parse error for agent {}: {}", agentId, error.errorMessage());
            }
        }
    }

    /**
     * Publishes a StreamJsonEvent wrapped with the agent ID to the Spring event bus.
     */
    private void publishAgentEvent(UUID agentId, StreamJsonEvent event) {
        eventPublisher.publishEvent(new AgentStreamEvent(agentId, event));
    }

    /**
     * Waits for the process to exit and returns the exit code.
     */
    private int waitForExit(ManagedProcess managed) {
        try {
            return managed.process().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // ─── Event wrapper ─────────────────────────────────────────────────

    /**
     * Wrapper that pairs an agent ID with a stream-json event,
     * published via Spring's ApplicationEventPublisher.
     */
    public record AgentStreamEvent(UUID agentId, StreamJsonEvent event) {}
}
