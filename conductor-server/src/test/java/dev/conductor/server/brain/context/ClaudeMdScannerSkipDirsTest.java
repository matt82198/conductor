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

/**
 * Extended tests for {@link ClaudeMdScanner} covering all skip-directory rules,
 * multiple nested levels, and edge cases in domain name extraction.
 * Supplements the base scanner test with additional coverage.
 */
class ClaudeMdScannerSkipDirsTest {

    @TempDir
    Path tempDir;

    private ClaudeMdScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ClaudeMdScanner();
    }

    // ─── Additional skip directories ─────────────────────────────────

    @Test
    @DisplayName("scanProject skips build/ directory")
    void scanProject_skipsBuildDir() throws IOException {
        Path buildDir = Files.createDirectories(tempDir.resolve("build/output"));
        Files.writeString(buildDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("Root", results.get(0).domainName());
    }

    @Test
    @DisplayName("scanProject skips .idea/ directory")
    void scanProject_skipsIdeaDir() throws IOException {
        Path ideaDir = Files.createDirectories(tempDir.resolve(".idea"));
        Files.writeString(ideaDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("scanProject skips .gradle/ directory")
    void scanProject_skipsGradleDir() throws IOException {
        Path gradleDir = Files.createDirectories(tempDir.resolve(".gradle/caches"));
        Files.writeString(gradleDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("scanProject skips .mvn/ directory")
    void scanProject_skipsMvnDir() throws IOException {
        Path mvnDir = Files.createDirectories(tempDir.resolve(".mvn/wrapper"));
        Files.writeString(mvnDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
    }

    // ─── Complex nested structure ────────────────────────────────────

    @Test
    @DisplayName("scanProject finds CLAUDE.md at multiple nesting levels")
    void scanProject_multipleNestingLevels() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        Path level1 = Files.createDirectories(tempDir.resolve("server"));
        Files.writeString(level1.resolve("CLAUDE.md"), "# Server");

        Path level2 = Files.createDirectories(tempDir.resolve("server/src/main/java/queue"));
        Files.writeString(level2.resolve("CLAUDE.md"), "# Queue Module");

        Path level3 = Files.createDirectories(tempDir.resolve("server/src/main/java/notification"));
        Files.writeString(level3.resolve("CLAUDE.md"), "# Notification Module");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(4, results.size());
    }

    @Test
    @DisplayName("scanProject skips node_modules nested inside a valid directory")
    void scanProject_skipsNestedNodeModules() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        Path uiDir = Files.createDirectories(tempDir.resolve("ui"));
        Files.writeString(uiDir.resolve("CLAUDE.md"), "# UI");

        Path nmDir = Files.createDirectories(tempDir.resolve("ui/node_modules/some-pkg"));
        Files.writeString(nmDir.resolve("CLAUDE.md"), "# Should be skipped");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(2, results.size());
    }

    // ─── Domain name extraction edge cases ───────────────────────────

    @Test
    @DisplayName("extractDomainName from second heading is ignored, first heading wins")
    void domainName_firstHeadingWins() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"),
                "# First Heading\n\nSome content.\n\n# Second Heading\n");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("First Heading", results.get(0).domainName());
    }

    @Test
    @DisplayName("extractDomainName with heading containing special characters")
    void domainName_specialCharacters() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"),
                "# brain/ \u2014 Conductor Brain (Leader Agent Layer)\n\nContent.");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("brain/ \u2014 Conductor Brain (Leader Agent Layer)", results.get(0).domainName());
    }

    @Test
    @DisplayName("empty file content falls back to path-based domain name")
    void domainName_emptyContentFallsBackToPath() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("mymodule"));
        Files.writeString(sub.resolve("CLAUDE.md"), "");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("mymodule", results.get(0).domainName());
    }

    // ─── Content correctness ─────────────────────────────────────────

    @Test
    @DisplayName("file content is read completely including multiline content")
    void contentReadCompletely() throws IOException {
        String content = "# Module\n\n## Section 1\nLine 1\nLine 2\n\n## Section 2\nLine 3\n";
        Files.writeString(tempDir.resolve("CLAUDE.md"), content);

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals(content, results.get(0).content());
    }
}
