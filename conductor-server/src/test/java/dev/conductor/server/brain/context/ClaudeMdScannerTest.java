package dev.conductor.server.brain.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClaudeMdScanner}.
 * Validates CLAUDE.md discovery, content reading, and directory skipping.
 */
class ClaudeMdScannerTest {

    @TempDir
    Path tempDir;

    private ClaudeMdScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ClaudeMdScanner();
    }

    @Test
    void finds_root_claude_md() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root Project\nSome content here.");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("CLAUDE.md", results.get(0).relativePath());
        assertTrue(results.get(0).content().contains("Root Project"));
        assertEquals("Root Project", results.get(0).domainName());
    }

    @Test
    void finds_nested_claude_md_files() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");
        Path subDir = Files.createDirectories(tempDir.resolve("server/src/queue"));
        Files.writeString(subDir.resolve("CLAUDE.md"), "# queue/ — Message Queue\nQueue docs.");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(2, results.size());
    }

    @Test
    void skips_git_directory() throws IOException {
        Path gitDir = Files.createDirectories(tempDir.resolve(".git/hooks"));
        Files.writeString(gitDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("Root", results.get(0).domainName());
    }

    @Test
    void skips_node_modules() throws IOException {
        Path nmDir = Files.createDirectories(tempDir.resolve("node_modules/some-pkg"));
        Files.writeString(nmDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
    }

    @Test
    void skips_target_directory() throws IOException {
        Path targetDir = Files.createDirectories(tempDir.resolve("target/classes"));
        Files.writeString(targetDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
    }

    @Test
    void skips_dist_directory() throws IOException {
        Path distDir = Files.createDirectories(tempDir.resolve("dist"));
        Files.writeString(distDir.resolve("CLAUDE.md"), "# Should be skipped");
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Root");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
    }

    @Test
    void extracts_domain_name_from_heading() throws IOException {
        Files.writeString(tempDir.resolve("CLAUDE.md"),
                "# queue/ — Message Queue Engine (Phase 1)\n\nSome content.");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("queue/ — Message Queue Engine (Phase 1)", results.get(0).domainName());
    }

    @Test
    void falls_back_to_path_for_domain_name() throws IOException {
        Path subDir = Files.createDirectories(tempDir.resolve("mymodule"));
        Files.writeString(subDir.resolve("CLAUDE.md"), "No heading here, just content.");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertEquals("mymodule", results.get(0).domainName());
    }

    @Test
    void empty_project_returns_empty_list() {
        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());
        assertTrue(results.isEmpty());
    }

    @Test
    void null_path_returns_empty_list() {
        assertTrue(scanner.scanProject(null).isEmpty());
    }

    @Test
    void blank_path_returns_empty_list() {
        assertTrue(scanner.scanProject("   ").isEmpty());
    }

    @Test
    void nonexistent_path_returns_empty_list() {
        assertTrue(scanner.scanProject(tempDir.resolve("nonexistent").toString()).isEmpty());
    }

    @Test
    void readFileContent_returns_content() throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.writeString(file, "Hello World");

        assertEquals("Hello World", scanner.readFileContent(file));
    }

    @Test
    void readFileContent_returns_empty_on_missing_file() {
        Path missing = tempDir.resolve("missing.md");
        assertEquals("", scanner.readFileContent(missing));
    }

    @Test
    void relative_paths_use_forward_slashes() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("a/b"));
        Files.writeString(sub.resolve("CLAUDE.md"), "# Test");

        List<DomainClaudeMd> results = scanner.scanProject(tempDir.toString());

        assertEquals(1, results.size());
        assertFalse(results.get(0).relativePath().contains("\\"),
                "Relative path should use forward slashes");
        assertEquals("a/b/CLAUDE.md", results.get(0).relativePath());
    }
}
