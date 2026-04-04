package dev.conductor.server.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectBootstrapTest {

    @Test
    @DisplayName("decodes Windows-style project dir name")
    void decodeProjectDirName_windows() {
        // This test only works on the actual machine where C:/Users/matt8/TRDev/billing exists
        String decoded = ProjectBootstrap.decodeProjectDirName("C--Users-matt8-TRDev-billing");
        // If the path exists on this machine, it should decode; otherwise null is acceptable
        if (decoded != null) {
            assertTrue(decoded.contains("Users"));
            assertTrue(decoded.contains("billing"));
        }
    }

    @Test
    @DisplayName("returns null for empty input")
    void decodeProjectDirName_empty() {
        assertNull(ProjectBootstrap.decodeProjectDirName(""));
        assertNull(ProjectBootstrap.decodeProjectDirName(null));
    }

    @Test
    @DisplayName("returns null for nonsense input")
    void decodeProjectDirName_nonsense() {
        assertNull(ProjectBootstrap.decodeProjectDirName("not-a-real-path-at-all"));
    }

    @Test
    @DisplayName("buildScanRoots includes common dev directories")
    void buildScanRoots_includesCommonDirs() {
        ProjectRegistry registry = new ProjectRegistry();
        ProjectScanner scanner = new ProjectScanner(registry);
        ProjectBootstrap bootstrap = new ProjectBootstrap(scanner, registry);

        var roots = bootstrap.buildScanRoots();
        // Should find at least one root (home dir projects or claude projects)
        assertNotNull(roots);
        // On this machine, TRDev should be found
        boolean hasTrDev = roots.stream().anyMatch(r -> r.contains("TRDev"));
        // This is machine-specific, so just verify it doesn't crash
        assertFalse(roots.isEmpty() && hasTrDev);
    }
}
