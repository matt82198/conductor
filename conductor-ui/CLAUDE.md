# conductor-ui — Electron Desktop Dashboard

## Responsibility
Desktop app for monitoring agents, responding to human-input requests, spawning agents, and managing noise.

## Status: Phase 0 Complete, Phase 1-2 UI components built, Phase 4 Task Decomposition UI built, Project Onboarding UI built, Brain Decisions panel built, Agent Detail panel built, Unified Dashboard consolidated

## Setup
```bash
cd conductor-ui
npm install
npm start    # Vite dev server + Electron
```

## Contracts

### Consumes (from conductor-server)
- **WebSocket** `ws://localhost:8090/ws/events` — single unified connection (useUnifiedWebSocket) handling all event types: agent streams, task_progress, brain_response, brain_escalation, human_input_needed, queued_message
- **REST** `POST /api/brain/feedback` — submit feedback on a brain decision (rating + optional correction)
- **REST** `POST /api/agents/spawn` — spawn new agent
- **REST** `GET /api/agents` — list agents (on reconnect)
- **REST** `DELETE /api/agents/{id}` — kill agent
- **REST** `GET /api/agents/{id}/output` — get full agent output/conversation history (returns OutputEntry[])
- **REST** `POST /api/agents/{id}/message` — send human response or follow-up message
- **REST** `POST /api/brain/tasks` — submit prompt for task decomposition (returns DecompositionPlan)
- **REST** `DELETE /api/brain/tasks/{planId}` — cancel a decomposition plan
- **REST** `POST /api/projects/register` — register a project by path (returns ProjectRecord)
- **REST** `POST /api/projects/scan` — scan root directory for projects (returns ProjectRecord[])
- **REST** `GET /api/projects` — list all registered projects
- **REST** `POST /api/brain/analyze/{projectId}` — trigger Brain analysis (returns ProjectKnowledge)
- **REST** `GET /api/brain/knowledge` — list all project knowledge
- **REST** `POST /api/brain/command` — natural-language command (returns CommandResponse)
- **REST** `GET /api/templates` — list agent templates (returns AgentTemplate[])
- **REST** `POST /api/templates/{id}/use` — use a template (returns TemplateUseResponse)
- See `docs/WEBSOCKET-PROTOCOL.md` for full event schema

### Provides
- Desktop window with agent list, event feed, spawn form, status bar
- Desktop notifications for CRITICAL urgency events (Phase 1+)
- Keyboard-driven workflow: Ctrl+N spawn, Esc clear, j/k navigate

## Layout (MainDashboard — unified)
```
CommandBar + TemplatePicker (top)
AgentList (left 224px) | EventFeed / AgentDetail (center) | [INPUT|TASKS|PROJECTS|BRAIN] (right 320px)
StatusBar (bottom)
```
Right panel is always visible. Tab auto-selection: INPUT (urgent) > BRAIN (unrated) > TASKS > PROJECTS.

