package dev.conductor.server.brain.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans {@code ~/.claude/} for personal knowledge: agent definitions, memories,
 * commands, and plans. Produces a {@link PersonalKnowledge} aggregate that the
 * Brain uses for full system awareness.
 *
 * <p>Results are cached for {@link #CACHE_TTL} to avoid re-reading ~300KB of files
 * on every prompt render. Call {@link #refresh()} to force a re-scan.
 */
@Service
public class PersonalKnowledgeScanner {

    private static final Logger log = LoggerFactory.getLogger(PersonalKnowledgeScanner.class);
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("^(\\w+):\\s*(.+)$", Pattern.MULTILINE);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private volatile PersonalKnowledge cached;
    private volatile Instant cachedAt;

    /**
     * Returns cached personal knowledge, re-scanning if stale or missing.
     */
    public PersonalKnowledge scan() {
        if (cached != null && cachedAt != null
                && Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }
        return refresh();
    }

    /**
     * Forces a full re-scan from disk, replacing the cache.
     */
    public PersonalKnowledge refresh() {
        Path claudeDir = Path.of(System.getProperty("user.home"), ".claude");
        if (!Files.isDirectory(claudeDir)) {
            log.debug("No ~/.claude/ directory found");
            cached = new PersonalKnowledge(List.of(), List.of(), List.of(), List.of(), null);
            cachedAt = Instant.now();
            return cached;
        }

        List<AgentDefinition> agents = scanAgents(claudeDir);
        List<MemoryEntry> memories = scanMemories(claudeDir);
        List<CommandDefinition> commands = scanCommands(claudeDir);
        List<PlanEntry> plans = scanPlans(claudeDir);

        log.info("Personal knowledge scan: {} agents, {} memories, {} commands, {} plans",
                agents.size(), memories.size(), commands.size(), plans.size());

        cached = new PersonalKnowledge(agents, memories, commands, plans, null);
        cachedAt = Instant.now();
        return cached;
    }

    /**
     * Scans ~/.claude/agents/ for .md files with agent definitions.
     */
    List<AgentDefinition> scanAgents(Path claudeDir) {
        Path agentsDir = claudeDir.resolve("agents");
        if (!Files.isDirectory(agentsDir)) return List.of();

        List<AgentDefinition> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(agentsDir)) {
            files.filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                String content = readFile(path);
                if (content.isBlank()) return;

                String[] parsed = parseFrontmatter(content);
                String frontmatter = parsed[0];
                String body = parsed[1];

                results.add(new AgentDefinition(
                        extractField(frontmatter, "name", filenameWithoutExt(path)),
                        extractField(frontmatter, "description", ""),
                        extractField(frontmatter, "model", null),
                        body,
                        path.toAbsolutePath().toString()
                ));
            });
        } catch (IOException e) {
            log.warn("Failed to scan agents dir: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Scans ~/.claude/projects/{project}/memory/ for memory .md files.
     * Also scans ~/.claude/agent-memory/ if it exists.
     */
    List<MemoryEntry> scanMemories(Path claudeDir) {
        List<MemoryEntry> results = new ArrayList<>();

        // Project memories: ~/.claude/projects/*/memory/*.md
        Path projectsDir = claudeDir.resolve("projects");
        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> projectDirs = Files.list(projectsDir)) {
                projectDirs.filter(Files::isDirectory).forEach(projDir -> {
                    Path memDir = projDir.resolve("memory");
                    if (!Files.isDirectory(memDir)) return;

                    String projectScope = projDir.getFileName().toString();
                    try (Stream<Path> memFiles = Files.list(memDir)) {
                        memFiles.filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                            MemoryEntry entry = parseMemoryFile(path, projectScope);
                            if (entry != null) results.add(entry);
                        });
                    } catch (IOException e) {
                        log.debug("Failed to scan memory dir {}: {}", memDir, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to scan projects dir: {}", e.getMessage());
            }
        }

        // Agent memories: ~/.claude/agent-memory/*/*.md
        Path agentMemDir = claudeDir.resolve("agent-memory");
        if (Files.isDirectory(agentMemDir)) {
            try (Stream<Path> agentDirs = Files.list(agentMemDir)) {
                agentDirs.filter(Files::isDirectory).forEach(aDir -> {
                    String scope = "agent:" + aDir.getFileName().toString();
                    try (Stream<Path> memFiles = Files.list(aDir)) {
                        memFiles.filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                            MemoryEntry entry = parseMemoryFile(path, scope);
                            if (entry != null) results.add(entry);
                        });
                    } catch (IOException e) {
                        log.debug("Failed to scan agent memory: {}", e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to scan agent-memory dir: {}", e.getMessage());
            }
        }

        return results;
    }

    /**
     * Scans ~/.claude/commands/ for command .md files.
     */
    List<CommandDefinition> scanCommands(Path claudeDir) {
        Path commandsDir = claudeDir.resolve("commands");
        if (!Files.isDirectory(commandsDir)) return List.of();

        List<CommandDefinition> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(commandsDir)) {
            files.filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                String content = readFile(path);
                if (!content.isBlank()) {
                    results.add(new CommandDefinition(
                            filenameWithoutExt(path),
                            content,
                            path.toAbsolutePath().toString()
                    ));
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan commands dir: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Scans ~/.claude/plans/ for plan .md files.
     */
    List<PlanEntry> scanPlans(Path claudeDir) {
        Path plansDir = claudeDir.resolve("plans");
        if (!Files.isDirectory(plansDir)) return List.of();

        List<PlanEntry> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(plansDir)) {
            files.filter(p -> p.toString().endsWith(".md")).forEach(path -> {
                String content = readFile(path);
                if (!content.isBlank()) {
                    results.add(new PlanEntry(
                            filenameWithoutExt(path),
                            content,
                            path.toAbsolutePath().toString()
                    ));
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan plans dir: {}", e.getMessage());
        }
        return results;
    }

    // ─── Internal ─────────────────────────────────────────────────────

    private MemoryEntry parseMemoryFile(Path path, String projectScope) {
        String content = readFile(path);
        if (content.isBlank()) return null;

        String[] parsed = parseFrontmatter(content);
        String frontmatter = parsed[0];
        String body = parsed[1];

        return new MemoryEntry(
                extractField(frontmatter, "name", filenameWithoutExt(path)),
                extractField(frontmatter, "description", ""),
                extractField(frontmatter, "type", "unknown"),
                body,
                projectScope,
                path.toAbsolutePath().toString()
        );
    }

    /**
     * Splits content into [frontmatter, body]. If no frontmatter, returns ["", content].
     */
    String[] parseFrontmatter(String content) {
        Matcher m = FRONTMATTER.matcher(content);
        if (m.find()) {
            String frontmatter = m.group(1);
            String body = content.substring(m.end()).trim();
            return new String[]{frontmatter, body};
        }
        return new String[]{"", content};
    }

    String extractField(String frontmatter, String fieldName, String defaultValue) {
        if (frontmatter == null || frontmatter.isBlank()) return defaultValue;
        Matcher m = FIELD.matcher(frontmatter);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(fieldName)) {
                String val = m.group(2).trim();
                // Strip surrounding quotes
                if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                return val;
            }
        }
        return defaultValue;
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Failed to read {}: {}", path, e.getMessage());
            return "";
        }
    }

    private String filenameWithoutExt(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
