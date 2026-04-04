package dev.conductor.server.brain.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RelevanceScorer} covering keyword extraction, text scoring,
 * memory scoring with type boosts, agent scoring, and domain doc scoring.
 */
class RelevanceScorerTest {

    private RelevanceScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new RelevanceScorer();
    }

    // -- Keyword extraction ----------------------------------------------

    @Test
    @DisplayName("extractKeywords: returns empty map for null prompt")
    void extractKeywords_null_returnsEmpty() {
        assertTrue(scorer.extractKeywords(null).isEmpty());
    }

    @Test
    @DisplayName("extractKeywords: returns empty map for blank prompt")
    void extractKeywords_blank_returnsEmpty() {
        assertTrue(scorer.extractKeywords("   ").isEmpty());
    }

    @Test
    @DisplayName("extractKeywords: removes stop words")
    void extractKeywords_removesStopWords() {
        Map<String, Integer> keywords = scorer.extractKeywords("add the tests for this module");
        // "add", "the", "for", "this" are stop words
        assertFalse(keywords.containsKey("the"));
        assertFalse(keywords.containsKey("for"));
        assertFalse(keywords.containsKey("this"));
        assertFalse(keywords.containsKey("add"));
        // "tests" and "module" should survive
        assertTrue(keywords.containsKey("tests"));
        assertTrue(keywords.containsKey("module"));
    }

    @Test
    @DisplayName("extractKeywords: lowercases all tokens")
    void extractKeywords_lowercases() {
        Map<String, Integer> keywords = scorer.extractKeywords("Deploy OAuth2 Authentication");
        assertTrue(keywords.containsKey("deploy"));
        assertTrue(keywords.containsKey("oauth2"));
        assertTrue(keywords.containsKey("authentication"));
    }

    @Test
    @DisplayName("extractKeywords: strips single-char tokens")
    void extractKeywords_stripsSingleChar() {
        Map<String, Integer> keywords = scorer.extractKeywords("a b cd efg");
        assertFalse(keywords.containsKey("a"));
        assertFalse(keywords.containsKey("b"));
        assertTrue(keywords.containsKey("cd"));
        assertTrue(keywords.containsKey("efg"));
    }

    @Test
    @DisplayName("extractKeywords: counts frequency of repeated words")
    void extractKeywords_countsFrequency() {
        Map<String, Integer> keywords = scorer.extractKeywords("test the test runner test");
        assertEquals(3, keywords.get("test"));
        assertEquals(1, keywords.get("runner"));
    }

    @Test
    @DisplayName("extractKeywords: handles punctuation separators")
    void extractKeywords_handlesPunctuation() {
        Map<String, Integer> keywords = scorer.extractKeywords("maven/gradle build-system");
        assertTrue(keywords.containsKey("maven"));
        assertTrue(keywords.containsKey("gradle"));
        assertTrue(keywords.containsKey("build"));
        assertTrue(keywords.containsKey("system"));
    }

    // -- Text scoring ----------------------------------------------------

    @Test
    @DisplayName("score: returns 0.0 for null text")
    void score_nullText_returnsZero() {
        Map<String, Integer> keywords = Map.of("test", 1);
        assertEquals(0.0, scorer.score(null, keywords));
    }

    @Test
    @DisplayName("score: returns 0.0 for blank text")
    void score_blankText_returnsZero() {
        Map<String, Integer> keywords = Map.of("test", 1);
        assertEquals(0.0, scorer.score("   ", keywords));
    }

    @Test
    @DisplayName("score: returns 0.0 for null keywords")
    void score_nullKeywords_returnsZero() {
        assertEquals(0.0, scorer.score("some text", null));
    }

    @Test
    @DisplayName("score: returns 0.0 for empty keywords")
    void score_emptyKeywords_returnsZero() {
        assertEquals(0.0, scorer.score("some text", Map.of()));
    }

    @Test
    @DisplayName("score: returns 1.0 when all keywords match")
    void score_allKeywordsMatch() {
        Map<String, Integer> keywords = Map.of("oauth", 1, "authentication", 1);
        double result = scorer.score("This module handles OAuth authentication flow", keywords);
        assertEquals(1.0, result, 0.01);
    }

    @Test
    @DisplayName("score: returns partial score for partial match")
    void score_partialMatch() {
        Map<String, Integer> keywords = Map.of("oauth", 1, "database", 1);
        double result = scorer.score("This module handles OAuth authentication", keywords);
        // "oauth" matches (weight 2.0 because it's high-signal), "database" doesn't (weight 2.0)
        // matched=2.0, total=4.0 -> 0.5
        assertEquals(0.5, result, 0.01);
    }

    @Test
    @DisplayName("score: returns 0.0 when no keywords match")
    void score_noMatch() {
        Map<String, Integer> keywords = Map.of("kubernetes", 1, "docker", 1);
        double result = scorer.score("This module handles email sending via SMTP", keywords);
        assertEquals(0.0, result, 0.01);
    }

    @Test
    @DisplayName("score: high-signal terms get 2x weight boost")
    void score_highSignalTermsGetBoost() {
        // "api" is high-signal (2x), "foobar" is normal (1x)
        Map<String, Integer> keywords = Map.of("api", 1, "foobar", 1);
        // If only "api" matches: matched=2.0, total=2.0+1.0=3.0 -> 0.667
        double result = scorer.score("This provides a REST api for users", keywords);
        assertTrue(result > 0.6, "High-signal match should score > 0.6, got " + result);
    }

    @Test
    @DisplayName("score: case insensitive matching")
    void score_caseInsensitive() {
        Map<String, Integer> keywords = Map.of("spring", 1);
        double result = scorer.score("Uses SPRING Boot framework", keywords);
        assertEquals(1.0, result, 0.01);
    }

    @Test
    @DisplayName("score: handles substring matching")
    void score_substringMatch() {
        Map<String, Integer> keywords = Map.of("oauth2", 1);
        double result = scorer.score("Supports OAuth2 authentication", keywords);
        assertTrue(result > 0.0, "Substring match should produce positive score");
    }

    // -- Memory scoring --------------------------------------------------

    @Test
    @DisplayName("scoreMemory: feedback type gets 0.3 baseline boost")
    void scoreMemory_feedbackTypeGetBoost() {
        MemoryEntry feedback = new MemoryEntry(
                "testing_rules", "Integration tests must hit real DB", "feedback",
                "Never mock the database in integration tests",
                "billing", "/path/feedback.md"
        );
        MemoryEntry project = new MemoryEntry(
                "testing_rules", "Integration tests must hit real DB", "project",
                "Never mock the database in integration tests",
                "billing", "/path/project.md"
        );
        // Same content, different types
        Map<String, Integer> keywords = Map.of("unrelatedstuff", 1);
        double feedbackScore = scorer.scoreMemory(feedback, keywords);
        double projectScore = scorer.scoreMemory(project, keywords);

        assertTrue(feedbackScore > projectScore,
                "Feedback memory should score higher due to 0.3 boost: " + feedbackScore + " vs " + projectScore);
        assertTrue(feedbackScore >= 0.3, "Feedback boost should be at least 0.3");
    }

    @Test
    @DisplayName("scoreMemory: user type gets 0.1 boost")
    void scoreMemory_userTypeGetsSmallBoost() {
        MemoryEntry user = new MemoryEntry(
                "profile", "Senior SWE", "user",
                "User is a senior software engineer",
                "global", "/path/user.md"
        );
        MemoryEntry reference = new MemoryEntry(
                "profile", "Senior SWE", "reference",
                "User is a senior software engineer",
                "global", "/path/ref.md"
        );

        Map<String, Integer> keywords = Map.of("zzzzz", 1); // no match
        double userScore = scorer.scoreMemory(user, keywords);
        double refScore = scorer.scoreMemory(reference, keywords);

        assertTrue(userScore > refScore, "User memory should get small boost");
    }

    @Test
    @DisplayName("scoreMemory: combines name, description, and content for matching")
    void scoreMemory_combinesAllFields() {
        MemoryEntry memory = new MemoryEntry(
                "database_patterns", "SQL migration rules", "feedback",
                "Always run migrations before deploying",
                "billing", "/path/db.md"
        );

        // "migration" appears only in content, "database" in name, "sql" in description
        Map<String, Integer> keywords = Map.of("migration", 1, "database", 1, "sql", 1);
        double result = scorer.scoreMemory(memory, keywords);
        assertTrue(result > 0.5, "Should match across all fields, got " + result);
    }

    @Test
    @DisplayName("scoreMemory: score capped at 1.0")
    void scoreMemory_cappedAtOne() {
        MemoryEntry memory = new MemoryEntry(
                "feedback_testing", "test feedback", "feedback",
                "test feedback content about testing tests",
                "global", "/path/test.md"
        );
        Map<String, Integer> keywords = Map.of("test", 5, "feedback", 3, "testing", 2);
        double result = scorer.scoreMemory(memory, keywords);
        assertTrue(result <= 1.0, "Score should be capped at 1.0, got " + result);
    }

    // -- Agent scoring ---------------------------------------------------

    @Test
    @DisplayName("scoreAgent: matches on name, description, and system prompt")
    void scoreAgent_matchesAllFields() {
        AgentDefinition agent = new AgentDefinition(
                "test-generator", "Generates unit tests for Java projects",
                "opus", "You are a test generation agent...", "/path/test-gen.md"
        );

        Map<String, Integer> keywords = Map.of("test", 1, "java", 1);
        double result = scorer.scoreAgent(agent, keywords);
        assertTrue(result > 0.0, "Should match on agent fields, got " + result);
    }

    @Test
    @DisplayName("scoreAgent: description is weighted more heavily than prompt")
    void scoreAgent_descriptionWeightedHigher() {
        // Agent where keyword is only in description
        AgentDefinition descAgent = new AgentDefinition(
                "generic", "Handles OAuth2 security configuration",
                null, "A general purpose agent", "/path/a.md"
        );
        // Agent where keyword is only in system prompt
        AgentDefinition promptAgent = new AgentDefinition(
                "generic", "A general purpose agent",
                null, "Handles OAuth2 security configuration", "/path/b.md"
        );

        Map<String, Integer> keywords = Map.of("oauth2", 1);
        double descScore = scorer.scoreAgent(descAgent, keywords);
        double promptScore = scorer.scoreAgent(promptAgent, keywords);

        assertTrue(descScore >= promptScore,
                "Description-match should score at least as high: " + descScore + " vs " + promptScore);
    }

    // -- Domain doc scoring -----------------------------------------------

    @Test
    @DisplayName("scoreDomainDoc: matches on domain name, path, and content")
    void scoreDomainDoc_matchesAllFields() {
        DomainClaudeMd doc = new DomainClaudeMd(
                "server/auth/CLAUDE.md",
                "# Authentication Module\nHandles OAuth2 and JWT tokens.",
                "auth"
        );

        Map<String, Integer> keywords = Map.of("auth", 1, "oauth2", 1);
        double result = scorer.scoreDomainDoc(doc, keywords);
        assertTrue(result > 0.0, "Should match on domain doc fields, got " + result);
    }

    @Test
    @DisplayName("scoreDomainDoc: returns 0 for unrelated content")
    void scoreDomainDoc_unrelatedContent() {
        DomainClaudeMd doc = new DomainClaudeMd(
                "server/queue/CLAUDE.md",
                "# Queue Module\nManages message queues.",
                "queue"
        );

        Map<String, Integer> keywords = Map.of("oauth2", 1, "authentication", 1);
        double result = scorer.scoreDomainDoc(doc, keywords);
        assertEquals(0.0, result, 0.01);
    }
}
