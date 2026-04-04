package dev.conductor.server.brain.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BrainFeedbackStore}.
 * Validates append, read, readRecent, size, and roundtrip serialization.
 */
class BrainFeedbackStoreTest {

    @TempDir
    Path tempDir;

    private BrainFeedbackStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        Path feedbackPath = tempDir.resolve("brain-feedback.jsonl");
        store = new BrainFeedbackStore(objectMapper, feedbackPath);
    }

    // ─── Append and readAll roundtrip ────────────────────────────────

    @Test
    void append_and_readAll_roundtrip() {
        BrainFeedback feedback = new BrainFeedback(
                "fb-1", "req-1", "RESPOND", "Yes, proceed", "GOOD", null, Instant.now()
        );

        store.append(feedback);

        List<BrainFeedback> all = store.readAll();
        assertEquals(1, all.size());
        assertEquals("fb-1", all.get(0).feedbackId());
        assertEquals("req-1", all.get(0).requestId());
        assertEquals("RESPOND", all.get(0).decision());
        assertEquals("Yes, proceed", all.get(0).brainResponse());
        assertEquals("GOOD", all.get(0).rating());
        assertNull(all.get(0).correction());
    }

    @Test
    void append_multipleEntries_preservesOrder() {
        store.append(new BrainFeedback("fb-1", "req-1", "RESPOND", "Yes", "GOOD", null, null));
        store.append(new BrainFeedback("fb-2", "req-2", "ESCALATE", null, "BAD", "Should not have escalated", null));
        store.append(new BrainFeedback("fb-3", "req-3", "RESPOND", "No", "NEUTRAL", null, null));

        List<BrainFeedback> all = store.readAll();
        assertEquals(3, all.size());
        assertEquals("fb-1", all.get(0).feedbackId());
        assertEquals("fb-2", all.get(1).feedbackId());
        assertEquals("fb-3", all.get(2).feedbackId());
    }

    @Test
    void append_badRating_withCorrection() {
        BrainFeedback feedback = new BrainFeedback(
                null, "req-1", "RESPOND", "Delete all files",
                "BAD", "Never auto-delete. Always escalate file deletions.", null
        );

        store.append(feedback);

        List<BrainFeedback> all = store.readAll();
        assertEquals(1, all.size());
        assertEquals("BAD", all.get(0).rating());
        assertEquals("Never auto-delete. Always escalate file deletions.", all.get(0).correction());
        assertNotNull(all.get(0).feedbackId()); // auto-generated
    }

    @Test
    void append_null_isIgnored() {
        store.append(null);
        assertEquals(0, store.size());
    }

    // ─── readRecent ──────────────────────────────────────────────────

    @Test
    void readRecent_returnsLastN() {
        for (int i = 0; i < 10; i++) {
            store.append(new BrainFeedback(
                    "fb-" + i, "req-" + i, "RESPOND", "Response " + i, "GOOD", null, null));
        }

        List<BrainFeedback> recent = store.readRecent(3);
        assertEquals(3, recent.size());
        assertEquals("fb-7", recent.get(0).feedbackId());
        assertEquals("fb-8", recent.get(1).feedbackId());
        assertEquals("fb-9", recent.get(2).feedbackId());
    }

    @Test
    void readRecent_moreThanAvailable_returnsAll() {
        store.append(new BrainFeedback("fb-1", "req-1", "RESPOND", "Yes", "GOOD", null, null));
        store.append(new BrainFeedback("fb-2", "req-2", "RESPOND", "No", "BAD", "Wrong", null));

        List<BrainFeedback> recent = store.readRecent(100);
        assertEquals(2, recent.size());
    }

    @Test
    void readRecent_zeroOrNegative_returnsEmpty() {
        store.append(new BrainFeedback("fb-1", "req-1", "RESPOND", "Yes", "GOOD", null, null));

        assertTrue(store.readRecent(0).isEmpty());
        assertTrue(store.readRecent(-1).isEmpty());
    }

    // ─── size ────────────────────────────────────────────────────────

    @Test
    void size_correct() {
        assertEquals(0, store.size());

        store.append(new BrainFeedback(null, "req-1", "RESPOND", "Yes", "GOOD", null, null));
        assertEquals(1, store.size());

        store.append(new BrainFeedback(null, "req-2", "ESCALATE", null, "BAD", "Wrong", null));
        assertEquals(2, store.size());
    }

    // ─── Empty store ─────────────────────────────────────────────────

    @Test
    void emptyStore_readAll_returnsEmpty() {
        assertTrue(store.readAll().isEmpty());
    }

    @Test
    void emptyStore_readRecent_returnsEmpty() {
        assertTrue(store.readRecent(10).isEmpty());
    }

    // ─── Defaults ────────────────────────────────────────────────────

    @Test
    void feedbackRecord_defaultsApplied() {
        BrainFeedback feedback = new BrainFeedback(null, "req-1", null, null, null, null, null);

        assertNotNull(feedback.feedbackId());
        assertNotNull(feedback.timestamp());
        assertEquals("NEUTRAL", feedback.rating());
        assertEquals("UNKNOWN", feedback.decision());
    }
}
