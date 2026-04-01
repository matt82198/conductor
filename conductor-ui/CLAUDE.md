# conductor-ui — Electron Desktop Dashboard

## Responsibility
Desktop app for monitoring agents, responding to human-input requests, spawning agents, and managing noise.

## Status: Phase 0 Complete

## Setup
```bash
cd conductor-ui
npm install
npm start    # Vite dev server + Electron
```

## Contracts

### Consumes (from conductor-server)
- **WebSocket** `ws://localhost:8090/ws/events` — real-time agent events
- **REST** `POST /api/agents/spawn` — spawn new agent
- **REST** `GET /api/agents` — list agents (on reconnect)
- **REST** `DELETE /api/agents/{id}` — kill agent
- **REST** `POST /api/agents/{id}/message` — send human response
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

## Future Phases
- Phase 1: Filtered feed with urgency badges, mute buttons
- Phase 2: Human Input panel with quick-respond buttons
- Phase 3: Project selector sidebar
- Phase 4: Task decomposition wizard
- Phase 5: IntelliJ plugin companion panel