## Key Files
| File | Purpose |
|------|---------|
| `src/stores/conductorStore.ts` | Zustand store — agents Map, events (cap 500), cost, connection |
| `src/hooks/useUnifiedWebSocket.ts` | **PRIMARY** — single WebSocket handling all event types (replaces 3-connection pattern) |
| `src/components/MainDashboard.tsx` | **PRIMARY** — unified layout with 4-tab right panel, single WebSocket, all stores |
| `src/hooks/useWebSocket.ts` | SUPERSEDED by useUnifiedWebSocket — agent-only WebSocket (kept for AppWithWelcome compat) |
| `src/hooks/useAgents.ts` | Derived sorted agent list + aggregate stats |
| `src/types/events.ts` | TypeScript interfaces matching server event schema |
| `src/components/SpawnForm.tsx` | Agent spawn form |
| `src/components/AgentList.tsx` | Agent list with state dots |
| `src/components/EventFeed.tsx` | Scrolling event feed, auto-scroll |
| `src/components/StatusBar.tsx` | Connection, count, cost |
| `src/types/taskTypes.ts` | TypeScript interfaces for decomposition plans, subtasks, progress events |
| `src/stores/taskStore.ts` | Zustand store for task decomposition state (plans, progress events) |
| `src/hooks/useTaskWebSocket.ts` | SUPERSEDED by useUnifiedWebSocket — secondary WebSocket for task_progress (dead code) |
| `src/components/TaskPanel.tsx` | Task decomposition panel: submit form, plan cards, subtask progress |
| `src/components/TaskMainLayout.tsx` | SUPERSEDED by MainDashboard — 2-tab layout (Input / Tasks) |
| `src/stores/taskStore.test.ts` | Unit tests for task store logic (run via `npx tsx`) |
| `src/types/projectTypes.ts` | TypeScript interfaces for projects, knowledge, patterns |
| `src/stores/projectStore.ts` | Zustand store for project registration, knowledge, and analysis state |
| `src/stores/projectStore.test.ts` | Unit tests for project store logic (run via `npx tsx`) |
| `src/components/ProjectPanel.tsx` | Project onboarding panel: register/scan forms, project cards, pattern views |
| `src/components/ProjectSpawnForm.tsx` | Extended SpawnForm with registered-project dropdown |
| `src/components/ProjectTaskMainLayout.tsx` | SUPERSEDED by MainDashboard — 3-tab layout (Input / Tasks / Projects) |
| `src/components/CommandBar.tsx` | Natural-language command input (replaces SpawnForm) |
| `src/components/TemplatePicker.tsx` | Template quick-action chip row below command bar |
| `src/components/CommandBarLayout.tsx` | SUPERSEDED by MainDashboard — CommandBar layout with 2-tab right panel |
| `src/types/commandTypes.ts` | Types: AgentTemplate, CommandResponse, TemplateUseRequest/Response |
| `src/components/commandBar.test.ts` | Unit tests for command bar logic (run via `npx tsx`) |
| `src/types/brainDecisionTypes.ts` | Types: BrainDecisionEntry, BrainFeedbackPayload, rating/action enums |
| `src/stores/brainDecisionStore.ts` | Zustand store for brain decision history (cap 50), feedback tracking |
| `src/stores/brainDecisionStore.test.ts` | Unit tests for brain decision store logic (run via `npx tsx`) |
| `src/components/WelcomeScreen.tsx` | Full-screen animated welcome/landing screen (CSS-only animations) |
| `src/components/AppWithWelcome.tsx` | Drop-in App replacement: LoadingScreen → WelcomeScreen → Dashboard |
| `src/components/welcomeScreen.test.ts` | Unit tests for welcome screen logic (run via `npx tsx`) |
| `src/hooks/useBrainDecisionWebSocket.ts` | SUPERSEDED by useUnifiedWebSocket — tertiary WebSocket for brain events (dead code) |
| `src/hooks/useUnifiedWebSocket.test.ts` | Unit tests for unified WebSocket routing logic (run via `npx tsx`) |
| `src/components/BrainDecisionsPanel.tsx` | Brain decisions panel: decision cards, thumbs up/down feedback, corrections |
| `src/components/BrainDecisionsMainLayout.tsx` | SUPERSEDED by MainDashboard — 4-tab layout (Input / Tasks / Projects / Brain) |
| `src/stores/agentDetailStore.ts` | Zustand store for agent detail selection and output cache |
| `src/stores/agentDetailStore.test.ts` | Unit tests for agent detail store logic (run via `npx tsx`) |
| `src/components/AgentDetail.tsx` | Full agent output/conversation panel with kill button, message input |
| `src/components/AgentDetailAgentList.tsx` | Enhanced agent list with click-to-select behavior |
| `src/components/AgentDetailMainLayout.tsx` | Extended layout: center panel swaps between EventFeed and AgentDetail |

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

## Unified Dashboard — Current Architecture

App.tsx renders: `LoadingScreen -> WelcomeScreen -> MainDashboard`

**MainDashboard** (`src/components/MainDashboard.tsx`) is the single layout component.
It consolidates the former chain of incremental layouts (TaskMainLayout, ProjectTaskMainLayout,
CommandBarLayout, BrainDecisionsMainLayout) into one component.

**useUnifiedWebSocket** (`src/hooks/useUnifiedWebSocket.ts`) is the single WebSocket hook.
It replaces the three-connection pattern (useWebSocket + useTaskWebSocket + useBrainDecisionWebSocket)
with one connection that routes messages by type:
- `task_progress` -> taskStore.processTaskProgress
- `brain_response` / `brain_escalation` -> conductorStore.processWsMessage + brainDecisionStore.addBrainDecision
- Agent stream events (agentId + eventType) -> conductorStore.processWsMessage
- `human_input_needed` / `queued_message` -> conductorStore.processWsMessage

The old layout files and WebSocket hooks are kept for backward compatibility but are dead code.
Do NOT use them for new features. All new features should integrate into MainDashboard
and useUnifiedWebSocket.

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

## Project Onboarding — Integration Notes

The project onboarding UI is fully built as standalone modules. To wire it into the app,
one change is needed in `src/App.tsx`: replace `<TaskMainLayout />` with `<ProjectTaskMainLayout />`.

