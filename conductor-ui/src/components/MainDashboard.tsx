import { useState, useEffect, useCallback } from 'react';
import { CommandBar } from './CommandBar';
import { AgentDetailAgentList } from './AgentDetailAgentList';
import { AgentDetail } from './AgentDetail';
import { EventFeed } from './EventFeed';
import { HumanInputPanel } from './HumanInputPanel';
import { TaskPanel } from './TaskPanel';
import { ProjectPanel } from './ProjectPanel';
import { BrainDecisionsPanel } from './BrainDecisionsPanel';
import { StatusBar } from './StatusBar';
import { useConductorStore } from '../stores/conductorStore';
import { useAgentDetailStore } from '../stores/agentDetailStore';
import { useTaskStore } from '../stores/taskStore';
import { useProjectStore } from '../stores/projectStore';
import { useBrainDecisionStore } from '../stores/brainDecisionStore';
import { useUnifiedWebSocket } from '../hooks/useUnifiedWebSocket';

type RightPanelTab = 'input' | 'tasks' | 'projects' | 'brain';

/**
 * Unified main dashboard layout consolidating all features into a single component.
 *
 * Replaces the chain of incremental layouts (TaskMainLayout, ProjectTaskMainLayout,
 * CommandBarLayout, BrainDecisionsMainLayout) with one clean component that includes
 * every feature:
 *
 * - CommandBar + TemplatePicker at top (CommandBar renders TemplatePicker internally)
 * - AgentList on the left
 * - EventFeed in the center
 * - Right panel with 4 tabs: INPUT, TASKS, PROJECTS, BRAIN
 * - StatusBar at the bottom
 * - All stores connected: conductorStore, taskStore, projectStore, brainDecisionStore
 * - Single unified WebSocket connection (useUnifiedWebSocket)
 *
 * Right panel tab auto-selection priority:
 *   1. INPUT  — when human input requests exist (most urgent)
 *   2. BRAIN  — when there are unrated brain decisions
 *   3. TASKS  — default fallback
 *   4. PROJECTS — lowest priority
 *
 * The right panel is always visible (it contains submission forms for tasks and projects).
 *
 * +----------------------------------------------------------+
 * | CommandBar (with TemplatePicker)                          |
 * +----------+-----------------------+-----------------------+
 * | AgentList| EventFeed             | [Input|Tasks|Projects|Brain] |
 * | (224px)  | (flex)                | (320px)               |
 * +----------+-----------------------+-----------------------+
 * | StatusBar                                                 |
 * +----------------------------------------------------------+
 */
export function MainDashboard() {
  // Single unified WebSocket — handles agent events, task progress, and brain decisions
  useUnifiedWebSocket();

  // --- Store subscriptions for tab logic ---
  const humanInputCount = useConductorStore((s) => s.humanInputRequests.length);
  const hasHumanInput = humanInputCount > 0;
  const planCount = useTaskStore((s) => s.activePlans.length);
  const projectCount = useProjectStore((s) => s.projects.length);
  const brainDecisionCount = useBrainDecisionStore((s) => s.brainDecisions.length);
  const unratedCount = useBrainDecisionStore((s) => s.unratedCount);

  const selectedAgentId = useAgentDetailStore((s) => s.selectedAgentId);
  const selectAgent = useAgentDetailStore((s) => s.selectAgent);
  const [rightPanel, setRightPanel] = useState<RightPanelTab>('tasks');

  // Auto-select tab based on priority: human input > brain (unrated) > tasks > projects
  useEffect(() => {
    if (hasHumanInput) {
      setRightPanel('input');
    } else if (unratedCount > 0) {
      setRightPanel('brain');
    }
  }, [hasHumanInput, unratedCount]);

  const handleTabSwitch = useCallback((tab: RightPanelTab) => {
    setRightPanel(tab);
  }, []);

  return (
    <div className="flex flex-col h-screen w-screen">
      {/* Top: Command bar (includes TemplatePicker internally) */}
      <CommandBar />

      {/* Middle: Agent list + Event feed + Right panel */}
      <div className="flex flex-1 min-h-0">
        {/* Left sidebar: Agent list (click to select for detail view) */}
        <div className="w-56 shrink-0">
          <AgentDetailAgentList />
        </div>

        {/* Center: Agent detail (when selected) or Event feed (default) */}
        <div className="flex-1 min-w-0">
          {selectedAgentId ? (
            <AgentDetail agentId={selectedAgentId} onClose={() => selectAgent(null)} />
          ) : (
            <EventFeed />
          )}
        </div>

        {/* Right sidebar: Always visible, 4-tab panel */}
        <div className="w-80 shrink-0 flex flex-col bg-surface-1 border-l border-surface-3">
          {/* Tab switcher */}
          <div className="flex border-b border-surface-3 shrink-0">
            <button
              onClick={() => handleTabSwitch('input')}
              className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
                rightPanel === 'input'
                  ? 'text-accent-blue border-b-2 border-accent-blue bg-surface-2'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              INPUT
              {hasHumanInput && (
                <span className="ml-1 text-accent-red">
                  ({humanInputCount})
                </span>
              )}
            </button>
            <button
              onClick={() => handleTabSwitch('tasks')}
              className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
                rightPanel === 'tasks'
                  ? 'text-purple-400 border-b-2 border-purple-400 bg-surface-2'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              TASKS
              {planCount > 0 && (
                <span className="ml-1 text-purple-400">
                  ({planCount})
                </span>
              )}
            </button>
            <button
              onClick={() => handleTabSwitch('projects')}
              className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
                rightPanel === 'projects'
                  ? 'text-purple-400 border-b-2 border-purple-400 bg-surface-2'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              PROJECTS
              {projectCount > 0 && (
                <span className="ml-1 text-purple-400">
                  ({projectCount})
                </span>
              )}
            </button>
            <button
              onClick={() => handleTabSwitch('brain')}
              className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors relative ${
                rightPanel === 'brain'
                  ? 'text-purple-400 border-b-2 border-purple-400 bg-surface-2'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              BRAIN
              {brainDecisionCount > 0 && (
                <span className="ml-1 text-purple-400">
                  ({brainDecisionCount})
                </span>
              )}
              {/* Pulsing dot when there are unrated decisions and tab is not active */}
              {unratedCount > 0 && rightPanel !== 'brain' && (
                <span className="absolute top-1 right-1 w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse" />
              )}
            </button>
          </div>

          {/* Active panel content */}
          <div className="flex-1 min-h-0 overflow-hidden">
            {rightPanel === 'input' ? (
              <HumanInputPanel />
            ) : rightPanel === 'tasks' ? (
              <TaskPanel />
            ) : rightPanel === 'projects' ? (
              <ProjectPanel />
            ) : (
              <BrainDecisionsPanel />
            )}
          </div>
        </div>
      </div>

      {/* Bottom: Status bar */}
      <StatusBar />
    </div>
  );
}
