package dev.conductor.server.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers projects by scanning a directory for immediate children that look like projects.
 *
 * <p>A directory is considered a project if it contains either:
 * <ul>
 *   <li>{@code .git/} — a Git repository</li>
 *   <li>{@code CLAUDE.md} — an agent-managed project</li>
 * </ul>
 *
 * <p>Scanning is non-recursive: only the immediate children of the root directory are
 * inspected. Each discovered project is automatically registered in the {@link ProjectRegistry}.
 */
@Service
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final ProjectRegistry registry;

    public ProjectScanner(ProjectRegistry registry) {
        this.registry = registry;
    }

    /**
     * Scans the immediate children of the given root directory for projects.
     *
     * <p>For each child directory that contains {@code .git} or {@code CLAUDE.md},
     * registers it in the {@link ProjectRegistry} and includes it in the returned list.
     * Already-registered projects are returned as-is (no duplicates created).
     *
     * @param rootPath absolute path to the directory to scan
     * @return list of discovered (and now registered) project records
     * @throws IllegalArgumentException if rootPath is not an existing directory
     */
    public List<ProjectRecord> scanDirectory(String rootPath) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not an existing directory: " + root);
        }

        log.info("Scanning for projects in: {}", root);
        List<ProjectRecord> discovered = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }

                if (isProject(child)) {
                    ProjectRecord project = registry.register(child.toString());
                    discovered.add(project);
                    log.debug("Discovered project: {} at {}", project.name(), project.path());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan directory: {}", root, e);
        }

        log.info("Scan complete: discovered {} projects in {}", discovered.size(), root);
        return discovered;
    }

    /**
     * Returns true if the directory looks like a project (contains .git or CLAUDE.md).
     */
    private static boolean isProject(Path directory) {
        return Files.isDirectory(directory.resolve(".git"))
                || Files.isRegularFile(directory.resolve("CLAUDE.md"));
    }
}
