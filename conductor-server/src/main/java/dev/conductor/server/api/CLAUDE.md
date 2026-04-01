# api/ — REST + WebSocket Endpoints

## Responsibility
HTTP and WebSocket surface for the conductor-ui and future clients (CLI, IntelliJ plugin).

## Status: Phase 0 Complete

## Contracts

### Consumes
- `AgentRegistry` (from agent/) — for CRUD operations
- `ClaudeProcessManager` (from process/) — for spawn/kill/message
- `AgentStreamEvent` (from process/) — via @EventListener for WebSocket broadcast
- Future: `QueuedMessageEvent` (from queue/), `HumanInputNeededEvent` (from humaninput/)

### Provides
- REST endpoints under `/api/`
- WebSocket endpoint at `/ws/events`

## REST API
| Method | Path | Body | Response | Phase |
|--------|------|------|----------|-------|
| POST | `/api/agents/spawn` | `{name, role, projectPath, prompt}` | AgentRecord | 0 |
| GET | `/api/agents` | — | List<AgentRecord> | 0 |
| GET | `/api/agents/{id}` | — | AgentRecord | 0 |
| DELETE | `/api/agents/{id}` | — | 200/404 | 0 |
| POST | `/api/agents/{id}/message` | `{message: "text"}` | 200/404 | 0 |
| POST | `/api/agents/{id}/mute` | `{muted: boolean}` | 200 | 1 |
| GET | `/api/queue/messages` | `?since=&limit=` | List<QueuedMessage> | 1 |
| GET | `/api/humaninput/pending` | — | List<HumanInputRequest> | 2 |
| POST | `/api/humaninput/{id}/respond` | `{text: "response"}` | 200 | 2 |
| GET | `/api/projects` | — | List<ProjectRecord> | 3 |
| POST | `/api/projects/register` | `{path: "/abs/path"}` | ProjectRecord | 3 |

## WebSocket
- Endpoint: `/ws/events`
- Protocol: Raw WebSocket (not STOMP)
- Server pushes JSON events to all connected clients
- Event envelope: `{agentId, eventType, timestamp, payload}`

## Files
| File | Purpose |
|------|---------|
| `AgentController.java` | @RestController — agent CRUD + spawn + message |
| `EventStreamWebSocketHandler.java` | WebSocket handler — broadcasts AgentStreamEvents |

## Gotchas
- WebSocketSession.sendMessage is NOT thread-safe — handler synchronizes per-session
- `Map.of()` NPEs on null values — use HashMap for REST responses if values might be null
- CORS is wide open in Phase 0 — lock down in production
