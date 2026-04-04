package dev.conductor.server.brain.task;

import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.process.ClaudeProcessManager;
import dev.conductor.common.StreamJsonEvent.ResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes {@link DecompositionPlan}s by spawning Claude CLI agents in waves.
 *
 * <p>The executor manages the full lifecycle of plan execution:
 * <ol>
 *   <li>Resolve the subtask DAG into execution waves via {@link DependencyResolver}</li>
 *   <li>Spawn agents for the first wave (tasks with no dependencies)</li>
 *   <li>Listen for agent completion events (via Spring event bus)</li>
 *   <li>When a wave completes, share context and spawn the next wave</li>
 *   <li>Mark the plan as COMPLETED when all subtasks are done, or FAILED if a subtask fails</li>
 * </ol>
 *
 * <p>Active plans are stored in-memory. The executor maps agent UUIDs back to
 * their subtasks so that {@link ClaudeProcessManager.AgentStreamEvent}s can be
 * correlated with plan progress.
 *
 * <p>Thread safety: all mutable state is in ConcurrentHashMaps. Plan updates
 * are synchronized on the plan ID to prevent race conditions when multiple
 * agents from the same wave complete simultaneously.
 */
@Service("conductorTaskExecutor")
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    private final ClaudeProcessManager processManager;
    private final AgentRegistry agentRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final InterAgentBridge interAgentBridge;

    /** Active plans indexed by planId. */
    private final ConcurrentHashMap<String, DecompositionPlan> activePlans = new ConcurrentHashMap<>();

    /** Maps agentId -> (planId, subtaskId) for event correlation. */
    private final ConcurrentHashMap<UUID, PlanSubtaskRef> agentToSubtask = new ConcurrentHashMap<>();

    public TaskExecutor(
            ClaudeProcessManager processManager,
            AgentRegistry agentRegistry,
            ApplicationEventPublisher eventPublisher,
            InterAgentBridge interAgentBridge
    ) {
        this.processManager = processManager;
        this.agentRegistry = agentRegistry;
        this.eventPublisher = eventPublisher;
        this.interAgentBridge = interAgentBridge;
    }

    /**
     * Starts executing a plan. Resolves the dependency DAG and spawns
     * wave 0 agents immediately.
     *
     * @param plan the decomposition plan to execute (must be in CREATED status)
     * @return the plan in EXECUTING status with any wave-0 agents spawned
     * @throws IllegalArgumentException if the plan has a dependency cycle
     */
    public DecompositionPlan execute(DecompositionPlan plan) {
        // Validate the DAG — throws on cycle
        DependencyResolver.resolveWaves(plan.subtasks());

        DecompositionPlan executing = plan.withStatus(DecompositionPlan.STATUS_EXECUTING);
        activePlans.put(executing.planId(), executing);

        log.info("Starting plan execution: planId={}, subtasks={}", executing.planId(), executing.subtasks().size());

        spawnNextWave(executing.planId());

        return activePlans.get(executing.planId());
    }

    /**
     * Listens for agent stream events. When a ResultEvent arrives for an
     * agent that belongs to an active plan, updates the subtask status and
     * checks if the next wave should be spawned.
     *
     * @param event the agent stream event
     */
    @EventListener
    public void onAgentStreamEvent(ClaudeProcessManager.AgentStreamEvent event) {
        // Only care about ResultEvents (agent completion)
        if (!(event.event() instanceof ResultEvent resultEvent)) {
            return;
        }

        PlanSubtaskRef ref = agentToSubtask.get(event.agentId());
        if (ref == null) {
            return; // Agent doesn't belong to any active plan
        }

        DecompositionPlan plan = activePlans.get(ref.planId());
        if (plan == null) {
            return; // Plan was removed or cancelled
        }

        // Determine the subtask's new status based on the result
        SubtaskStatus newStatus = resultEvent.isSuccess() ? SubtaskStatus.COMPLETED : SubtaskStatus.FAILED;
        String resultSummary = resultEvent.resultText() != null ? resultEvent.resultText() : "";

        // Truncate very long results (keep first 2000 chars for context sharing)
        if (resultSummary.length() > 2000) {
            resultSummary = resultSummary.substring(0, 2000) + "... [truncated]";
        }

        log.info("Subtask '{}' in plan {} -> {} (agent={})",
                ref.subtaskId(), ref.planId(), newStatus, event.agentId());

        // Update the plan atomically (synchronized on planId to prevent races)
        synchronized (ref.planId().intern()) {
            DecompositionPlan currentPlan = activePlans.get(ref.planId());
            if (currentPlan == null) return;

            // Find the subtask and update it
            Subtask subtask = findSubtask(currentPlan, ref.subtaskId());
            if (subtask == null) return;

            Subtask updatedSubtask = subtask.withResult(resultSummary, newStatus);
            DecompositionPlan updatedPlan = currentPlan.withSubtask(ref.subtaskId(), updatedSubtask);

            // Share context with dependent running agents (best-effort)
            if (newStatus == SubtaskStatus.COMPLETED) {
                interAgentBridge.shareContext(updatedPlan, updatedSubtask);
            }

            // Determine the current wave for the progress event
            String currentPhase = determineCurrentPhase(updatedPlan);

            // Check if the plan is complete
            if (updatedPlan.isComplete()) {
                String terminalStatus = updatedPlan.failedCount() > 0
                        ? DecompositionPlan.STATUS_FAILED
                        : DecompositionPlan.STATUS_COMPLETED;
                updatedPlan = updatedPlan.withStatus(terminalStatus);
                log.info("Plan {} -> {} (completed={}, failed={})",
                        ref.planId(), terminalStatus, updatedPlan.completedCount(), updatedPlan.failedCount());
            }

            activePlans.put(ref.planId(), updatedPlan);

            // Publish progress event
            eventPublisher.publishEvent(new TaskProgressEvent(
                    ref.planId(),
                    updatedPlan.completedCount(),
                    updatedPlan.subtasks().size(),
                    currentPhase,
                    null
            ));

            // If the plan is still executing, try to spawn the next wave
            if (DecompositionPlan.STATUS_EXECUTING.equals(updatedPlan.status())) {
                if (newStatus == SubtaskStatus.FAILED) {
                    // On failure, cancel the entire plan
                    cancelInternal(ref.planId(), "Subtask '" + subtask.name() + "' failed");
                } else {
                    spawnNextWave(ref.planId());
                }
            }
        }

        // Clean up the agent mapping
        agentToSubtask.remove(event.agentId());
    }

    /**
     * Returns current state of a plan.
     *
     * @param planId the plan identifier
     * @return the plan if found, empty otherwise
     */
    public Optional<DecompositionPlan> getPlan(String planId) {
        return Optional.ofNullable(activePlans.get(planId));
    }

    /**
     * Returns all active plans (including completed/failed ones still in memory).
     *
     * @return an unmodifiable copy of all plans
     */
    public Collection<DecompositionPlan> getActivePlans() {
        return List.copyOf(activePlans.values());
    }

    /**
     * Cancels a plan, killing all running agents and marking remaining
     * subtasks as CANCELLED.
     *
     * @param planId the plan to cancel
     * @return the cancelled plan if found, empty otherwise
     */
    public Optional<DecompositionPlan> cancel(String planId) {
        return cancelInternal(planId, "Cancelled by user");
    }

    // ─── Internal ─────────────────────────────────────────────────────

    /**
     * Spawns agents for the next eligible wave. A subtask is eligible when:
     * - Its status is PENDING
     * - All of its dependsOn subtasks are COMPLETED
     */
    private void spawnNextWave(String planId) {
        DecompositionPlan plan = activePlans.get(planId);
        if (plan == null) return;

        // Resolve waves for the full DAG
        List<List<String>> waves = DependencyResolver.resolveWaves(plan.subtasks());

        // Find the first wave where all tasks are PENDING and all dependencies are satisfied
        Map<String, SubtaskStatus> statusMap = new HashMap<>();
        for (Subtask s : plan.subtasks()) {
            statusMap.put(s.subtaskId(), s.status());
        }

        List<String> toSpawn = new ArrayList<>();

        for (List<String> wave : waves) {
            for (String subtaskId : wave) {
                SubtaskStatus status = statusMap.get(subtaskId);
                if (status != SubtaskStatus.PENDING) continue;

                // Check if all dependencies are completed
                Subtask subtask = findSubtask(plan, subtaskId);
                if (subtask == null) continue;

                boolean allDepsCompleted = subtask.dependsOn().stream()
                        .allMatch(dep -> statusMap.get(dep) == SubtaskStatus.COMPLETED);

                if (allDepsCompleted) {
                    toSpawn.add(subtaskId);
                }
            }
        }

        if (toSpawn.isEmpty()) {
            log.debug("No subtasks ready to spawn for plan {}", planId);
            return;
        }

        log.info("Spawning wave of {} subtasks for plan {}: {}",
                toSpawn.size(), planId, toSpawn);

        for (String subtaskId : toSpawn) {
            spawnSubtaskAgent(planId, subtaskId);
        }
    }

    /**
     * Spawns a single Claude CLI agent for a subtask.
     */
    private void spawnSubtaskAgent(String planId, String subtaskId) {
        DecompositionPlan plan = activePlans.get(planId);
        if (plan == null) return;

        Subtask subtask = findSubtask(plan, subtaskId);
        if (subtask == null) return;

        try {
            // Build agent name from plan + subtask
            String agentName = String.format("task-%s-%s",
                    planId.substring(0, Math.min(8, planId.length())),
                    subtask.name().replaceAll("[^a-zA-Z0-9-]", "-").toLowerCase());

            AgentRecord agent = processManager.spawnAgent(
                    agentName,
                    subtask.role(),
                    subtask.projectPath(),
                    subtask.prompt()
            );

            // Update subtask with agent ID and RUNNING status
            Subtask running = subtask.withAgent(agent.id()).withStatus(SubtaskStatus.RUNNING);
            DecompositionPlan updatedPlan = activePlans.get(planId);
            if (updatedPlan != null) {
                activePlans.put(planId, updatedPlan.withSubtask(subtaskId, running));
            }

            // Register agent->subtask mapping for event correlation
            agentToSubtask.put(agent.id(), new PlanSubtaskRef(planId, subtaskId));

            log.info("Spawned agent {} for subtask '{}' (planId={}, role={})",
                    agent.id(), subtask.name(), planId, subtask.role());

        } catch (IOException e) {
            log.error("Failed to spawn agent for subtask '{}' (planId={}): {}",
                    subtask.name(), planId, e.getMessage());

            // Mark subtask as FAILED
            Subtask failed = subtask.withResult("Failed to spawn agent: " + e.getMessage(), SubtaskStatus.FAILED);
            DecompositionPlan updatedPlan = activePlans.get(planId);
            if (updatedPlan != null) {
                activePlans.put(planId, updatedPlan.withSubtask(subtaskId, failed));
            }

            // Cancel the entire plan on spawn failure
            cancelInternal(planId, "Failed to spawn agent for subtask '" + subtask.name() + "'");
        } catch (IllegalStateException e) {
            // Max concurrent agents reached — log and retry later
            log.warn("Cannot spawn agent for subtask '{}' (planId={}): {}",
                    subtask.name(), planId, e.getMessage());
        }
    }

    /**
     * Internal cancellation: kills running agents, marks remaining subtasks as CANCELLED.
     */
    private Optional<DecompositionPlan> cancelInternal(String planId, String reason) {
        DecompositionPlan plan = activePlans.get(planId);
        if (plan == null) {
            return Optional.empty();
        }

        log.info("Cancelling plan {} — reason: {}", planId, reason);

        // Kill all running agents and mark non-terminal subtasks as CANCELLED
        List<Subtask> updatedSubtasks = new ArrayList<>();
        for (Subtask subtask : plan.subtasks()) {
            if (!subtask.status().isTerminal()) {
                // Kill the agent if it's running
                if (subtask.agentId() != null && processManager.hasActiveProcess(subtask.agentId())) {
                    processManager.killAgent(subtask.agentId());
                    agentToSubtask.remove(subtask.agentId());
                }
                updatedSubtasks.add(subtask.withStatus(SubtaskStatus.CANCELLED));
            } else {
                updatedSubtasks.add(subtask);
            }
        }

        DecompositionPlan cancelled = new DecompositionPlan(
                plan.planId(), plan.originalPrompt(), plan.projectPath(),
                List.copyOf(updatedSubtasks), plan.createdAt(), DecompositionPlan.STATUS_CANCELLED
        );

        activePlans.put(planId, cancelled);

        // Publish final progress event
        eventPublisher.publishEvent(new TaskProgressEvent(
                planId,
                cancelled.completedCount(),
                cancelled.subtasks().size(),
                "cancelled",
                null
        ));

        return Optional.of(cancelled);
    }

    /**
     * Determines the human-readable phase label based on which waves are executing.
     */
    private String determineCurrentPhase(DecompositionPlan plan) {
        try {
            List<List<String>> waves = DependencyResolver.resolveWaves(plan.subtasks());
            Map<String, SubtaskStatus> statusMap = new HashMap<>();
            for (Subtask s : plan.subtasks()) {
                statusMap.put(s.subtaskId(), s.status());
            }

            for (int i = 0; i < waves.size(); i++) {
                boolean hasRunning = waves.get(i).stream()
                        .anyMatch(id -> statusMap.get(id) == SubtaskStatus.RUNNING);
                boolean hasPending = waves.get(i).stream()
                        .anyMatch(id -> statusMap.get(id) == SubtaskStatus.PENDING);
                if (hasRunning || hasPending) {
                    return "wave-" + (i + 1);
                }
            }
            return "complete";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Finds a subtask by ID within a plan.
     */
    private Subtask findSubtask(DecompositionPlan plan, String subtaskId) {
        return plan.subtasks().stream()
                .filter(s -> s.subtaskId().equals(subtaskId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Internal reference pairing a plan ID with a subtask ID.
     */
    private record PlanSubtaskRef(String planId, String subtaskId) {}
}
