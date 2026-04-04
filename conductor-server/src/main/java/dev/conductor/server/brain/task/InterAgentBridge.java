package dev.conductor.server.brain.task;

import dev.conductor.server.process.ClaudeProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shares context between agents during task decomposition execution.
 *
 * <p>When a subtask completes, the bridge checks if any currently-running
 * subtasks have declared {@code contextFrom} containing the completed task's
 * ID. If so, it sends the completed task's result to those agents via stdin.
 *
 * <p>Context sharing is best-effort — failures are logged but do not halt
 * plan execution. This is intentional: an agent can succeed without the
 * context hint, even if the hint would have been helpful.
 */
@Service
public class InterAgentBridge {

    private static final Logger log = LoggerFactory.getLogger(InterAgentBridge.class);

    private final ClaudeProcessManager processManager;

    public InterAgentBridge(ClaudeProcessManager processManager) {
        this.processManager = processManager;
    }

    /**
     * Shares the result of a completed subtask with any running agents that
     * declared it in their {@code contextFrom} list.
     *
     * <p>For each running subtask that lists the completed task in its
     * contextFrom, sends a context message to the agent's stdin. The message
     * includes the completed task's name, role, and result summary.
     *
     * @param plan          the current decomposition plan
     * @param completedTask the subtask that just completed
     */
    public void shareContext(DecompositionPlan plan, Subtask completedTask) {
        if (plan == null || completedTask == null) {
            return;
        }

        for (Subtask other : plan.subtasks()) {
            if (other.contextFrom().contains(completedTask.subtaskId())
                    && other.status() == SubtaskStatus.RUNNING
                    && other.agentId() != null) {
                try {
                    String contextMsg = String.format(
                            "[Context from %s] %s completed: %s",
                            completedTask.name(),
                            completedTask.role(),
                            completedTask.result() != null
                                    ? completedTask.result()
                                    : "Task completed successfully."
                    );
                    processManager.sendMessage(other.agentId(), contextMsg);
                    log.debug("Shared context from subtask '{}' to agent {} (subtask '{}')",
                            completedTask.name(), other.agentId(), other.name());
                } catch (Exception e) {
                    // Context sharing is best-effort — log but don't fail
                    log.warn("Failed to share context from '{}' to agent {} (subtask '{}'): {}",
                            completedTask.name(), other.agentId(), other.name(), e.getMessage());
                }
            }
        }
    }
}
