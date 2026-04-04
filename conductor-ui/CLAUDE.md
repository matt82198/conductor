# conductor-ui — Electron Desktop Dashboard

## Responsibility
Desktop app for monitoring agents, responding to human-input requests, spawning agents, and managing noise.

## Status: Phase 0 Complete, Phase 1-2 UI components built, Phase 4 Task Decomposition UI built

## Setup
```bash
cd conductor-ui
npm install
npm start    # Vite dev server + Electron
```

## Contracts

### Consumes (from conductor-server)
- **WebSocket** `ws://localhost:8090/ws/events` — real-time agent events
- **WebSocket** `ws://localhost:8090/ws/events` — task_progress events (secondary connection via useTaskWebSocket)
- **REST** `POST /api/agents/spawn` — spawn new agent
- **REST** `GET /api/agents` — list agents (on reconnect)
- **REST** `DELETE /api/agents/{id}` — kill agent
- **REST** `POST /api/agents/{id}/message` — send human response
- **REST** `POST /api/brain/tasks` — submit prompt for task decomposition (returns DecompositionPlan)
- **REST** `DELETE /api/brain/tasks/{planId}` — cancel a decomposition plan
- See `docs/WEBSOCKET-PROTOCOL.md` for full event schema

### Provides
- Desktop window with agent list, event feed, spawn form, status bar
- Desktop notifications for CRITICAL urgency events (Phase 1+)
- Keyboard-driven workflow: Ctrl+N spawn, Esc clear, j/k navigate

## Layout
```
SpawnForm (top)
AgentList (left 224px) | EventFeed (center)
StatusBar (bottom)
```

## Key Files
| File | Purpose |
|------|---------|
| `src/stores/conductorStore.ts` | Zustand store — agents Map, events (cap 500), cost, connection |
| `src/hooks/useWebSocket.ts` | WebSocket with exponential backoff reconnect (3s → 30s) |
| `src/hooks/useAgents.ts` | Derived sorted agent list + aggregate stats |
| `src/types/events.ts` | TypeScript interfaces matching server event schema |
| `src/components/SpawnForm.tsx` | Agent spawn form |
| `src/components/AgentList.tsx` | Agent list with state dots |
| `src/components/EventFeed.tsx` | Scrolling event feed, auto-scroll |
| `src/components/StatusBar.tsx` | Connection, count, cost |
| `src/types/taskTypes.ts` | TypeScript interfaces for decomposition plans, subtasks, progress events |
| `src/stores/taskStore.ts` | Zustand store for task decomposition state (plans, progress events) |
| `src/hooks/useTaskWebSocket.ts` | Secondary WebSocket for task_progress messages (same endpoint, filters by type) |
| `src/components/TaskPanel.tsx` | Task decomposition panel: submit form, plan cards, subtask progress |
| `src/components/TaskMainLayout.tsx` | Extended MainLayout with tabbed right panel (Input / Tasks) |
| `src/stores/taskStore.test.ts` | Unit tests for task store logic (run via `npx tsx`) |

## State Management (Zustand)
- `agents: Map<string, AgentInfo>` — O(1) lookup by ID
- `events: ConductorEvent[]` — capped at 500 entries
- `totalCostUsd: number` — running total
- `connected: boolean` — WebSocket state
- `processEvent(event)` — dispatches on eventType, updates state

## Gotchas
- Vite must be running on 5173 before Electron launches (npm start handles this)
- CSP in index.html must whitelist ws://localhost:8090 and http://localhost:8090
- Electron TypeScript compiled separately to dist-electron/ via tsconfig.electron.json
- Dark theme only — styled for developer ergonomics

## Task Decomposition (Phase 4) — Integration Notes

The task decomposition UI is fully built as standalone modules. To wire it into the app,
one change is needed in `src/App.tsx`: replace `<MainLayout />` with `<TaskMainLayout />`.

```tsx
// In App.tsx, change:
import { TaskMainLayout } from './components/TaskMainLayout';
// ... and in the App component:
return <TaskMainLayout />;
```

Alternatively, to add `task_progress` handling to the existing WebSocket flow, add
`data.type === 'task_progress'` to the condition in `useWebSocket.ts` onmessage handler,
and add the `task_progress` union member to `ServerWsMessage` in `types/events.ts`.

The standalone approach (TaskMainLayout) avoids modifying existing files by using a
secondary WebSocket connection and a separate Zustand store.

### WebSocket: task_progress message format
```json
{ "type": "task_progress", "planId": "...", "completed": 2, "total": 5, "currentPhase": "executing" }
```

### REST: Task decomposition endpoints
- `POST /api/brain/tasks` — body: `{ prompt, projectPath }` — returns `DecompositionPlan`
- `DELETE /api/brain/tasks/{planId}` — cancels a plan

## Future Phases
- Phase 1: Filtered feed with urgency badges, mute buttons — **Built**
- Phase 2: Human Input panel with quick-respond buttons — **Built**
- Phase 3: Project selector sidebar
- Phase 4: Task decomposition wizard — **Built** (TaskPanel, TaskMainLayout)
- Phase 5: IntelliJ plugin companion panel
