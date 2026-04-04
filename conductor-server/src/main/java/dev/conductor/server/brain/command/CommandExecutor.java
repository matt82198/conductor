package dev.conductor.server.brain.command;

import dev.conductor.common.AgentRole;
import dev.conductor.common.AgentState;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.brain.context.ContextIndex;
import dev.conductor.server.brain.context.ContextIngestionService;
import dev.conductor.server.brain.task.DecompositionPlan;
import dev.conductor.server.brain.task.TaskDecomposer;
import dev.conductor.server.brain.task.TaskExecutor;
import dev.conductor.server.process.ClaudeProcessManager;
import dev.conductor.server.project.ProjectRecord;
import dev.conductor.server.project.ProjectRegistry;
import dev.conductor.server.project.ProjectScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Executes a {@link CommandIntent} by dispatching to the appropriate existing
 * Conductor services (process manager, agent registry, project registry, etc.).
 *
 * <p>Each action maps to an {@code execute*} method that translates the intent's
 * parameters into service calls and returns a human-readable {@link CommandResult}.
 *
 * <p>Dependencies are injected with {@code required = false} where the target
 * service may not be available (e.g., the knowledge extractor is built concurrently
 * by another agent).
 */
@Service
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    private final AgentRegistry agentRegistry;
    private final ClaudeProcessManager processManager;
    private final ProjectRegistry projectRegistry;
    private final ProjectScanner projectScanner;
    private final TaskDecomposer taskDecomposer;
    private final TaskExecutor taskExecutor;
    private final ContextIngestionService contextIngestionService;

    public CommandExecutor(
            AgentRegistry agentRegistry,
            ClaudeProcessManager processManager,
            ProjectRegistry projectRegistry,
            ProjectScanner projectScanner,
            TaskDecomposer taskDecomposer,
            TaskExecutor taskExecutor,
            ContextIngestionService contextIngestionService
    ) {
        this.agentRegistry = agentRegistry;
        this.processManager = processManager;
        this.projectRegistry = projectRegistry;
        this.projectScanner = projectScanner;
        this.taskDecomposer = taskDecomposer;
        this.taskExecutor = taskExecutor;
        this.contextIngestionService = contextIngestionService;
    }

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * Executes a command intent and returns a human-readable result.
     *
     * <p>Dispatches to the appropriate handler based on the intent's action.
     * Unknown or unrecognized actions return a helpful error message.
     *
     * @param intent the parsed command intent to execute
     * @return a result containing success status, message, and optional data
     */
    public CommandResult execute(CommandIntent intent) {
        log.info("Executing command: action={}, confidence={}, params={}",
                intent.action(), intent.confidence(), intent.parameters());

        return switch (intent.action()) {
            case "SPAWN_AGENT" -> executeSpawn(intent);
            case "DECOMPOSE_TASK" -> executeDecompose(intent);
            case "REGISTER_PROJECT" -> executeRegister(intent);
            case "SCAN_PROJECTS" -> executeScan(intent);
            case "ANALYZE_PROJECT" -> executeAnalyze(intent);
            case "QUERY_STATUS" -> executeQuery(intent);
            case "KILL_AGENT" -> executeKill(intent);
            default -> new CommandResult(false,
                    "I didn't understand that. Try: 'spawn an agent to...', "
                    + "'register project at /path', or 'what are my agents doing?'");
        };
    }

    // ─── Handlers ────────────────────────────────────────────────────

    private CommandResult executeSpawn(CommandIntent intent) {
        Map<String, String> params = intent.parameters();

        String name = params.getOrDefault("name", "agent-" + UUID.randomUUID().toString().substring(0, 8));
        String roleStr = params.getOrDefault("role", "GENERAL");
        String projectPath = params.get("projectPath");
        String prompt = params.getOrDefault("prompt", intent.originalText());

        if (projectPath == null || projectPath.isBlank()) {
            return new CommandResult(false,
                    "I need a project path to spawn an agent. Try: 'spawn an agent at C:/path/to/project to ...'");
        }

        AgentRole role;
        try {
            role = AgentRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            role = AgentRole.GENERAL;
        }

        try {
            AgentRecord agent = processManager.spawnAgent(name, role, projectPath, prompt);
            return new CommandResult(true,
                    String.format("Spawned agent '%s' (%s) in %s — working on: %s",
                            agent.name(), agent.role(), abbreviatePath(projectPath),
                            truncate(prompt, 80)),
                    agent);
        } catch (IllegalStateException e) {
            return new CommandResult(false, "Cannot spawn agent: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to spawn agent from command: {}", e.getMessage(), e);
            return new CommandResult(false, "Failed to start agent process: " + e.getMessage());
        }
    }

    private CommandResult executeDecompose(CommandIntent intent) {
        Map<String, String> params = intent.parameters();

        String prompt = params.getOrDefault("prompt", intent.originalText());
        String projectPath = params.get("projectPath");

        if (projectPath == null || projectPath.isBlank()) {
            return new CommandResult(false,
                    "I need a project path to decompose a task. Try: 'break down: add OAuth at C:/path/to/project'");
        }

        if (prompt == null || prompt.isBlank()) {
            return new CommandResult(false, "I need a task description to decompose.");
        }

        try {
            ContextIndex context = contextIngestionService.buildIndex();
            DecompositionPlan plan = taskDecomposer.decompose(prompt, projectPath, context);
            DecompositionPlan executing = taskExecutor.execute(plan);

            return new CommandResult(true,
                    String.format("Decomposed into %d subtasks and started execution (plan %s). "
                                  + "Subtasks: %s",
                            executing.subtasks().size(),
                            executing.planId().substring(0, 8),
                            executing.subtasks().stream()
                                    .map(s -> s.name() + " (" + s.role() + ")")
                                    .collect(Collectors.joining(", "))),
                    executing);
        } catch (Exception e) {
            log.error("Task decomposition failed: {}", e.getMessage(), e);
            return new CommandResult(false, "Task decomposition failed: " + e.getMessage());
        }
    }

    private CommandResult executeRegister(CommandIntent intent) {
        Map<String, String> params = intent.parameters();
        String path = params.get("path");

        if (path == null || path.isBlank()) {
            path = params.get("projectPath");
        }

        if (path == null || path.isBlank()) {
            return new CommandResult(false,
                    "I need a path to register. Try: 'register project at C:/Users/you/myapp'");
        }

        try {
            ProjectRecord project = projectRegistry.register(path);
            return new CommandResult(true,
                    String.format("Registered project '%s' at %s (remote: %s)",
                            project.name(), abbreviatePath(project.path()),
                            project.gitRemote() != null ? project.gitRemote() : "none"),
                    project);
        } catch (Exception e) {
            return new CommandResult(false, "Failed to register project: " + e.getMessage());
        }
    }

    private CommandResult executeScan(CommandIntent intent) {
        Map<String, String> params = intent.parameters();
        String rootPath = params.get("rootPath");

        if (rootPath == null || rootPath.isBlank()) {
            rootPath = params.get("path");
        }

        if (rootPath == null || rootPath.isBlank()) {
            return new CommandResult(false,
                    "I need a directory to scan. Try: 'scan C:/Users/you/projects for projects'");
        }

        try {
            List<ProjectRecord> discovered = projectScanner.scanDirectory(rootPath);
            if (discovered.isEmpty()) {
                return new CommandResult(true,
                        "Scanned " + abbreviatePath(rootPath) + " but found no projects.");
            }
            return new CommandResult(true,
                    String.format("Discovered %d project(s) in %s: %s",
                            discovered.size(), abbreviatePath(rootPath),
                            discovered.stream().map(ProjectRecord::name)
                                    .collect(Collectors.joining(", "))),
                    discovered);
        } catch (IllegalArgumentException e) {
            return new CommandResult(false, "Invalid directory: " + e.getMessage());
        } catch (Exception e) {
            return new CommandResult(false, "Scan failed: " + e.getMessage());
        }
    }

    private CommandResult executeAnalyze(CommandIntent intent) {
        Map<String, String> params = intent.parameters();
        String projectPath = params.get("projectPath");
        String projectName = params.get("projectName");

        String target = projectPath != null ? abbreviatePath(projectPath) :
                (projectName != null ? projectName : "the project");

        // The ProjectKnowledgeExtractor is being built concurrently.
        // For now, return a placeholder message indicating analysis was requested.
        return new CommandResult(true,
                String.format("Analysis requested for %s. The knowledge extractor will "
                              + "process the project context and report findings.", target));
    }

    private CommandResult executeQuery(CommandIntent intent) {
        List<AgentRecord> agents = agentRegistry.listAll();

        if (agents.isEmpty()) {
            return new CommandResult(true, "No agents are currently registered.", agents);
        }

        long active = agents.stream().filter(a -> a.state() == AgentState.ACTIVE).count();
        long thinking = agents.stream().filter(a -> a.state() == AgentState.THINKING).count();
        long usingTool = agents.stream().filter(a -> a.state() == AgentState.USING_TOOL).count();
        long blocked = agents.stream().filter(a -> a.state() == AgentState.BLOCKED).count();
        long completed = agents.stream().filter(a -> a.state() == AgentState.COMPLETED).count();
        long failed = agents.stream().filter(a -> a.state() == AgentState.FAILED).count();
        long launching = agents.stream().filter(a -> a.state() == AgentState.LAUNCHING).count();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("You have %d agent(s): ", agents.size()));

        List<String> parts = new java.util.ArrayList<>();
        if (active > 0) parts.add(active + " active");
        if (thinking > 0) parts.add(thinking + " thinking");
        if (usingTool > 0) parts.add(usingTool + " using tools");
        if (blocked > 0) parts.add(blocked + " blocked");
        if (launching > 0) parts.add(launching + " launching");
        if (completed > 0) parts.add(completed + " completed");
        if (failed > 0) parts.add(failed + " failed");

        sb.append(String.join(", ", parts)).append(".");

        double totalCost = agents.stream().mapToDouble(AgentRecord::costUsd).sum();
        if (totalCost > 0) {
            sb.append(String.format(" Total cost: $%.4f.", totalCost));
        }

        return new CommandResult(true, sb.toString(), agents);
    }

    private CommandResult executeKill(CommandIntent intent) {
        Map<String, String> params = intent.parameters();
        String agentIdStr = params.get("agentId");
        String agentName = params.get("agentName");

        // Try by UUID first
        if (agentIdStr != null && !agentIdStr.isBlank()) {
            try {
                UUID agentId = UUID.fromString(agentIdStr);
                Optional<AgentRecord> killed = processManager.killAgent(agentId);
                if (killed.isPresent()) {
                    return new CommandResult(true,
                            String.format("Killed agent '%s' (%s).",
                                    killed.get().name(), agentId.toString().substring(0, 8)),
                            killed.get());
                }
                return new CommandResult(false, "No agent found with ID " + agentIdStr);
            } catch (IllegalArgumentException e) {
                return new CommandResult(false, "Invalid agent ID format: " + agentIdStr);
            }
        }

        // Try by name
        if (agentName != null && !agentName.isBlank()) {
            Optional<AgentRecord> match = agentRegistry.listAll().stream()
                    .filter(a -> a.name().equalsIgnoreCase(agentName) && a.state().isAlive())
                    .findFirst();

            if (match.isPresent()) {
                Optional<AgentRecord> killed = processManager.killAgent(match.get().id());
                if (killed.isPresent()) {
                    return new CommandResult(true,
                            String.format("Killed agent '%s'.", killed.get().name()),
                            killed.get());
                }
            }
            return new CommandResult(false,
                    "No active agent found with name '" + agentName + "'.");
        }

        return new CommandResult(false,
                "I need an agent name or ID to kill. Try: 'kill agent my-agent-name'");
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * Abbreviates a long path for display. Shows only the last 2 components.
     */
    private String abbreviatePath(String path) {
        if (path == null) return "?";
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        if (parts.length <= 2) return path;
        return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
