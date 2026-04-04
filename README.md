# Conductor

An agentic IDE for agentic development. Conductor is a meta-orchestration platform that manages fleets of Claude Code agents — not by showing you 50 terminals, but by replacing you in the loop.

## The Idea

You shouldn't have to babysit AI agents. Conductor acts as a **leader agent** — it has its own Claude API connection, reads every CLAUDE.md, memory, and skill on your system, and coordinates child agents at machine speed. It decides what to delegate, shares context between agents, responds to their questions, and handles tool approvals — all without a human bottleneck.

**Conductor learns how you work.** It watches how you interact with agents — what you approve, what you reject, how you phrase prompts, when you intervene vs. let things run — and builds a model of your behavior. Over time it replicates your decision-making style with the agents autonomously. It's not a generic orchestrator. It becomes *your* orchestrator.

**The conductor is visible.** It's not a hidden background process — it's a first-class agent in the dashboard, right alongside the child agents it manages. You see it spin up, gather context, make decisions, and delegate work. When it needs you, it asks. When it doesn't, you can watch it work or ignore it entirely. Same transparency you'd want from any team member.

You step in when it matters. Conductor handles the rest.

```
                         ┌──────────────────────┐
                         │     Conductor Brain   │
                         │  (Leader Agent - API) │
                         │  Never writes code.   │
                         │  Reads all CLAUDE.md,  │
                         │  memories, skills.     │
                         └──────────┬───────────┘
                                    │ orchestrates
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
     ┌────────▼───────┐   ┌────────▼───────┐   ┌────────▼───────┐
     │  Agent: Feature │   │  Agent: Tests  │   │  Agent: Review │
     │  (Claude CLI)   │   │  (Claude CLI)  │   │  (Claude CLI)  │
     │  writes code    │   │  writes tests  │   │  reviews PRs   │
     └────────────────┘   └────────────────┘   └────────────────┘
```

## What's Built

~23,000 lines of code. 533+ tests. Phases 0-4 complete.

| Layer | What It Does | Status |
|-------|-------------|--------|
| **Process management** | Spawn/kill/message Claude CLI agents as child processes | Done |
| **Stream parsing** | Real-time JSON event parsing from agent stdout | Done |
| **Noise reduction** | Classify, deduplicate, batch agent output by urgency | Done |
| **Human input detection** | 4-layer detection when agents are blocked (pattern, stall, tool, permission) | Done |
| **Notifications** | Route alerts by urgency, Do Not Disturb mode | Done |
| **Multi-project** | Register projects, scan for git repos and CLAUDE.md | Done |
| **Brain — Behavior model** | Learns from user interactions, auto-responds in your style | Done |
| **Brain — Context engine** | Scans CLAUDE.md files, extracts project knowledge via Claude API | Done |
| **Brain — Task decomposition** | Decomposes prompts into DAG plans, assigns to parallel agents | Done |
| **Brain — NLP command bar** | Natural language commands interpreted and executed | Done |
| **Brain — Knowledge store** | Persists extracted patterns, tech stacks, architecture summaries | Done |
| **REST + WebSocket API** | Full CRUD, real-time event streaming, brain endpoints | Done |
| **Desktop dashboard** | Electron + React: agents, events, tasks, projects, brain decisions | Done |

## Stack

| Component | Tech |
|-----------|------|
| Server | Java 21 (virtual threads), Spring Boot 3.4.4, Maven multi-module |
| UI | Electron, React 19, TypeScript, Tailwind CSS, Zustand |
| Agent Communication | Claude CLI `--output-format stream-json` via stdin/stdout |
| Brain API | Claude API via Spring RestClient (direct HTTP, no SDK) |
| Build | Maven (server), Vite (UI) |

## Quick Start

```bash
# Prerequisites: JDK 21+, Node.js 18+, Claude Code CLI installed

# Server
export JAVA_HOME="path/to/jdk21+"
export ANTHROPIC_API_KEY="your-key"    # for Brain features
./mvnw package -DskipTests
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar
# http://localhost:8090, WebSocket at ws://localhost:8090/ws/events

# UI (separate terminal)
cd conductor-ui
npm install
npm start
# Opens Electron app connecting to localhost:8090
```

