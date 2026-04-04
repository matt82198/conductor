package dev.conductor.server.brain.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enhances the standard context rendering with cross-project pattern knowledge.
 *
 * <p>Wraps {@link ContextIngestionService#renderForPrompt} by appending a
 * "CROSS-PROJECT PATTERN LIBRARY" section from the {@link ProjectKnowledgeStore}.
 * Allocates 25% of the character budget to knowledge and 75% to the base context.
 *
 * <p>This service exists as a separate component because the base
 * {@link ContextIngestionService} is used by other domains. Components that
 * want knowledge-enriched context (e.g., the Brain decision engine) should
 * use this service's {@link #renderForPrompt} method instead.
 */
@Service
public class KnowledgeAwareContextRenderer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAwareContextRenderer.class);

    private final ContextIngestionService contextIngestionService;
    private final ProjectKnowledgeStore projectKnowledgeStore;

    public KnowledgeAwareContextRenderer(
            ContextIngestionService contextIngestionService,
            ProjectKnowledgeStore projectKnowledgeStore
    ) {
        this.contextIngestionService = contextIngestionService;
        this.projectKnowledgeStore = projectKnowledgeStore;
    }

    /**
     * Renders context with cross-project pattern knowledge appended.
     *
     * <p>Allocates 75% of the character budget to the base context (CLAUDE.md files,
     * project structure) and 25% to the cross-project pattern library.
     *
     * @param index             the full context index
     * @param targetProjectPath the project path to prioritize
     * @param maxChars          total character budget
     * @return the enriched context string
     */
    public String renderForPrompt(ContextIndex index, String targetProjectPath, int maxChars) {
        int baseBudget = (int) (maxChars * 0.75);
        int knowledgeBudget = maxChars - baseBudget;

        String baseContext = contextIngestionService.renderForPrompt(index, targetProjectPath, baseBudget);

        String knowledgeSection = projectKnowledgeStore.renderForPrompt(knowledgeBudget);
        if (!knowledgeSection.isBlank()) {
            StringBuilder sb = new StringBuilder(baseContext);
            sb.append("\n\n## CROSS-PROJECT PATTERN LIBRARY\n");
            sb.append(knowledgeSection);

            if (sb.length() > maxChars) {
                return sb.substring(0, maxChars);
            }
            return sb.toString();
        }

        return baseContext;
    }

    /**
     * Builds a context index and renders it with knowledge in one step.
     *
     * @param targetProjectPath the project path to prioritize
     * @param maxChars          total character budget
     * @return the enriched context string
     */
    public String buildAndRender(String targetProjectPath, int maxChars) {
        ContextIndex index = contextIngestionService.buildIndex();
        return renderForPrompt(index, targetProjectPath, maxChars);
    }
}
