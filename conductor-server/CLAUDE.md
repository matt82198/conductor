# conductor-server — Spring Boot Backend

## Responsibility
Manage Claude CLI agent processes, route events, serve REST + WebSocket API.

## Status: Phase 0 Complete, compiles and boots

## Build
```bash
export JAVA_HOME="C:/Users/matt8/.jdks/openjdk-25.0.2"
./mvnw package -DskipTests        # from claude-orchestrator/ root
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar
```
Server starts on port 8090. Virtual threads enabled.

## Domain Map (Each Has Its Own CLAUDE.md)

**Agents working on this module: scope to ONE domain + its CLAUDE.md. Never read another domain's internals.**

| Domain | Package | CLAUDE.md | Status | Phase |
|--------|---------|-----------|--------|-------|
| **process** | `dev.conductor.server.process` | `process/CLAUDE.md` | Built | 0 |
| **agent** | `dev.conductor.server.agent` | `agent/CLAUDE.md` | Built | 0 |
| **api** | `dev.conductor.server.api` | `api/CLAUDE.md` | Built | 0 |
| **config** | `dev.conductor.server.config` | `config/CLAUDE.md` | Built | 0 |
| **queue** | `dev.conductor.server.queue` | `queue/CLAUDE.md` | Planned | 1 |
| **notification** | `dev.conductor.server.notification` | `notification/CLAUDE.md` | Planned | 1 |
| **humaninput** | `dev.conductor.server.humaninput` | `humaninput/CLAUDE.md` | Planned | 2 |
| **project** | `dev.conductor.server.project` | `project/CLAUDE.md` | Planned | 3 |
| **decomposer** | `dev.conductor.server.decomposer` | `decomposer/CLAUDE.md` | Planned | 4 |

## Event Flow
```
Claude CLI stdout → process/StreamJsonParser → process/ClaudeProcessManager
    → publishes AgentStreamEvent (Spring ApplicationEvent)
        → queue/QueueManager (classify, dedup, filter)
            → publishes QueuedMessageEvent
                → notification/NotificationRouter (route by urgency)
                → api/EventStreamWebSocketHandler (push to UI)
        → humaninput/HumanInputDetector (check if blocked)
            → publishes HumanInputNeededEvent
                → notification/ (CRITICAL alert)
                → api/ (push to UI)
```

## Cross-Domain Contracts (the ONLY way domains talk)
| Event | Published By | Consumed By |
|-------|-------------|-------------|
| `AgentStreamEvent(agentId, StreamJsonEvent)` | process/ | queue/, humaninput/, api/ |
| `QueuedMessageEvent(QueuedMessage)` | queue/ | notification/, api/ |
| `HumanInputNeededEvent(HumanInputRequest)` | humaninput/ | notification/, api/ |
| `TaskProgressEvent(planId, completed, total)` | decomposer/ | api/ |

## Gotchas
- JAVA_HOME must point to JDK 25 (compiles with --release 21)
- Map.of() NPEs on null values — use HashMap for REST responses
- WebSocketSession.sendMessage is not thread-safe — synchronize per-session
- Virtual threads enabled via `spring.threads.virtual.enabled=true`
