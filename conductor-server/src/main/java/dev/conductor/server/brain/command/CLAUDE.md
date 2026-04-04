# brain/command/ — Natural Language Command Bar

## Responsibility
Interpret free-form natural language from the UI command bar into structured commands and execute them against existing Conductor services. Two-tier interpretation: fast local pattern matching, with Claude API fallback for ambiguous input.

## Status: Built

## Contracts

### Consumes
- `BrainProperties` (from brain/) — API key and model for Tier 2 interpretation
- `AgentRegistry` (from agent/) — agent state lookups for QUERY_STATUS and KILL_AGENT
- `ClaudeProcessManager` (from process/) — spawns and kills agents
- `ProjectRegistry` (from project/) — registers projects
- `ProjectScanner` (from project/) — scans directories for projects
- `TaskDecomposer` (from brain/task/) — decomposes prompts into plans
- `TaskExecutor` (from brain/task/) — executes decomposition plans
- `ContextIngestionService` (from brain/context/) — builds context for decomposer

### Provides
- `CommandIntent` — record: action, originalText, parameters, confidence, reasoning
- `CommandResult` — record: success, message, data
- `CommandInterpreter.interpret(userInput)` -> CommandIntent — two-tier NL interpretation
- `CommandExecutor.execute(intent)` -> CommandResult — dispatches to services

### REST Endpoint (via BrainController)
| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/api/brain/command` | `{ "text": "..." }` | `{ "intent": {...}, "result": {...} }` |

## Supported Actions
| Action | Trigger Keywords | Extracted Parameters |
|--------|-----------------|---------------------|
| SPAWN_AGENT | spawn, create, start, launch + agent | name, role, projectPath, prompt |
| DECOMPOSE_TASK | decompose, break down, plan | prompt, projectPath |
| REGISTER_PROJECT | register, add, open + project | path |
| SCAN_PROJECTS | scan + path | rootPath |
| ANALYZE_PROJECT | analyze, study, learn | projectPath, projectName |
| QUERY_STATUS | status, what, how many, show, list + agent | (none) |
| KILL_AGENT | kill, stop, cancel + agent | agentId, agentName |

## Files
| File | Purpose |
|------|---------|
| `CommandIntent.java` | Record — parsed user command with action, params, confidence |
| `CommandResult.java` | Record — execution result with success, message, optional data |
| `CommandInterpreter.java` | @Service — two-tier NL interpreter (patterns + Claude API) |
| `CommandExecutor.java` | @Service — dispatches intents to existing Conductor services |

## Test Files
| File | Coverage |
|------|----------|
| `CommandInterpreterTest.java` | 46 tests: all actions, name/role/path extraction, edge cases, record defaults |

## Key Design
- Pattern matching (Tier 1) handles 90%+ of commands with zero latency and no API cost
- Claude API (Tier 2) only called when pattern matching fails or has low confidence
- CommandExecutor delegates to existing services — no duplication of business logic
- ANALYZE_PROJECT returns a placeholder; the knowledge extractor is injected with `required = false`
- All path formats supported: Windows (`C:\...`, `C:/...`) and Unix (`/...`)
- Name extraction supports: `called X`, `named X`, `"X"`, `'X'`

## Gotchas
- BrainProperties must be available (it always is since BrainConfig enables it)
- CommandInterpreter creates its own RestClient — does not depend on BrainApiClient
- Kill pattern is checked before spawn to avoid "kill agent X" matching as spawn
- Pattern matching returns confidence < 0.8 when key info is missing (e.g., register without path)
