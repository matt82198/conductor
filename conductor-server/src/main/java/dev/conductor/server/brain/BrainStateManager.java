package dev.conductor.server.brain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable runtime state wrapper for the Brain's enabled flag.
 *
 * <p>{@link BrainProperties} is an immutable record, so it cannot be toggled
 * at runtime. This service holds the enabled state as an {@link AtomicBoolean},
 * initialized from the configured value, and exposes thread-safe toggle methods.
 *
 * <p>All components that need to check whether the Brain is enabled should inject
 * this service and call {@link #isEnabled()} instead of reading
 * {@code BrainProperties.enabled()} directly.
 */
@Service
public class BrainStateManager {

    private static final Logger log = LoggerFactory.getLogger(BrainStateManager.class);

    private final AtomicBoolean enabled;

    public BrainStateManager(BrainProperties brainProperties) {
        this.enabled = new AtomicBoolean(brainProperties.enabled());
        log.info("BrainStateManager initialized — enabled={}", enabled.get());
    }

    /**
     * Returns whether the Brain is currently enabled.
     *
     * @return true if the Brain is active
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Sets the Brain enabled state.
     *
     * @param value true to enable, false to disable
     */
    public void setEnabled(boolean value) {
        boolean previous = enabled.getAndSet(value);
        if (previous != value) {
            log.info("Brain enabled state changed: {} -> {}", previous, value);
        }
    }
}