```tsx
// In App.tsx, change:
import { ProjectTaskMainLayout } from './components/ProjectTaskMainLayout';
// ... and in the App component:
return <ProjectTaskMainLayout />;
```

This replaces `TaskMainLayout` with `ProjectTaskMainLayout`, which includes everything
TaskMainLayout has plus:
- A "PROJECTS" tab in the right panel alongside INPUT and TASKS
- A `ProjectSpawnForm` that shows a dropdown of registered projects when focusing the path input
- Full project management: register by path, scan directories, trigger Brain analysis
- Pattern/knowledge viewer with expandable cards showing tech stack, patterns, key files

### REST: Project onboarding endpoints
- `POST /api/projects/register` — body: `{ path }` — returns `ProjectRecord`
- `POST /api/projects/scan` — body: `{ rootPath }` — returns `ProjectRecord[]`
- `GET /api/projects` — returns all registered `ProjectRecord[]`
- `POST /api/brain/analyze/{projectId}` — triggers analysis, returns `ProjectKnowledge`
- `GET /api/brain/knowledge` — returns all `ProjectKnowledge[]`

### Store: projectStore
- `projects: ProjectRecord[]` — all registered projects
- `knowledge: Map<string, ProjectKnowledge>` — Brain-extracted knowledge by projectId
- `analyzingProjects: Set<string>` — projectIds currently being analyzed
- `initialized: boolean` — whether initial fetch has completed
- Async methods: `fetchAll()`, `registerProject(path)`, `scanProjects(rootPath)`, `analyzeProject(projectId)`

### UI flow
1. User registers a project path via the PROJECTS panel
2. Registration triggers auto-analysis (Brain analyzes the project)
3. Purple pulsing indicator shows while analysis is in progress
4. On success, card shows tech stack badge, pattern count, key files
5. User can expand any project card to see full pattern list with tags
6. Registered projects appear in the SpawnForm dropdown for quick selection

## Command Bar — Integration Notes

The Command Bar replaces the SpawnForm as the primary interaction surface. Users type
natural-language commands and the Brain figures out what to do. Built as standalone modules.

To wire it into the app, one change is needed in `src/App.tsx`: replace the current layout
with `<CommandBarLayout />`.

```tsx
// In App.tsx, change:
import { CommandBarLayout } from './components/CommandBarLayout';
// ... and in the App component:
return <CommandBarLayout />;
```

`CommandBarLayout` is a drop-in replacement for `ProjectTaskMainLayout` / `TaskMainLayout`
that swaps SpawnForm for the CommandBar. It preserves the same three-column layout with
tabbed right panel (Input / Tasks).

### Components
| File | Purpose |
|------|---------|
| `src/components/CommandBar.tsx` | Natural-language command input with history, rotating placeholders, result flash |
| `src/components/TemplatePicker.tsx` | Template quick-action chips below the command bar |
| `src/components/CommandBarLayout.tsx` | Layout replacing SpawnForm with CommandBar (drop-in for TaskMainLayout) |
| `src/types/commandTypes.ts` | AgentTemplate, CommandResponse, TemplateUseRequest/Response types |
| `src/components/commandBar.test.ts` | Unit tests for command bar logic (run via `npx tsx`) |

### REST: Command Bar endpoints
- `POST /api/brain/command` — body: `{ text }` — returns `CommandResponse` (intent + result)
- `GET /api/templates` — returns `AgentTemplate[]`
- `POST /api/templates/{id}/use` — body: `{ projectPath, promptOverride }` — returns `TemplateUseResponse`

### Keyboard shortcuts
- `Ctrl+K` — focus the command bar from anywhere
- `Enter` — submit command
- `Up/Down` — navigate command history (last 20 entries, deduplicated)
- `Escape` — clear input and blur

### Design
- Purple `▶` prompt character ties the bar to the Brain visually
- Template chips sorted by usage count (most used first), horizontally scrollable
- Transient result messages (green success / red error) auto-clear after 4 seconds
- Placeholder text rotates through 5 example commands every 4 seconds

## Brain Decisions Panel — Integration Notes

The Brain Decisions panel shows the Brain's decision history (auto-responses and escalations)
with thumbs up/down feedback buttons so the user can teach the Brain. Built as standalone modules.

To wire it into the app, one change is needed in `src/App.tsx`: replace `<ProjectTaskMainLayout />`
with `<BrainDecisionsMainLayout />`.

```tsx
// In App.tsx, change:
import { BrainDecisionsMainLayout } from './components/BrainDecisionsMainLayout';
// ... in the App component:
return <BrainDecisionsMainLayout />;
```

