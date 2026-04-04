package dev.conductor.server.config;

import dev.conductor.server.api.TaskProgressWebSocketBroadcaster;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the {@code /ws/tasks} WebSocket endpoint for real-time
 * task decomposition progress events.
 *
 * <p>This configuration complements the existing {@link WebSocketConfig}
 * ({@code /ws/events}) and {@link AdditionalWebSocketConfig}
 * ({@code /ws/notifications}). The tasks endpoint broadcasts:
 * <ul>
 *   <li>{@code task_progress} — when a subtask completes or a plan advances</li>
 * </ul>
 *
 * <p>CORS is allowed from all origins during development. This must be
 * locked down before any multi-user deployment.
 */
@Configuration
public class TaskWebSocketConfig implements WebSocketConfigurer {

    private final TaskProgressWebSocketBroadcaster taskProgressBroadcaster;

    public TaskWebSocketConfig(TaskProgressWebSocketBroadcaster taskProgressBroadcaster) {
        this.taskProgressBroadcaster = taskProgressBroadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(taskProgressBroadcaster, "/ws/tasks")
                .setAllowedOrigins("*");
    }
}
