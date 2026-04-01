package dev.conductor.server.humaninput;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PatternMatcher} — regex-based question detection.
 */
class PatternMatcherTest {

    // ─── Explicit request patterns (0.90) ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "please choose one of these options",
            "Please decide which approach to take",
            "please confirm before I proceed",
            "Please select the target branch",
            "please pick a database driver"
    })
    void explicitRequestPatterns_detectWithHighConfidence(String text) {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertTrue(result.isPresent(), "Should match: " + text);
        assertEquals(0.90, result.get().confidence(), 0.01);
        assertEquals("explicit_request_please", result.get().matchedPattern());
    }

    // ─── Tentative proposal patterns (0.85) ───────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "Should I refactor this method?",
            "shall I create a new file for this?",
            "Should I use the existing pattern?",
            "Shall I proceed with the deletion?"
    })
    void tentativeProposalPatterns_detectWithHighConfidence(String text) {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertTrue(result.isPresent(), "Should match: " + text);
        assertEquals(0.85, result.get().confidence(), 0.01);
        assertEquals("tentative_proposal_should_shall", result.get().matchedPattern());
    }

    // ─── Option-seeking patterns (0.80) ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "which approach would you prefer?",
            "Which option should we go with?",
            "which method is better here?",
            "Which way should I implement this?",
            "which strategy aligns with the codebase?"
    })
    void optionSeekingPatterns_detectWithMediumHighConfidence(String text) {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertTrue(result.isPresent(), "Should match: " + text);
        assertEquals(0.80, result.get().confidence(), 0.01);
        assertEquals("option_seeking_which", result.get().matchedPattern());
    }

    // ─── Opinion-seeking patterns (0.80) ──────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "what do you think about this approach?",
            "I'd like your preference on the naming",
            "What are your thoughts on using records here?",
            "I need your input on the database schema"
    })
    void opinionSeekingPatterns_detectWithMediumHighConfidence(String text) {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertTrue(result.isPresent(), "Should match: " + text);
        assertEquals(0.80, result.get().confidence(), 0.01);
        assertEquals("opinion_seeking", result.get().matchedPattern());
    }

    // ─── Choice presentation patterns (0.75) ─────────────────────────

    @Test
    void choicePresentation_aOrB() {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate("We can go with A or B for the implementation");
        assertTrue(result.isPresent());
        assertEquals(0.75, result.get().confidence(), 0.01);
    }

    @Test
    void choicePresentation_optionOr() {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate("option 1 or option 2?");
        assertTrue(result.isPresent());
        assertEquals(0.75, result.get().confidence(), 0.01);
    }

    // ─── Generic long question fallback (0.60) ────────────────────────

    @Test
    void genericLongQuestion_detectsWithLowConfidence() {
        String text = "Is this the right way to handle the configuration loading process?";
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertTrue(result.isPresent(), "Should match a long question ending with ?");
        assertEquals(0.60, result.get().confidence(), 0.01);
        assertEquals("generic_long_question", result.get().matchedPattern());
    }

    @Test
    void shortQuestion_doesNotMatch() {
        // Under 20 chars with "?" — should NOT match the generic pattern
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate("ok?");
        assertFalse(result.isPresent(), "Short questions should not match");
    }

    // ─── Non-matching text ────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "I will now create the file.",
            "The build completed successfully.",
            "Running tests...",
            "Done.",
            "Writing to disk"
    })
    void normalAgentOutput_doesNotMatch(String text) {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertFalse(result.isPresent(), "Should not match: " + text);
    }

    // ─── Null / blank handling ────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void nullAndBlankInput_returnsEmpty(String text) {
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertFalse(result.isPresent());
    }

    // ─── Priority: higher confidence patterns match first ─────────────

    @Test
    void higherConfidencePatternWins_whenMultipleCouldMatch() {
        // "please choose" (0.90) should win over generic question (0.60)
        String text = "Could you please choose one of the following options for the implementation?";
        Optional<PatternMatcher.PatternMatch> result = PatternMatcher.evaluate(text);
        assertTrue(result.isPresent());
        assertEquals(0.90, result.get().confidence(), 0.01);
        assertEquals("explicit_request_please", result.get().matchedPattern());
    }
}
