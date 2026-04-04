# api/ — REST + WebSocket Endpoints

## Responsibility
HTTP and WebSocket surface for the conductor-ui and future clients (CLI, IntelliJ plugin).

## Status: Phase 0-3 Endpoints Built + Agent Templates

## Contracts

### Consumes
- `AgentRegistry` (from agent/) — for CRUD operations
- `ClaudeProcessManager` (from process/) — for spawn/kill/message
- `AgentStreamEvent` (from process/) — via @EventListener for WebSocket broadcast
- `MuteRegistry` (from queue/) — for per-agent mute state
- `HumanInputQueue` (from humaninput/) — for pending input requests
- `HumanInputResponder` (from humaninput/) — to pipe user responses to agents
- `HumanInputNeededEvent` (from humaninput/) — via @EventListener for WebSocket broadcast
- `QueuedMessageEvent` (from queue/) — via @EventListener for WebSocket broadcast
- `ProjectRegistry` (from project/) — for project CRUD
- `ProjectScanner` (from project/) — for directory scanning
- `DndManager` (from notification/) — for Do Not Disturb toggle
- `ProjectKnowledgeExtractor` (from brain/context/) — for project analysis
- `ProjectKnowledgeStore` (from brain/context/) — for knowledge persistence
- `AgentTemplateRegistry` (from agent/) — for template CRUD and usage tracking
- `AgentOutputStore` (from agent/) — for full agent output history
- `BrainFeedbackStore` (from brain/behavior/) — for Brain decision feedback

### Provides
- REST endpoints under `/api/`
- WebSocket endpoint at `/ws/events` (raw agent stream events)
- WebSocket endpoint at `/ws/notifications` (human input + queued message events)

## REST API
| Method | Path | Body | Response | Phase |
|--------|------|------|----------|-------|
| POST | `/api/agents/spawn` | `{name, role, projectPath, prompt}` | AgentRecord (201) | 0 |
| GET | `/api/agents` | — | List\<AgentRecord\> | 0 |
| GET | `/api/agents/{id}` | — | AgentRecord / 404 | 0 |
| DELETE | `/api/agents/{id}` | — | 200/404 | 0 |
| POST | `/api/agents/{id}/message` | `{text: "text"}` | 200/404/409/500 | 0 |
| POST | `/api/agents/{id}/mute` | `{muted: boolean}` | `{agentId, muted}` | 1 |
| GET | `/api/agents/{id}/muted` | — | `{muted: boolean}` | 1 |
| GET | `/api/humaninput/pending` | — | List\<HumanInputRequest\> | 2 |
| POST | `/api/humaninput/{requestId}/respond` | `{text: "response"}` | 200/404/409/500 | 2 |
| POST | `/api/humaninput/{requestId}/dismiss` | — | 200/404 | 2 |
| GET | `/api/humaninput/count` | — | `{count: int}` | 2 |
| GET | `/api/projects` | — | Collection\<ProjectRecord\> | 3 |
| POST | `/api/projects/register` | `{path: "/abs/path"}` | ProjectRecord (201) / 400 | 3 |
| POST | `/api/projects/scan` | `{rootPath: "/abs/path"}` | List\<ProjectRecord\> / 400 | 3 |
| DELETE | `/api/projects/{id}` | — | 200/404 | 3 |
| GET | `/api/notifications/dnd` | — | `{enabled: boolean}` | 1 |
| POST | `/api/notifications/dnd` | `{enabled: boolean}` | `{enabled: boolean}` | 1 |
| POST | `/api/brain/analyze/{projectId}` | — | ProjectKnowledge (201) / 404 / 503 | 4 |
| GET | `/api/brain/knowledge` | — | List\<ProjectKnowledge\> | 4 |
| GET | `/api/brain/knowledge/{projectId}` | — | ProjectKnowledge / 404 / 503 | 4 |
| GET | `/api/templates` | — | List\<AgentTemplate\> | — |
| GET | `/api/templates/search?q=query` | — | List\<AgentTemplate\> | — |
| GET | `/api/templates/{id}` | — | AgentTemplate / 404 | — |
| POST | `/api/templates` | `{name, description, role, defaultPrompt, tags}` | AgentTemplate (201) / 400 | — |
| PUT | `/api/templates/{id}` | `{name?, description?, role?, defaultPrompt?, tags?}` | AgentTemplate / 404 | — |
| DELETE | `/api/templates/{id}` | — | 200/404 | — |
| POST | `/api/templates/{id}/use` | `{projectPath, promptOverride?}` | `{agent, template}` (201) / 404 / 429 / 500 | — |
| GET | `/api/agents/{id}/output` | `?offset=0&limit=100` | List\<OutputEntry\> / 404 | MVP |
| GET | `/api/agents/{id}/output/count` | — | `{count: int}` / 404 | MVP |
| GET | `/api/brain/status` | — | `{enabled, model, confidenceThreshold, behaviorLogSize, projectsIndexed}` | 4 |
| GET | `/api/brain/behavior` | — | BehaviorModel | 4 |
| GET | `/api/brain/context` | — | ContextIndex | 4 |
| POST | `/api/brain/context/refresh` | — | ContextIndex | 4 |
| POST | `/api/brain/feedback` | `{requestId, decision, brainResponse, rating, correction}` | `{status, feedbackId}` | MVP |
| GET | `/api/brain/feedback` | `?limit=50` | List\<BrainFeedback\> | MVP |
| POST | `/api/brain/command` | `{text: "command"}` | `{intent, result}` | 4 |
| POST | `/api/brain/tasks` | `{prompt, projectPath}` | DecompositionPlan (201) | 4C |
| GET | `/api/brain/tasks` | — | Collection\<DecompositionPlan\> | 4C |
| GET | `/api/brain/tasks/{planId}` | — | DecompositionPlan / 404 | 4C |
| DELETE | `/api/brain/tasks/{planId}` | — | DecompositionPlan / 404 | 4C |

