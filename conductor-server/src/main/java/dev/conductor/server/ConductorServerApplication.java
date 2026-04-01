package dev.conductor.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Conductor server.
 *
 * <p>Virtual threads are enabled via {@code spring.threads.virtual.enabled=true}
 * in application.yml, allowing the server to manage hundreds of concurrent
 * agent processes without thread pool exhaustion.
 */
@SpringBootApplication
public class ConductorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConductorServerApplication.class, args);
    }
}
