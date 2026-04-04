package dev.conductor.server.brain.context;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProjectKnowledgeStore}.
 * Validates save/load roundtrips, multi-project loading, deletion, and prompt rendering.
 */
class ProjectKnowledgeStoreTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private ProjectKnowledgeStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        store = new ProjectKnowledgeStore(objectMapper, tempDir);
    }

    @Test
    void save_and_load_roundtrip() {
        ProjectKnowledge knowledge = new ProjectKnowledge(
                "proj-001", "my-project", "/path/to/project",
                "Java 21, Spring Boot 3.4, Maven",
                List.of(
                        new ProjectKnowledge.PatternEntry(
                                "Event-driven architecture",
                                "Uses Spring ApplicationEventPublisher for domain events",
                                "src/main/java/EventPublisher.java",
                                List.of("events", "spring", "architecture")
                        )
                ),
                List.of("pom.xml", "src/main/java/App.java"),
                "A Spring Boot application using event-driven architecture.",
                Instant.parse("2026-04-01T12:00:00Z")
        );

        store.save(knowledge);
        Optional<ProjectKnowledge> loaded = store.load("proj-001");

        assertTrue(loaded.isPresent());
        ProjectKnowledge result = loaded.get();
        assertEquals("proj-001", result.projectId());
        assertEquals("my-project", result.projectName());
        assertEquals("/path/to/project", result.projectPath());
        assertEquals("Java 21, Spring Boot 3.4, Maven", result.techStack());
        assertEquals(1, result.patterns().size());
        assertEquals("Event-driven architecture", result.patterns().get(0).name());
        assertEquals(3, result.patterns().get(0).tags().size());
        assertEquals(2, result.keyFiles().size());
        assertEquals("A Spring Boot application using event-driven architecture.", result.architectureSummary());
        assertEquals(Instant.parse("2026-04-01T12:00:00Z"), result.analyzedAt());
    }

    @Test
    void load_nonexistent_returnsEmpty() {
        Optional<ProjectKnowledge> loaded = store.load("nonexistent-id");
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadAll_multipleProjects() {
        store.save(new ProjectKnowledge(
                "proj-001", "project-alpha", "/alpha",
                "Java", List.of(), List.of(), "Alpha project", null
        ));
        store.save(new ProjectKnowledge(
                "proj-002", "project-beta", "/beta",
                "Python", List.of(), List.of(), "Beta project", null
        ));

        List<ProjectKnowledge> all = store.loadAll();

        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(k -> "proj-001".equals(k.projectId())));
        assertTrue(all.stream().anyMatch(k -> "proj-002".equals(k.projectId())));
    }

    @Test
    void hasKnowledge_true_and_false() {
        assertFalse(store.hasKnowledge("proj-001"));

        store.save(new ProjectKnowledge(
                "proj-001", "my-project", "/path",
                "Java", List.of(), List.of(), "", null
        ));

        assertTrue(store.hasKnowledge("proj-001"));
        assertFalse(store.hasKnowledge("proj-999"));
    }

    @Test
    void delete_removes_file() {
        store.save(new ProjectKnowledge(
                "proj-001", "my-project", "/path",
                "Java", List.of(), List.of(), "", null
        ));

        assertTrue(store.hasKnowledge("proj-001"));
        assertTrue(store.delete("proj-001"));
        assertFalse(store.hasKnowledge("proj-001"));
    }

    @Test
    void delete_nonexistent_returnsFalse() {
        assertFalse(store.delete("nonexistent"));
    }

    @Test
    void renderForPrompt_includesPatterns() {
        store.save(new ProjectKnowledge(
                "proj-001", "conductor", "/conductor",
                "Java 21, Spring Boot",
                List.of(
                        new ProjectKnowledge.PatternEntry(
                                "RestClient pattern",
                                "Uses Spring RestClient for API calls",
                                "BrainApiClient.java",
                                List.of("api", "rest")
                        ),
                        new ProjectKnowledge.PatternEntry(
                                "Event bus",
                                "Spring ApplicationEventPublisher for domain events",
                                "EventPublisher.java",
                                List.of("events")
                        )
                ),
                List.of("pom.xml"),
                "Event-driven orchestration platform.",
                null
        ));

        String rendered = store.renderForPrompt(10000);

        assertFalse(rendered.isBlank());
        assertTrue(rendered.contains("conductor"));
        assertTrue(rendered.contains("Java 21, Spring Boot"));
        assertTrue(rendered.contains("RestClient pattern"));
        assertTrue(rendered.contains("Event bus"));
        assertTrue(rendered.contains("Event-driven orchestration platform."));
        assertTrue(rendered.contains("pom.xml"));
    }

    @Test
    void renderForPrompt_respectsMaxChars() {
        store.save(new ProjectKnowledge(
                "proj-001", "big-project", "/big",
                "Java 21, Spring Boot, Maven, React, TypeScript, Electron",
                List.of(
                        new ProjectKnowledge.PatternEntry(
                                "A very long pattern name that takes up space",
                                "A very long description that also takes up a lot of space in the rendered output",
                                "some/very/long/path/to/implementation.java",
                                List.of("tag1", "tag2", "tag3")
                        )
                ),
                List.of("file1.java", "file2.java", "file3.java"),
                "A large and complex architecture with many moving parts.",
                null
        ));

        String rendered = store.renderForPrompt(100);

        assertTrue(rendered.length() <= 100);
    }

    @Test
    void renderForPrompt_emptyStore_returnsEmpty() {
        String rendered = store.renderForPrompt(10000);
        assertEquals("", rendered);
    }

    @Test
    void save_createsDirectories() {
        Path nested = tempDir.resolve("a/b/c");
        ProjectKnowledgeStore nestedStore = new ProjectKnowledgeStore(objectMapper, nested);

        nestedStore.save(new ProjectKnowledge(
                "proj-001", "my-project", "/path",
                "Java", List.of(), List.of(), "", null
        ));

        assertTrue(Files.isDirectory(nested));
        assertTrue(nestedStore.hasKnowledge("proj-001"));
    }

    @Test
    void save_overwrites_existing() {
        store.save(new ProjectKnowledge(
                "proj-001", "my-project", "/path",
                "Java", List.of(), List.of(), "v1", null
        ));

        store.save(new ProjectKnowledge(
                "proj-001", "my-project", "/path",
                "Java 21, Spring Boot", List.of(), List.of(), "v2", null
        ));

        Optional<ProjectKnowledge> loaded = store.load("proj-001");
        assertTrue(loaded.isPresent());
        assertEquals("Java 21, Spring Boot", loaded.get().techStack());
        assertEquals("v2", loaded.get().architectureSummary());
    }

    @Test
    void loadAll_emptyDir_returnsEmptyList() {
        List<ProjectKnowledge> all = store.loadAll();
        assertTrue(all.isEmpty());
    }
}