## WebSocket
- `/ws/events` — Raw agent stream-json events: `{agentId, eventType, event}`
- `/ws/notifications` — Domain events: `{type: "human_input_needed", request: {...}}` and `{type: "queued_message", message: {...}}`
- Protocol: Raw WebSocket (not STOMP)
- Both endpoints allow CORS from all origins (lock down for production)

## Files
| File | Purpose |
|------|---------|
| `BrainController.java` | @RestController — unified Brain endpoints: status, behavior, context, knowledge, feedback, command, tasks |
| `AgentController.java` | @RestController — agent CRUD + spawn + message |
| `AgentMuteController.java` | @RestController — per-agent mute/unmute endpoints |
| `HumanInputController.java` | @RestController — pending requests, respond, dismiss, count |
| `ProjectController.java` | @RestController — project list, register, scan, remove |
| `NotificationSettingsController.java` | @RestController — DND enable/disable |
| `EventStreamWebSocketHandler.java` | WebSocket handler — broadcasts AgentStreamEvents at /ws/events |
| `AdditionalEventWebSocketHandler.java` | WebSocket handler — broadcasts HumanInputNeeded + QueuedMessage events at /ws/notifications |
| `AgentTemplateController.java` | @RestController — template CRUD, search, and one-click spawn from template |
| `AgentOutputController.java` | @RestController — paginated agent output history retrieval |

## Test Files
| File | Coverage |
|------|----------|
| `AgentMuteControllerTest.java` | 6 tests: mute, unmute, get state, idempotency |
| `HumanInputControllerTest.java` | 10 tests: pending list, respond (success/404/409/500), dismiss, count |
| `ProjectControllerTest.java` | 8 tests: list, register (success/400/idempotent), scan, remove |
| `NotificationSettingsControllerTest.java` | 7 tests: get DND, set DND, idempotency, toggle cycle |
| `AdditionalEventWebSocketHandlerTest.java` | 10 tests: connection lifecycle, event broadcast, multi-session, closed session handling |

## Gotchas
- WebSocketSession.sendMessage is NOT thread-safe — both handlers synchronize per-session
- `Map.of()` NPEs on null values — use HashMap for REST responses if values might be null
- CORS is wide open in Phase 0 — lock down in production
- On JDK 25+, Mockito cannot inline-mock concrete classes — use real instances or test stubs
- AgentMuteController mounts under `/api/agents` alongside AgentController — Spring merges mappings
- AgentOutputController also mounts under `/api/agents` — same merge pattern
- BrainController is the single unified controller for all `/api/brain` endpoints (status, behavior, context, knowledge, feedback, command, tasks)
