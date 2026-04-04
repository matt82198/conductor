package dev.conductor.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS configuration for the Conductor REST API.
 *
 * <p>The UI runs on localhost:5173 (Vite dev server) during development while
 * the server runs on localhost:8090. Without CORS headers, every fetch() from
 * the UI would fail with a cross-origin error.
 *
 * <p>This configuration allows requests from known development origins. WebSocket
 * CORS is handled separately by each WebSocketConfig (already set to allow all
 * origins in development).
 *
 * <p>Production deployments should restrict origins further.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:5173",    // Vite dev server
                "http://localhost:3000",    // Alternative dev port
                "http://localhost:8090",    // Same-origin
                "app://."                   // Electron app
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