`BrainDecisionsMainLayout` is a drop-in replacement for `ProjectTaskMainLayout` that adds:
- A "BRAIN" tab in the right panel alongside INPUT, TASKS, PROJECTS
- Pulsing purple dot on the BRAIN tab when there are unrated decisions
- Full decision history with feedback flow

### Components
| File | Purpose |
|------|---------|
| `src/components/BrainDecisionsPanel.tsx` | Decision cards with action badges, confidence, reasoning, feedback buttons |
| `src/components/BrainDecisionsMainLayout.tsx` | Four-tab layout (Input / Tasks / Projects / Brain) |
| `src/types/brainDecisionTypes.ts` | BrainDecisionEntry, BrainFeedbackPayload types |
| `src/stores/brainDecisionStore.ts` | Zustand store: decisions (cap 50), unratedCount, feedback |
| `src/hooks/useBrainDecisionWebSocket.ts` | WebSocket listener for brain_response/brain_escalation |
| `src/stores/brainDecisionStore.test.ts` | Unit tests (run via `npx tsx`) |

### REST: Brain feedback endpoint
- `POST /api/brain/feedback` — body: `{ requestId, decision, brainResponse, rating, correction }` — submits user feedback

### Store: brainDecisionStore
- `brainDecisions: BrainDecisionEntry[]` — last 50 brain decisions
- `unratedCount: number` — count of decisions without user feedback
- `addBrainDecision(entry)` — add from WebSocket events
- `setFeedback(id, rating, correction)` — record user's thumbs up/down

### Decision card layout
- Purple left border for RESPOND, gray for ESCALATE
- Agent name (purple mono) + action badge (AUTO-RESPONDED green / ESCALATED yellow)
- Confidence percentage badge (green >= 80%, yellow >= 50%, gray < 50%)
- Response text (RESPOND only), reasoning text (gray, small)
- Thumbs up (green) / thumbs down (red) buttons
- Thumbs down opens correction text input (Enter to submit, Escape to cancel)
- After feedback: selected button highlighted, other fades

### WebSocket: brain decision messages
```json
{ "type": "brain_response", "requestId": "...", "agentId": "...", "response": "...", "confidence": 0.85, "reasoning": "..." }
{ "type": "brain_escalation", "requestId": "...", "agentId": "...", "reason": "...", "recommendation": null, "confidence": 0.3 }
```

## Welcome Screen — Integration Notes

The Welcome Screen is a premium full-screen landing experience that shows after LoadingScreen
completes but before the main dashboard. It is a brief atmospheric "vibe check" (not onboarding)
and dismisses instantly on any key press or click.

### Lifecycle
```
LoadingScreen → WelcomeScreen → BrainDecisionsMainLayout
```

### Components
| File | Purpose |
|------|---------|
| `src/components/WelcomeScreen.tsx` | Full-screen animated welcome (CSS-only animations, zero deps) |
| `src/components/AppWithWelcome.tsx` | Drop-in root component with LoadingScreen + WelcomeScreen + Dashboard |
| `src/components/welcomeScreen.test.ts` | Unit tests for animation logic (run via `npx tsx`) |

### Wiring into the app

Replace the default `<App />` import with `<AppWithWelcome />` in the entry point:

```tsx
// In src/main.tsx, change:
import AppWithWelcome from './components/AppWithWelcome';
// ... render <AppWithWelcome /> instead of <App />
```

Alternatively, modify `src/App.tsx` directly:

```tsx
import { useState, useCallback } from 'react';
import { LoadingScreen } from './components/LoadingScreen';
import { WelcomeScreen } from './components/WelcomeScreen';
import { BrainDecisionsMainLayout } from './components/BrainDecisionsMainLayout';
import { useWebSocket } from './hooks/useWebSocket';

export default function App() {
  const [loaded, setLoaded] = useState(false);
  const [welcomed, setWelcomed] = useState(false);
  const handleReady = useCallback(() => setLoaded(true), []);
  const handleEnter = useCallback(() => setWelcomed(true), []);

  useWebSocket();

  if (!loaded) return <LoadingScreen onReady={handleReady} />;
  if (!welcomed) return <WelcomeScreen onEnter={handleEnter} />;
  return <BrainDecisionsMainLayout />;
}
```

