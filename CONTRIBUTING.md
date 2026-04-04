# Contributing to Conductor

Thanks for your interest in Conductor! We welcome contributions of all kinds — bug fixes, new features, documentation, and testing.

## Getting Started

### Prerequisites
- **JDK 21+** (virtual threads are load-bearing)
- **Node.js 18+** (for the Electron UI)
- **Claude Code CLI** installed (`claude` on PATH)
- **ANTHROPIC_API_KEY** set as env var (for Brain features; optional for core agent management)

### Setup

```bash
# Clone
git clone https://github.com/matt82198/conductor.git
cd conductor

# Build server
export JAVA_HOME="path/to/jdk21+"
./mvnw package -DskipTests

# Run server
java -jar conductor-server/target/conductor-server-0.1.0-SNAPSHOT.jar

# Build & run UI (separate terminal)
cd conductor-ui
npm install
npm start
```

### Running Tests

```bash
# All server tests (533+)
./mvnw test

# Specific module
./mvnw test -pl conductor-server

# Specific test class
./mvnw test -pl conductor-server -Dtest=ClaudeMdScannerTest
```

## Architecture

Conductor is a Maven multi-module project:

```
conductor/
  conductor-common/    — Shared types, enums, event contracts
  conductor-server/    — Spring Boot backend (all domain logic)
  conductor-ui/        — Electron + React dashboard
```

### Domain Isolation (Cardinal Rule)

Every domain module has its own `CLAUDE.md` that defines:
- What the domain does
- Its public contracts (events published, events consumed)
- Key classes and patterns

**Never read another domain's internals — only its CLAUDE.md contracts.** Domains communicate through Spring `ApplicationEventPublisher`.

### Key Domains

| Domain | Package | What It Owns |
|--------|---------|-------------|
| process | `.process` | Claude CLI child process lifecycle |
| agent | `.agent` | Agent registry, templates, output |
| queue | `.queue` | Noise filtering, dedup, batching |
| notification | `.notification` | Alert routing by urgency |
| humaninput | `.humaninput` | Blocked agent detection |
| project | `.project` | Multi-project management |
| brain/context | `.brain.context` | CLAUDE.md scanning, knowledge extraction |
| brain/behavior | `.brain.behavior` | User behavior learning |
| brain/decision | `.brain.decision` | Auto-respond vs escalate |
| brain/task | `.brain.task` | Task decomposition + DAG execution |
| brain/command | `.brain.command` | NLP command interpretation |
| api | `.api` | REST + WebSocket endpoints |

### Testing Patterns

This project was built with TDD. Every domain has comprehensive tests. When contributing:

1. **Read the existing tests** for the domain you're touching
2. **Write tests first** — they define the contract
3. **Use temp directories** for filesystem tests (see `ClaudeMdScannerTest`)
4. **Mock cross-domain dependencies** — don't reach into other domains' internals
5. **Run the full suite** before submitting a PR

## Coding Conventions

- **Java 21 records** for DTOs and value objects — compact constructors for defaults
- **No Lombok** — records handle it
- **Spring DI** via constructor injection (no `@Autowired` on fields)
- **`@Autowired(required = false)`** for optional dependencies (e.g., Brain features when no API key)
- **SLF4J logging** — `private static final Logger log = LoggerFactory.getLogger(...)` 
- **Event-driven** — publish events, don't call across domains

## Pull Request Process

1. Fork the repo and create a feature branch from `main`
2. Write tests for your changes
3. Ensure `./mvnw test` passes
4. Update the relevant `CLAUDE.md` if you change a domain's contract
5. Open a PR with a clear description of what and why

## Good First Issues

- Wire up cost tracking per agent in the StatusBar
- Add rate limiting on Brain auto-responses
- Clean up terminated agent output from `AgentOutputStore`
- Make welcome screen stats pull from live data
- Add a "copy to clipboard" button on agent output entries

## Questions?

Open an issue or start a discussion. We're happy to help you get oriented.
