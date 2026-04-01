# agent/ — Agent Registry & Lifecycle

## Responsibility
Track all agents (identity, state, cost), provide thread-safe lookup, persist to database (Phase 1+).

## Status: Phase 0 Complete (in-memory only)

## Contracts

### Consumes
- `AgentState`, `AgentRole` (from conductor-common)

### Provides
- `AgentRecord` — immutable snapshot: id, name, role, projectPath, state, sessionId, spawnedAt, costUsd, lastActivityAt
- `AgentRegistry.register(record)` → void
- `AgentRegistry.remove(id)` → void
- `AgentRegistry.get(id)` → Optional<AgentRecord>
- `AgentRegistry.listAll()` → Collection<AgentRecord>
- `AgentRegistry.updateState(id, state)` → void
- `AgentRegistry.updateCost(id, cost)` → void
- `AgentRegistry.touchActivity(id)` → void
- `AgentRegistry.countAlive()` → long

## Files
| File | Purpose |
|------|---------|
| `AgentRecord.java` | Immutable record with `withState()`, `withCost()`, `withActivity()` builders |
| `AgentRegistry.java` | @Service — ConcurrentHashMap-based registry, atomic updates via computeIfPresent |

## Key Design
- `AgentRecord` is immutable — state changes produce new records via `with*()` methods
- Registry is thread-safe via ConcurrentHashMap
- No persistence in Phase 0 — planned: JPA entities + Postgres in Phase 3

## Future (Phase 3+)
- `AgentEntity.java` — JPA entity for persistence
- `AgentRepository.java` — Spring Data JPA
- `AgentCostTracker.java` — per-agent cost accounting from ResultEvent.totalCostUsd
- `AgentLifecycleManager.java` — crash recovery, auto-restart, health checks
