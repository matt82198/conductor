package dev.conductor.server.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BrainProperties} — validates compact constructor defaults
 * and property binding behavior.
 */
class BrainPropertiesTest {

    @Test
    @DisplayName("null model defaults to claude-sonnet-4-6")
    void nullModel_defaultsToClaude() {
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, null, 100000);
        assertEquals("claude-sonnet-4-6", props.model());
    }

    @Test
    @DisplayName("explicit model is preserved")
    void explicitModel_preserved() {
        BrainProperties props = new BrainProperties(
                true, "key", "claude-opus-4", 0.9, 5, "/path/log.jsonl", 50000
        );
        assertEquals("claude-opus-4", props.model());
    }

    @Test
    @DisplayName("zero confidenceThreshold defaults to 0.8")
    void zeroConfidence_defaultsTo08() {
        BrainProperties props = new BrainProperties(false, null, null, 0.0, 10, null, 100000);
        assertEquals(0.8, props.confidenceThreshold());
    }

    @Test
    @DisplayName("negative confidenceThreshold defaults to 0.8")
    void negativeConfidence_defaultsTo08() {
        BrainProperties props = new BrainProperties(false, null, null, -1.0, 10, null, 100000);
        assertEquals(0.8, props.confidenceThreshold());
    }

    @Test
    @DisplayName("valid confidenceThreshold is preserved")
    void validConfidence_preserved() {
        BrainProperties props = new BrainProperties(false, null, null, 0.6, 10, null, 100000);
        assertEquals(0.6, props.confidenceThreshold());
    }

    @Test
    @DisplayName("zero maxAutoResponsesPerMinute defaults to 10")
    void zeroMaxResponses_defaultsTo10() {
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 0, null, 100000);
        assertEquals(10, props.maxAutoResponsesPerMinute());
    }

    @Test
    @DisplayName("negative maxAutoResponsesPerMinute defaults to 10")
    void negativeMaxResponses_defaultsTo10() {
        BrainProperties props = new BrainProperties(false, null, null, 0.8, -5, null, 100000);
        assertEquals(10, props.maxAutoResponsesPerMinute());
    }

    @Test
    @DisplayName("null behaviorLogPath defaults to ~/.conductor/behavior-log.jsonl")
    void nullLogPath_defaultsToHome() {
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, null, 100000);
        String expected = System.getProperty("user.home") + "/.conductor/behavior-log.jsonl";
        assertEquals(expected, props.behaviorLogPath());
    }

    @Test
    @DisplayName("explicit behaviorLogPath is preserved")
    void explicitLogPath_preserved() {
        BrainProperties props = new BrainProperties(
                false, null, null, 0.8, 10, "/custom/path/log.jsonl", 100000
        );
        assertEquals("/custom/path/log.jsonl", props.behaviorLogPath());
    }

    @Test
    @DisplayName("zero contextWindowBudget defaults to 100000")
    void zeroContextBudget_defaultsTo100000() {
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, null, 0);
        assertEquals(100000, props.contextWindowBudget());
    }

    @Test
    @DisplayName("negative contextWindowBudget defaults to 100000")
    void negativeContextBudget_defaultsTo100000() {
        BrainProperties props = new BrainProperties(false, null, null, 0.8, 10, null, -1);
        assertEquals(100000, props.contextWindowBudget());
    }

    @Test
    @DisplayName("enabled flag is preserved")
    void enabledFlag_preserved() {
        BrainProperties enabled = new BrainProperties(true, null, null, 0.8, 10, null, 100000);
        assertTrue(enabled.enabled());

        BrainProperties disabled = new BrainProperties(false, null, null, 0.8, 10, null, 100000);
        assertFalse(disabled.enabled());
    }

    @Test
    @DisplayName("apiKey is preserved as-is (nullable)")
    void apiKey_preserved() {
        BrainProperties props = new BrainProperties(
                true, "sk-ant-abc123", null, 0.8, 10, null, 100000
        );
        assertEquals("sk-ant-abc123", props.apiKey());
    }

    @Test
    @DisplayName("all defaults applied when all optional fields are zero/null")
    void allDefaults_applied() {
        BrainProperties props = new BrainProperties(false, null, null, 0, 0, null, 0);

        assertFalse(props.enabled());
        assertNull(props.apiKey());
        assertEquals("claude-sonnet-4-6", props.model());
        assertEquals(0.8, props.confidenceThreshold());
        assertEquals(10, props.maxAutoResponsesPerMinute());
        assertNotNull(props.behaviorLogPath());
        assertEquals(100000, props.contextWindowBudget());
    }
}
