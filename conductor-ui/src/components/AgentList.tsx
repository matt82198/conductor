import { useAgents } from '../hooks/useAgents';
import type { AgentState } from '../types/events';

/**
 * Color and label for each agent state.
 */
const STATE_DISPLAY: Record<
  AgentState,
  { color: string; bg: string; label: string }
> = {
  LAUNCHING: {
    color: 'text-accent-blue',
    bg: 'bg-blue-500/20',
    label: 'LAUNCHING',
  },
  ACTIVE: {
    color: 'text-accent-green',
    bg: 'bg-green-500/20',
    label: 'ACTIVE',
  },
  THINKING: {
    color: 'text-accent-yellow',
    bg: 'bg-yellow-500/20',
    label: 'THINKING',
  },
  USING_TOOL: {
    color: 'text-accent-green',
    bg: 'bg-green-500/20',
    label: 'TOOL USE',
  },
  BLOCKED: {
    color: 'text-accent-red',
    bg: 'bg-red-500/20',
    label: 'BLOCKED',
  },
  COMPLETED: {
    color: 'text-accent-gray',
    bg: 'bg-gray-500/20',
    label: 'COMPLETED',
  },
  FAILED: {
    color: 'text-accent-red',
    bg: 'bg-red-500/20',
    label: 'FAILED',
  },
};

/**
 * Left sidebar panel showing all registered agents with their current state.
 * Agents are sorted by priority: blocked first, then active, then completed.
 */
export function AgentList() {
  const { agents, stats } = useAgents();

  return (
    <div className="flex flex-col h-full bg-surface-1 border-r border-surface-3">
      {/* Header */}
      <div className="px-3 py-2 border-b border-surface-3">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          AGENTS
          <span className="ml-2 text-gray-500 font-normal">
            {stats.active}/{stats.total}
          </span>
        </h2>
      </div>

      {/* Agent list */}
      <div className="flex-1 overflow-y-auto">
        {agents.length === 0 ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            No agents running.
            <br />
            <span className="text-gray-700">Ctrl+N to spawn one.</span>
          </div>
        ) : (
          <ul className="py-1">
            {agents.map((agent) => {
              const display =
                STATE_DISPLAY[agent.state] ?? STATE_DISPLAY.ACTIVE;
              return (
                <li
                  key={agent.id}
                  className="px-3 py-2 hover:bg-surface-2 cursor-default transition-colors"
                >
                  <div className="flex items-center gap-2">
                    {/* State dot */}
                    <span
                      className={`inline-block w-2 h-2 rounded-full ${
                        agent.state === 'BLOCKED' || agent.state === 'FAILED'
                          ? 'bg-accent-red'
                          : agent.state === 'THINKING'
                            ? 'bg-accent-yellow animate-pulse'
                            : agent.state === 'COMPLETED'
                              ? 'bg-accent-gray'
                              : 'bg-accent-green'
                      }`}
                    />
                    <span className="text-sm text-gray-200 font-mono truncate">
                      {agent.name}
                    </span>
                  </div>
                  <div className="flex items-center gap-2 mt-1 ml-4">
                    <span
                      className={`text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded ${display.color} ${display.bg}`}
                    >
                      {display.label}
                    </span>
                    <span className="text-[10px] text-gray-600 truncate">
                      {agent.role}
                    </span>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
