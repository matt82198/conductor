# Conductor - Agent Orchestration Platform

## Mission
An agentic IDE for agentic development. Conductor is a self-learning leader agent that orchestrates fleets of Claude Code agents — replacing the human in the loop for routine decisions while maintaining full visibility and control.

## Status: MVP Built — Phase 0-4 Complete, 533+ tests

## Technology Stack
- **Runtime:** Java 21 (virtual threads are load-bearing), Spring Boot 3.4.4
- **Build:** Maven multi-module (wrapper included)
- **Group:** dev.conductor
- **UI:** Electron + React 19 + TypeScript + Tailwind + Zustand
- **CLI Integration:** `claude --verbose --output-format stream-json --input-format stream-json`
- **Brain API:** Claude API via Spring RestClient (no SDK — direct HTTP to api.anthropic.com)

## Architecture

```
┌─────────────────────┐                    ┌──────────────────────┐
│   conductor-ui      │  WebSocket/REST    │   conductor-server   │
│   (Electron+React)  │◄──────────────────►│   (Spring Boot)      │
│                     │                    │                      │
│ - Welcome Screen    │                    │ - Brain (leader)     │
│ - Command Bar (NLP) │                    │   - Behavior model   │
│ - Agent List+Detail │                    │   - Context engine   │
│ - Event Feed        │                    │   - Decision engine  │
│ - Task Panel        │                    │   - Command interp.  │
│ - Project Panel     │                    │   - Task decomposer  │
│ - Brain Decisions   │                    │   - Knowledge store  │
│ - Template Picker   │                    │ - Process manager    │
│ - Status Bar        │                    │ - Agent registry     │
└─────────────────────┘                    │ - Queue pipeline     │
                                           │ - Notifications      │
                                           │ - Human input detect │
                                           └──────────┬───────────┘
                                                      │ stdin/stdout JSON
                                           ┌──────────▼───────────┐
                                           │  Claude CLI Agents    │
                                           │  (child processes)    │
                                           └──────────────────────┘
```

## Module Map (Each Has Its Own CLAUDE.md)

| Module | Path | Responsibility | Status |
|--------|------|----------------|--------|
| **common** | `conductor-common/` | Shared types, enums, event contracts | Built |
| **process** | `conductor-server/.../process/` | Spawn/kill/communicate with Claude CLI | Built |
| **agent** | `conductor-server/.../agent/` | Agent registry, templates, output store | Built |
| **queue** | `conductor-server/.../queue/` | Noise filtering, dedup, batching, muting | Built |
| **notification** | `conductor-server/.../notification/` | Route alerts by urgency to UI/desktop | Built |
| **humaninput** | `conductor-server/.../humaninput/` | Detect blocked agents, queue for user | Built |
| **project** | `conductor-server/.../project/` | Multi-project registration & management | Built |
| **brain** | `conductor-server/.../brain/` | Leader agent: behavior, context, decisions, tasks, commands | Built |
| **brain/behavior** | `conductor-server/.../brain/behavior/` | User behavior logging, model building, feedback | Built |
| **brain/context** | `conductor-server/.../brain/context/` | CLAUDE.md scanning, project knowledge extraction | Built |
| **brain/decision** | `conductor-server/.../brain/decision/` | Auto-respond or escalate decisions | Built |
| **brain/task** | `conductor-server/.../brain/task/` | Task decomposition, DAG execution | Built |
| **brain/command** | `conductor-server/.../brain/command/` | Natural language command interpretation | Built |
| **api** | `conductor-server/.../api/` | REST + WebSocket endpoints | Built |
| **config** | `conductor-server/.../config/` | Spring config, CORS, WebSocket registration | Built |
| **ui** | `conductor-ui/` | Electron desktop dashboard | Built |

