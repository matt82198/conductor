package dev.conductor.server.brain.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans project directories for CLAUDE.md files and reads their contents.
 *
 * <p>Recursively walks the project tree, skipping common non-source directories
 * ({@code .git}, {@code node_modules}, {@code target}, {@code dist}). For each
 * CLAUDE.md found, reads the content and extracts the domain name from the first
 * Markdown heading.
 */
@Service
public class ClaudeMdScanner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdScanner.class);

    private static final String CLAUDE_MD_FILENAME = "CLAUDE.md";
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".gradle", ".mvn"
    );
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    /**
     * Recursively scans the given project path for all CLAUDE.md files.
     *
     * @param projectPath absolute path to the project root directory
     * @return list of domain CLAUDE.md records found, or empty list on error
     */
    public List<DomainClaudeMd> scanProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return List.of();
        }

        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            log.warn("Cannot scan non-directory path: {}", projectPath);
            return List.of();
        }

        List<DomainClaudeMd> results = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (SKIP_DIRECTORIES.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (CLAUDE_MD_FILENAME.equals(file.getFileName().toString())) {
                        String content = readFileContent(file);
                        String relativePath = root.relativize(file).toString().replace('\\', '/');
                        String domainName = extractDomainName(content, relativePath);
                        results.add(new DomainClaudeMd(relativePath, content, domainName));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("Cannot read file {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan project {} for CLAUDE.md files: {}", projectPath, e.getMessage());
        }

        log.debug("Found {} CLAUDE.md files in {}", results.size(), projectPath);
        return results;
    }

    /**
     * Reads a file's content to a string, returning empty string on error.
     *
     * @param path the file path to read
     * @return file content, or empty string if the file cannot be read
     */
    public String readFileContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read file {}: {}", path, e.getMessage());
            return "";
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────

    /**
     * Extracts the domain name from the first Markdown heading in the content.
     * Falls back to deriving the domain name from the file's relative path.
     */
    private String extractDomainName(String content, String relativePath) {
        if (content != null && !content.isBlank()) {
            Matcher matcher = HEADING_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }

        // Fallback: derive from path (e.g., "conductor-server/.../queue/CLAUDE.md" -> "queue")
        String[] parts = relativePath.replace('\\', '/').split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return "root";
    }
}
