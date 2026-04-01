# process/ — Claude CLI Process Management

## Responsibility
Spawn, monitor, kill, and communicate with Claude CLI child processes via stdin/stdout JSON streaming.

## Status: Phase 0 Complete

## Contracts

### Consumes
- `StreamJsonEvent` (from conductor-common) — sealed event types
- `AgentRegistry` (from agent/) — to update agent state on events
- `AgentRecord` (from agent/) — agent identity
- `Spring ApplicationEventPublisher` — to broadcast parsed events

### Provides
- `ClaudeProcessManager.spawnAgent(name, role, projectPath, prompt)` → AgentRecord
- `ClaudeProcessManager.killAgent(agentId)` → void
- `ClaudeProcessManager.sendMessage(agentId, text)` → void (writes to stdin)
- `StreamJsonParser.parse(line)` → StreamJsonEvent (stateless, thread-safe)
- Publishes `AgentStreamEvent(agentId, StreamJsonEvent)` via Spring events

### Events Published
```java
// Wraps a StreamJsonEvent with the originating agent ID
record AgentStreamEvent(String agentId, StreamJsonEvent event) {}
```
Any @EventListener for `AgentStreamEvent` receives all parsed CLI output.

## Files
| File | Purpose |
|------|---------|
| `ClaudeProcessManager.java` | @Service — spawns `claude` CLI, reads stdout via virtual threads, publishes events |
| `StreamJsonParser.java` | Stateless utility — parses one JSON line into a StreamJsonEvent |
| `ManagedProcess.java` | Record — holds Process handle + stdin/stdout streams |

## Key Design
- Each agent gets 2 virtual threads: one for stdout, one for stderr
- stdin writes are `synchronized` on the ManagedProcess's OutputStream
- CLI command: `claude --verbose --output-format stream-json --input-format stream-json -p <prompt>`
- Working directory set via `ProcessBuilder.directory()`
- Max concurrent agents enforced via `ConductorProperties.maxConcurrent()`

## Gotchas
- `Process.getInputStream().read()` does NOT pin virtual threads (validated in Spike 2)
- stdin JSON format: `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}`
- Closing stdin signals end of input — don't close if you want multi-turn
- On Windows, process cleanup uses `destroyForcibly()` — no SIGTERM
