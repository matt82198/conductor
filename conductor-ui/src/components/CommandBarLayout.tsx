import { useState, useEffect, useCallback } from 'react';
import { CommandBar } from './CommandBar';
import { AgentList } from './AgentList';
import { EventFeed } from './EventFeed';
import { HumanInputPanel } from './HumanInputPanel';
import { TaskPanel } from './TaskPanel';
import { StatusBar } from './StatusBar';
import { useConductorStore } from '../stores/conductorStore';
import { useTaskStore } from '../stores/taskStore';
import { useTaskWebSocket } from '../hooks/useTaskWebSocket';

type RightPanelTab = 'input' | 'tasks';

/**
 * Drop-in replacement for TaskMainLayout that uses the CommandBar instead of SpawnForm.
 *
 * This is a **new file** — it does not modify TaskMainLayout.tsx.
 * To adopt: in App.tsx, swap `<TaskMainLayout />` for `<CommandBarLayout />`.
 *
 * Layout:
 * +----------------------------------------------------------+
 * | CommandBar (with template picker)                         |
 * +----------+-----------------------+-----------------------+
 * | AgentList| EventFeed             | [Input|Tasks] panel   |
 * | (224px)  | (flex)                | (320px, if content)   |
 * +----------+-----------------------+-----------------------+
 * | StatusBar                                                 |
 * +----------------------------------------------------------+
 */
export function CommandBarLayout() {
  // Connect secondary WebSocket for task progress events
  useTaskWebSocket();

  const hasHumanInput = useConductorStore((s) => s.humanInputRequests.length > 0);
  const hasPlans = useTaskStore((s) => s.activePlans.length > 0);

  const [rightPanel, setRightPanel] = useState<RightPanelTab>('tasks');

  // Auto-select: if human input requests exist, switch to input panel (more urgent)
  useEffect(() => {
    if (hasHumanInput) {
      setRightPanel('input');
    }
  }, [hasHumanInput]);

  // Show right panel when there is content, or always show tasks tab for submission
  const showRightPanel = hasHumanInput || hasPlans || rightPanel === 'tasks';

  const handleTabSwitch = useCallback((tab: RightPanelTab) => {
    setRightPanel(tab);
  }, []);

  return (
    <div className="flex flex-col h-screen w-screen">
      {/* Top: Command bar (replaces SpawnForm) */}
      <CommandBar />

      {/* Middle: Agent list + Event feed + Right panel */}
      <div className="flex flex-1 min-h-0">
        {/* Left sidebar: Agent list */}
        <div className="w-56 shrink-0">
          <AgentList />
        </div>

        {/* Center: Event feed */}
        <div className="flex-1 min-w-0">
          <EventFeed />
        </div>

        {/* Right sidebar: Tabbed panel */}
        {showRightPanel && (
          <div className="w-80 shrink-0 flex flex-col bg-surface-1 border-l border-surface-3">
            {/* Tab switcher */}
            <div className="flex border-b border-surface-3 shrink-0">
              <button
                onClick={() => handleTabSwitch('input')}
                className={`flex-1 px-3 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
                  rightPanel === 'input'
                    ? 'text-accent-blue border-b-2 border-accent-blue bg-surface-2'
                    : 'text-gray-600 hover:text-gray-400'
                }`}
              >
                INPUT
                {hasHumanInput && (
                  <span className="ml-1 text-accent-red">
                    ({useConductorStore.getState().humanInputRequests.length})
                  </span>
                )}
              </button>
              <button
                onClick={() => handleTabSwitch('tasks')}
                className={`flex-1 px-3 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
                  rightPanel === 'tasks'
                    ? 'text-purple-400 border-b-2 border-purple-400 bg-surface-2'
                    : 'text-gray-600 hover:text-gray-400'
                }`}
              >
                TASKS
                {hasPlans && (
                  <span className="ml-1 text-purple-400">
                    ({useTaskStore.getState().activePlans.length})
                  </span>
                )}
              </button>
            </div>

            {/* Active panel content */}
            <div className="flex-1 min-h-0 overflow-hidden">
              {rightPanel === 'input' ? <HumanInputPanel /> : <TaskPanel />}
            </div>
          </div>
        )}
      </div>

      {/* Bottom: Status bar */}
      <StatusBar />
    </div>
  );
}
