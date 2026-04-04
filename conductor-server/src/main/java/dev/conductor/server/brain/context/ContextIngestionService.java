package dev.conductor.server.brain.context;

import dev.conductor.server.project.ProjectRecord;
import dev.conductor.server.project.ProjectRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ingests project context by scanning CLAUDE.md files, user memories, and global
 * Claude Code configuration. Produces a {@link ContextIndex} that the Brain uses
 * for decision-making and prompt construction.
 *
 * <p>Scans all registered projects via {@link ProjectRegistry} and the global
 * {@code ~/.claude/} directory for user-level configuration and memories.
 */
@Service
public class ContextIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ContextIngestionService.class);

    private final ProjectRegistry projectRegistry;
    private final ClaudeMdScanner claudeMdScanner;

    public ContextIngestionService(ProjectRegistry projectRegistry, ClaudeMdScanner claudeMdScanner) {
        this.projectRegistry = projectRegistry;
        this.claudeMdScanner = claudeMdScanner;
    }

    /**
     * Builds a complete context index from all registered projects and global state.
     *
     * @return the assembled context index
     */
    public ContextIndex buildIndex() {
        List<ProjectContext> projectContexts = new ArrayList<>();

        for (ProjectRecord project : projectRegistry.listAll()) {
            ProjectContext ctx = scanProject(project.path());
            if (ctx != null) {
                projectContexts.add(ctx);
            }
        }

        GlobalContext globalContext = scanGlobal();

        log.info("Built context index: {} projects, {} global memory files",
                projectContexts.size(),
                globalContext != null ? globalContext.memoryFileSummaries().size() : 0);

        return new ContextIndex(List.copyOf(projectContexts), globalContext, Instant.now());
    }

    /**
     * Scans a single project directory for CLAUDE.md files and builds its context.
     *
     * @param projectPath absolute path to the project root
     * @return the project context, or null if the path is invalid
     */
    public ProjectContext scanProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }

        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            log.warn("Project path is not a directory: {}", projectPath);
            return null;
        }

        // Scan all CLAUDE.md files
        List<DomainClaudeMd> domainDocs = claudeMdScanner.scanProject(projectPath);

        // Find the root CLAUDE.md content
        String rootClaudeMd = null;
        Path rootMd = root.resolve("CLAUDE.md");
        if (Files.isRegularFile(rootMd)) {
            rootClaudeMd = claudeMdScanner.readFileContent(rootMd);
        }

        // Derive project name from directory
        String projectName = root.getFileName() != null ? root.getFileName().toString() : "unknown";

        // Try to get the project ID from the registry
        String projectId = projectRegistry.getByPath(projectPath)
                .map(ProjectRecord::id)
                .orElse(projectPath);

        return new ProjectContext(
                projectId, projectName, projectPath,
                rootClaudeMd, List.copyOf(domainDocs), Instant.now()
        );
    }

    /**
     * Scans the global {@code ~/.claude/} directory for CLAUDE.md and memory files.
     *
     * @return global context, or a minimal context if the directory doesn't exist
     */
    public GlobalContext scanGlobal() {
        String userHome = System.getProperty("user.home");
        Path claudeDir = Path.of(userHome, ".claude");

        // Read global CLAUDE.md
        String globalClaudeMd = null;
        Path globalMd = claudeDir.resolve("CLAUDE.md");
        if (Files.isRegularFile(globalMd)) {
            globalClaudeMd = claudeMdScanner.readFileContent(globalMd);
        }

        // Scan memory directories
        Path projectsDir = claudeDir.resolve("projects");
        String memoryDirPath = projectsDir.toString();
        List<String> memorySummaries = new ArrayList<>();

        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> projectDirs = Files.list(projectsDir)) {
                projectDirs.filter(Files::isDirectory).forEach(dir -> {
                    Path memoryMd = dir.resolve("MEMORY.md");
                    if (Files.isRegularFile(memoryMd)) {
                        String content = claudeMdScanner.readFileContent(memoryMd);
                        if (!content.isBlank()) {
                            // Take the first non-empty line as summary
                            String summary = dir.getFileName() + ": " + firstLine(content);
                            memorySummaries.add(summary);
                        }
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to scan memory directory {}: {}", projectsDir, e.getMessage());
            }
        }

        log.debug("Scanned global context: CLAUDE.md={}, memory files={}",
                globalClaudeMd != null, memorySummaries.size());

        return new GlobalContext(globalClaudeMd, memoryDirPath, List.copyOf(memorySummaries), Instant.now());
    }

    /**
     * Renders the context index as a string suitable for inclusion in a prompt,
     * prioritizing the target project's CLAUDE.md files.
     *
     * @param index             the full context index
     * @param targetProjectPath the project path to prioritize (full content)
     * @param maxChars          maximum character budget for the rendered output
     * @return the rendered context string, truncated to maxChars
     */
    public String renderForPrompt(ContextIndex index, String targetProjectPath, int maxChars) {
        if (index == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // Target project first — full content
        for (ProjectContext project : index.projects()) {
            if (project.projectPath().equals(targetProjectPath)) {
                sb.append("## Project: ").append(project.projectName()).append("\n\n");

                if (project.rootClaudeMd() != null && !project.rootClaudeMd().isBlank()) {
                    sb.append("### Root CLAUDE.md\n");
                    sb.append(project.rootClaudeMd()).append("\n\n");
                }

                for (DomainClaudeMd domain : project.domainClaudeMds()) {
                    if (sb.length() >= maxChars) break;
                    sb.append("### ").append(domain.domainName()).append(" (")
                            .append(domain.relativePath()).append(")\n");
                    sb.append(domain.content()).append("\n\n");
                }
                break;
            }
        }

        // Other projects — one-line summaries
        for (ProjectContext project : index.projects()) {
            if (sb.length() >= maxChars) break;
            if (!project.projectPath().equals(targetProjectPath)) {
                sb.append("- Project: ").append(project.projectName())
                        .append(" (").append(project.domainClaudeMds().size()).append(" CLAUDE.md files)\n");
            }
        }

        // Global context
        if (index.global() != null && sb.length() < maxChars) {
            GlobalContext global = index.global();
            if (global.globalClaudeMd() != null && !global.globalClaudeMd().isBlank()) {
                sb.append("\n## Global CLAUDE.md\n");
                sb.append(global.globalClaudeMd()).append("\n");
            }
            if (!global.memoryFileSummaries().isEmpty()) {
                sb.append("\n## User Memories\n");
                for (String summary : global.memoryFileSummaries()) {
                    if (sb.length() >= maxChars) break;
                    sb.append("- ").append(summary).append("\n");
                }
            }
        }

        // Truncate to budget
        if (sb.length() > maxChars) {
            return sb.substring(0, maxChars);
        }
        return sb.toString();
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 100 ? trimmed.substring(0, 100) + "..." : trimmed;
            }
        }
        return "(empty)";
    }
}
