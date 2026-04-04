package dev.conductor.server.agent;

import dev.conductor.common.AgentRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentTemplateRegistry}.
 * Uses @TempDir to isolate file persistence from the real ~/.conductor directory.
 */
class AgentTemplateRegistryTest {

    @TempDir
    Path tempDir;

    private AgentTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentTemplateRegistry(tempDir.resolve("agent-templates.json"));
        // init() would seed defaults since file doesn't exist — we call it explicitly
        registry.init();
    }

    // ─── Seed Defaults ────────────────────────────────────────────────

    @Test
    void seedDefaults_creates5Templates() {
        // init() seeds defaults because the file doesn't exist yet
        assertEquals(5, registry.size());
    }

    @Test
    void seedDefaults_containsExpectedNames() {
        List<AgentTemplate> all = registry.listAll();
        List<String> names = all.stream().map(AgentTemplate::name).toList();
        assertTrue(names.contains("Test Writer"));
        assertTrue(names.contains("Code Reviewer"));
        assertTrue(names.contains("Feature Builder"));
        assertTrue(names.contains("Codebase Explorer"));
        assertTrue(names.contains("Refactoring Agent"));
    }

    @Test
    void seedDefaults_assignsCorrectRoles() {
        List<AgentTemplate> all = registry.listAll();
        AgentTemplate testWriter = all.stream()
                .filter(t -> "Test Writer".equals(t.name())).findFirst().orElseThrow();
        assertEquals(AgentRole.TESTER, testWriter.role());

        AgentTemplate reviewer = all.stream()
                .filter(t -> "Code Reviewer".equals(t.name())).findFirst().orElseThrow();
        assertEquals(AgentRole.REVIEWER, reviewer.role());
    }

    // ─── Save & Get ───────────────────────────────────────────────────

    @Test
    void save_and_get_roundtrip() {
        AgentTemplate template = new AgentTemplate(
                "test-id", "My Template", "Does stuff", AgentRole.GENERAL,
                "Do {thing}", List.of("custom"), 0, null, null);
        registry.save(template);

        Optional<AgentTemplate> retrieved = registry.get("test-id");
        assertTrue(retrieved.isPresent());
        assertEquals("My Template", retrieved.get().name());
        assertEquals("Does stuff", retrieved.get().description());
        assertEquals(AgentRole.GENERAL, retrieved.get().role());
        assertEquals("Do {thing}", retrieved.get().defaultPrompt());
        assertEquals(List.of("custom"), retrieved.get().tags());
    }

    @Test
    void save_overwritesExisting() {
        AgentTemplate v1 = new AgentTemplate(
                "overwrite-id", "Version 1", "v1", AgentRole.GENERAL,
                "prompt v1", List.of(), 0, null, null);
        registry.save(v1);

        AgentTemplate v2 = new AgentTemplate(
                "overwrite-id", "Version 2", "v2", AgentRole.TESTER,
                "prompt v2", List.of("updated"), 0, null, null);
        registry.save(v2);

        Optional<AgentTemplate> retrieved = registry.get("overwrite-id");
        assertTrue(retrieved.isPresent());
        assertEquals("Version 2", retrieved.get().name());
        assertEquals(AgentRole.TESTER, retrieved.get().role());
    }

    @Test
    void get_returnsEmptyForUnknownId() {
        Optional<AgentTemplate> result = registry.get("nonexistent-id");
        assertTrue(result.isEmpty());
    }

    @Test
    void save_rejectsNullTemplate() {
        assertThrows(NullPointerException.class, () -> registry.save(null));
    }

    // ─── List ─────────────────────────────────────────────────────────

    @Test
    void listAll_sortedByUsageDescending() {
        // Clear defaults and add controlled data
        AgentTemplateRegistry fresh = new AgentTemplateRegistry(tempDir.resolve("sorted-test.json"));

        AgentTemplate low = new AgentTemplate("low", "Low Usage", "desc", AgentRole.GENERAL,
                "prompt", List.of(), 1, null, null);
        AgentTemplate high = new AgentTemplate("high", "High Usage", "desc", AgentRole.GENERAL,
                "prompt", List.of(), 10, null, null);
        AgentTemplate mid = new AgentTemplate("mid", "Mid Usage", "desc", AgentRole.GENERAL,
                "prompt", List.of(), 5, null, null);

        fresh.save(low);
        fresh.save(high);
        fresh.save(mid);

        List<AgentTemplate> sorted = fresh.listAll();
        assertEquals(3, sorted.size());
        assertEquals("High Usage", sorted.get(0).name());
        assertEquals("Mid Usage", sorted.get(1).name());
        assertEquals("Low Usage", sorted.get(2).name());
    }

    // ─── Search ───────────────────────────────────────────────────────

    @Test
    void search_byName() {
        List<AgentTemplate> results = registry.search("Test Writer");
        assertEquals(1, results.size());
        assertEquals("Test Writer", results.get(0).name());
    }

    @Test
    void search_byTag() {
        List<AgentTemplate> results = registry.search("security");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(t -> t.name().equals("Code Reviewer")));
    }

    @Test
    void search_byDescription() {
        List<AgentTemplate> results = registry.search("readability");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(t -> t.name().equals("Refactoring Agent")));
    }

    @Test
    void search_caseInsensitive() {
        List<AgentTemplate> results = registry.search("TEST WRITER");
        assertEquals(1, results.size());
        assertEquals("Test Writer", results.get(0).name());
    }

    @Test
    void search_byRole() {
        List<AgentTemplate> results = registry.search("tester");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(t -> t.role() == AgentRole.TESTER));
    }

    @Test
    void search_emptyQueryReturnsAll() {
        List<AgentTemplate> results = registry.search("");
        assertEquals(5, results.size());
    }

    @Test
    void search_nullQueryReturnsAll() {
        List<AgentTemplate> results = registry.search(null);
        assertEquals(5, results.size());
    }

    // ─── Delete ───────────────────────────────────────────────────────

    @Test
    void delete_removesTemplate() {
        AgentTemplate template = new AgentTemplate(
                "delete-me", "To Delete", "desc", AgentRole.GENERAL,
                "prompt", List.of(), 0, null, null);
        registry.save(template);

        int sizeBefore = registry.size();
        boolean deleted = registry.delete("delete-me");
        assertTrue(deleted);
        assertEquals(sizeBefore - 1, registry.size());
        assertTrue(registry.get("delete-me").isEmpty());
    }

    @Test
    void delete_returnsFalseForUnknownId() {
        boolean deleted = registry.delete("nonexistent-id");
        assertFalse(deleted);
    }

    // ─── Record Usage ─────────────────────────────────────────────────

    @Test
    void recordUsage_incrementsCount() {
        AgentTemplate template = new AgentTemplate(
                "usage-id", "Usage Test", "desc", AgentRole.GENERAL,
                "prompt", List.of(), 0, null, null);
        registry.save(template);

        AgentTemplate updated = registry.recordUsage("usage-id");
        assertEquals(1, updated.usageCount());
        assertNotNull(updated.lastUsedAt());

        updated = registry.recordUsage("usage-id");
        assertEquals(2, updated.usageCount());
    }

    @Test
    void recordUsage_throwsForUnknownId() {
        assertThrows(NoSuchElementException.class, () -> registry.recordUsage("nonexistent"));
    }

    // ─── Persistence ──────────────────────────────────────────────────

    @Test
    void persistence_survives_reload() {
        // Save a custom template
        AgentTemplate custom = new AgentTemplate(
                "persist-id", "Persistent Template", "survives reload", AgentRole.EXPLORER,
                "explore {thing}", List.of("persistence", "test"), 7, null, Instant.now());
        registry.save(custom);

        // Create a new registry pointing to the same file
        AgentTemplateRegistry reloaded = new AgentTemplateRegistry(tempDir.resolve("agent-templates.json"));
        reloaded.init();

        // The custom template and seeded defaults should all be present
        Optional<AgentTemplate> retrieved = reloaded.get("persist-id");
        assertTrue(retrieved.isPresent());
        assertEquals("Persistent Template", retrieved.get().name());
        assertEquals("survives reload", retrieved.get().description());
        assertEquals(AgentRole.EXPLORER, retrieved.get().role());
        assertEquals("explore {thing}", retrieved.get().defaultPrompt());
        assertEquals(List.of("persistence", "test"), retrieved.get().tags());
        assertEquals(7, retrieved.get().usageCount());
    }

    @Test
    void persistence_deleteSurvivesReload() {
        // Get one of the seeded template IDs
        String templateId = registry.listAll().get(0).templateId();

        // Delete it
        assertTrue(registry.delete(templateId));

        // Reload
        AgentTemplateRegistry reloaded = new AgentTemplateRegistry(tempDir.resolve("agent-templates.json"));
        reloaded.init();

        assertTrue(reloaded.get(templateId).isEmpty());
        assertEquals(4, reloaded.size());
    }

    // ─── Record (AgentTemplate) ───────────────────────────────────────

    @Test
    void agentTemplate_autoGeneratesIdWhenNull() {
        AgentTemplate template = new AgentTemplate(
                null, "Auto ID", "desc", AgentRole.GENERAL,
                "prompt", null, 0, null, null);
        assertNotNull(template.templateId());
        assertFalse(template.templateId().isBlank());
    }

    @Test
    void agentTemplate_defaultsNullTagsToEmptyList() {
        AgentTemplate template = new AgentTemplate(
                "id", "Name", "desc", AgentRole.GENERAL,
                "prompt", null, 0, null, null);
        assertNotNull(template.tags());
        assertTrue(template.tags().isEmpty());
    }

    @Test
    void agentTemplate_defaultsNullCreatedAtToNow() {
        Instant before = Instant.now();
        AgentTemplate template = new AgentTemplate(
                "id", "Name", "desc", AgentRole.GENERAL,
                "prompt", List.of(), 0, null, null);
        assertNotNull(template.createdAt());
        assertFalse(template.createdAt().isBefore(before));
    }

    @Test
    void agentTemplate_withIncrementedUsage() {
        AgentTemplate template = new AgentTemplate(
                "id", "Name", "desc", AgentRole.GENERAL,
                "prompt", List.of("tag"), 5, Instant.now(), null);

        AgentTemplate incremented = template.withIncrementedUsage();
        assertEquals(6, incremented.usageCount());
        assertNotNull(incremented.lastUsedAt());
        // Original unchanged
        assertEquals(5, template.usageCount());
        // Other fields preserved
        assertEquals("id", incremented.templateId());
        assertEquals("Name", incremented.name());
        assertEquals(List.of("tag"), incremented.tags());
    }

    @Test
    void agentTemplate_withUpdatedFields() {
        Instant created = Instant.now();
        AgentTemplate template = new AgentTemplate(
                "id", "Old Name", "old desc", AgentRole.GENERAL,
                "old prompt", List.of("old"), 3, created, Instant.now());

        AgentTemplate updated = template.withUpdatedFields(
                "New Name", "new desc", AgentRole.TESTER, "new prompt", List.of("new"));

        assertEquals("id", updated.templateId());
        assertEquals("New Name", updated.name());
        assertEquals("new desc", updated.description());
        assertEquals(AgentRole.TESTER, updated.role());
        assertEquals("new prompt", updated.defaultPrompt());
        assertEquals(List.of("new"), updated.tags());
        assertEquals(3, updated.usageCount());  // preserved
        assertEquals(created, updated.createdAt());  // preserved
    }
}
