package dev.conductor.server.brain.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Intelligent context manager that replaces the naive "dump everything" approach
 * of {@link KnowledgeAwareContextRenderer} with relevance-scored, budget-aware
 * context curation.
 *
 * <p>Given a task prompt, target project, and character budget, the ContextManager:
 * <ol>
 *   <li>Extracts keywords from the task prompt via {@link RelevanceScorer}</li>
 *   <li>Scores every context source (CLAUDE.md sections, memories, agent defs,
 *       cross-project knowledge) by keyword overlap</li>
 *   <li>Allocates a character budget across four categories
 *       (60% target project, 20% memories, 10% agent defs, 10% cross-project)</li>
 *   <li>Renders only what fits, highest-relevance entries first</li>
 * </ol>
 *
 * <p>Special rules:
 * <ul>
 *   <li>Feedback memories are always included (baseline relevance boost)</li>
 *   <li>Agent definitions render description-only unless they score above 0.5</li>
 *   <li>Non-target CLAUDE.md files render section headers only, not full content</li>
 * </ul>
 *
 * <p>This is a separate service from {@link KnowledgeAwareContextRenderer}.
 * Components that want intelligent, relevance-scored context should inject
 * this service. Existing code using {@code KnowledgeAwareContextRenderer}
 * directly is unaffected.
 */