### Design details
- **Background:** Deep dark gradient (#050510 → #0a0a1a) with dual animated grid mesh
- **Title:** "CONDUCTOR" with staggered per-letter fade-in (55ms apart) and purple glow pulse
- **Subtitle:** "Unseen Productivity" fades in after title completes
- **Pulse line:** Thin purple gradient line radiates outward from center
- **Stats:** 4 items with count-up animation (requestAnimationFrame + ease-out cubic)
- **Typing:** Character-by-character reveal with blinking cursor
- **Button:** "Enter Conductor" with purple border glow pulse, hover fill
- **Dismiss:** Any key press, any click, or button click triggers 300ms fade-out
- **Color palette:** Purple accent (#8b5cf6) matching the Brain theme
- **Fonts:** JetBrains Mono (project standard)
- **Animations:** All CSS @keyframes, prefixed `wc-` to avoid conflicts

### Tests
```bash
npx tsx src/components/welcomeScreen.test.ts
```

Tests cover: easing function, count-up math, typing reveal, timing constants, letter splitting, stat definitions, fade-out behavior.

## Agent Detail Panel — Integration Notes

The Agent Detail panel shows full output/conversation history for any agent when clicked in
the left sidebar. It replaces the EventFeed in the center panel and includes a kill button,
message input, and typed/styled output entries. Built as standalone modules.

To wire it into the app, one change is needed in `src/App.tsx`: replace the current layout
with `<AgentDetailMainLayout />`.

```tsx
// In App.tsx, change:
import { AgentDetailMainLayout } from './components/AgentDetailMainLayout';
// ... in the App component:
return <AgentDetailMainLayout />;
```

`AgentDetailMainLayout` is a drop-in replacement for `BrainDecisionsMainLayout` that adds:
- Click-to-select agents in the left sidebar (blue left border highlight)
- Center panel swaps between EventFeed (no selection) and AgentDetail (agent selected)
- Back button or click-away to deselect and return to EventFeed
- All features from BrainDecisionsMainLayout preserved (four-tab right panel)

### Components
| File | Purpose |
|------|---------|
| `src/components/AgentDetail.tsx` | Full agent output panel: header, scrollable output, kill button, message input |
| `src/components/AgentDetailAgentList.tsx` | Agent list with click-to-select behavior, blue highlight |
| `src/components/AgentDetailMainLayout.tsx` | Layout swapping center panel between EventFeed and AgentDetail |
| `src/stores/agentDetailStore.ts` | Zustand store: selectedAgentId, output cache, loading state |
| `src/stores/agentDetailStore.test.ts` | Unit tests (run via `npx tsx`) |

### REST: Agent Detail endpoints
- `GET /api/agents/{id}/output` — returns `OutputEntry[]` (full conversation history)
- `DELETE /api/agents/{id}` — kills the agent process
- `POST /api/agents/{id}/message` — body: `{ text }` — sends a follow-up message

### Store: agentDetailStore
- `selectedAgentId: string | null` — currently selected agent (null = show EventFeed)
- `output: OutputEntry[]` — cached output entries for the selected agent
- `loading: boolean` — whether output is being fetched
- `fetchError: string | null` — error from last fetch attempt
- `selectAgent(id)` — select agent (or null to deselect), resets state
- `setOutput(entries)` — replace output list
- `clear()` — deselect and reset all state

### OutputEntry type
```typescript
interface OutputEntry {
  timestamp: string;   // ISO timestamp
  type: string;        // thinking | text | tool_use | tool_result | error | system
  content: string;     // full content, not truncated
  toolName: string | null;  // tool name for tool_use entries
  isError: boolean;    // true for error entries
}
```

### Output styling by type
- `thinking` — yellow italic, collapsible (click to expand/collapse)
- `text` — gray-300, full content with whitespace-pre-wrap
- `tool_use` — blue, tool name badge + code block for input
- `tool_result` — cyan, code block
- `error` — red text, red background tint
- `system` — gray-500

### Agent Detail header
- Back button (left arrow, also Escape key)
- Agent name (mono bold), role badge (purple), state dot + label
- Cost display ($X.XXXX)
- Kill button (red, only shown for non-terminated agents)

### Polling behavior
- Fetches output on mount and every 2 seconds via polling
- Auto-scrolls to bottom like EventFeed (with resume auto-scroll button)
- Message input at bottom (Enter to send, Escape to close panel)

### Tests
```bash
npx tsx src/stores/agentDetailStore.test.ts
```

Tests cover: initial state, selectAgent, deselect, agent switching, setOutput, setLoading,
setFetchError, clear, output entry types, edge cases (empty store, re-selecting same agent).

## Future Phases
- Phase 1: Filtered feed with urgency badges, mute buttons — **Built**
- Phase 2: Human Input panel with quick-respond buttons — **Built**
- Phase 3: Project selector sidebar — **Built** (ProjectPanel, ProjectTaskMainLayout)
- Phase 4: Task decomposition wizard — **Built** (TaskPanel, TaskMainLayout)
- Phase 5: IntelliJ plugin companion panel
