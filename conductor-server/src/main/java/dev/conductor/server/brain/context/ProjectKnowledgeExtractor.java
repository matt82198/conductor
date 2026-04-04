package dev.conductor.server.brain.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conductor.server.brain.BrainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Analyzes a project directory and extracts reusable patterns and knowledge
 * by scanning build files, CLAUDE.md files, and key source files, then calling
 * the Claude API for deep analysis.
 *
 * <p>When the API key is unavailable or the API call fails, returns minimal
 * knowledge derived from build file parsing only (no patterns).
 */
@Service
public class ProjectKnowledgeExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProjectKnowledgeExtractor.class);

    private static final int MAX_SOURCE_FILES = 10;
    private static final int MAX_CHARS_PER_FILE = 3000;
    private static final int MAX_TOTAL_SOURCE_CHARS = 30000;

    /** Hard cap on total characters sent to the Claude API for analysis. */
    private static final int MAX_API_PROMPT_CHARS = 50_000;

    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".gradle", ".mvn",
            "__pycache__", ".venv", "venv", ".next", "out"
    );

    private final ClaudeMdScanner claudeMdScanner;
    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ProjectKnowledgeExtractor(
            ClaudeMdScanner claudeMdScanner,
            BrainProperties brainProperties,
            ObjectMapper objectMapper
    ) {
        this.claudeMdScanner = claudeMdScanner;
        this.brainProperties = brainProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        log.info("ProjectKnowledgeExtractor initialized — model: {}", brainProperties.model());
    }

    /**
     * Analyzes a project and extracts reusable knowledge.
     *
     * <p>This calls the Claude API (costs money, takes a few seconds). If the
     * API key is blank or the call fails, returns minimal knowledge with just
     * the tech stack detected from the build file.
     *
     * @param projectPath absolute path to the project root
     * @param projectId   the project's unique identifier
     * @param projectName human-readable project name
     * @return extracted project knowledge
     */
    public ProjectKnowledge analyze(String projectPath, String projectId, String projectName) {
        log.info("Analyzing project: {} at {}", projectName, projectPath);

        // Step 1: Gather raw context
        String buildFile = readBuildFile(projectPath);
        List<DomainClaudeMd> claudeMds = claudeMdScanner.scanProject(projectPath);
        List<String> sourceFiles = discoverKeySourceFiles(projectPath);
        String sourceSamples = readSourceSamples(projectPath, sourceFiles);

        // Step 2: Check if we have an API key
        if (brainProperties.apiKey() == null || brainProperties.apiKey().isBlank()) {
            log.warn("No API key configured — returning minimal knowledge for {}", projectName);
            return buildMinimalKnowledge(projectId, projectName, projectPath, buildFile, sourceFiles);
        }

        // Step 3: Call Claude API for analysis
        try {
            String analysisPrompt = buildAnalysisPrompt(projectName, buildFile, claudeMds, sourceSamples);
            String apiResponse = callClaudeApi(analysisPrompt);
            ProjectKnowledge knowledge = parseAnalysis(apiResponse, projectId, projectName, projectPath);
            log.info("Successfully analyzed project {} — {} patterns, {} key files",
                    projectName, knowledge.patterns().size(), knowledge.keyFiles().size());
            return knowledge;
        } catch (Exception e) {
            log.error("Claude API analysis failed for {} — falling back to minimal knowledge: {}",
                    projectName, e.getMessage());
            return buildMinimalKnowledge(projectId, projectName, projectPath, buildFile, sourceFiles);
        }
    }

    // ─── File Discovery ───────────────────────────────────────────────

    /**
     * Reads the primary build file from the project root.
     * Checks for pom.xml, package.json, build.gradle, build.gradle.kts in order.
     *
     * @param projectPath absolute path to the project root
     * @return build file content, or empty string if none found
     */
    String readBuildFile(String projectPath) {
        Path root = Path.of(projectPath);
        String[] buildFiles = {"pom.xml", "package.json", "build.gradle", "build.gradle.kts",
                "Cargo.toml", "go.mod", "requirements.txt", "pyproject.toml"};

        for (String name : buildFiles) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.length() > MAX_CHARS_PER_FILE) {
                        content = content.substring(0, MAX_CHARS_PER_FILE);
                    }
                    log.debug("Read build file: {}", name);
                    return content;
                } catch (IOException e) {
                    log.warn("Failed to read build file {}: {}", name, e.getMessage());
                }
            }
        }
        return "";
    }

    /**
     * Discovers key source files in the project for analysis.
     *
     * <p>Priority:
     * <ol>
     *   <li>Main application classes (*Application.java, main.ts, index.ts, app.py)</li>
     *   <li>Service classes (files in service/ or services/ directories)</li>
     *   <li>Config files (application.yml, application.properties, tsconfig.json, .env.example)</li>
     *   <li>API/controller files (files in api/, controller/, routes/ directories)</li>
     * </ol>
     * Capped at {@value #MAX_SOURCE_FILES} files total.
     *
     * @param projectPath absolute path to the project root
     * @return list of relative file paths, ordered by relevance
     */
    List<String> discoverKeySourceFiles(String projectPath) {
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<String> entryPoints = new ArrayList<>();
        List<String> services = new ArrayList<>();
        List<String> configs = new ArrayList<>();
        List<String> controllers = new ArrayList<>();

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
                    String fileName = file.getFileName().toString();
                    String relativePath = root.relativize(file).toString().replace('\\', '/');
                    String parentDir = file.getParent() != null
                            ? file.getParent().getFileName().toString().toLowerCase()
                            : "";

                    // Entry points
                    if (fileName.endsWith("Application.java") || fileName.equals("main.ts")
                            || fileName.equals("index.ts") || fileName.equals("app.py")
                            || fileName.equals("main.py") || fileName.equals("main.go")) {
                        entryPoints.add(relativePath);
                    }
                    // Services
                    else if ("service".equals(parentDir) || "services".equals(parentDir)) {
                        if (fileName.endsWith(".java") || fileName.endsWith(".ts")
                                || fileName.endsWith(".py") || fileName.endsWith(".go")) {
                            services.add(relativePath);
                        }
                    }
                    // Config files
                    else if (fileName.equals("application.yml") || fileName.equals("application.properties")
                            || fileName.equals("tsconfig.json") || fileName.equals(".env.example")) {
                        configs.add(relativePath);
                    }
                    // Controllers / API
                    else if ("api".equals(parentDir) || "controller".equals(parentDir)
                            || "controllers".equals(parentDir) || "routes".equals(parentDir)) {
                        if (fileName.endsWith(".java") || fileName.endsWith(".ts")
                                || fileName.endsWith(".py") || fileName.endsWith(".go")) {
                            controllers.add(relativePath);
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error walking project directory {}: {}", projectPath, e.getMessage());
        }

        // Merge in priority order, capped at MAX_SOURCE_FILES
        List<String> result = new ArrayList<>();
        for (String f : entryPoints) { if (result.size() < MAX_SOURCE_FILES) result.add(f); }
        for (String f : services) { if (result.size() < MAX_SOURCE_FILES) result.add(f); }
        for (String f : configs) { if (result.size() < MAX_SOURCE_FILES) result.add(f); }
        for (String f : controllers) { if (result.size() < MAX_SOURCE_FILES) result.add(f); }

        log.debug("Discovered {} key source files in {}", result.size(), projectPath);
        return result;
    }

    /**
     * Reads the content of discovered source files, capped per-file and in total.
     *
     * @param projectPath absolute path to the project root
     * @param sourceFiles list of relative file paths to read
     * @return concatenated source content with file headers
     */
    String readSourceSamples(String projectPath, List<String> sourceFiles) {
        Path root = Path.of(projectPath);
        StringBuilder sb = new StringBuilder();

        for (String relPath : sourceFiles) {
            if (sb.length() >= MAX_TOTAL_SOURCE_CHARS) break;

            Path file = root.resolve(relPath);
            if (!Files.isRegularFile(file)) continue;

            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.length() > MAX_CHARS_PER_FILE) {
                    content = content.substring(0, MAX_CHARS_PER_FILE);
                }
                sb.append("--- FILE: ").append(relPath).append(" ---\n");
                sb.append(content).append("\n\n");
            } catch (IOException e) {
                log.debug("Failed to read source file {}: {}", relPath, e.getMessage());
            }
        }

        if (sb.length() > MAX_TOTAL_SOURCE_CHARS) {
            return sb.substring(0, MAX_TOTAL_SOURCE_CHARS);
        }
        return sb.toString();
    }

    // ─── Claude API ───────────────────────────────────────────────────

    /**
     * Builds the analysis prompt for the Claude API.
     */
    String buildAnalysisPrompt(
            String projectName,
            String buildFile,
            List<DomainClaudeMd> claudeMds,
            String sourceSamples
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are analyzing a software project to extract reusable patterns and knowledge.\n\n");
        sb.append("PROJECT: ").append(projectName).append("\n\n");

        if (!buildFile.isBlank()) {
            sb.append("BUILD FILE:\n").append(buildFile).append("\n\n");
        }

        if (!claudeMds.isEmpty()) {
            sb.append("CLAUDE.MD FILES:\n");
            for (DomainClaudeMd cmd : claudeMds) {
                sb.append("--- ").append(cmd.relativePath()).append(" ---\n");
                sb.append(cmd.content()).append("\n\n");
            }
        }

        if (!sourceSamples.isBlank()) {
            sb.append("KEY SOURCE FILES:\n").append(sourceSamples).append("\n\n");
        }

        sb.append("""
                Analyze this project and respond in this exact JSON format (no markdown, no code fences):
                {
                  "techStack": "Java 21, Spring Boot 3.4, Maven, ...",
                  "architectureSummary": "2-3 sentences describing the architecture",
                  "patterns": [
                    {
                      "name": "Pattern name (e.g., 'Claude API via RestClient')",
                      "description": "How the pattern works and when to use it",
                      "sourceFile": "relative/path/to/reference/implementation.java",
                      "tags": ["api", "claude", "rest-client"]
                    }
                  ],
                  "keyFiles": [
                    "relative/path/to/important/file.java"
                  ]
                }

                Focus on:
                - Integration patterns (APIs, databases, external services)
                - Architecture patterns (event-driven, layered, microservices)
                - Testing patterns
                - Build/deploy patterns
                - Anything reusable across projects
                """);

        String result = sb.toString();

        // Hard cap on total chars sent to the API
        if (result.length() > MAX_API_PROMPT_CHARS) {
            log.warn("Analysis prompt for {} exceeds cap ({} chars > {} max) — truncating",
                    projectName, result.length(), MAX_API_PROMPT_CHARS);
            result = result.substring(0, MAX_API_PROMPT_CHARS);
        }

        return result;
    }

    /**
     * Calls the Claude API with the given prompt and returns the raw text response.
     *
     * @param prompt the analysis prompt
     * @return the text content from the API response
     * @throws RuntimeException if the API call or parsing fails
     */
    String callClaudeApi(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", brainProperties.model(),
                "max_tokens", 2048,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        String body;
        try {
            body = objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize API request", e);
        }

        log.info("Calling Claude API for project analysis — model={}, prompt length={}",
                brainProperties.model(), prompt.length());

        String response = restClient.post()
                .uri("/v1/messages")
                .header("x-api-key", brainProperties.apiKey())
                .body(body)
                .retrieve()
                .body(String.class);

        if (response == null) {
            throw new RuntimeException("Claude API returned null response");
        }

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentArray = root.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                throw new RuntimeException("Unexpected API response structure: no content array");
            }
            return contentArray.get(0).get("text").asText();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse API response", e);
        }
    }

    /**
     * Parses the Claude API response text into a {@link ProjectKnowledge} record.
     */
    ProjectKnowledge parseAnalysis(String apiResponse, String projectId, String projectName, String projectPath) {
        try {
            JsonNode root = objectMapper.readTree(apiResponse.trim());

            String techStack = root.has("techStack") ? root.get("techStack").asText() : "";
            String summary = root.has("architectureSummary") ? root.get("architectureSummary").asText() : "";

            List<ProjectKnowledge.PatternEntry> patterns = new ArrayList<>();
            if (root.has("patterns") && root.get("patterns").isArray()) {
                for (JsonNode node : root.get("patterns")) {
                    String name = node.has("name") ? node.get("name").asText() : "Unnamed";
                    String desc = node.has("description") ? node.get("description").asText() : "";
                    String src = node.has("sourceFile") ? node.get("sourceFile").asText() : "";
                    List<String> tags = new ArrayList<>();
                    if (node.has("tags") && node.get("tags").isArray()) {
                        for (JsonNode tag : node.get("tags")) {
                            tags.add(tag.asText());
                        }
                    }
                    patterns.add(new ProjectKnowledge.PatternEntry(name, desc, src, tags));
                }
            }

            List<String> keyFiles = new ArrayList<>();
            if (root.has("keyFiles") && root.get("keyFiles").isArray()) {
                for (JsonNode node : root.get("keyFiles")) {
                    keyFiles.add(node.asText());
                }
            }

            return new ProjectKnowledge(
                    projectId, projectName, projectPath,
                    techStack, patterns, keyFiles, summary, Instant.now()
            );
        } catch (IOException e) {
            log.error("Failed to parse Claude API analysis response: {}", e.getMessage());
            return new ProjectKnowledge(
                    projectId, projectName, projectPath,
                    "", List.of(), List.of(), "", Instant.now()
            );
        }
    }

    // ─── Fallback ─────────────────────────────────────────────────────

    /**
     * Builds minimal knowledge from the build file when the API is unavailable.
     */
    private ProjectKnowledge buildMinimalKnowledge(
            String projectId, String projectName, String projectPath,
            String buildFile, List<String> sourceFiles
    ) {
        String techStack = detectTechStack(projectPath, buildFile);
        return new ProjectKnowledge(
                projectId, projectName, projectPath,
                techStack, List.of(), sourceFiles, "", Instant.now()
        );
    }

    /**
     * Detects the tech stack from the build file and project structure.
     */
    private String detectTechStack(String projectPath, String buildFile) {
        List<String> stack = new ArrayList<>();
        Path root = Path.of(projectPath);

        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            stack.add("Maven");
            if (buildFile.contains("spring-boot")) stack.add("Spring Boot");
            if (buildFile.contains("<java.version>21") || buildFile.contains("--release 21")) stack.add("Java 21");
            else if (buildFile.contains("<java.version>17") || buildFile.contains("--release 17")) stack.add("Java 17");
            else stack.add("Java");
        } else if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            stack.add("Gradle");
            if (buildFile.contains("spring-boot")) stack.add("Spring Boot");
            stack.add("Java/Kotlin");
        } else if (Files.isRegularFile(root.resolve("package.json"))) {
            stack.add("Node.js");
            if (buildFile.contains("\"react\"")) stack.add("React");
            if (buildFile.contains("\"typescript\"")) stack.add("TypeScript");
            if (buildFile.contains("\"next\"")) stack.add("Next.js");
            if (buildFile.contains("\"express\"")) stack.add("Express");
            if (buildFile.contains("\"electron\"")) stack.add("Electron");
        } else if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            stack.add("Rust");
        } else if (Files.isRegularFile(root.resolve("go.mod"))) {
            stack.add("Go");
        } else if (Files.isRegularFile(root.resolve("requirements.txt")) || Files.isRegularFile(root.resolve("pyproject.toml"))) {
            stack.add("Python");
        }

        return stack.isEmpty() ? "Unknown" : String.join(", ", stack);
    }
}
