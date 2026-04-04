# Conductor - Claude Code Agent Orchestration Platform

## Mission
Transform agent management from "watch 50 terminal tabs" into "glance at one dashboard, respond to what matters."

## Status: Phase 3 Complete — Core Platform Built

## Technology Stack
- **Runtime:** Java 21 (virtual threads are load-bearing), Spring Boot 3.4.4
- **Build:** Maven multi-module (wrapper included)
- **Group:** dev.conductor
- **UI:** Electron + React 19 + TypeScript + Tailwind + Zustand
- **CLI Integration:** `claude --verbose --output-format stream-json --input-format stream-json`

## Architecture

```
┌────────────────────┐   WebSocket    ┌─────────────────────┐
│   conductor-ui     │◄──────────────►│  conductor-server    │
│   (Electron+React) │   REST API     │  (Spring Boot)       │
└────────────────────┘                └──────────┬──────────┘
                                                 │ stdin/stdout JSON
                                      ┌──────────▼──────────┐
                                      │  Claude CLI Agents   │
                                      │  (child processes)   │
                                      └──────────────────────┘
```

## Module Map (Each Has Its Own CLAUDE.md)

| Module | Path | Responsibility | CLAUDE.md |
|--------|------|----------------|-----------|
| **common** | `conductor-common/` | Shared types, enums, event contracts | `conductor-common/CLAUDE.md` |
| **process** | `conductor-server/.../process/` | Spawn/kill/communicate with Claude CLI | `conductor-server/.../process/CLAUDE.md` |
| **agent** | `conductor-server/.../agent/` | Agent registry, lifecycle, cost tracking | `conductor-server/.../agent/CLAUDE.md` |
| **queue** | `conductor-server/.../queue/` | Noise filtering, dedup, batching, muting | `conductor-server/.../queue/CLAUDE.md` |
| **notification** | `conductor-server/.../notification/` | Route alerts by urgency to UI/desktop | `conductor-server/.../notification/CLAUDE.md` |
| **humaninput** | `conductor-server/.../humaninput/` | Detect blocked agents, queue for user | `conductor-server/.../humaninput/CLAUDE.md` |
| **decomposer** | `conductor-server/.../decomposer/` | Task breakdown, sub-agent spawning | `conductor-server/.../decomposer/CLAUDE.md` |
| **project** | `conductor-server/.../project/` | Multi-project registration & management | `conductor-server/.../project/CLAUDE.md` |
| **api** | `conductor-server/.../api/` | REST + WebSocket endpoints | `conductor-server/.../api/CLAUDE.md` |
| **config** | `conductor-server/.../config/` | Spring config, properties binding | `conductor-server/.../config/CLAUDE.md` |
| **ui** | `conductor-ui/` | Electron desktop dashboard | `conductor-ui/CLAUDE.md` |

## Cardinal Rules
1. **Every domain maintains its own CLAUDE.md** — contracts in, contracts out
2. **Agents work on ONE domain at a time** — scope to the domain's package + its CLAUDE.md
3. **Domains communicate through typed events** — Spring ApplicationEventPublisher
4. **Never read another domain's internals** — only its CLAUDE.md contracts
5. **All analysis runs LOCAL** — no cloud deployment
6. **Java 21 required** — virtual threads are load-bearing, do not downgrade
7. **Maven, not Gradle** — `./mvnw package -DskipTests` to build
8. **Windows 11 primary** — test with Git Bash

## Spike Results (All Validated)
| Spike | Result | Key Finding |
|-------|--------|-------------|
| Bidirectional streaming | PASS | stdin JSON works, 720KB/agent |
| Virtual threads | PASS | No pinning, 170KB overhead, scales to 200+ |
| Stream-JSON schema | PASS | 5 event types, cost data included, trivial to parse |

## Build
```bash
export JAVA_HOME="C:/Users/matt8/.jdks/openjdk-25.0.2"
./mvnw package -DskipTests
```

## Environment
- **JAVA_HOME:** C:/Users/matt8/.jdks/openjdk-25.0.2 (JDK 25, compiles with --release 21)
- **Server port:** 8090
- **WebSocket:** ws://localhost:8090/ws/events

## Phased Delivery
| Phase | Goal | Status |
|-------|------|--------|
| 0 | Spawn agent, see output | **DONE** |
| 1 | Noise reduction (queue, dedup, filter) | **DONE** |
| 2 | Human input detection | **DONE** |
| 3 | Multi-project support | **DONE** |
| 4 | Task decomposition (meta-agent) | Planned |
| 5 | IntelliJ plugin | Planned |
| 6 | Analytics & cost tracking | Planned |
| 7 | Team features | Planned |

## Reference Docs
| File | Contents |
|------|----------|
| `ARCHITECTURE.md` | Full architectural design document |
| `PROJECT-STRUCTURE.md` | File-by-file layout specification |
| `docs/WEBSOCKET-PROTOCOL.md` | WebSocket event contract (server↔UI) |
| `spikes/spike*/RESULTS.md` | Spike validation results |
| `spikes/spike3-stream-json/STREAM_JSON_SCHEMA.md` | Claude CLI stream-json schema |
