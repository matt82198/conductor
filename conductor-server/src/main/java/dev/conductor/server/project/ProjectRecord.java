package dev.conductor.server.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a registered project's identity and metadata.
 *
 * <p>Projects are discovered by path. Conductor does not create projects — it finds
 * directories that contain {@code .git} or {@code CLAUDE.md} and registers them.
 * Because agent counts change over time, the registry creates new instances via
 * {@code with*} methods rather than mutating fields.
 *
 * @param id           unique identifier assigned at registration time
 * @param name         human-readable name (derived from directory name)
 * @param path         absolute filesystem path to the project root
 * @param gitRemote    remote "origin" URL from .git/config, or null if unavailable
 * @param agentCount   number of agents currently scoped to this project
 * @param registeredAt timestamp when the project was first registered
 */
public record ProjectRecord(
        String id,
        String name,
        String path,
        String gitRemote,
        int agentCount,
        Instant registeredAt
) {

    /**
     * Creates a new ProjectRecord by inspecting the given directory path.
     *
     * <p>Auto-detects:
     * <ul>
     *   <li><strong>name</strong> — last component of the directory path</li>
     *   <li><strong>gitRemote</strong> — parsed from {@code .git/config} if present</li>
     * </ul>
     *
     * @param absolutePath absolute filesystem path to the project directory
     * @return a new ProjectRecord with zero agent count and current timestamp
     * @throws IllegalArgumentException if the path is not an existing directory
     */
    public static ProjectRecord create(String absolutePath) {
        Path dir = Path.of(absolutePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not an existing directory: " + dir);
        }

        String name = dir.getFileName().toString();
        String gitRemote = parseGitRemote(dir);
        Instant now = Instant.now();

        return new ProjectRecord(
                UUID.randomUUID().toString(),
                name,
                dir.toString(),
                gitRemote,
                0,
                now
        );
    }

    /**
     * Returns a copy with the agent count incremented by one.
     */
    public ProjectRecord withIncrementedAgentCount() {
        return new ProjectRecord(id, name, path, gitRemote, agentCount + 1, registeredAt);
    }

    /**
     * Returns a copy with the agent count decremented by one (floored at zero).
     */
    public ProjectRecord withDecrementedAgentCount() {
        return new ProjectRecord(id, name, path, gitRemote, Math.max(0, agentCount - 1), registeredAt);
    }

    // -------------------------------------------------------------------------
    // Git config parsing
    // -------------------------------------------------------------------------

    /**
     * Reads {@code .git/config} and extracts the {@code url} value from the
     * {@code [remote "origin"]} section. Returns null if the file doesn't exist,
     * can't be read, or doesn't contain an origin remote.
     */
    static String parseGitRemote(Path projectDir) {
        Path gitConfig = projectDir.resolve(".git").resolve("config");
        if (!Files.isRegularFile(gitConfig)) {
            return null;
        }

        try {
            var lines = Files.readAllLines(gitConfig);
            boolean inOriginSection = false;

            for (String line : lines) {
                String trimmed = line.trim();

                // Detect section headers
                if (trimmed.startsWith("[")) {
                    inOriginSection = trimmed.equals("[remote \"origin\"]");
                    continue;
                }

                // Inside [remote "origin"], look for url =
                if (inOriginSection && trimmed.startsWith("url")) {
                    int eqIndex = trimmed.indexOf('=');
                    if (eqIndex >= 0) {
                        return trimmed.substring(eqIndex + 1).trim();
                    }
                }
            }
        } catch (IOException e) {
            // Can't read git config — treat as no remote
            return null;
        }

        return null;
    }
}
