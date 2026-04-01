# decomposer/ — Task Decomposition & Sub-Agent Spawning (Phase 4)

## Responsibility
Meta-agent that breaks complex tasks into subtasks, spawns specialized sub-agents for each, coordinates execution, and merges results.

## Status: NOT YET BUILT

## Contracts

### Consumes
- `ClaudeProcessManager.spawnAgent()` (from process/) — to spawn sub-agents
- `AgentRegistry` (from agent/) — to track sub-agent lifecycle
- `AgentStreamEvent` (from process/) — to monitor sub-agent progress

### Provides
- `TaskDecomposer.decompose(taskDescription, projectPath)` → DecompositionPlan
- `TaskExecutionEngine.execute(plan)` → tracks execution across sub-agents
- Publishes `TaskProgressEvent(planId, completedSubtasks, totalSubtasks)` via Spring events

## Planned Files
| File | Purpose |
|------|---------|
| `DecompositionPlan.java` | Record: planId, subtasks (DAG), dependencies, estimated cost |
| `Subtask.java` | Record: id, description, role, dependsOn[], status |
| `TaskDecomposer.java` | @Service — spawns a Claude agent in Plan mode to break down the task |
| `TaskExecutionEngine.java` | @Service — executes subtask DAG in waves (parallel where deps allow) |
| `DependencyResolver.java` | Utility — topological sort for execution ordering |
| `SubtaskCoordinator.java` | @Service — passes context/artifacts between subtasks |
| `WorktreeManager.java` | @Service — creates git worktrees for isolated sub-agent work |

## Execution Model
```
User: "Add caching to the API layer"
  ↓ TaskDecomposer (spawns a Plan-mode agent)
  ↓ DecompositionPlan:
      Wave 1 (parallel): [analyze-current-api, research-caching-patterns]
      Wave 2 (parallel): [implement-cache-layer, write-cache-config]  
      Wave 3 (sequential): [integrate-and-test]
  ↓ TaskExecutionEngine spawns agents for each subtask
  ↓ SubtaskCoordinator passes outputs between waves
  ↓ Final merge + user review
```

## Key Design
- Decomposition itself is done by a Claude agent (agent spawning agents)
- Execution is wave-based: all independent subtasks in a wave run in parallel
- Each sub-agent gets its own git worktree for isolation
- WorktreeManager cleans up worktrees after merge
- Cost budget is split across subtasks with a per-subtask cap
