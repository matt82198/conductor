# config/ — Spring Configuration

## Responsibility
Spring Boot configuration beans, property binding, and infrastructure setup.

## Status: Phase 0 Complete

## Files
| File | Purpose |
|------|---------|
| `ConductorProperties.java` | @ConfigurationProperties record binding `conductor.agents.*` |
| `WebSocketConfig.java` | Registers `/ws/events` endpoint, CORS config |
| `JacksonConfig.java` | ObjectMapper with JavaTimeModule, lenient deserialization |
| `PropertiesConfig.java` | Enables @ConfigurationProperties binding |

## Properties (application.yml)
```yaml
server.port: 8090
spring.threads.virtual.enabled: true
conductor.agents.max-concurrent: 200
```

## Gotchas
- `@ConfigurationProperties` on records requires Spring Boot 3.2+
- CORS in WebSocketConfig allows `*` — lock down for production
- JacksonConfig disables WRITE_DATES_AS_TIMESTAMPS — Instants serialize as ISO strings
