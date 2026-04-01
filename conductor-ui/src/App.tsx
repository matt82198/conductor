import { SpawnForm } from './components/SpawnForm';
import { AgentList } from './components/AgentList';
import { EventFeed } from './components/EventFeed';
import { StatusBar } from './components/StatusBar';
import { useWebSocket } from './hooks/useWebSocket';

/**
 * Root application layout.
 *
 * ┌──────────────────────────────────────────┐
 * │ SpawnForm                                │
 * ├──────────┬───────────────────────────────┤
 * │ AgentList│ EventFeed                     │
 * │          │                               │
 * ├──────────┴───────────────────────────────┤
 * │ StatusBar                                │
 * └──────────────────────────────────────────┘
 */
export default function App() {
  // Establish WebSocket connection to Conductor server
  useWebSocket();

  return (
    <div className="flex flex-col h-screen w-screen">
      {/* Top: Spawn form */}
      <SpawnForm />

      {/* Middle: Agent list + Event feed */}
      <div className="flex flex-1 min-h-0">
        {/* Left sidebar: Agent list */}
        <div className="w-56 shrink-0">
          <AgentList />
        </div>

        {/* Center: Event feed */}
        <div className="flex-1 min-w-0">
          <EventFeed />
        </div>
      </div>

      {/* Bottom: Status bar */}
      <StatusBar />
    </div>
  );
}
