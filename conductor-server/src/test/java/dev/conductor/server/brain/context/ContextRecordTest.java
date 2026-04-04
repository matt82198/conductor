package dev.conductor.server.brain.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the context record types: {@link ContextIndex}, {@link ProjectContext},
 * {@link DomainClaudeMd}, and {@link GlobalContext}.
 * Validates compact constructor defaults and accessor behavior.
 */
class ContextRecordTest {

    // ─── ContextIndex ────────────────────────────────────────────────

    @Test
    @DisplayName("ContextIndex: null projects defaults to empty list")
    void contextIndex_nullProjects_defaultsToEmptyList() {
        ContextIndex index = new ContextIndex(null, null, null);
        assertNotNull(index.projects());
        assertTrue(index.projects().isEmpty());
    }

    @Test
    @DisplayName("ContextIndex: null lastUpdated defaults to Instant.now()")
    void contextIndex_nullLastUpdated_defaultsToNow() {
        Instant before = Instant.now();
        ContextIndex index = new ContextIndex(List.of(), null, null);
        Instant after = Instant.now();

        assertNotNull(index.lastUpdated());
        assertFalse(index.lastUpdated().isBefore(before));
        assertFalse(index.lastUpdated().isAfter(after));
    }

    @Test
    @DisplayName("ContextIndex: projectCount() returns correct size")
    void contextIndex_projectCount_returnsCorrectSize() {
        List<ProjectContext> projects = List.of(
                new ProjectContext("id1", "proj1", "/path1", null, List.of(), Instant.now()),
                new ProjectContext("id2", "proj2", "/path2", null, List.of(), Instant.now())
        );
        ContextIndex index = new ContextIndex(projects, null, Instant.now());
        assertEquals(2, index.projectCount());
    }

    @Test
    @DisplayName("ContextIndex: empty index has projectCount() of 0")
    void contextIndex_emptyIndex_projectCountZero() {
        ContextIndex index = new ContextIndex(List.of(), null, Instant.now());
        assertEquals(0, index.projectCount());
    }

    // ─── ProjectContext ──────────────────────────────────────────────

    @Test
    @DisplayName("ProjectContext: null domainClaudeMds defaults to empty list")
    void projectContext_nullDomainDocs_defaultsToEmptyList() {
        ProjectContext ctx = new ProjectContext(
                "id", "name", "/path", "# Root", null, null
        );
        assertNotNull(ctx.domainClaudeMds());
        assertTrue(ctx.domainClaudeMds().isEmpty());
    }

    @Test
    @DisplayName("ProjectContext: null lastScannedAt defaults to Instant.now()")
    void projectContext_nullLastScanned_defaultsToNow() {
        Instant before = Instant.now();
        ProjectContext ctx = new ProjectContext(
                "id", "name", "/path", null, List.of(), null
        );
        Instant after = Instant.now();

        assertNotNull(ctx.lastScannedAt());
        assertFalse(ctx.lastScannedAt().isBefore(before));
        assertFalse(ctx.lastScannedAt().isAfter(after));
    }

    @Test
    @DisplayName("ProjectContext: all fields preserved when non-null")
    void projectContext_allFieldsPreserved() {
        Instant ts = Instant.parse("2026-04-01T12:00:00Z");
        List<DomainClaudeMd> docs = List.of(
                new DomainClaudeMd("queue/CLAUDE.md", "# queue", "queue")
        );
        ProjectContext ctx = new ProjectContext(
                "proj-1", "my-project", "/home/project", "# Root Content", docs, ts
        );

        assertEquals("proj-1", ctx.projectId());
        assertEquals("my-project", ctx.projectName());
        assertEquals("/home/project", ctx.projectPath());
        assertEquals("# Root Content", ctx.rootClaudeMd());
        assertEquals(1, ctx.domainClaudeMds().size());
        assertEquals(ts, ctx.lastScannedAt());
    }

    // ─── DomainClaudeMd ─────────────────────────────────────────────

    @Test
    @DisplayName("DomainClaudeMd: null relativePath defaults to empty string")
    void domainClaudeMd_nullRelativePath_defaultsToEmpty() {
        DomainClaudeMd doc = new DomainClaudeMd(null, "content", "name");
        assertEquals("", doc.relativePath());
    }

    @Test
    @DisplayName("DomainClaudeMd: null content defaults to empty string")
    void domainClaudeMd_nullContent_defaultsToEmpty() {
        DomainClaudeMd doc = new DomainClaudeMd("path/CLAUDE.md", null, "name");
        assertEquals("", doc.content());
    }

    @Test
    @DisplayName("DomainClaudeMd: null domainName defaults to 'unknown'")
    void domainClaudeMd_nullDomainName_defaultsToUnknown() {
        DomainClaudeMd doc = new DomainClaudeMd("path/CLAUDE.md", "content", null);
        assertEquals("unknown", doc.domainName());
    }

    @Test
    @DisplayName("DomainClaudeMd: all fields preserved when non-null")
    void domainClaudeMd_allFieldsPreserved() {
        DomainClaudeMd doc = new DomainClaudeMd(
                "server/queue/CLAUDE.md", "# queue/ docs", "queue/"
        );
        assertEquals("server/queue/CLAUDE.md", doc.relativePath());
        assertEquals("# queue/ docs", doc.content());
        assertEquals("queue/", doc.domainName());
    }

    // ─── GlobalContext ───────────────────────────────────────────────

    @Test
    @DisplayName("GlobalContext: null memoryFileSummaries defaults to empty list")
    void globalContext_nullMemories_defaultsToEmptyList() {
        GlobalContext ctx = new GlobalContext("# Global", "/home/.claude/projects", null, null);
        assertNotNull(ctx.memoryFileSummaries());
        assertTrue(ctx.memoryFileSummaries().isEmpty());
    }

    @Test
    @DisplayName("GlobalContext: null scannedAt defaults to Instant.now()")
    void globalContext_nullScannedAt_defaultsToNow() {
        Instant before = Instant.now();
        GlobalContext ctx = new GlobalContext(null, null, List.of(), null);
        Instant after = Instant.now();

        assertNotNull(ctx.scannedAt());
        assertFalse(ctx.scannedAt().isBefore(before));
        assertFalse(ctx.scannedAt().isAfter(after));
    }

    @Test
    @DisplayName("GlobalContext: all fields preserved when non-null")
    void globalContext_allFieldsPreserved() {
        Instant ts = Instant.parse("2026-04-01T12:00:00Z");
        List<String> memories = List.of("proj1: Memory summary", "proj2: Other summary");
        GlobalContext ctx = new GlobalContext("# Global Config", "/home/.claude/projects", memories, ts);

        assertEquals("# Global Config", ctx.globalClaudeMd());
        assertEquals("/home/.claude/projects", ctx.userMemoryDir());
        assertEquals(2, ctx.memoryFileSummaries().size());
        assertEquals(ts, ctx.scannedAt());
    }

    @Test
    @DisplayName("GlobalContext: nullable globalClaudeMd is allowed")
    void globalContext_nullGlobalClaudeMd_allowed() {
        GlobalContext ctx = new GlobalContext(null, null, List.of(), Instant.now());
        assertNull(ctx.globalClaudeMd());
    }
}
