package dev.conductor.server.brain.command;

import java.util.Map;

/**
 * Represents a parsed user command extracted from natural language input.
 *
 * <p>The interpreter produces a {@code CommandIntent} from free-form text,
 * identifying the action the user wants to perform and any extracted parameters.
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code SPAWN_AGENT} — spawn a new Claude agent</li>
 *   <li>{@code DECOMPOSE_TASK} — break a task into subtasks and execute via the task decomposer</li>
 *   <li>{@code REGISTER_PROJECT} — register a project directory in the registry</li>
 *   <li>{@code SCAN_PROJECTS} — scan a root directory for project children</li>
 *   <li>{@code ANALYZE_PROJECT} — run knowledge extraction on a project</li>
 *   <li>{@code QUERY_STATUS} — query agent/system status</li>
 *   <li>{@code KILL_AGENT} — stop a running agent</li>
 *   <li>{@code UNKNOWN} — the command could not be interpreted</li>
 * </ul>
 *
 * @param action       the identified action type
 * @param originalText the raw user input before interpretation
 * @param parameters   extracted key-value parameters (name, role, projectPath, prompt, etc.)
 * @param confidence   0.0 to 1.0 indicating interpretation confidence
 * @param reasoning    brief explanation of how the command was interpreted
 */
public record CommandIntent(
        String action,
        String originalText,
        Map<String, String> parameters,
        double confidence,
        String reasoning
) {

    public CommandIntent {
        if (action == null) action = "UNKNOWN";
        if (originalText == null) originalText = "";
        if (parameters == null) parameters = Map.of();
        if (reasoning == null) reasoning = "";
    }
}
