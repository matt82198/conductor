package dev.conductor.server.brain.context;

import dev.conductor.server.project.ProjectRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link ContextIngestionService} covering prompt rendering
 * with multiple projects, character budget enforcement, and global context
 * integration. Supplements the base test class.
 */
class ContextIngestionServiceRenderTest {

    @TempDir
    Path tempDir;

    private ProjectRegistry projectRegistry;
    private ClaudeMdScanner scanner;
    private ContextIngestionService service;

    @BeforeEach
    void setUp() {
        projectRegistry = new ProjectRegistry();
        scanner = new ClaudeMdScanner();
        service = new ContextIngestionService(projectRegistry, scanner);
    }

    // ─── Prompt rendering edge cases ─────────────────────────────────

    @Test
    @DisplayName("renderForPrompt includes target project's domain CLAUDE.md files")
    void renderForPrompt_includesDomainDocs() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("conductor"));
        Files.writeString(project.resolve("CLAUDE.md"), "# Conductor Root\nRoot content.");
        Files.createDirectories(project.resolve(".git"));

        Path queue = Files.createDirectories(project.resolve("server/queue"));
        Files.writeString(queue.resolve("CLAUDE.md"), "# queue/ -- Queue Module\nQueue docs.");

        projectRegistry.register(project.toString());
        ContextIndex index = service.buildIndex();
        String rendered = service.renderForPrompt(index, project.toString(), 50000);

        assertTrue(rendered.contains("Conductor Root"));
        assertTrue(rendered.contains("Root content"));
        assertTrue(rendered.contains("Queue docs"));
    }

    @Test
    @DisplayName("renderForPrompt shows non-target projects as one-line summaries")
    void renderForPrompt_otherProjectsAsSummaries() throws IOException {
        Path target = Files.createDirectories(tempDir.resolve("target-proj"));
        Files.writeString(target.resolve("CLAUDE.md"), "# Target");
        Files.createDirectories(target.resolve(".git"));

        Path other = Files.createDirectories(tempDir.resolve("other-proj"));
        Files.writeString(other.resolve("CLAUDE.md"), "# Other");
        Files.createDirectories(other.resolve(".git"));
        Path otherSub = Files.createDirectories(other.resolve("sub"));
        Files.writeString(otherSub.resolve("CLAUDE.md"), "# Sub Module");

        projectRegistry.register(target.toString());
        projectRegistry.register(other.toString());

        ContextIndex index = service.buildIndex();
        String rendered = service.renderForPrompt(index, target.toString(), 50000);

        // Target should have full content
        assertTrue(rendered.contains("Target"));
        // Other project should be a summary line with CLAUDE.md count
        assertTrue(rendered.contains("other-proj"));
        assertTrue(rendered.contains("2 CLAUDE.md files"));
    }

    @Test
    @DisplayName("renderForPrompt truncates output to maxChars budget")
    void renderForPrompt_truncatesToBudget() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("big-proj"));
        Files.writeString(project.resolve("CLAUDE.md"), "# Big Project\n" + "content ".repeat(500));
        Files.createDirectories(project.resolve(".git"));

        projectRegistry.register(project.toString());
        ContextIndex index = service.buildIndex();

        String rendered = service.renderForPrompt(index, project.toString(), 50);
        assertTrue(rendered.length() <= 50, "Rendered output should not exceed maxChars");
    }

    @Test
    @DisplayName("renderForPrompt returns empty string for null index")
    void renderForPrompt_nullIndex_returnsEmpty() {
        assertEquals("", service.renderForPrompt(null, "/path", 1000));
    }

    // ─── Project scan with no root CLAUDE.md ─────────────────────────

    @Test
    @DisplayName("scanProject handles project with only nested CLAUDE.md files")
    void scanProject_noRootClaudeMd_onlyNested() throws IOException {
        Path sub1 = Files.createDirectories(tempDir.resolve("module1"));
        Files.writeString(sub1.resolve("CLAUDE.md"), "# Module 1");
        Path sub2 = Files.createDirectories(tempDir.resolve("module2"));
        Files.writeString(sub2.resolve("CLAUDE.md"), "# Module 2");

        ProjectContext ctx = service.scanProject(tempDir.toString());

        assertNotNull(ctx);
        assertNull(ctx.rootClaudeMd());
        assertEquals(2, ctx.domainClaudeMds().size());
    }

    // ─── Index with multiple registered projects ─────────────────────

    @Test
    @DisplayName("buildIndex includes all registered projects in the index")
    void buildIndex_multipleRegisteredProjects() throws IOException {
        Path proj1 = Files.createDirectories(tempDir.resolve("project-alpha"));
        Files.writeString(proj1.resolve("CLAUDE.md"), "# Alpha");
        Files.createDirectories(proj1.resolve(".git"));

        Path proj2 = Files.createDirectories(tempDir.resolve("project-beta"));
        Files.writeString(proj2.resolve("CLAUDE.md"), "# Beta");
        Files.createDirectories(proj2.resolve(".git"));

        projectRegistry.register(proj1.toString());
        projectRegistry.register(proj2.toString());

        ContextIndex index = service.buildIndex();

        assertEquals(2, index.projectCount());
    }

    // ─── Blank and empty paths ───────────────────────────────────────

    @Test
    @DisplayName("scanProject with blank path returns null")
    void scanProject_blankPath_returnsNull() {
        assertNull(service.scanProject("   "));
    }

    @Test
    @DisplayName("scanProject with empty string returns null")
    void scanProject_emptyString_returnsNull() {
        assertNull(service.scanProject(""));
    }

    // ─── Context index global field ──────────────────────────────────

    @Test
    @DisplayName("buildIndex always includes a non-null global context")
    void buildIndex_alwaysIncludesGlobalContext() {
        ContextIndex index = service.buildIndex();
        assertNotNull(index.global(), "Global context should never be null in the index");
    }

    @Test
    @DisplayName("buildIndex sets lastUpdated to approximately now")
    void buildIndex_lastUpdatedIsRecent() {
        Instant before = Instant.now();
        ContextIndex index = service.buildIndex();
        Instant after = Instant.now();

        assertNotNull(index.lastUpdated());
        assertFalse(index.lastUpdated().isBefore(before));
        assertFalse(index.lastUpdated().isAfter(after));
    }
}
