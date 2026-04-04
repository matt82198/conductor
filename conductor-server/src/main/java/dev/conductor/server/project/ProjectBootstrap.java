package dev.conductor.server.project;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers and registers projects on first boot.
 *
 * <p>Builds the scan root list from three sources:
 * <ol>
 *   <li>The user's {@code ~/.claude/projects/} directory — each subdirectory name
 *       is a URL-encoded absolute path. These are decoded to extract the parent
 *       directories that contain real projects.</li>
 *   <li>Common development directories under the user's home:
 *       {@code ~/TRDev}, {@code ~/IdeaProjects}, {@code ~/fscpay}, etc.</li>
 *   <li>Any additional roots configured via properties (future).</li>
 * </ol>
 *
 * <p>Each root is scanned for immediate children containing {@code .git/} or
 * {@code CLAUDE.md}. Discovered projects are registered in the {@link ProjectRegistry}.
 * Also registers standalone projects (like ~/fscpay) that are not inside a parent dev folder.
 */
@Service
public class ProjectBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ProjectBootstrap.class);

    private final ProjectScanner projectScanner;
    private final ProjectRegistry projectRegistry;

    public ProjectBootstrap(ProjectScanner projectScanner, ProjectRegistry projectRegistry) {
        this.projectScanner = projectScanner;
        this.projectRegistry = projectRegistry;
    }

    @PostConstruct
    void discoverOnBoot() {
        Set<String> scanRoots = buildScanRoots();
        log.info("Project bootstrap: scanning {} root directories", scanRoots.size());

        int totalDiscovered = 0;
        for (String root : scanRoots) {
            Path rootPath = Path.of(root);
            if (!Files.isDirectory(rootPath)) {
                log.debug("Skipping non-existent scan root: {}", root);
                continue;
            }

            // Check if the root itself is a project (e.g., ~/fscpay has .git directly)
            if (isProject(rootPath)) {
                projectRegistry.register(rootPath.toString());
                totalDiscovered++;
                log.debug("Registered standalone project: {}", rootPath);
            }

            // Scan children
            List<ProjectRecord> discovered = projectScanner.scanDirectory(root);
            totalDiscovered += discovered.size();
        }

        log.info("Project bootstrap complete: {} projects registered", totalDiscovered);
    }

    /**
     * Builds a deduplicated set of directories to scan for projects.
     */
    Set<String> buildScanRoots() {
        String userHome = System.getProperty("user.home");
        Set<String> roots = new LinkedHashSet<>();

        // 1. Derive scan roots from ~/.claude/projects/ directory names
        //    Each subdir is named like "C--Users-matt8-TRDev-billing" (URL-encoded path)
        Path claudeProjectsDir = Path.of(userHome, ".claude", "projects");
        if (Files.isDirectory(claudeProjectsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(claudeProjectsDir)) {
                for (Path entry : stream) {
                    if (!Files.isDirectory(entry)) continue;
                    String decoded = decodeProjectDirName(entry.getFileName().toString());
                    if (decoded != null) {
                        // The decoded path is the project itself; add its parent as a scan root
                        Path projectPath = Path.of(decoded);
                        if (Files.isDirectory(projectPath) && projectPath.getParent() != null) {
                            roots.add(projectPath.getParent().toString());
                            // Also register the project directly (in case it's nested)
                            if (isProject(projectPath)) {
                                roots.add(projectPath.toString());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read ~/.claude/projects/: {}", e.getMessage());
            }
        }

        // 2. Common dev directories
        for (String dir : List.of("TRDev", "IdeaProjects", "projects", "dev", "repos", "src")) {
            Path candidate = Path.of(userHome, dir);
            if (Files.isDirectory(candidate)) {
                roots.add(candidate.toString());
            }
        }

        // 3. Scan home directory for standalone projects (one level only)
        Path home = Path.of(userHome);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(home)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && isProject(child)) {
                    roots.add(child.toString());
                }
            }
        } catch (IOException e) {
            log.debug("Failed to scan home directory: {}", e.getMessage());
        }

        return roots;
    }

    /**
     * Decodes a ~/.claude/projects/ directory name back to an absolute path.
     * Format: "C--Users-matt8-TRDev-billing" → "C:/Users/matt8/TRDev/billing"
     */
    static String decodeProjectDirName(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;

        // Replace -- with : (drive letter separator on Windows)
        // Then replace remaining - with /
        // But we need to be smarter: the pattern is C--Users-matt8-...
        // First two chars + -- is the drive letter
        if (encoded.length() > 2 && encoded.charAt(1) == '-' && encoded.charAt(2) == '-') {
            // Windows path: "C--Users-matt8-TRDev-billing"
            String drive = encoded.substring(0, 1) + ":";
            String rest = encoded.substring(3).replace('-', '/');
            String path = drive + "/" + rest;
            // Verify it looks like a real path
            if (Files.isDirectory(Path.of(path))) {
                return Path.of(path).toAbsolutePath().normalize().toString();
            }
            // Try progressive reconstruction (hyphens in dir names)
            return reconstructPath(drive, encoded.substring(3));
        }

        // Unix-style or unknown format
        String asPath = "/" + encoded.replace('-', '/');
        if (Files.isDirectory(Path.of(asPath))) {
            return Path.of(asPath).toAbsolutePath().normalize().toString();
        }

        return null;
    }

    /**
     * Reconstructs a path by trying to match each segment against the filesystem.
     * Handles directories with hyphens in their names (e.g., "Chris-Basso-Sessions-Dev").
     */
    private static String reconstructPath(String drive, String encoded) {
        String[] parts = encoded.split("-");
        Path current = Path.of(drive + "/");

        int i = 0;
        while (i < parts.length) {
            // Try progressively longer segments (to handle hyphens in dir names)
            boolean found = false;
            for (int len = parts.length - i; len >= 1; len--) {
                StringBuilder candidate = new StringBuilder(parts[i]);
                for (int j = 1; j < len; j++) {
                    // Try with hyphen (original char) or as path separator
                    candidate.append("-").append(parts[i + j]);
                }
                Path test = current.resolve(candidate.toString());
                if (Files.isDirectory(test)) {
                    // Try with space too (encoded spaces might be hyphens)
                    current = test;
                    i += len;
                    found = true;
                    break;
                }
                // Also try with spaces (for dirs like "Chris Basso Sessions Dev")
                String withSpaces = candidate.toString().replace("-", " ");
                try {
                    test = current.resolve(withSpaces);
                    if (Files.isDirectory(test)) {
                        current = test;
                        i += len;
                        found = true;
                        break;
                    }
                } catch (java.nio.file.InvalidPathException ignored) {
                    // Invalid path chars on this OS — skip
                }
            }
            if (!found) {
                // Can't resolve further — give up
                return null;
            }
        }

        return current.toAbsolutePath().normalize().toString();
    }

    private static boolean isProject(Path dir) {
        return Files.isDirectory(dir.resolve(".git"))
                || Files.isRegularFile(dir.resolve("CLAUDE.md"));
    }
}
