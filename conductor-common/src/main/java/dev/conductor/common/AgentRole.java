package dev.conductor.common;

/**
 * Predefined roles for Claude agents. Determines default instructions,
 * tool permissions, and behavioral expectations.
 */
public enum AgentRole {

    /** Builds self-contained features from scratch without modifying existing code. */
    FEATURE_ENGINEER,

    /** Writes and maintains tests for existing or new code. */
    TESTER,

    /** Restructures existing code for clarity, performance, or maintainability. */
    REFACTORER,

    /** Reviews code, PRs, or architectural decisions and provides feedback. */
    REVIEWER,

    /** Explores codebases, gathers context, and reports findings. */
    EXPLORER,

    /** General-purpose agent with no specialized role constraints. */
    GENERAL
}
