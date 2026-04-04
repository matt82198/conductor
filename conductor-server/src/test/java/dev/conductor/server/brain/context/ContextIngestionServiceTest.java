package dev.conductor.server.brain.context;

import dev.conductor.server.project.ProjectRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContextIngestionService}.
 * Validates project scanning, index building, and prompt rendering.
 */
class ContextIngestionServiceTest {

    @TempDir
    Path tempDir;

    private ProjectRegistry projectRegistry;
    private ClaudeMdScanner scanner;
    private ContextIngestionService service;

    @BeforeEach
    void setUp() {
        projectRegistry = new ProjectRegistry();
        scanner = new ClaudeMdScanner();
        service = new ContextIngestionService(projectRegistry, scanner, null);
    }

    // ─── Single project scan ──────────────────────────────────────────

    @Test
    void scanProject_returns_context_with_claude_md() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# My Project\nProject docs.");

        ProjectContext ctx = service.scanProject(tempDir.toString());

        assertNotNull(ctx);
        assertEquals(tempDir.getFileName().toString(), ctx.projectName());
        assertEquals(tempDir.toString(), ctx.projectPath());
        assertNotNull(ctx.rootClaudeMd());
        assertTrue(ctx.rootClaudeMd().contains("My Project"));
    }

    @Test
    void scanProject_includes_nested_domain_docs() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");
        Path queue = Files.createDirectories(tempDir.resolve("server/queue"));
        Files.writeString(queue.resolve("CLAUDE.md"), "# queue/ — Queue Module");

        ProjectContext ctx = service.scanProject(tempDir.toString());

        assertEquals(2, ctx.domainClaudeMds().size());
    }

    @Test
    void scanProject_null_path_returns_null() {
        assertNull(service.scanProject(null));
    }

    @Test
    void scanProject_nonexistent_path_returns_null() {
        assertNull(service.scanProject(tempDir.resolve("nonexistent").toString()));
    }

    @Test
    void scanProject_without_root_claude_md() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("module"));
        Files.writeString(sub.resolve("CLAUDE.md"), "# Module Docs");

        ProjectContext ctx = service.scanProject(tempDir.toString());

        assertNull(ctx.rootClaudeMd());
        assertEquals(1, ctx.domainClaudeMds().size());
    }

    // ─── Full index ───────────────────────────────────────────────────

    @Test
    void buildIndex_with_no_projects_returns_empty() {
        ContextIndex index = service.buildIndex();

        assertTrue(index.projects().isEmpty());
        assertEquals(0, index.projectCount());
        assertNotNull(index.global());
        assertNotNull(index.lastUpdated());
    }

    @Test
    void buildIndex_includes_registered_projects() throws IOException {
        // Create a project directory
        Path projectDir = Files.createDirectories(tempDir.resolve("my-project"));
        Files.writeString(projectDir.resolve("CLAUDE.md"), "# My Project");
        Files.createDirectories(projectDir.resolve(".git")); // Mark as project

        projectRegistry.register(projectDir.toString());

        ContextIndex index = service.buildIndex();

        assertEquals(1, index.projectCount());
        assertEquals("my-project", index.projects().get(0).projectName());
    }

    // ─── Prompt rendering ─────────────────────────────────────────────

    @Test
    void renderForPrompt_prioritizes_target_project() throws IOException {
        Path project1 = Files.createDirectories(tempDir.resolve("target-project"));
        Files.writeString(project1.resolve("CLAUDE.md"), "# Target Project\nFull content here.");
        Files.createDirectories(project1.resolve(".git"));

        Path project2 = Files.createDirectories(tempDir.resolve("other-project"));
        Files.writeString(project2.resolve("CLAUDE.md"), "# Other Project\nOther content.");
        Files.createDirectories(project2.resolve(".git"));

        projectRegistry.register(project1.toString());
        projectRegistry.register(project2.toString());

        ContextIndex index = service.buildIndex();
        String rendered = service.renderForPrompt(index, project1.toString(), 10000);

        // Target project should appear with full content
        assertTrue(rendered.contains("Target Project"));
        assertTrue(rendered.contains("Full content here"));
        // Other project should appear as summary
        assertTrue(rendered.contains("other-project"));
    }

    @Test
    void renderForPrompt_respects_max_chars() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("big-project"));
        Files.writeString(project.resolve("CLAUDE.md"), "# Big\n" + "x".repeat(1000));
        Files.createDirectories(project.resolve(".git"));

        projectRegistry.register(project.toString());

        ContextIndex index = service.buildIndex();
        String rendered = service.renderForPrompt(index, project.toString(), 100);

        assertTrue(rendered.length() <= 100);
    }

    @Test
    void renderForPrompt_null_index_returns_empty() {
        assertEquals("", service.renderForPrompt(null, "/some/path", 1000));
    }

    // ─── Global context ───────────────────────────────────────────────

    @Test
    void scanGlobal_returns_context_even_without_claude_dir() {
        GlobalContext global = service.scanGlobal();

        assertNotNull(global);
        assertNotNull(global.scannedAt());
        // global CLAUDE.md may or may not exist on the machine
    }
}
