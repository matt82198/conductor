package dev.conductor.server.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drops NOISE-level messages unless verbose mode is enabled.
 *
 * <p>Simple boolean toggle. When verbose mode is off (the default), messages
 * with {@link Urgency#NOISE} are silently dropped. When on, they pass through.
 *
 * <p>Thread-safe: uses a volatile boolean.
 */
@Service
public class NoiseFilter {

    private static final Logger log = LoggerFactory.getLogger(NoiseFilter.class);

    /** When true, NOISE messages are allowed through. Default: false. */
    private volatile boolean verbose = false;

    /**
     * Tests whether a message should pass through the filter.
     *
     * @param message the message to test
     * @return true if the message should be kept; false if it should be dropped
     */
    public boolean shouldKeep(QueuedMessage message) {
        if (message.urgency() == Urgency.NOISE && !verbose) {
            log.trace("Noise filtered: agent={} category={}", message.agentId(), message.category());
            return false;
        }
        return true;
    }

    /**
     * Enables or disables verbose mode.
     *
     * @param verbose true to allow NOISE messages through; false to drop them
     */
    public void setVerbose(boolean verbose) {
        boolean previous = this.verbose;
        this.verbose = verbose;
        if (previous != verbose) {
            log.info("Noise filter verbose mode: {} -> {}", previous, verbose);
        }
    }

    /**
     * Returns the current verbose mode state.
     */
    public boolean isVerbose() {
        return verbose;
    }
}
