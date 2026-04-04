package dev.conductor.server.api;

import dev.conductor.server.project.ProjectRecord;
import dev.conductor.server.project.ProjectRegistry;
import dev.conductor.server.project.ProjectScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProjectController}.
 *
 * <p>Uses real {@link ProjectRegistry} and {@link ProjectScanner} backed
 * by a temporary directory to avoid Mockito issues on JDK 25+ while
 * still exercising real behavior.
 */
class ProjectControllerTest {

    private ProjectRegistry registry;
    private ProjectScanner scanner;
    private ProjectController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        registry = new ProjectRegistry();
        scanner = new ProjectScanner(registry);
        controller = new ProjectController(registry, scanner);
    }

    // ─── GET /projects ────────────────────────────────────────────────

    @Test
    @DisplayName("GET projects returns all registered projects")
    void listProjects() throws Exception {
        Path project1 = Files.createDirectory(tempDir.resolve("project1"));
        Files.createDirectory(project1.resolve(".git"));
        Path project2 = Files.createDirectory(tempDir.resolve("project2"));
        Files.createDirectory(project2.resolve(".git"));

        registry.register(project1.toString());
        registry.register(project2.toString());

        Collection<ProjectRecord> result = controller.listProjects();

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("GET projects returns empty when no projects registered")
    void listProjectsEmpty() {
        Collection<ProjectRecord> result = controller.listProjects();

        assertTrue(result.isEmpty());
    }

    // ─── POST /projects/register ──────────────────────────────────────

    @Test
    @DisplayName("POST register returns 201 with ProjectRecord")
    void registerSuccess() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("myapp"));
        Files.createDirectory(project.resolve(".git"));

        ResponseEntity<?> response = controller.registerProject(
                new ProjectController.RegisterRequest(project.toString()));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertInstanceOf(ProjectRecord.class, response.getBody());
        ProjectRecord record = (ProjectRecord) response.getBody();
        assertEquals("myapp", record.name());
    }

    @Test
    @DisplayName("POST register returns 400 on nonexistent path")
    void registerInvalidPath() {
        ResponseEntity<?> response = controller.registerProject(
                new ProjectController.RegisterRequest(tempDir.resolve("nonexistent").toString()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("POST register is idempotent — same path returns same record")
    void registerIdempotent() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("myapp"));
        Files.createDirectory(project.resolve(".git"));

        ResponseEntity<?> first = controller.registerProject(
                new ProjectController.RegisterRequest(project.toString()));
        ResponseEntity<?> second = controller.registerProject(
                new ProjectController.RegisterRequest(project.toString()));

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.CREATED, second.getStatusCode());
        ProjectRecord r1 = (ProjectRecord) first.getBody();
        ProjectRecord r2 = (ProjectRecord) second.getBody();
        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(r1.id(), r2.id());
    }

    // ─── POST /projects/scan ──────────────────────────────────────────

    @Test
    @DisplayName("POST scan discovers projects in directory")
    void scanSuccess() throws Exception {
        // Create two project directories with .git
        Path p1 = Files.createDirectory(tempDir.resolve("app1"));
        Files.createDirectory(p1.resolve(".git"));
        Path p2 = Files.createDirectory(tempDir.resolve("app2"));
        Files.createFile(p2.resolve("CLAUDE.md"));
        // Create a non-project directory
        Files.createDirectory(tempDir.resolve("docs"));

        ResponseEntity<?> response = controller.scanDirectory(
                new ProjectController.ScanRequest(tempDir.toString()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<ProjectRecord> body = (List<ProjectRecord>) response.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
    }

    @Test
    @DisplayName("POST scan returns 400 on nonexistent root path")
    void scanInvalidPath() {
        ResponseEntity<?> response = controller.scanDirectory(
                new ProjectController.ScanRequest(tempDir.resolve("nonexistent").toString()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ─── DELETE /projects/{id} ────────────────────────────────────────

    @Test
    @DisplayName("DELETE project returns 200 when found")
    void removeSuccess() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("myapp"));
        Files.createDirectory(project.resolve(".git"));
        ProjectRecord record = registry.register(project.toString());

        ResponseEntity<?> response = controller.removeProject(record.id());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(registry.listAll().isEmpty());
    }

    @Test
    @DisplayName("DELETE project returns 404 when not found")
    void removeNotFound() {
        ResponseEntity<?> response = controller.removeProject("nonexistent-id");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
