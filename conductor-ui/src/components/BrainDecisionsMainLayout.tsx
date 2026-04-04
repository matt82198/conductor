import { useState, useEffect, useCallback } from 'react';
import { CommandBar } from './CommandBar';
import { TemplatePicker } from './TemplatePicker';
import { AgentList } from './AgentList';
import { EventFeed } from './EventFeed';
import { HumanInputPanel } from './HumanInputPanel';
import { TaskPanel } from './TaskPanel';
import { ProjectPanel } from './ProjectPanel';
import { BrainDecisionsPanel } from './BrainDecisionsPanel';
import { StatusBar } from './StatusBar';
import { useConductorStore } from '../stores/conductorStore';
import { useTaskStore } from '../stores/taskStore';
import { useProjectStore } from '../stores/projectStore';
import { useBrainDecisionStore } from '../stores/brainDecisionStore';
import { useTaskWebSocket } from '../hooks/useTaskWebSocket';
import { useBrainDecisionWebSocket } from '../hooks/useBrainDecisionWebSocket';

type RightPanelTab = 'input' | 'tasks' | 'projects' | 'brain';

/**
 * Extended main dashboard layout with Brain Decisions panel.
 *
 * This is a drop-in replacement for ProjectTaskMainLayout in App.tsx that adds:
 * - Brain Decisions panel as a fourth right sidebar tab
 * - All features from ProjectTaskMainLayout (projects, tasks, human input)
 * - Auto-selection: human input > brain (if unrated) > tasks > projects
 * - Pulsing indicator on BRAIN tab when there are unrated decisions
 *
 * +----------------------------------------------------------+
 * | ProjectSpawnForm (with project dropdown)                  |
 * +----------+-----------------------+-----------------------+
 * | AgentList| EventFeed             | [Input|Tasks|Projects|Brain] |
 * | (224px)  | (flex)                | (320px, if content)   |
 * +----------+-----------------------+-----------------------+
 * | StatusBar                                                 |
 * +----------------------------------------------------------+
 *
 * Integration: Replace <ProjectTaskMainLayout /> with <BrainDecisionsMainLayout />
 * in App.tsx to enable the brain decisions panel.
 *
 * ```tsx
 * import { BrainDecisionsMainLayout } from './components/BrainDecisionsMainLayout';
 * // ... in the App component:
 * return <BrainDecisionsMainLayout />;
 * ```
 */
export function BrainDecisionsMainLayout() {
  // Connect secondary WebSocket for task progress events
  useTaskWebSocket();
  // Connect tertiary WebSocket for brain decision events
  useBrainDecisionWebSocket();

  const hasHumanInput = useConductorStore((s) => s.humanInputRequests.length > 0);
  const hasPlans = useTaskStore((s) => s.activePlans.length > 0);
  const projectCount = useProjectStore((s) => s.projects.length);
  const hasProjects = projectCount > 0;
  const brainDecisionCount = useBrainDecisionStore((s) => s.brainDecisions.length);
  const hasBrainDecisions = brainDecisionCount > 0;
  const unratedCount = useBrainDecisionStore((s) => s.unratedCount);

  const [rightPanel, setRightPanel] = useState<RightPanelTab>('projects');

  // Auto-select: human input takes priority (most urgent)
  useEffect(() => {
    if (hasHumanInput) {
      setRightPanel('input');
    }
  }, [hasHumanInput]);

  // Show right panel when there is content, or always show for submission forms
  const showRightPanel =
    hasHumanInput ||
    hasPlans ||
    hasProjects ||
    hasBrainDecisions ||
    rightPanel === 'tasks' ||
    rightPanel === 'projects' ||
    rightPanel === 'brain';

  const handleTabSwitch = useCallback((tab: RightPanelTab) => {
    setRightPanel(tab);
  }, []);

  return (
    <div className="flex flex-col h-screen w-screen">
      {/* Top: Spawn form with project dropdown */}
      <CommandBar />
      <TemplatePicker onResult={() => {}} />

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
                className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
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
                className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
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
              <button
                onClick={() => handleTabSwitch('projects')}
                className={`flex-1 px-2 py-1.5 text-[10px] font-bold tracking-wider transition-colors ${
                  rightPanel === 'projects'
                    ? 'text-purple-400 border-b-2 border-purple-400 bg-surface-2'
                    : 'text-gray-600 hover:text-gray-400'
                }`}
              >
                PROJECTS
                {hasProjects && (
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
                {hasBrainDecisions && (
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
        )}
      </div>

      {/* Bottom: Status bar */}
      <StatusBar />
    </div>
  );
}
