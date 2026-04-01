package dev.conductor.server.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageBatcher}.
 */
class MessageBatcherTest {

    private MessageBatcher batcher;
    private final List<QueuedMessage> emitted = new ArrayList<>();
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        batcher = new MessageBatcher();
        emitted.clear();
        batcher.setEmitCallback(emitted::add);
    }

    @Test
    void critical_messages_bypass_batching() {
        QueuedMessage critical = new QueuedMessage(
                agentId, "test-agent", "Need input!",
                Urgency.CRITICAL, "ask_user", Instant.now(), "hash1", null
        );

        batcher.submit(critical);

        // Should be emitted immediately, not batched
        assertEquals(1, emitted.size());
        assertEquals(Urgency.CRITICAL, emitted.get(0).urgency());
        assertEquals(0, batcher.activeBatchCount());
    }

    @Test
    void non_critical_messages_are_held_in_batch() {
        QueuedMessage medium = makeMessage(Urgency.MEDIUM, "tool_use", "Using Read");

        batcher.submit(medium);

        // Should NOT be emitted yet -- held in batch
        assertEquals(0, emitted.size());
        assertEquals(1, batcher.activeBatchCount());
    }

    @Test
    void flushAll_emits_individual_messages_when_under_threshold() {
        // Submit 2 messages (under threshold of 3)
        batcher.submit(makeMessage(Urgency.MEDIUM, "tool_use", "Using Read"));
        batcher.submit(makeMessage(Urgency.MEDIUM, "text", "Some text"));

        batcher.flushAll();

        // Both should be emitted individually
        assertEquals(2, emitted.size());
        assertNull(emitted.get(0).batchId());
        assertNull(emitted.get(1).batchId());
    }

    @Test
    void flushAll_emits_digest_when_over_threshold() {
        // Submit 5 messages (over threshold of 3)
        batcher.submit(makeMessage(Urgency.MEDIUM, "tool_use", "Using Read"));
        batcher.submit(makeMessage(Urgency.MEDIUM, "tool_use", "Using Write"));
        batcher.submit(makeMessage(Urgency.MEDIUM, "tool_use", "Using Bash"));
        batcher.submit(makeMessage(Urgency.MEDIUM, "text", "Some analysis"));
        batcher.submit(makeMessage(Urgency.MEDIUM, "text", "More text"));

        batcher.flushAll();

        // Should emit a single digest
        assertEquals(1, emitted.size());
        QueuedMessage digest = emitted.get(0);
        assertEquals("digest", digest.category());
        assertNotNull(digest.batchId());
        assertTrue(digest.text().contains("5 MEDIUM events"));
        assertTrue(digest.text().contains("tool_use x3"));
        assertTrue(digest.text().contains("text x2"));
    }

    @Test
    void different_urgencies_go_to_different_batches() {
        batcher.submit(makeMessage(Urgency.MEDIUM, "tool_use", "Using Read"));
        batcher.submit(makeMessage(Urgency.LOW, "thinking", "Thinking..."));

        // Two different batches (MEDIUM and LOW)
        assertEquals(2, batcher.activeBatchCount());
    }

    @Test
    void different_agents_go_to_different_batches() {
        UUID otherAgent = UUID.randomUUID();
        batcher.submit(makeMessage(agentId, Urgency.MEDIUM, "tool_use", "Using Read"));
        batcher.submit(makeMessage(otherAgent, Urgency.MEDIUM, "tool_use", "Using Write"));

        assertEquals(2, batcher.activeBatchCount());
    }

    @Test
    void no_callback_does_not_throw() {
        MessageBatcher noCb = new MessageBatcher();
        // No setEmitCallback -- should not throw
        assertDoesNotThrow(() -> noCb.submit(
                new QueuedMessage(agentId, "test", "msg",
                        Urgency.CRITICAL, "ask_user", Instant.now(), "h", null)
        ));
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private QueuedMessage makeMessage(Urgency urgency, String category, String text) {
        return makeMessage(agentId, urgency, category, text);
    }

    private QueuedMessage makeMessage(UUID agent, Urgency urgency, String category, String text) {
        return new QueuedMessage(agent, "test-agent", text, urgency, category, Instant.now(), "hash", null);
    }
}