## Architecture

Each server domain is isolated with its own `CLAUDE.md` defining contracts in and out. Domains communicate through Spring `ApplicationEvent` — never by reading each other's internals.

```
Electron UI  <--WebSocket-->  Spring Boot Server  <--stdin/stdout JSON-->  Claude CLI Agents
                                    |
                              Claude API (direct HTTP)
                                    |
                            Conductor Brain (leader agent)
```

### Server Modules

| Module | Responsibility |
|--------|---------------|
| `process` | Spawn/kill/communicate with Claude CLI child processes |
| `agent` | Agent registry, templates, output storage |
| `queue` | Noise filtering, deduplication, batching, muting |
| `notification` | Route alerts by urgency to UI/desktop |
| `humaninput` | Detect blocked agents, queue for user response |
| `project` | Multi-project registration and management |
| `brain/behavior` | User behavior logging, model building, feedback loop |
| `brain/context` | CLAUDE.md scanning, project knowledge extraction |
| `brain/decision` | Auto-respond or escalate based on confidence |
| `brain/task` | Task decomposition into DAG plans, parallel execution |
| `brain/command` | Natural language command interpretation |
| `api` | REST + WebSocket endpoints |
| `config` | Spring config, CORS, WebSocket registration |

See `ARCHITECTURE.md` and `ARCHITECTURE-BRAIN.md` for full design docs.

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/agents` | List all agents |
| POST | `/api/agents` | Spawn a new agent |
| GET | `/api/agents/{id}` | Agent details |
| DELETE | `/api/agents/{id}` | Kill an agent |
| GET | `/api/agents/{id}/output` | Agent execution history |
| POST | `/api/agents/{id}/message` | Send message to agent |
| GET | `/api/projects` | List registered projects |
| POST | `/api/projects/register` | Register a project |
| POST | `/api/projects/scan` | Scan directory for projects |
| GET | `/api/brain/status` | Brain module status |
| POST | `/api/brain/enable` | Toggle Brain on/off |
| GET | `/api/brain/context` | Full context index |
| POST | `/api/brain/context/refresh` | Re-scan all projects |
| GET | `/api/brain/knowledge` | All extracted project knowledge |
| POST | `/api/brain/analyze/{id}` | Deep-analyze a project via Claude API |
| POST | `/api/brain/command` | Execute natural language command |
| POST | `/api/brain/tasks` | Decompose prompt into plan and execute |
| GET | `/api/brain/tasks` | List active plans |
| POST | `/api/brain/feedback` | Rate a Brain decision |
| GET | `/api/humaninput` | Pending human input requests |
| POST | `/api/humaninput/{id}/respond` | Respond to agent question |

WebSocket: `ws://localhost:8090/ws/events` — all event types on a single connection.

## Roadmap

| Phase | Goal | Status |
|-------|------|--------|
| 0 | Spawn agent, see output | **Done** |
| 1 | Noise reduction (queue, dedup, filter) | **Done** |
| 2 | Human input detection | **Done** |
| 3 | Multi-project support | **Done** |
| 4 | Brain + tasks + commands + knowledge | **Done** |
| 5 | Personal knowledge aggregation | In Progress |
| 6 | IntelliJ / VS Code plugin | Planned |
| 7 | Analytics & cost tracking | Planned |
| 8 | Team features | Planned |
| 9 | State persistence (SQLite/Postgres) | Planned |

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions, architecture overview, and coding conventions.

Good first issues:
- Wire up cost tracking per agent in the UI
- Add rate limiting on Brain auto-responses
- Clean up terminated agent output from `AgentOutputStore`
- Make welcome screen stats dynamic (currently hardcoded)

## Design Philosophy

- **Visible orchestration** — The Brain is a first-class entity in the dashboard, not a hidden background process
- **Learn, don't configure** — Behavior model learns from your interactions rather than requiring upfront rules
- **Domain isolation** — Each module owns its CLAUDE.md contract; no cross-domain internal reads
- **Parallel by default** — Identify all work, delegate all at once, integrate as results land
- **Local only** — Everything runs on your machine, no cloud deployment

## License

MIT
