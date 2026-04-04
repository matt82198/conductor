package dev.conductor.server.brain.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersonalKnowledgeScannerTest {

    private PersonalKnowledgeScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new PersonalKnowledgeScanner();
    }

    // ─── Agents ───────────────────────────────────────────────────────

    @Test
    @DisplayName("scans agent definitions with frontmatter")
    void scanAgents_withFrontmatter(@TempDir Path tempDir) throws IOException {
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Files.writeString(agentsDir.resolve("my-agent.md"),
                "---\nname: My Agent\ndescription: Does things\nmodel: opus\n---\nYou are a helpful agent.");

        List<AgentDefinition> agents = scanner.scanAgents(tempDir);
        assertEquals(1, agents.size());
        assertEquals("My Agent", agents.get(0).name());
        assertEquals("Does things", agents.get(0).description());
        assertEquals("opus", agents.get(0).model());
        assertEquals("You are a helpful agent.", agents.get(0).systemPrompt());
    }

    @Test
    @DisplayName("agent without frontmatter uses filename")
    void scanAgents_noFrontmatter(@TempDir Path tempDir) throws IOException {
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Files.writeString(agentsDir.resolve("simple.md"), "Just a prompt with no frontmatter.");

        List<AgentDefinition> agents = scanner.scanAgents(tempDir);
        assertEquals(1, agents.size());
        assertEquals("simple", agents.get(0).name());
    }

    @Test
    @DisplayName("no agents dir returns empty")
    void scanAgents_noDir(@TempDir Path tempDir) {
        assertEquals(List.of(), scanner.scanAgents(tempDir));
    }

    // ─── Memories ─────────────────────────────────────────────────────

    @Test
    @DisplayName("scans project memories from projects/*/memory/")
    void scanMemories_projectMemories(@TempDir Path tempDir) throws IOException {
        Path memDir = Files.createDirectories(tempDir.resolve("projects").resolve("my-project").resolve("memory"));
        Files.writeString(memDir.resolve("user_role.md"),
                "---\nname: User Role\ndescription: Matt is a developer\ntype: user\n---\nMatt builds things.");

        List<MemoryEntry> memories = scanner.scanMemories(tempDir);
        assertEquals(1, memories.size());
        assertEquals("User Role", memories.get(0).name());
        assertEquals("user", memories.get(0).type());
        assertEquals("my-project", memories.get(0).projectScope());
        assertEquals("Matt builds things.", memories.get(0).content());
    }

    @Test
    @DisplayName("scans agent memories from agent-memory/")
    void scanMemories_agentMemories(@TempDir Path tempDir) throws IOException {
        Path memDir = Files.createDirectories(tempDir.resolve("agent-memory").resolve("agent1"));
        Files.writeString(memDir.resolve("feedback.md"),
                "---\nname: Feedback\ntype: feedback\n---\nUser prefers short responses.");

        List<MemoryEntry> memories = scanner.scanMemories(tempDir);
        assertEquals(1, memories.size());
        assertTrue(memories.get(0).projectScope().startsWith("agent:"));
    }

    @Test
    @DisplayName("no memory dirs returns empty")
    void scanMemories_noDirs(@TempDir Path tempDir) {
        assertEquals(List.of(), scanner.scanMemories(tempDir));
    }

    // ─── Commands ─────────────────────────────────────────────────────

    @Test
    @DisplayName("scans command files")
    void scanCommands(@TempDir Path tempDir) throws IOException {
        Path cmdsDir = Files.createDirectories(tempDir.resolve("commands"));
        Files.writeString(cmdsDir.resolve("deploy.md"), "Run the deploy pipeline for $PROJECT");

        List<CommandDefinition> commands = scanner.scanCommands(tempDir);
        assertEquals(1, commands.size());
        assertEquals("deploy", commands.get(0).name());
        assertTrue(commands.get(0).content().contains("deploy pipeline"));
    }

    // ─── Plans ────────────────────────────────────────────────────────

    @Test
    @DisplayName("scans plan files")
    void scanPlans(@TempDir Path tempDir) throws IOException {
        Path plansDir = Files.createDirectories(tempDir.resolve("plans"));
        Files.writeString(plansDir.resolve("auth-migration.md"), "# Auth Migration\n- Step 1\n- Step 2");

        List<PlanEntry> plans = scanner.scanPlans(tempDir);
        assertEquals(1, plans.size());
        assertEquals("auth-migration", plans.get(0).name());
    }

    // ─── Frontmatter parsing ──────────────────────────────────────────

    @Test
    @DisplayName("parseFrontmatter splits correctly")
    void parseFrontmatter() {
        String[] result = scanner.parseFrontmatter("---\nname: Test\ntype: user\n---\nBody content here.");
        assertEquals("name: Test\ntype: user", result[0]);
        assertEquals("Body content here.", result[1]);
    }

    @Test
    @DisplayName("parseFrontmatter with no frontmatter")
    void parseFrontmatter_none() {
        String[] result = scanner.parseFrontmatter("Just plain content.");
        assertEquals("", result[0]);
        assertEquals("Just plain content.", result[1]);
    }

    @Test
    @DisplayName("extractField handles quoted values")
    void extractField_quoted() {
        assertEquals("hello world", scanner.extractField("name: \"hello world\"", "name", "default"));
    }

    @Test
    @DisplayName("extractField returns default when missing")
    void extractField_missing() {
        assertEquals("default", scanner.extractField("other: value", "name", "default"));
    }

    // ─── Full scan ────────────────────────────────────────────────────

    @Test
    @DisplayName("full scan aggregates all types")
    void fullScan(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("agents"));
        Files.writeString(tempDir.resolve("agents").resolve("a.md"), "agent");
        Files.createDirectories(tempDir.resolve("commands"));
        Files.writeString(tempDir.resolve("commands").resolve("c.md"), "command");
        Files.createDirectories(tempDir.resolve("plans"));
        Files.writeString(tempDir.resolve("plans").resolve("p.md"), "plan");
        Path memDir = Files.createDirectories(tempDir.resolve("projects").resolve("proj").resolve("memory"));
        Files.writeString(memDir.resolve("m.md"), "memory");

        // Can't easily test scan() since it reads ~/.claude,
        // but we can test individual scanners with a custom dir
        assertEquals(1, scanner.scanAgents(tempDir).size());
        assertEquals(1, scanner.scanCommands(tempDir).size());
        assertEquals(1, scanner.scanPlans(tempDir).size());
        assertEquals(1, scanner.scanMemories(tempDir).size());
    }
}
