package dev.conductor.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties bound from {@code conductor.*} in application.yml.
 *
 * @param agents agent-related configuration
 */
@ConfigurationProperties(prefix = "conductor")
public record ConductorProperties(
        AgentsProperties agents
) {

    /**
     * Configuration for agent management.
     *
     * @param maxConcurrent maximum number of simultaneously alive agents
     */
    public record AgentsProperties(
            int maxConcurrent
    ) {
        public AgentsProperties {
            if (maxConcurrent <= 0) {
                maxConcurrent = 200;
            }
        }
    }

    public ConductorProperties {
        if (agents == null) {
            agents = new AgentsProperties(200);
        }
    }
}
