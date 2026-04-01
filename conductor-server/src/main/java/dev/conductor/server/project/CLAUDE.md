# project/ — Multi-Project Management (Phase 3)

## Responsibility
Register, track, and manage multiple IntelliJ/codebase projects. Each agent is scoped to a project.

## Status: Phase 3 Complete

## Contracts

### Consumes
- Nothing directly — this is a foundational registry

### Provides
- `ProjectRecord` — immutable record: id (UUID String), name, path, gitRemote (nullable), agentCount, registeredAt
- `ProjectRecord.create(absolutePath)` → ProjectRecord (factory: auto-detects name from dir, gitRemote from .git/config)
- `ProjectRecord.withIncrementedAgentCount()` → ProjectRecord
- `ProjectRecord.withDecrementedAgentCount()` → ProjectRecord
- `ProjectRegistry.register(absolutePath)` → ProjectRecord (idempotent — returns existing if path already registered)
- `ProjectRegistry.remove(id)` → boolean
- `ProjectRegistry.get(id)` → Optional<ProjectRecord>
- `ProjectRegistry.getByPath(path)` → Optional<ProjectRecord>
- `ProjectRegistry.listAll()` → Collection<ProjectRecord>
- `ProjectRegistry.incrementAgentCount(id)` → Optional<ProjectRecord>
- `ProjectRegistry.decrementAgentCount(id)` → Optional<ProjectRecord>
- `ProjectRegistry.size()` → int
- `ProjectScanner.scanDirectory(rootPath)` → List<ProjectRecord> (non-recursive, scans immediate children)

## Files
| File | Purpose |
|------|---------|
| `ProjectRecord.java` | Immutable record + `create(path)` factory + git remote parser |
| `ProjectRegistry.java` | @Service — ConcurrentHashMap-backed thread-safe registry |
| `ProjectScanner.java` | @Service — scans root dir for children containing .git or CLAUDE.md |

## Key Design
- Projects are registered by path — Conductor doesn't create projects, it discovers them
- `register()` is idempotent: re-registering the same path returns the existing record
- Agent spawn requests include projectPath; registry validates it's a known project
- ProjectScanner scans only immediate children (non-recursive) for .git/ or CLAUDE.md
- Git remote parsed from `.git/config` by finding `url =` under `[remote "origin"]` section
- Each project's CLAUDE.md is read by the agent when it spawns (handled by Claude CLI, not Conductor)
- All mutations produce new immutable records via `with*` methods (same pattern as AgentRecord)
