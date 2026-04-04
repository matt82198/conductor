package dev.conductor.server.brain;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Brain domain.
 *
 * <p>Enables type-safe binding of {@code conductor.brain.*} properties from
 * application.yml to the {@link BrainProperties} record.
 */
@Configuration
@EnableConfigurationProperties(BrainProperties.class)
public class BrainConfig {
}
