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

## What Exists Today

| Layer | What's Built | Status |
|-------|-------------|--------|
| **Process management** | Spawn/kill/message Claude CLI agents as child processes | Working |
| **Stream parsing** | Real-time JSON event parsing from agent stdout | Working |
| **Noise reduction** | Classify, deduplicate, batch agent output by urgency | Working |
| **Human input detection** | 4-layer detection when agents are blocked (pattern match, stall, tool use, permission) | Working |
| **Notifications** | Route alerts by urgency, Do Not Disturb mode | Working |
| **Multi-project** | Register projects, scan for git repos and CLAUDE.md | Working |
| **REST + WebSocket API** | Agent CRUD, real-time event streaming | Working |
| **Desktop dashboard** | Electron + React: spawn form, agent list, event feed, status bar | Working |

124 tests. Builds in 2 seconds. Boots in 1 second.

## What's Next — The Vision

### Near-term: Surface what's built
The backend has queue filtering, human input detection, and project management — but the UI only shows Phase 0 (spawn and watch). Wire up the existing backend to the dashboard: mute buttons, urgency badges, human input response panel, project selector.

### Mid-term: The Conductor Brain
Replace the human in the loop with a self-learning leader agent powered by the Claude API:
- **Behavior learning** — Observes how you interact with agents (approvals, rejections, prompt style, intervention patterns) and builds a model of your decision-making. Over time, acts as you would.
- **Context engine** — Reads and indexes all CLAUDE.md files, memories, skills, and agent configurations across the system. Every child agent gets the context it needs, automatically.
- **Task decomposition** — One prompt becomes a coordinated plan: which agents, what order, what context each gets
- **Inter-agent communication** — Agent A discovers something, Conductor shares it with Agent B immediately
- **Autonomous decision-making** — Approves tool use, answers agent questions, handles permission requests — in your style, at machine speed
- **Escalation only** — Surfaces to the human only what genuinely requires human judgment

### Long-term: Full IDE
- IntelliJ / VS Code plugin integration
- Analytics and cost optimization across agent fleets
- Team features — shared orchestration across developers

## Stack

| Component | Tech |
|-----------|------|
| Server | Java 21, Spring Boot 3.4, Virtual Threads |
| UI | Electron, React 19, TypeScript, Tailwind, Zustand |
| Agent Communication | Claude CLI `--output-format stream-json` via stdin/stdout |
| Future: Conductor Brain | Claude API (`@anthropic-ai/sdk`) |
| Build | Maven (server), Vite (UI) |

## Quick Start

```bash
# Server
export JAVA_HOME="path/to/jdk21+"
./mvnw package -DskipTests
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar
# Runs on http://localhost:8090, WebSocket on ws://localhost:8090/ws/events

# UI (separate terminal)
cd conductor-ui
npm install
npm start
```

## Architecture

Each server domain is isolated with its own `CLAUDE.md` defining contracts in and out. Domains communicate through Spring `ApplicationEvent` — never by reading each other's internals. See `ARCHITECTURE.md` for the full design.

```
Electron UI  ◄─WebSocket─►  Spring Boot Server  ◄─stdin/stdout JSON─►  Claude CLI Agents
                                    │
                             (future: Claude API)
                                    │
                            Conductor Brain (leader agent)
```

## License

MIT
