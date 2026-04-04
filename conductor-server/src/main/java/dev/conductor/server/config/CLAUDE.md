# config/ — Spring Configuration

## Responsibility
Spring Boot configuration beans, property binding, and infrastructure setup.

## Status: Phase 0 Complete + CORS Config

## Files
| File | Purpose |
|------|---------|
| `ConductorProperties.java` | @ConfigurationProperties record binding `conductor.agents.*` |
| `WebSocketConfig.java` | Registers `/ws/events` endpoint, CORS config |
| `AdditionalWebSocketConfig.java` | Registers `/ws/notifications` endpoint |
| `TaskWebSocketConfig.java` | Registers `/ws/tasks` endpoint |
| `JacksonConfig.java` | ObjectMapper with JavaTimeModule, lenient deserialization |
| `PropertiesConfig.java` | Enables @ConfigurationProperties binding |
| `CorsConfig.java` | @Bean CorsFilter — allows localhost:5173, :3000, :8090, app://. for REST API |

## Properties (application.yml)
```yaml
server.port: 8090
spring.threads.virtual.enabled: true
conductor.agents.max-concurrent: 200
```

## Gotchas
- `@ConfigurationProperties` on records requires Spring Boot 3.2+
- CORS in WebSocketConfig allows `*` — lock down for production
- CorsConfig handles REST API CORS (paths under /api/**); WebSocket CORS is separate
- JacksonConfig disables WRITE_DATES_AS_TIMESTAMPS — Instants serialize as ISO strings
