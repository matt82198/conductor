package dev.conductor.server.brain.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.brain.BrainProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CommandInterpreter} pattern matching (Tier 1).
 *
 * <p>These tests validate the local keyword/regex interpretation layer.
 * Claude API (Tier 2) tests are not included since they require a real API key.
 */
class CommandInterpreterTest {

    private CommandInterpreter interpreter;

    @BeforeEach
    void setUp() {
        // BrainProperties with no API key — forces pattern matching only
        BrainProperties props = new BrainProperties(
                false, null, "claude-sonnet-4-6", 0.8, 10, null, 100000);
        interpreter = new CommandInterpreter(props, new ObjectMapper());
    }

    // ─── SPAWN_AGENT ─────────────────────────────────────────────────

    @Test
    void spawn_keywords_detected() {
        CommandIntent intent = interpreter.interpret("spawn an agent to write tests");
        assertEquals("SPAWN_AGENT", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void spawn_create_keyword() {
        CommandIntent intent = interpreter.interpret("create an agent to refactor the API");
        assertEquals("SPAWN_AGENT", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void spawn_launch_keyword() {
        CommandIntent intent = interpreter.interpret("launch agent for code review");
        assertEquals("SPAWN_AGENT", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void spawn_start_keyword() {
        CommandIntent intent = interpreter.interpret("start an agent to build auth module");
        assertEquals("SPAWN_AGENT", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void role_extraction_tester() {
        CommandIntent intent = interpreter.interpret("spawn a TESTER agent to verify the login flow");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("TESTER", intent.parameters().get("role"));
    }

    @Test
    void role_extraction_feature_engineer() {
        CommandIntent intent = interpreter.interpret("create a FEATURE_ENGINEER agent for new dashboard");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("FEATURE_ENGINEER", intent.parameters().get("role"));
    }

    @Test
    void name_extraction_called() {
        CommandIntent intent = interpreter.interpret("create an agent called auth-builder to add OAuth");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("auth-builder", intent.parameters().get("name"));
    }

    @Test
    void name_extraction_named() {
        CommandIntent intent = interpreter.interpret("spawn an agent named test-writer for unit tests");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("test-writer", intent.parameters().get("name"));
    }

    @Test
    void name_extraction_quoted() {
        CommandIntent intent = interpreter.interpret("spawn an agent \"my-worker\" to do stuff");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("my-worker", intent.parameters().get("name"));
    }

    @Test
    void path_extraction_windows() {
        CommandIntent intent = interpreter.interpret(
                "spawn an agent at C:/Users/matt8/myapp to add tests");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("C:/Users/matt8/myapp", intent.parameters().get("projectPath"));
    }

    @Test
    void path_extraction_unix() {
        CommandIntent intent = interpreter.interpret(
                "spawn an agent at /home/user/projects/myapp to add tests");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("/home/user/projects/myapp", intent.parameters().get("projectPath"));
    }

    @Test
    void spawn_extracts_prompt() {
        CommandIntent intent = interpreter.interpret("spawn an agent to add OAuth2 authentication");
        assertEquals("SPAWN_AGENT", intent.action());
        assertNotNull(intent.parameters().get("prompt"));
        assertFalse(intent.parameters().get("prompt").isBlank());
    }

    // ─── REGISTER_PROJECT ────────────────────────────────────────────

    @Test
    void register_with_path() {
        CommandIntent intent = interpreter.interpret("register project at C:/Users/matt8/myapp");
        assertEquals("REGISTER_PROJECT", intent.action());
        assertEquals("C:/Users/matt8/myapp", intent.parameters().get("path"));
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void register_add_keyword() {
        CommandIntent intent = interpreter.interpret("add project C:/dev/myproject");
        assertEquals("REGISTER_PROJECT", intent.action());
        assertNotNull(intent.parameters().get("path"));
    }

    @Test
    void register_open_keyword() {
        CommandIntent intent = interpreter.interpret("open project at /home/dev/app");
        assertEquals("REGISTER_PROJECT", intent.action());
        assertEquals("/home/dev/app", intent.parameters().get("path"));
    }

    @Test
    void register_without_path_low_confidence() {
        CommandIntent intent = interpreter.interpret("register project my-cool-app");
        assertEquals("REGISTER_PROJECT", intent.action());
        assertTrue(intent.confidence() < 0.8,
                "Should have low confidence when no path-like string is found");
    }

    // ─── QUERY_STATUS ────────────────────────────────────────────────

    @Test
    void status_what_are_agents() {
        CommandIntent intent = interpreter.interpret("what are my agents doing?");
        assertEquals("QUERY_STATUS", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void status_how_many_agents() {
        CommandIntent intent = interpreter.interpret("how many agents are running?");
        assertEquals("QUERY_STATUS", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void status_show_agents() {
        CommandIntent intent = interpreter.interpret("show agents");
        assertEquals("QUERY_STATUS", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void status_list_agents() {
        CommandIntent intent = interpreter.interpret("list all agents");
        assertEquals("QUERY_STATUS", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    // ─── KILL_AGENT ──────────────────────────────────────────────────

    @Test
    void kill_by_name() {
        CommandIntent intent = interpreter.interpret("kill agent test-writer");
        assertEquals("KILL_AGENT", intent.action());
        assertEquals("test-writer", intent.parameters().get("agentName"));
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void stop_agent() {
        CommandIntent intent = interpreter.interpret("stop agent auth-builder");
        assertEquals("KILL_AGENT", intent.action());
        assertEquals("auth-builder", intent.parameters().get("agentName"));
    }

    @Test
    void cancel_agent() {
        CommandIntent intent = interpreter.interpret("cancel agent my-worker");
        assertEquals("KILL_AGENT", intent.action());
        assertEquals("my-worker", intent.parameters().get("agentName"));
    }

    @Test
    void kill_agent_by_uuid() {
        CommandIntent intent = interpreter.interpret(
                "kill agent 12345678-1234-1234-1234-123456789abc");
        assertEquals("KILL_AGENT", intent.action());
        assertEquals("12345678-1234-1234-1234-123456789abc", intent.parameters().get("agentId"));
    }

    // ─── DECOMPOSE_TASK ──────────────────────────────────────────────

    @Test
    void decompose_keyword() {
        CommandIntent intent = interpreter.interpret("decompose: add OAuth to the API");
        assertEquals("DECOMPOSE_TASK", intent.action());
        assertTrue(intent.confidence() >= 0.8);
        assertNotNull(intent.parameters().get("prompt"));
    }

    @Test
    void break_down_keyword() {
        CommandIntent intent = interpreter.interpret("break down: add OAuth to the API");
        assertEquals("DECOMPOSE_TASK", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void plan_keyword() {
        CommandIntent intent = interpreter.interpret("plan the authentication migration");
        assertEquals("DECOMPOSE_TASK", intent.action());
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void decompose_with_path() {
        CommandIntent intent = interpreter.interpret(
                "decompose: add tests at C:/Users/matt8/myapp");
        assertEquals("DECOMPOSE_TASK", intent.action());
        assertEquals("C:/Users/matt8/myapp", intent.parameters().get("projectPath"));
    }

    // ─── SCAN_PROJECTS ──────────────────────────────────────────────

    @Test
    void scan_directory() {
        CommandIntent intent = interpreter.interpret("scan C:/Users/matt8/projects for projects");
        assertEquals("SCAN_PROJECTS", intent.action());
        assertEquals("C:/Users/matt8/projects", intent.parameters().get("rootPath"));
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void scan_unix_path() {
        CommandIntent intent = interpreter.interpret("scan /home/user/repos");
        assertEquals("SCAN_PROJECTS", intent.action());
        assertEquals("/home/user/repos", intent.parameters().get("rootPath"));
    }

    // ─── ANALYZE_PROJECT ─────────────────────────────────────────────

    @Test
    void analyze_project() {
        CommandIntent intent = interpreter.interpret("analyze C:/Users/matt8/myapp");
        assertEquals("ANALYZE_PROJECT", intent.action());
        assertEquals("C:/Users/matt8/myapp", intent.parameters().get("projectPath"));
        assertTrue(intent.confidence() >= 0.8);
    }

    @Test
    void study_project() {
        CommandIntent intent = interpreter.interpret("study the myapp project");
        assertEquals("ANALYZE_PROJECT", intent.action());
    }

    @Test
    void learn_project() {
        CommandIntent intent = interpreter.interpret("learn about the codebase at /home/dev/app");
        assertEquals("ANALYZE_PROJECT", intent.action());
        assertEquals("/home/dev/app", intent.parameters().get("projectPath"));
    }

    // ─── UNKNOWN / Edge Cases ────────────────────────────────────────

    @Test
    void unknown_text_low_confidence() {
        CommandIntent intent = interpreter.interpret("hello world this is random text");
        assertEquals("UNKNOWN", intent.action());
        assertTrue(intent.confidence() < 0.5);
    }

    @Test
    void empty_input_returns_unknown() {
        CommandIntent intent = interpreter.interpret("");
        assertEquals("UNKNOWN", intent.action());
        assertEquals(0.0, intent.confidence());
    }

    @Test
    void null_input_returns_unknown() {
        CommandIntent intent = interpreter.interpret(null);
        assertEquals("UNKNOWN", intent.action());
        assertEquals(0.0, intent.confidence());
    }

    @Test
    void whitespace_input_returns_unknown() {
        CommandIntent intent = interpreter.interpret("   ");
        assertEquals("UNKNOWN", intent.action());
        assertEquals(0.0, intent.confidence());
    }

    @Test
    void original_text_preserved() {
        String input = "spawn an agent to write tests";
        CommandIntent intent = interpreter.interpret(input);
        assertEquals(input, intent.originalText());
    }

    @Test
    void reasoning_is_not_blank() {
        CommandIntent intent = interpreter.interpret("spawn an agent to write tests");
        assertNotNull(intent.reasoning());
        assertFalse(intent.reasoning().isBlank());
    }

    // ─── Kill takes precedence over spawn when both match ────────────

    @Test
    void kill_prioritized_over_spawn() {
        // "kill agent" contains "agent" which could match spawn, but kill should win
        CommandIntent intent = interpreter.interpret("kill agent my-worker");
        assertEquals("KILL_AGENT", intent.action());
    }

    // ─── Case insensitivity ──────────────────────────────────────────

    @Test
    void case_insensitive_spawn() {
        CommandIntent intent = interpreter.interpret("SPAWN an AGENT to build");
        assertEquals("SPAWN_AGENT", intent.action());
    }

    @Test
    void case_insensitive_role() {
        CommandIntent intent = interpreter.interpret("spawn a tester agent");
        assertEquals("SPAWN_AGENT", intent.action());
        assertEquals("TESTER", intent.parameters().get("role"));
    }

    // ─── Windows backslash paths ─────────────────────────────────────

    @Test
    void windows_backslash_path() {
        CommandIntent intent = interpreter.interpret(
                "register project at C:\\Users\\matt8\\myapp");
        assertEquals("REGISTER_PROJECT", intent.action());
        assertNotNull(intent.parameters().get("path"));
        assertTrue(intent.parameters().get("path").startsWith("C:"));
    }

    // ─── Record defaults ─────────────────────────────────────────────

    @Test
    void commandIntent_null_parameters_defaultsToEmptyMap() {
        CommandIntent intent = new CommandIntent("TEST", "text", null, 1.0, "reason");
        assertNotNull(intent.parameters());
        assertTrue(intent.parameters().isEmpty());
    }

    @Test
    void commandIntent_null_fields_default() {
        CommandIntent intent = new CommandIntent(null, null, null, 0.0, null);
        assertEquals("UNKNOWN", intent.action());
        assertEquals("", intent.originalText());
        assertNotNull(intent.parameters());
        assertEquals("", intent.reasoning());
    }

    @Test
    void commandResult_convenience_constructor() {
        CommandResult result = new CommandResult(true, "Success");
        assertTrue(result.success());
        assertEquals("Success", result.message());
        assertNull(result.data());
    }
}
