package dev.conductor.server.brain.task;

import dev.conductor.common.AgentRole;
import dev.conductor.server.brain.context.ContextIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Creates {@link DecompositionPlan}s from high-level user prompts.
 *
 * <p>Phase 4C uses a template-based decomposition strategy that produces a
 * standard 3-step plan: explore, implement, review. Each step maps to an
 * {@link AgentRole} and declares its dependency and context-sharing edges.
 *
 * <p>Future phases will replace the template logic with Claude API calls
 * that analyze the project context and produce intelligent, prompt-specific
 * decompositions. The interface remains stable — callers always get back a
 * {@link DecompositionPlan} regardless of the decomposition strategy.
 */
@Service
public class TaskDecomposer {

    private static final Logger log = LoggerFactory.getLogger(TaskDecomposer.class);

    /**
     * Decomposes a high-level prompt into a structured execution plan.
     *
     * <p>Phase 4C template strategy:
     * <ol>
     *   <li><b>EXPLORER</b> — Analyze the codebase and identify relevant files</li>
     *   <li><b>FEATURE_ENGINEER</b> — Implement the requested changes (depends on step 1,
     *       receives context from step 1)</li>
     *   <li><b>REVIEWER</b> — Review the changes (depends on step 2,
     *       receives context from steps 1 and 2)</li>
     * </ol>
     *
     * @param prompt      the user's high-level prompt (e.g., "Add OAuth2 authentication")
     * @param projectPath absolute path to the target project directory
     * @param context     the current context index (used in future phases for intelligent decomposition)
     * @return a new DecompositionPlan in CREATED status
     */
    public DecompositionPlan decompose(String prompt, String projectPath, ContextIndex context) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be blank");
        }
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("Project path must not be blank");
        }

        String planId = UUID.randomUUID().toString();

        // Step 1: Explorer — codebase analysis
        String exploreId = UUID.randomUUID().toString();
        Subtask explore = new Subtask(
                exploreId,
                "Explore codebase",
                "Analyze the codebase and identify relevant files, patterns, and integration points for the task.",
                AgentRole.EXPLORER,
                List.of(),           // no dependencies — runs first
                List.of(),           // no context input
                projectPath,
                String.format(
                        "Analyze the codebase and identify all relevant files, patterns, and integration points for the following task. "
                        + "Report your findings clearly including file paths, existing patterns, and recommended approach.\n\n"
                        + "Task: %s", prompt),
                "Comprehensive analysis of relevant code areas with specific file paths and recommendations",
                SubtaskStatus.PENDING,
                null, null, null, null
        );

        // Step 2: Feature Engineer — implementation (depends on explore)
        String implementId = UUID.randomUUID().toString();
        Subtask implement = new Subtask(
                implementId,
                "Implement changes",
                "Implement the requested changes based on the exploration findings.",
                AgentRole.FEATURE_ENGINEER,
                List.of(exploreId),      // must wait for exploration
                List.of(exploreId),      // receive exploration context
                projectPath,
                String.format(
                        "Implement the following task. Use the context provided by the exploration step "
                        + "to understand the codebase structure and integration points.\n\n"
                        + "Task: %s", prompt),
                "Working implementation that fulfills the task requirements",
                SubtaskStatus.PENDING,
                null, null, null, null
        );

        // Step 3: Reviewer — code review (depends on implementation)
        String reviewId = UUID.randomUUID().toString();
        Subtask review = new Subtask(
                reviewId,
                "Review changes",
                "Review the implementation for correctness, code quality, and adherence to project conventions.",
                AgentRole.REVIEWER,
                List.of(implementId),              // must wait for implementation
                List.of(exploreId, implementId),   // receive both exploration and implementation context
                projectPath,
                String.format(
                        "Review the changes made for the following task. Check for correctness, code quality, "
                        + "edge cases, and adherence to project conventions. Use the exploration context to "
                        + "understand what the codebase looked like before and the implementation context to "
                        + "understand what was changed.\n\n"
                        + "Task: %s", prompt),
                "Code review with specific feedback and any issues identified",
                SubtaskStatus.PENDING,
                null, null, null, null
        );

        List<Subtask> subtasks = List.of(explore, implement, review);

        DecompositionPlan plan = new DecompositionPlan(
                planId, prompt, projectPath, subtasks, null, null
        );

        log.info("Decomposed prompt into {} subtasks (planId={}): {}",
                subtasks.size(), planId, subtasks.stream().map(Subtask::name).toList());

        return plan;
    }
}
