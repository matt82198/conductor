package dev.conductor.server.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NoiseFilter}.
 */
class NoiseFilterTest {

    private NoiseFilter filter;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        filter = new NoiseFilter();
    }

    @Test
    void noise_messages_dropped_by_default() {
        QueuedMessage noise = makeMessage(Urgency.NOISE);

        assertFalse(filter.shouldKeep(noise));
    }

    @Test
    void noise_messages_pass_when_verbose() {
        filter.setVerbose(true);
        QueuedMessage noise = makeMessage(Urgency.NOISE);

        assertTrue(filter.shouldKeep(noise));
    }

    @Test
    void critical_messages_always_pass() {
        QueuedMessage critical = makeMessage(Urgency.CRITICAL);

        assertTrue(filter.shouldKeep(critical));
    }

    @Test
    void high_messages_always_pass() {
        QueuedMessage high = makeMessage(Urgency.HIGH);

        assertTrue(filter.shouldKeep(high));
    }

    @Test
    void medium_messages_always_pass() {
        QueuedMessage medium = makeMessage(Urgency.MEDIUM);

        assertTrue(filter.shouldKeep(medium));
    }

    @Test
    void low_messages_always_pass() {
        QueuedMessage low = makeMessage(Urgency.LOW);

        assertTrue(filter.shouldKeep(low));
    }

    @Test
    void verbose_toggle() {
        assertFalse(filter.isVerbose());

        filter.setVerbose(true);
        assertTrue(filter.isVerbose());

        filter.setVerbose(false);
        assertFalse(filter.isVerbose());
    }

    @Test
    void verbose_off_drops_noise_then_on_allows_it() {
        QueuedMessage noise = makeMessage(Urgency.NOISE);

        assertFalse(filter.shouldKeep(noise)); // default off

        filter.setVerbose(true);
        assertTrue(filter.shouldKeep(noise)); // verbose on

        filter.setVerbose(false);
        assertFalse(filter.shouldKeep(noise)); // verbose off again
    }

    private QueuedMessage makeMessage(Urgency urgency) {
        return new QueuedMessage(agentId, "test-agent", "some text",
                urgency, "test_category", Instant.now(), "hash", null);
    }
}
