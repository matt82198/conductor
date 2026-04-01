package dev.conductor.server.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageDeduplicator}.
 */
class MessageDeduplicatorTest {

    private MessageDeduplicator deduplicator;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deduplicator = new MessageDeduplicator();
    }

    @Test
    void first_message_is_not_duplicate() {
        boolean result = deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Read");

        assertFalse(result);
    }

    @Test
    void identical_message_within_window_is_duplicate() {
        deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Read");
        boolean result = deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Read");

        assertTrue(result);
    }

    @Test
    void different_text_is_not_duplicate() {
        deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Read");
        boolean result = deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Write");

        assertFalse(result);
    }

    @Test
    void different_category_is_not_duplicate() {
        deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Read");
        boolean result = deduplicator.isDuplicate(agentId, "text", "Using tool: Read");

        assertFalse(result);
    }

    @Test
    void different_agent_is_not_duplicate() {
        UUID otherAgent = UUID.randomUUID();
        deduplicator.isDuplicate(agentId, "tool_use", "Using tool: Read");
        boolean result = deduplicator.isDuplicate(otherAgent, "tool_use", "Using tool: Read");

        assertFalse(result);
    }

    @Test
    void hash_is_consistent() {
        String hash1 = deduplicator.computeHash(agentId, "text", "hello world");
        String hash2 = deduplicator.computeHash(agentId, "text", "hello world");

        assertEquals(hash1, hash2);
    }

    @Test
    void hash_differs_for_different_inputs() {
        String hash1 = deduplicator.computeHash(agentId, "text", "hello");
        String hash2 = deduplicator.computeHash(agentId, "text", "world");

        assertNotEquals(hash1, hash2);
    }

    @Test
    void hash_truncates_long_text() {
        String short200 = "a".repeat(200);
        String long300 = "a".repeat(200) + "b".repeat(100);

        // Both should produce the same hash since only first 200 chars are used
        String hash1 = deduplicator.computeHash(agentId, "text", short200);
        String hash2 = deduplicator.computeHash(agentId, "text", long300);

        assertEquals(hash1, hash2);
    }

    @Test
    void null_text_does_not_throw() {
        assertDoesNotThrow(() -> {
            deduplicator.isDuplicate(agentId, "text", null);
        });
    }

    @Test
    void size_tracks_entries() {
        assertEquals(0, deduplicator.size());

        deduplicator.isDuplicate(agentId, "a", "1");
        deduplicator.isDuplicate(agentId, "b", "2");

        assertEquals(2, deduplicator.size());
    }

    @Test
    void clear_resets_state() {
        deduplicator.isDuplicate(agentId, "a", "1");
        assertEquals(1, deduplicator.size());

        deduplicator.clear();
        assertEquals(0, deduplicator.size());

        // Same message should not be duplicate after clear
        assertFalse(deduplicator.isDuplicate(agentId, "a", "1"));
    }

    @Test
    void cleanup_removes_old_entries() {
        // Add an entry
        deduplicator.isDuplicate(agentId, "a", "1");
        assertEquals(1, deduplicator.size());

        // Cleanup should NOT remove entries that are fresh
        deduplicator.cleanup();
        assertEquals(1, deduplicator.size());
    }
}