## Cardinal Rules
1. **Every domain maintains its own CLAUDE.md** — contracts in, contracts out
2. **Agents work on ONE domain at a time** — scope to the domain's package + its CLAUDE.md
3. **Domains communicate through typed events** — Spring ApplicationEventPublisher
4. **Never read another domain's internals** — only its CLAUDE.md contracts
5. **All analysis runs LOCAL** — no cloud deployment
6. **Java 21 required** — virtual threads are load-bearing, do not downgrade
7. **Maven, not Gradle** — `./mvnw package -DskipTests` to build
8. **Windows 11 primary** — test with Git Bash
9. **Brain API for orchestration only** — not for routine agent questions. Behavior model handles those.
10. **Fix everything in parallel** — don't triage, don't defer. Identify all issues, delegate all fixes simultaneously.

## Build & Run
```bash
# Environment (already set via setx on this machine)
# JAVA_HOME=C:/Users/matt8/.jdks/openjdk-25.0.2
# ANTHROPIC_API_KEY=<key in .env>

# Server
./mvnw package -DskipTests
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar

# UI (separate terminal)
cd conductor-ui && npm start
```

## Environment
- **JAVA_HOME:** C:/Users/matt8/.jdks/openjdk-25.0.2 (JDK 25, compiles with --release 21)
- **ANTHROPIC_API_KEY:** Set via setx (persisted). Also in `.env` (gitignored).
- **Server port:** 8090
- **WebSocket:** ws://localhost:8090/ws/events (unified — all event types)
- **UI dev port:** 5173 (Vite)

## Key Design Decisions
- **Brain API = RestClient, not SDK** — Direct HTTP to api.anthropic.com/v1/messages. Pattern from medallioGenAi project's GenerationService.java. No Maven dependency needed.
- **Behavior model for routine decisions, API for orchestration** — The Brain does NOT call the API to answer agent questions. It uses the behavior model (learned from user interactions). API is reserved for: command interpretation, task decomposition, project analysis.
- **Single unified WebSocket** — All events (agent stream, task progress, brain decisions, human input, queued messages) flow through `/ws/events`. UI connects once.
- **One MainDashboard layout** — Consolidated from 5 incremental layouts. Single source of truth for the UI structure.
- **Bootstrap model on first run** — Brain ships with 10 auto-approve patterns ("should I proceed", etc.) and 10 always-escalate patterns ("git push", "delete", etc.) so it's useful from day one.

## Known Issues (Being Fixed)
- Brain `enabled` defaults to `false` — needs runtime toggle
- TaskDecomposer may not read API key correctly from env var
- CommandExecutor needs null safety on extracted parameters
- Welcome screen stats are hardcoded
- Rate limiting on auto-responses not enforced yet
- AgentOutputStore doesn't clean up terminated agents

## Phased Delivery
| Phase | Goal | Status |
|-------|------|--------|
| 0 | Spawn agent, see output | **DONE** |
| 1 | Noise reduction (queue, dedup, filter) | **DONE** |
| 2 | Human input detection | **DONE** |
| 3 | Multi-project support | **DONE** |
| 4 | Brain + task decomposition + command bar + templates + knowledge | **DONE** |
| 5 | IntelliJ plugin | Planned |
| 6 | Analytics & cost tracking | Planned |
| 7 | Team features | Planned |
| 8 | State persistence (SQLite/Postgres) | Planned |

## Reference Docs
| File | Contents |
|------|----------|
| `ARCHITECTURE.md` | Original architectural design document |
| `ARCHITECTURE-BRAIN.md` | Brain layer design: behavior learning, context engine, task decomposition |
| `README.md` | Product vision: self-learning leader agent, visible orchestration |
| `PROJECT-STRUCTURE.md` | File-by-file layout specification |
| `docs/WEBSOCKET-PROTOCOL.md` | WebSocket event contract (server↔UI) |

## Session Pattern (How This Was Built)
This project was built using the exact pattern Conductor should automate:
1. Read all CLAUDE.md contracts to understand the system
2. Identify all work items and gaps honestly
3. Delegate to parallel agents scoped to independent domains
4. Integrate results as they land, fix conflicts immediately
5. Never defer — fix everything that's identified
6. Log learnings for future sessions

~23,000 lines of code, 533+ tests, full Electron dashboard built in one extended session.
