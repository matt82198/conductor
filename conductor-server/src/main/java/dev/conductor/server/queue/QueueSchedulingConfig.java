package dev.conductor.server.queue;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} task execution for the queue package.
 *
 * <p>Required by {@link MessageDeduplicator} (cleanup every 30s)
 * and {@link MessageBatcher} (flush every 5s).
 *
 * <p>This is a no-op if scheduling is already enabled elsewhere.
 * Spring merges duplicate {@code @EnableScheduling} annotations safely.
 */
@Configuration
@EnableScheduling
public class QueueSchedulingConfig {
    // Marker configuration -- no beans needed
}
