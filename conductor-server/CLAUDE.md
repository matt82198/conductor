# conductor-server — Spring Boot Backend

## Responsibility
Manage Claude CLI agent processes, run the Brain (leader agent), route events, serve REST + WebSocket API.

## Status: MVP Complete — Phases 0-4 Built, 533+ tests

## Build
```bash
# Env vars already set via setx on this machine
./mvnw package -DskipTests
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar
```
Server starts on port 8090. Virtual threads enabled. Brain requires ANTHROPIC_API_KEY env var.

## Domain Map

| Domain | Package | Status |
|--------|---------|--------|
| **process** | `dev.conductor.server.process` | Built |
| **agent** | `dev.conductor.server.agent` | Built (registry + templates + output store) |
| **api** | `dev.conductor.server.api` | Built (all endpoints) |
| **config** | `dev.conductor.server.config` | Built (CORS, WebSocket, properties) |
| **queue** | `dev.conductor.server.queue` | Built |
| **notification** | `dev.conductor.server.notification` | Built |
| **humaninput** | `dev.conductor.server.humaninput` | Built |
| **project** | `dev.conductor.server.project` | Built |
| **brain** | `dev.conductor.server.brain` | Built (leader agent layer) |
| **brain/behavior** | `...brain.behavior` | Built (logging, model, feedback, bootstrap) |
| **brain/context** | `...brain.context` | Built (scanner, knowledge extractor, renderer) |
| **brain/decision** | `...brain.decision` | Built (auto-respond/escalate, rate limiting) |
| **brain/task** | `...brain.task` | Built (decompose, execute DAG, inter-agent bridge) |
| **brain/command** | `...brain.command` | Built (NLP interpreter, executor) |

## Event Flow
```
Claude CLI stdout → process/StreamJsonParser → ClaudeProcessManager
    → publishes AgentStreamEvent
        → queue/QueueManager → QueuedMessageEvent → notification/
        → humaninput/HumanInputDetector → HumanInputNeededEvent
            → brain/BrainDecisionEngine (HIGHEST_PRECEDENCE)
                → auto-respond (behavior model) OR escalate to human
        → brain/TaskExecutor (track subtask completion)
        → agent/AgentOutputStore (capture full output)
        → api/EventStreamWebSocketHandler → UI via WebSocket
```

## Cross-Domain Events
| Event | Published By | Consumed By |
|-------|-------------|-------------|
| `AgentStreamEvent` | process/ | queue/, humaninput/, brain/, agent/, api/ |
| `QueuedMessageEvent` | queue/ | notification/, api/ |
| `HumanInputNeededEvent` | humaninput/ | brain/, notification/, api/ |
| `BrainResponseEvent` | brain/ | api/ |
| `BrainEscalationEvent` | brain/ | api/ |
| `TaskProgressEvent` | brain/ | api/ |

## Gotchas
- JDK 25 needs `@Autowired` on public constructors when package-private test constructors exist
- Bean name `conductorTaskExecutor` (not `taskExecutor` — conflicts with Spring's built-in)
- Brain API = RestClient to api.anthropic.com (no SDK dependency)
- Brain enabled state is runtime-togglable via BrainStateManager (AtomicBoolean)
- `Map.of()` NPEs on null — use HashMap for REST responses with nullable values
