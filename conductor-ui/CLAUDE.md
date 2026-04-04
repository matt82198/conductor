# conductor-ui — Electron Desktop Dashboard

## Responsibility
Desktop app for orchestrating agents via natural language, viewing agent output, responding to input requests, managing projects, and monitoring Brain decisions.

## Status: MVP Complete — All UI features built, zero TS errors

## Setup
```bash
cd conductor-ui && npm install && npm start
```

## App Flow
LoadingScreen → WelcomeScreen → MainDashboard

## Layout (MainDashboard)
```
CommandBar + TemplatePicker (top)
AgentList (left) | EventFeed or AgentDetail (center) | 4-tab right panel
StatusBar (bottom)
```
Right panel tabs: INPUT, TASKS, PROJECTS, BRAIN

## Key Architecture
- **ONE layout:** `MainDashboard.tsx` (consolidated from 5 incremental layouts)
- **ONE WebSocket:** `useUnifiedWebSocket.ts` (consolidated from 3 hooks)
- **ONE command input:** `CommandBar.tsx` (replaced SpawnForm)
- **Purple = Brain/Conductor** accent color throughout

## Stores (Zustand)
- `conductorStore` — agents, events, connection, human input, mute
- `taskStore` — decomposition plans, task events
- `projectStore` — projects, knowledge, analysis state
- `brainDecisionStore` — brain decisions, feedback, unrated count
- `agentDetailStore` — selected agent, output cache

## Gotchas
- Vite on 5173, Electron launches after
- CORS configured server-side for localhost:5173
- Dark theme only — custom Tailwind colors in tailwind.config.js
- Superseded layout/hook files still exist but are dead code
