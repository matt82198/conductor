package dev.conductor.server.brain.context;

import dev.conductor.server.project.ProjectRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContextManager} covering intelligent context rendering,
 * budget allocation, relevance-based filtering, and edge cases.
 *
 * <p>Uses real instances (no mocks) following the project convention for
 * JDK 25 + Mockito compatibility.
 */
class ContextManagerTest {

    @TempDir
    Path tempDir;

    private ProjectRegistry projectRegistry;
    private ClaudeMdScanner claudeMdScanner;
    private ContextIngestionService contextIngestionService;
    private RelevanceScorer relevanceScorer;
    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        projectRegistry = new ProjectRegistry();
        claudeMdScanner = new ClaudeMdScanner();
        contextIngestionService = new ContextIngestionService(projectRegistry, claudeMdScanner, null);
        relevanceScorer = new RelevanceScorer();
        // No PersonalKnowledgeScanner or ProjectKnowledgeStore in tests
        contextManager = new ContextManager(
                contextIngestionService, relevanceScorer, null, null
        );
    }

    // -- renderForPrompt: basic behavior ----------------------------------

    @Test
    @DisplayName("renderForPrompt: includes target project content")
    void renderForPrompt_includesTargetProject() throws IOException {
        Path project = createProjectWithClaudeMd("my-app",
                "# My App\n\n## Architecture\nSpring Boot REST API.\n\n## Gotchas\nPort 8080 required.");

        projectRegistry.register(project.toString());

        String rendered = contextManager.renderForPrompt(
                "Fix the REST API authentication", project.toString(), 50_000);

        assertTrue(rendered.contains("My App"), "Should include project name");
        assertTrue(rendered.contains("Spring Boot REST API"), "Should include project content");
    }

    @Test
    @DisplayName("renderForPrompt: includes domain CLAUDE.md content for target project")
    void renderForPrompt_includesDomainDocs() throws IOException {
        Path project = createProjectWithClaudeMd("conductor",
                "# Conductor\nAgent orchestrator.");

        Path authDomain = Files.createDirectories(project.resolve("server/auth"));
        Files.writeString(authDomain.resolve("CLAUDE.md"), "# Auth Module\nOAuth2 flow docs here.");

        projectRegistry.register(project.toString());

        String rendered = contextManager.renderForPrompt(
                "Fix OAuth2 authentication", project.toString(), 50_000);

        assertTrue(rendered.contains("Auth Module") || rendered.contains("OAuth2 flow"),
                "Should include domain CLAUDE.md content");
    }

    @Test
    @DisplayName("renderForPrompt: other projects shown as summaries, not full content")
    void renderForPrompt_otherProjectsSummarized() throws IOException {
        Path target = createProjectWithClaudeMd("target-app", "# Target App\nMain project.");
        Path other = createProjectWithClaudeMd("other-app",
                "# Other App\n\n## Secret Architecture\nDo not include this full content.");

        projectRegistry.register(target.toString());
        projectRegistry.register(other.toString());

        String rendered = contextManager.renderForPrompt(
                "Work on the target app", target.toString(), 50_000);

        assertTrue(rendered.contains("Target App"), "Should include target project");
        assertTrue(rendered.contains("other-app"), "Should mention other project");
        // The other project's full content should NOT appear
        assertFalse(rendered.contains("Do not include this full content"),
                "Should not include full content of non-target projects");
    }

    @Test
    @DisplayName("renderForPrompt: respects maxChars budget")
    void renderForPrompt_respectsMaxChars() throws IOException {
        Path project = createProjectWithClaudeMd("big-project",
                "# Big Project\n" + "Long content here. ".repeat(500));

        projectRegistry.register(project.toString());

        String rendered = contextManager.renderForPrompt(
                "Do something", project.toString(), 200);

        assertTrue(rendered.length() <= 200,
                "Rendered output (" + rendered.length() + ") should not exceed maxChars (200)");
    }

    @Test
    @DisplayName("renderForPrompt: handles empty prompt gracefully")
    void renderForPrompt_emptyPrompt() throws IOException {
        Path project = createProjectWithClaudeMd("app", "# App\nSome content.");
        projectRegistry.register(project.toString());

        String rendered = contextManager.renderForPrompt("", project.toString(), 50_000);
        // Should still render something (target project content)
        assertNotNull(rendered);
    }

    @Test
    @DisplayName("renderForPrompt: handles null target project path")
    void renderForPrompt_nullTargetPath() throws IOException {
        Path project = createProjectWithClaudeMd("app", "# App\nContent.");
        projectRegistry.register(project.toString());

        String rendered = contextManager.renderForPrompt("do something", null, 50_000);
        assertNotNull(rendered);
    }

    @Test
    @DisplayName("renderForPrompt: handles no registered projects")
    void renderForPrompt_noProjects() {
        String rendered = contextManager.renderForPrompt("do something", "/nonexistent", 50_000);
        assertNotNull(rendered);
    }

    // -- buildBudget: allocation analysis --------------------------------

    @Test
    @DisplayName("buildBudget: returns correct budget ratios")
    void buildBudget_correctRatios() {
        ContextBudget budget = contextManager.buildBudget(
                "Fix the database query", "/some/path", 100_000);

        assertEquals(100_000, budget.totalChars());
        assertEquals(60_000, budget.targetProjectChars());
        assertEquals(20_000, budget.memoriesChars());
        assertEquals(10_000, budget.agentDefsChars());
        assertEquals(10_000, budget.crossProjectChars());
    }

    @Test
    @DisplayName("buildBudget: scores target project entries with high relevance")
    void buildBudget_targetProjectHighRelevance() throws IOException {
        Path project = createProjectWithClaudeMd("my-api",
                "# API Project\n\nREST endpoints for OAuth2 authentication.");

        projectRegistry.register(project.toString());

        ContextBudget budget = contextManager.buildBudget(
                "Fix OAuth2 authentication", project.toString(), 100_000);

        // Target project root should always have relevance 1.0
        boolean hasMaxRelevance = budget.rankedEntries().stream()
                .filter(e -> "target-project".equals(e.source()))
                .anyMatch(e -> e.relevance() == 1.0);
        assertTrue(hasMaxRelevance, "Target project root CLAUDE.md should have 1.0 relevance");
    }

    @Test
    @DisplayName("buildBudget: entries sorted by relevance descending")
    void buildBudget_entriesSortedDescending() throws IOException {
        Path project = createProjectWithClaudeMd("app", "# App\nOAuth2 auth module.");
        Path other = createProjectWithClaudeMd("other", "# Other\nUnrelated queue system.");

        projectRegistry.register(project.toString());
        projectRegistry.register(other.toString());

        ContextBudget budget = contextManager.buildBudget(
                "Fix OAuth2 auth", project.toString(), 100_000);

        List<ContextBudget.ScoredEntry> entries = budget.rankedEntries();
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).relevance() >= entries.get(i).relevance(),
                    "Entries should be sorted by relevance descending at index " + i);
        }
    }

    @Test
    @DisplayName("buildBudget: cross-project entries present for non-target projects")
    void buildBudget_crossProjectEntries() throws IOException {
        Path target = createProjectWithClaudeMd("target", "# Target\nMain project.");
        Path cross = createProjectWithClaudeMd("cross", "# Cross\nDatabase utilities.");

        projectRegistry.register(target.toString());
        projectRegistry.register(cross.toString());

        ContextBudget budget = contextManager.buildBudget(
                "Fix the database", target.toString(), 100_000);

        long crossEntries = budget.entriesInCategory("cross-project");
        assertTrue(crossEntries > 0, "Should have cross-project entries");
    }

    // -- extractSectionHeaders -------------------------------------------

    @Test
    @DisplayName("extractSectionHeaders: extracts ## and ### headers")
    void extractSectionHeaders_extractsBothLevels() {
        String content = "# Title\n\n## Architecture\nSome text.\n\n### Database\nMore text.\n\n## API\nEndpoints.";
        String headers = contextManager.extractSectionHeaders(content);

        assertTrue(headers.contains("Architecture"));
        assertTrue(headers.contains("Database"));
        assertTrue(headers.contains("API"));
        assertFalse(headers.contains("Title"), "Should not extract # headers (only ## and ###)");
    }

    @Test
    @DisplayName("extractSectionHeaders: returns empty for no headers")
    void extractSectionHeaders_noHeaders() {
        String headers = contextManager.extractSectionHeaders("Just plain text, no headers.");
        assertEquals("", headers);
    }

    @Test
    @DisplayName("extractSectionHeaders: handles null input")
    void extractSectionHeaders_null() {
        assertEquals("", contextManager.extractSectionHeaders(null));
    }

    @Test
    @DisplayName("extractSectionHeaders: handles blank input")
    void extractSectionHeaders_blank() {
        assertEquals("", contextManager.extractSectionHeaders("   "));
    }

    // -- Context with PersonalKnowledgeScanner ---------------------------

    @Test
    @DisplayName("renderForPrompt: works without PersonalKnowledgeScanner (null)")
    void renderForPrompt_worksWithoutPKS() throws IOException {
        // Already tested implicitly since setUp passes null, but let's be explicit
        ContextManager mgr = new ContextManager(
                contextIngestionService, relevanceScorer, null, null
        );

        Path project = createProjectWithClaudeMd("app", "# App\nContent.");
        projectRegistry.register(project.toString());

        String rendered = mgr.renderForPrompt("test", project.toString(), 50_000);
        assertNotNull(rendered);
        assertFalse(rendered.isBlank());
    }

    // -- Relevance-based filtering in rendering --------------------------

    @Test
    @DisplayName("renderForPrompt: domain docs sorted by relevance for target project")
    void renderForPrompt_domainDocsSortedByRelevance() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("multi-domain"));
        Files.writeString(project.resolve("CLAUDE.md"), "# Multi Domain\nRoot.");
        Files.createDirectories(project.resolve(".git"));

        Path authDomain = Files.createDirectories(project.resolve("auth"));
        Files.writeString(authDomain.resolve("CLAUDE.md"), "# auth/\nOAuth2 authentication flow.");

        Path queueDomain = Files.createDirectories(project.resolve("queue"));
        Files.writeString(queueDomain.resolve("CLAUDE.md"), "# queue/\nMessage queue processing.");

        projectRegistry.register(project.toString());

        String rendered = contextManager.renderForPrompt(
                "Fix OAuth2 authentication", project.toString(), 50_000);

        // Auth domain should appear before queue domain since it's more relevant
        int authPos = rendered.indexOf("auth/");
        int queuePos = rendered.indexOf("queue/");

        // Both should be present (target project gets all domains within budget)
        assertTrue(authPos >= 0, "Auth domain should be present");
        assertTrue(queuePos >= 0, "Queue domain should be present");

        // Auth should come first (higher relevance)
        assertTrue(authPos < queuePos,
                "Auth domain (authPos=" + authPos + ") should come before queue domain (queuePos=" + queuePos + ")");
    }

    @Test
    @DisplayName("buildBudget: small budget still produces valid result")
    void buildBudget_smallBudget() {
        ContextBudget budget = contextManager.buildBudget("test", "/path", 100);
        assertEquals(100, budget.totalChars());
        assertTrue(budget.targetProjectChars() >= 0);
        assertTrue(budget.memoriesChars() >= 0);
    }

    // -- Helper -----------------------------------------------------------

    /**
     * Creates a project directory with a CLAUDE.md file and a .git marker.
     */
    private Path createProjectWithClaudeMd(String name, String content) throws IOException {
        Path project = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(project.resolve("CLAUDE.md"), content);
        Files.createDirectories(project.resolve(".git"));
        return project;
    }
}
