package dev.conductor.server.humaninput;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} annotation processing for the
 * humaninput domain.
 *
 * <p>Required by {@link StallDetector}'s {@code @Scheduled(fixedRate = 10000)}
 * method. This is scoped to this package's configuration — if scheduling is
 * enabled elsewhere in the application, this is a harmless no-op (Spring
 * deduplicates @EnableScheduling).
 */
@Configuration
@EnableScheduling
public class HumanInputSchedulingConfig {
    // Marker configuration — enables @Scheduled annotation processing
}
