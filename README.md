# Conductor

Agentic Coding IDE. Meta-orchestration platform for managing fleets of Claude Code agents across multiple projects.

**Transform "watch 50 terminal tabs" into "glance at one dashboard, respond to what matters."**

## What it does

- Spawn and monitor Claude Code agents as child processes
- Real-time event streaming via WebSocket
- Noise filtering — classify, deduplicate, batch agent output by urgency
- Human input detection — automatically detects when agents are blocked
- Multi-project support — manage agents across codebases
- Desktop dashboard (Electron + React)

## Stack

| Component | Tech |
|-----------|------|
| Server | Java 21, Spring Boot 3.4, Virtual Threads |
| UI | Electron, React 19, TypeScript, Tailwind, Zustand |
| Agent Communication | Claude CLI `--output-format stream-json` via stdin/stdout |
| Build | Maven |

## Quick Start

```bash
# Server
export JAVA_HOME="path/to/jdk21+"
./mvnw package -DskipTests
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar

# UI (separate terminal)
cd conductor-ui
npm install
npm run dev
# Open http://localhost:5173
```

## Architecture

```
Electron UI  <--WebSocket-->  Spring Boot Server  <--stdin/stdout-->  Claude CLI Agents
```

Each domain has its own `CLAUDE.md` with contracts for independent parallel development. See `ARCHITECTURE.md` for the full design.

## Status

Phase 0-3 built. 55 Java source files, 85 tests, compiles in 3 seconds.

## License

MIT
