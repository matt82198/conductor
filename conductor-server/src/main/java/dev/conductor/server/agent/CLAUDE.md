# agent/ — Agent Registry & Lifecycle

## Responsibility
Track all agents (identity, state, cost), provide thread-safe lookup, persist to database (Phase 1+).

## Status: Phase 0 Complete (in-memory only) + Agent Templates + Output Store

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
- `AgentTemplate` — immutable record: templateId, name, description, role, defaultPrompt, tags, usageCount, createdAt, lastUsedAt
- `AgentTemplateRegistry.save(template)` → AgentTemplate
- `AgentTemplateRegistry.get(id)` → Optional<AgentTemplate>
- `AgentTemplateRegistry.listAll()` → List<AgentTemplate> (sorted by usage desc)
- `AgentTemplateRegistry.search(query)` → List<AgentTemplate> (name/desc/tag/role search)
- `AgentTemplateRegistry.delete(id)` → boolean
- `AgentTemplateRegistry.recordUsage(id)` → AgentTemplate
- `AgentOutputStore.onAgentStreamEvent(event)` — @EventListener, captures full agent output
- `AgentOutputStore.getOutput(agentId)` → List<OutputEntry> — all stored output for an agent
- `AgentOutputStore.getOutput(agentId, offset, limit)` → List<OutputEntry> — paginated output
- `AgentOutputStore.size(agentId)` → int — entry count
- `AgentOutputStore.clear(agentId)` → void — free memory for terminated agents

## Files
| File | Purpose |
|------|---------|
| `AgentRecord.java` | Immutable record with `withState()`, `withCost()`, `withActivity()` builders |
| `AgentRegistry.java` | @Service — ConcurrentHashMap-based registry, atomic updates via computeIfPresent |
| `AgentTemplate.java` | Immutable record for reusable agent configurations (role, prompt, tags) |
| `AgentTemplateRegistry.java` | @Service — ConcurrentHashMap + JSON file persistence at `~/.conductor/agent-templates.json` |
| `AgentOutputStore.java` | @Service — captures full agent output via @EventListener, capped at 500/agent |

## Test Files
| File | Coverage |
|------|----------|
| `AgentTemplateRegistryTest.java` | 26 tests: save/get roundtrip, list sorting, search (name/tag/desc/role), delete, usage tracking, persistence reload, record defaults |
| `AgentOutputStoreTest.java` | 16 tests: store/retrieve, capping, clear, pagination, unknown agent, immutable copy |

## Key Design
- `AgentRecord` is immutable — state changes produce new records via `with*()` methods
- Registry is thread-safe via ConcurrentHashMap
- No persistence in Phase 0 — planned: JPA entities + Postgres in Phase 3

## Future (Phase 3+)
- `AgentEntity.java` — JPA entity for persistence
- `AgentRepository.java` — Spring Data JPA
- `AgentCostTracker.java` — per-agent cost accounting from ResultEvent.totalCostUsd
- `AgentLifecycleManager.java` — crash recovery, auto-restart, health checks