@Service
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /** Minimum relevance score for a memory to be included (unless type=feedback). */
    private static final double MEMORY_RELEVANCE_THRESHOLD = 0.05;

    /** Agent defs above this threshold get full description; below get name only. */
    private static final double AGENT_FULL_DESC_THRESHOLD = 0.15;

    /** Agent defs above this threshold get their system prompt included. */
    private static final double AGENT_PROMPT_THRESHOLD = 0.5;

    /** Cross-project docs above this threshold get section headers included. */
    private static final double CROSS_PROJECT_HEADER_THRESHOLD = 0.1;

    private final ContextIngestionService contextIngestionService;
    private final RelevanceScorer relevanceScorer;
    private final PersonalKnowledgeScanner personalKnowledgeScanner;
    private final ProjectKnowledgeStore projectKnowledgeStore;

    public ContextManager(
            ContextIngestionService contextIngestionService,
            RelevanceScorer relevanceScorer,
            @Autowired(required = false) PersonalKnowledgeScanner personalKnowledgeScanner,
            @Autowired(required = false) ProjectKnowledgeStore projectKnowledgeStore
    ) {
        this.contextIngestionService = contextIngestionService;
        this.relevanceScorer = relevanceScorer;
        this.personalKnowledgeScanner = personalKnowledgeScanner;
        this.projectKnowledgeStore = projectKnowledgeStore;
    }

    /**
     * Builds a context budget showing what would be allocated for a given task.
     *
     * <p>This is the analysis/preview method used by the REST endpoint. It scores
     * all context sources and returns the budget with ranked entries, but does not
     * render any content.
     *
     * @param taskPrompt        the user's task prompt for relevance scoring
     * @param targetProjectPath the project this task targets
     * @param maxChars          total character budget
     * @return the computed budget with all scored entries
     */
    public ContextBudget buildBudget(String taskPrompt, String targetProjectPath, int maxChars) {
        Map<String, Integer> keywords = relevanceScorer.extractKeywords(taskPrompt);
        ContextIndex index = contextIngestionService.buildIndex();
        PersonalKnowledge pk = personalKnowledgeScanner != null
                ? personalKnowledgeScanner.scan()
                : new PersonalKnowledge(List.of(), List.of(), List.of(), List.of(), null);

        List<ContextBudget.ScoredEntry> allEntries = new ArrayList<>();

        // Score target project content
        scoreTargetProject(index, targetProjectPath, keywords, allEntries);

        // Score memories
        scoreMemories(pk, keywords, allEntries);

        // Score agent definitions
        scoreAgents(pk, keywords, allEntries);

        // Score cross-project content
        scoreCrossProject(index, targetProjectPath, keywords, allEntries);

        // Sort by relevance descending
        allEntries.sort(Comparator.comparingDouble(ContextBudget.ScoredEntry::relevance).reversed());

        return ContextBudget.withDefaults(maxChars, allEntries);
    }

    /**
     * Renders an intelligently curated context string for a task prompt.
     *
     * <p>This is the main entry point that replaces
     * {@link KnowledgeAwareContextRenderer#renderForPrompt}. It scores all
     * context sources, allocates a budget, and renders only what fits.
     *
     * @param taskPrompt        the user's task prompt for relevance scoring
     * @param targetProjectPath the project this task targets
     * @param maxChars          total character budget
     * @return the rendered context string, within the character budget
     */
    public String renderForPrompt(String taskPrompt, String targetProjectPath, int maxChars) {
        ContextBudget budget = buildBudget(taskPrompt, targetProjectPath, maxChars);
        return renderFromBudget(budget, taskPrompt, targetProjectPath);
    }

    /**
     * Convenience method that builds the index and renders in one step.
     *
     * @param taskPrompt        the user's task prompt
     * @param targetProjectPath the project this task targets
     * @param maxChars          total character budget
     * @return the rendered context string
     */
    public String buildAndRender(String taskPrompt, String targetProjectPath, int maxChars) {
        return renderForPrompt(taskPrompt, targetProjectPath, maxChars);
    }

    // -- Scoring helpers ------------------------------------------------

    private void scoreTargetProject(ContextIndex index, String targetProjectPath,
                                     Map<String, Integer> keywords,
                                     List<ContextBudget.ScoredEntry> entries) {
        for (ProjectContext project : index.projects()) {
            if (!project.projectPath().equals(targetProjectPath)) continue;

            // Root CLAUDE.md always gets max relevance for target project
            if (project.rootClaudeMd() != null && !project.rootClaudeMd().isBlank()) {
                entries.add(new ContextBudget.ScoredEntry(
                        "target-project",
                        project.projectName() + "/CLAUDE.md",
                        1.0,
                        project.rootClaudeMd().length()
                ));
            }

            // Score each domain doc
            for (DomainClaudeMd doc : project.domainClaudeMds()) {
                double docScore = relevanceScorer.scoreDomainDoc(doc, keywords);
                // Boost domain docs in target project by 0.2
                docScore = Math.min(1.0, docScore + 0.2);
                entries.add(new ContextBudget.ScoredEntry(
                        "target-project",
                        project.projectName() + "/" + doc.domainName(),
                        docScore,
                        doc.content().length()
                ));
            }
            break;
        }
    }

    private void scoreMemories(PersonalKnowledge pk, Map<String, Integer> keywords,
                                List<ContextBudget.ScoredEntry> entries) {
        for (MemoryEntry memory : pk.memories()) {
            double memScore = relevanceScorer.scoreMemory(memory, keywords);
            String rendered = renderMemoryEntry(memory);
            entries.add(new ContextBudget.ScoredEntry(
                    "memory",
                    memory.name() + " [" + memory.type() + "]",
                    memScore,
                    rendered.length()
            ));
        }
    }

    private void scoreAgents(PersonalKnowledge pk, Map<String, Integer> keywords,
                              List<ContextBudget.ScoredEntry> entries) {
        for (AgentDefinition agent : pk.agents()) {
            double agentScore = relevanceScorer.scoreAgent(agent, keywords);
            // Size depends on how much we'd render
            String rendered = renderAgentEntry(agent, agentScore);
            entries.add(new ContextBudget.ScoredEntry(
                    "agent-def",
                    agent.name(),
                    agentScore,
                    rendered.length()
            ));
        }
    }

    private void scoreCrossProject(ContextIndex index, String targetProjectPath,
                                    Map<String, Integer> keywords,
                                    List<ContextBudget.ScoredEntry> entries) {
        for (ProjectContext project : index.projects()) {
            if (project.projectPath().equals(targetProjectPath)) continue;

            // Score the project's root CLAUDE.md
            double projectScore = 0.0;
            if (project.rootClaudeMd() != null && !project.rootClaudeMd().isBlank()) {
                projectScore = relevanceScorer.score(project.rootClaudeMd(), keywords);
            }

            // Render as summary or headers based on score
            String rendered = renderCrossProjectEntry(project, projectScore);
            entries.add(new ContextBudget.ScoredEntry(
                    "cross-project",
                    project.projectName(),
                    projectScore,
                    rendered.length()
            ));
        }

        // Also score cross-project knowledge from the knowledge store
        if (projectKnowledgeStore != null) {
            for (ProjectKnowledge pk : projectKnowledgeStore.loadAll()) {
                // Skip the target project's knowledge
                if (targetProjectPath != null && targetProjectPath.contains(pk.projectName())) {
                    continue;
                }
                double knowledgeScore = relevanceScorer.score(
                        pk.techStack() + " " + pk.architectureSummary(), keywords);
                String rendered = renderKnowledgeEntry(pk);
                entries.add(new ContextBudget.ScoredEntry(
                        "cross-project",
                        pk.projectName() + " (knowledge)",
                        knowledgeScore,
                        rendered.length()
                ));
            }
        }
    }

    // -- Rendering ------------------------------------------------------

    /**
     * Renders context from a pre-built budget, respecting category limits.
     */
    String renderFromBudget(ContextBudget budget, String taskPrompt, String targetProjectPath) {
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> keywords = relevanceScorer.extractKeywords(taskPrompt);

        ContextIndex index = contextIngestionService.buildIndex();
        PersonalKnowledge pk = personalKnowledgeScanner != null
                ? personalKnowledgeScanner.scan()
                : new PersonalKnowledge(List.of(), List.of(), List.of(), List.of(), null);

        // 1. Target project -- full content up to budget
        int targetUsed = renderTargetProject(sb, index, targetProjectPath, keywords,
                budget.targetProjectChars(), budget.rankedEntries());

        // 2. Memories -- ranked by relevance
        int memoriesUsed = renderMemories(sb, pk, keywords,
                budget.memoriesChars(), budget.rankedEntries());

        // 3. Agent definitions -- description only unless high relevance
        int agentsUsed = renderAgentDefs(sb, pk, keywords,
                budget.agentDefsChars(), budget.rankedEntries());

        // 4. Cross-project -- summaries and knowledge
        int crossUsed = renderCrossProjectSection(sb, index, targetProjectPath, keywords,
                budget.crossProjectChars(), budget.rankedEntries());

        log.info("Context rendered: total={} chars (target={}, memories={}, agents={}, cross={}), budget={}",
                sb.length(), targetUsed, memoriesUsed, agentsUsed, crossUsed, budget.totalChars());

        // Hard truncate to total budget
        if (sb.length() > budget.totalChars()) {
            return sb.substring(0, budget.totalChars());
        }
        return sb.toString();
    }

    private int renderTargetProject(StringBuilder sb, ContextIndex index,
                                     String targetProjectPath, Map<String, Integer> keywords,
                                     int budgetChars, List<ContextBudget.ScoredEntry> ranked) {
        int startLen = sb.length();

        for (ProjectContext project : index.projects()) {
            if (!project.projectPath().equals(targetProjectPath)) continue;

            sb.append("## Project: ").append(project.projectName()).append("\n\n");

            // Root CLAUDE.md always included
            if (project.rootClaudeMd() != null && !project.rootClaudeMd().isBlank()) {
                sb.append("### Root CLAUDE.md\n");
                sb.append(project.rootClaudeMd()).append("\n\n");
            }

            // Domain docs sorted by relevance from ranked entries
            List<DomainClaudeMd> sortedDocs = project.domainClaudeMds().stream()
                    .sorted((a, b) -> {
                        double scoreA = relevanceScorer.scoreDomainDoc(a, keywords);
                        double scoreB = relevanceScorer.scoreDomainDoc(b, keywords);
                        return Double.compare(scoreB, scoreA);
                    })
                    .toList();

            for (DomainClaudeMd doc : sortedDocs) {
                if ((sb.length() - startLen) >= budgetChars) break;
                sb.append("### ").append(doc.domainName())
                        .append(" (").append(doc.relativePath()).append(")\n");
                sb.append(doc.content()).append("\n\n");
            }
            break;
        }

        return sb.length() - startLen;
    }

    private int renderMemories(StringBuilder sb, PersonalKnowledge pk,
                                Map<String, Integer> keywords, int budgetChars,
                                List<ContextBudget.ScoredEntry> ranked) {
        int startLen = sb.length();

        // Get memories sorted by relevance
        List<MemoryEntry> sortedMemories = pk.memories().stream()
                .sorted((a, b) -> {
                    double scoreA = relevanceScorer.scoreMemory(a, keywords);
                    double scoreB = relevanceScorer.scoreMemory(b, keywords);
                    return Double.compare(scoreB, scoreA);
                })
                .toList();

        if (sortedMemories.isEmpty()) return 0;

        boolean headerAdded = false;

        for (MemoryEntry memory : sortedMemories) {
            double memScore = relevanceScorer.scoreMemory(memory, keywords);

            // Skip low-relevance non-feedback memories
            if (memScore < MEMORY_RELEVANCE_THRESHOLD
                    && !"feedback".equalsIgnoreCase(memory.type())) {
                continue;
            }

            if (!headerAdded) {
                sb.append("\n## Relevant Memories\n");
                headerAdded = true;
            }

            if ((sb.length() - startLen) >= budgetChars) break;

            sb.append("- **").append(memory.name()).append("** [").append(memory.type())
                    .append("]: ");
            if (!memory.description().isBlank()) {
                sb.append(memory.description());
            } else {
                sb.append(truncate(memory.content(), 100));
            }
            sb.append("\n");
        }

        return sb.length() - startLen;
    }

    private int renderAgentDefs(StringBuilder sb, PersonalKnowledge pk,
                                 Map<String, Integer> keywords, int budgetChars,
                                 List<ContextBudget.ScoredEntry> ranked) {
        int startLen = sb.length();

        List<AgentDefinition> sortedAgents = pk.agents().stream()
                .sorted((a, b) -> {
                    double scoreA = relevanceScorer.scoreAgent(a, keywords);
                    double scoreB = relevanceScorer.scoreAgent(b, keywords);
                    return Double.compare(scoreB, scoreA);
                })
                .toList();

        if (sortedAgents.isEmpty()) return 0;

        sb.append("\n## Available Agents\n");

        for (AgentDefinition agent : sortedAgents) {
            if ((sb.length() - startLen) >= budgetChars) break;

            double agentScore = relevanceScorer.scoreAgent(agent, keywords);
            sb.append("- **").append(agent.name()).append("**");

            if (agentScore >= AGENT_FULL_DESC_THRESHOLD && !agent.description().isBlank()) {
                sb.append(": ").append(agent.description());
            }
            if (agent.model() != null) {
                sb.append(" (model: ").append(agent.model()).append(")");
            }
            sb.append("\n");

            // Include system prompt excerpt for highly relevant agents
            if (agentScore >= AGENT_PROMPT_THRESHOLD && !agent.systemPrompt().isBlank()) {
                String excerpt = truncate(agent.systemPrompt(), 200);
                sb.append("  Prompt excerpt: ").append(excerpt).append("\n");
            }
        }

        return sb.length() - startLen;
    }

    private int renderCrossProjectSection(StringBuilder sb, ContextIndex index,
                                           String targetProjectPath,
                                           Map<String, Integer> keywords, int budgetChars,
                                           List<ContextBudget.ScoredEntry> ranked) {
        int startLen = sb.length();
        boolean headerAdded = false;

        for (ProjectContext project : index.projects()) {
            if (project.projectPath().equals(targetProjectPath)) continue;
            if ((sb.length() - startLen) >= budgetChars) break;

            double projectScore = 0.0;
            if (project.rootClaudeMd() != null && !project.rootClaudeMd().isBlank()) {
                projectScore = relevanceScorer.score(project.rootClaudeMd(), keywords);
            }

            if (!headerAdded) {
                sb.append("\n## Other Projects\n");
                headerAdded = true;
            }

            sb.append("- **").append(project.projectName()).append("** (")
                    .append(project.domainClaudeMds().size()).append(" CLAUDE.md files)");

            // Include section headers for relevant cross-project docs
            if (projectScore >= CROSS_PROJECT_HEADER_THRESHOLD
                    && project.rootClaudeMd() != null) {
                String headers = extractSectionHeaders(project.rootClaudeMd());
                if (!headers.isBlank()) {
                    sb.append("\n  Sections: ").append(headers);
                }
            }
            sb.append("\n");
        }

        // Cross-project knowledge from store
        if (projectKnowledgeStore != null) {
            List<ProjectKnowledge> allKnowledge = projectKnowledgeStore.loadAll();
            for (ProjectKnowledge pk : allKnowledge) {
                if ((sb.length() - startLen) >= budgetChars) break;
                if (targetProjectPath != null && targetProjectPath.contains(pk.projectName())) {
                    continue;
                }

                double knowledgeScore = relevanceScorer.score(
                        pk.techStack() + " " + pk.architectureSummary(), keywords);
                if (knowledgeScore >= CROSS_PROJECT_HEADER_THRESHOLD) {
                    if (!headerAdded) {
                        sb.append("\n## Other Projects\n");
                        headerAdded = true;
                    }
                    sb.append("- **").append(pk.projectName()).append("** [")
                            .append(pk.techStack()).append("]: ")
                            .append(truncate(pk.architectureSummary(), 100))
                            .append("\n");
                }
            }
        }

        return sb.length() - startLen;
    }

    // -- Entry rendering for budget sizing ------------------------------

    private String renderMemoryEntry(MemoryEntry memory) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(memory.name()).append("** [").append(memory.type()).append("]: ");
        if (!memory.description().isBlank()) {
            sb.append(memory.description());
        } else {
            sb.append(truncate(memory.content(), 100));
        }
        return sb.toString();
    }

    private String renderAgentEntry(AgentDefinition agent, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(agent.name()).append("**");
        if (score >= AGENT_FULL_DESC_THRESHOLD && !agent.description().isBlank()) {
            sb.append(": ").append(agent.description());
        }
        if (agent.model() != null) {
            sb.append(" (model: ").append(agent.model()).append(")");
        }
        if (score >= AGENT_PROMPT_THRESHOLD && !agent.systemPrompt().isBlank()) {
            sb.append("\n  Prompt excerpt: ").append(truncate(agent.systemPrompt(), 200));
        }
        return sb.toString();
    }

    private String renderCrossProjectEntry(ProjectContext project, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(project.projectName()).append("** (")
                .append(project.domainClaudeMds().size()).append(" CLAUDE.md files)");
        if (score >= CROSS_PROJECT_HEADER_THRESHOLD && project.rootClaudeMd() != null) {
            String headers = extractSectionHeaders(project.rootClaudeMd());
            if (!headers.isBlank()) {
                sb.append("\n  Sections: ").append(headers);
            }
        }
        return sb.toString();
    }

    private String renderKnowledgeEntry(ProjectKnowledge pk) {
        return "- **" + pk.projectName() + "** [" + pk.techStack() + "]: "
                + truncate(pk.architectureSummary(), 100);
    }

    // -- Utilities ------------------------------------------------------

    /**
     * Extracts Markdown section headers (## and ###) from content.
     * Returns a comma-separated list of header titles.
     */
    String extractSectionHeaders(String content) {
        if (content == null || content.isBlank()) return "";

        List<String> headers = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ") || trimmed.startsWith("### ")) {
                String header = trimmed.replaceFirst("^#{2,3}\\s+", "").trim();
                if (!header.isBlank()) {
                    headers.add(header);
                }
            }
        }
        return String.join(", ", headers);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
