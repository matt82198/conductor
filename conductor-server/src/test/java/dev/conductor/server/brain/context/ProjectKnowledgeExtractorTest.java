package dev.conductor.server.brain.context;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.conductor.server.brain.BrainProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProjectKnowledgeExtractor}.
 * Validates file discovery, source reading, and graceful fallback when no API key is set.
 */
class ProjectKnowledgeExtractorTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private ClaudeMdScanner claudeMdScanner;
    private BrainProperties noKeyProperties;
    private ProjectKnowledgeExtractor extractor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        claudeMdScanner = new ClaudeMdScanner();

        // Properties with no API key — ensures no real API calls
        noKeyProperties = new BrainProperties(
                true, "", "claude-sonnet-4-6", 0.8, 10,
                tempDir.resolve("behavior-log.jsonl").toString(), 100000
        );
        extractor = new ProjectKnowledgeExtractor(claudeMdScanner, noKeyProperties, objectMapper);
    }

    // ─── discoverKeySourceFiles ───────────────────────────────────────

    @Test
    void discoverKeySourceFiles_findsPomXml() throws IOException {
        // pom.xml is a build file, not a "source" file — but application.yml IS discovered
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Path resources = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.writeString(resources.resolve("application.yml"), "server.port: 8080");

        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());

        assertTrue(files.stream().anyMatch(f -> f.contains("application.yml")),
                "Should discover application.yml as a config file");
    }

    @Test
    void discoverKeySourceFiles_findsApplicationClass() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("FooApplication.java"),
                "package com.example;\npublic class FooApplication {}");

        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());

        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("FooApplication.java"));
    }

    @Test
    void discoverKeySourceFiles_findsServiceClasses() throws IOException {
        Path serviceDir = Files.createDirectories(tempDir.resolve("src/main/java/com/example/service"));
        Files.writeString(serviceDir.resolve("UserService.java"),
                "package com.example.service;\npublic class UserService {}");
        Files.writeString(serviceDir.resolve("OrderService.java"),
                "package com.example.service;\npublic class OrderService {}");

        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());

        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> f.contains("UserService.java")));
        assertTrue(files.stream().anyMatch(f -> f.contains("OrderService.java")));
    }

    @Test
    void discoverKeySourceFiles_findsControllers() throws IOException {
        Path apiDir = Files.createDirectories(tempDir.resolve("src/main/java/com/example/api"));
        Files.writeString(apiDir.resolve("UserController.java"),
                "package com.example.api;\npublic class UserController {}");

        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());

        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("UserController.java"));
    }

    @Test
    void discoverKeySourceFiles_capsAtMaxFiles() throws IOException {
        // Create more than 10 service files
        Path serviceDir = Files.createDirectories(tempDir.resolve("src/main/java/service"));
        for (int i = 0; i < 15; i++) {
            Files.writeString(serviceDir.resolve("Service" + i + ".java"),
                    "public class Service" + i + " {}");
        }

        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());

        assertTrue(files.size() <= 10, "Should cap at 10 files, got " + files.size());
    }

    @Test
    void discoverKeySourceFiles_skipsGitDir() throws IOException {
        Path gitDir = Files.createDirectories(tempDir.resolve(".git/hooks"));
        Files.writeString(gitDir.resolve("FooApplication.java"), "should be skipped");
        Path src = Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(src.resolve("BarApplication.java"), "public class BarApplication {}");

        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());

        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("BarApplication.java"));
    }

    @Test
    void discoverKeySourceFiles_emptyDir_returnsEmpty() {
        List<String> files = extractor.discoverKeySourceFiles(tempDir.toString());
        assertTrue(files.isEmpty());
    }

    @Test
    void discoverKeySourceFiles_nonexistentDir_returnsEmpty() {
        List<String> files = extractor.discoverKeySourceFiles(tempDir.resolve("nonexistent").toString());
        assertTrue(files.isEmpty());
    }

    // ─── readBuildFile ────────────────────────────────────────────────

    @Test
    void readBuildFile_readsPomXml() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project>test</project>");

        String content = extractor.readBuildFile(tempDir.toString());

        assertEquals("<project>test</project>", content);
    }

    @Test
    void readBuildFile_prefersFirstMatch() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "pom-content");
        Files.writeString(tempDir.resolve("package.json"), "pkg-content");

        String content = extractor.readBuildFile(tempDir.toString());

        assertEquals("pom-content", content, "pom.xml should be preferred over package.json");
    }

    @Test
    void readBuildFile_readsPackageJson() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{\"name\":\"test\"}");

        String content = extractor.readBuildFile(tempDir.toString());

        assertEquals("{\"name\":\"test\"}", content);
    }

    @Test
    void readBuildFile_noBuildFile_returnsEmpty() {
        String content = extractor.readBuildFile(tempDir.toString());
        assertEquals("", content);
    }

    // ─── readSourceSamples ────────────────────────────────────────────

    @Test
    void readSourceSamples_addsFileHeaders() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(src.resolve("Main.java"), "public class Main {}");

        String samples = extractor.readSourceSamples(tempDir.toString(), List.of("src/Main.java"));

        assertTrue(samples.contains("--- FILE: src/Main.java ---"));
        assertTrue(samples.contains("public class Main {}"));
    }

    @Test
    void readSourceSamples_missingFiles_skipped() {
        String samples = extractor.readSourceSamples(tempDir.toString(), List.of("missing/File.java"));
        assertEquals("", samples);
    }

    // ─── analyze ──────────────────────────────────────────────────────

    @Test
    void analyze_noApiKey_returnsMinimalKnowledge() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><dependencies><dependency>spring-boot</dependency></dependencies></project>");

        ProjectKnowledge knowledge = extractor.analyze(
                tempDir.toString(), "proj-001", "test-project"
        );

        assertNotNull(knowledge);
        assertEquals("proj-001", knowledge.projectId());
        assertEquals("test-project", knowledge.projectName());
        assertEquals(tempDir.toString(), knowledge.projectPath());
        assertFalse(knowledge.techStack().isBlank(), "Should detect tech stack from build file");
        assertTrue(knowledge.techStack().contains("Maven"));
        assertTrue(knowledge.patterns().isEmpty(), "No patterns without API");
        assertNotNull(knowledge.analyzedAt());
    }

    @Test
    void analyze_noApiKey_detectsNodeStack() throws IOException {
        Files.writeString(tempDir.resolve("package.json"),
                "{\"dependencies\":{\"react\":\"^19.0.0\",\"typescript\":\"^5.0\"}}");

        ProjectKnowledge knowledge = extractor.analyze(
                tempDir.toString(), "proj-002", "react-app"
        );

        assertTrue(knowledge.techStack().contains("Node.js"));
        assertTrue(knowledge.techStack().contains("React"));
        assertTrue(knowledge.techStack().contains("TypeScript"));
    }

    // ─── parseAnalysis ────────────────────────────────────────────────

    @Test
    void parseAnalysis_validJson() {
        String json = """
                {
                  "techStack": "Java 21, Spring Boot",
                  "architectureSummary": "Event-driven Spring Boot app.",
                  "patterns": [
                    {
                      "name": "RestClient",
                      "description": "Uses Spring RestClient for API calls",
                      "sourceFile": "Client.java",
                      "tags": ["api", "rest"]
                    }
                  ],
                  "keyFiles": ["pom.xml", "App.java"]
                }
                """;

        ProjectKnowledge knowledge = extractor.parseAnalysis(json, "id-1", "test", "/path");

        assertEquals("Java 21, Spring Boot", knowledge.techStack());
        assertEquals("Event-driven Spring Boot app.", knowledge.architectureSummary());
        assertEquals(1, knowledge.patterns().size());
        assertEquals("RestClient", knowledge.patterns().get(0).name());
        assertEquals(2, knowledge.patterns().get(0).tags().size());
        assertEquals(2, knowledge.keyFiles().size());
    }

    @Test
    void parseAnalysis_invalidJson_returnsEmptyKnowledge() {
        ProjectKnowledge knowledge = extractor.parseAnalysis(
                "not valid json", "id-1", "test", "/path"
        );

        assertNotNull(knowledge);
        assertEquals("id-1", knowledge.projectId());
        assertTrue(knowledge.patterns().isEmpty());
    }

    // ─── buildAnalysisPrompt ──────────────────────────────────────────

    @Test
    void buildAnalysisPrompt_includesAllSections() {
        List<DomainClaudeMd> claudeMds = List.of(
                new DomainClaudeMd("CLAUDE.md", "# Root\nProject docs.", "Root")
        );

        String prompt = extractor.buildAnalysisPrompt(
                "my-project", "<project>pom</project>", claudeMds, "source code here"
        );

        assertTrue(prompt.contains("my-project"));
        assertTrue(prompt.contains("<project>pom</project>"));
        assertTrue(prompt.contains("# Root"));
        assertTrue(prompt.contains("source code here"));
        assertTrue(prompt.contains("JSON format"));
    }

    @Test
    void buildAnalysisPrompt_emptyInputs() {
        String prompt = extractor.buildAnalysisPrompt("empty-project", "", List.of(), "");

        assertTrue(prompt.contains("empty-project"));
        assertTrue(prompt.contains("JSON format"));
        assertFalse(prompt.contains("BUILD FILE:"));
        assertFalse(prompt.contains("CLAUDE.MD FILES:"));
        assertFalse(prompt.contains("KEY SOURCE FILES:"));
    }
}
