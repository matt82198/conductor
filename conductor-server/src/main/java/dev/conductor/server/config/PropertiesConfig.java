package dev.conductor.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables type-safe configuration property binding for all
 * {@code @ConfigurationProperties} classes in the config package.
 */
@Configuration
@EnableConfigurationProperties(ConductorProperties.class)
public class PropertiesConfig {
}
