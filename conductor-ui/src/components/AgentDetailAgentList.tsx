import { useState, useCallback } from 'react';
import { useAgents } from '../hooks/useAgents';
import { useConductorStore } from '../stores/conductorStore';
import { useAgentDetailStore } from '../stores/agentDetailStore';
import type { AgentState } from '../types/events';

const API_BASE = 'http://localhost:8090';

/**
 * Color and label for each agent state.
 * (Duplicated from AgentList.tsx to avoid modifying the original.)
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
 * Returns true if the agent is the Conductor brain agent.
 */
function isConductorAgent(agent: { name: string; role: string }): boolean {
  return agent.name === 'conductor' || agent.role === 'CONDUCTOR';
}

/**
 * Enhanced agent list with click-to-select behavior.
 *
 * This is a standalone replacement for AgentList that adds:
 * - Click on an agent row to select it (shows AgentDetail in center panel)
 * - Blue left border highlight on the selected agent
 * - Same mute toggle, state dots, and layout as the original AgentList
 *
 * Integration: Use this component instead of <AgentList /> in your layout.
 */
export function AgentDetailAgentList() {
  const { agents, stats } = useAgents();
  const mutedAgents = useConductorStore((s) => s.mutedAgents);
  const setMuted = useConductorStore((s) => s.setMuted);
  const selectedAgentId = useAgentDetailStore((s) => s.selectedAgentId);
  const selectAgent = useAgentDetailStore((s) => s.selectAgent);
  const [mutingId, setMutingId] = useState<string | null>(null);

  // Separate conductor agent from regular agents
  const conductorAgent = agents.find(isConductorAgent);
  const regularAgents = agents.filter((a) => !isConductorAgent(a));

  const toggleMute = useCallback(
    async (agentId: string) => {
      const currentlyMuted = mutedAgents.has(agentId);
      setMutingId(agentId);
      try {
        const res = await fetch(`${API_BASE}/api/agents/${agentId}/mute`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ muted: !currentlyMuted }),
        });
        if (res.ok) {
          setMuted(agentId, !currentlyMuted);
        }
      } catch {
        // Silently fail -- mute is non-critical
      } finally {
        setMutingId(null);
      }
    },
    [mutedAgents, setMuted],
  );

  const handleAgentClick = useCallback(
    (agentId: string) => {
      // Toggle: click again to deselect
      selectAgent(selectedAgentId === agentId ? null : agentId);
    },
    [selectedAgentId, selectAgent],
  );

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
        {/* Conductor Brain agent -- always pinned at top */}
        {conductorAgent && (
          <div
            className={`px-3 py-2 border-b border-surface-3 bg-purple-500/5 cursor-pointer hover:bg-purple-500/10 transition-colors ${
              selectedAgentId === conductorAgent.id
                ? 'border-l-2 border-l-accent-blue'
                : ''
            }`}
            onClick={() => handleAgentClick(conductorAgent.id)}
          >
            <div className="flex items-center gap-2">
              <span className="inline-block w-2 h-2 rounded-full bg-purple-400 animate-pulse" />
              <span className="text-sm text-purple-300 font-mono font-bold">
                {conductorAgent.name}
              </span>
            </div>
            <div className="mt-1 ml-4">
              <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded text-purple-300 bg-purple-500/20">
                CONDUCTOR
              </span>
            </div>
          </div>
        )}

        {/* Regular agents */}
        {regularAgents.length === 0 && !conductorAgent ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            No agents running.
            <br />
            <span className="text-gray-700">Ctrl+N to spawn one.</span>
          </div>
        ) : (
          <ul className="py-1">
            {regularAgents.map((agent) => {
              const display =
                STATE_DISPLAY[agent.state] ?? STATE_DISPLAY.ACTIVE;
              const isMuted = mutedAgents.has(agent.id);
              const isSelected = selectedAgentId === agent.id;
              return (
                <li
                  key={agent.id}
                  onClick={() => handleAgentClick(agent.id)}
                  className={`px-3 py-2 hover:bg-surface-2 cursor-pointer transition-colors ${
                    isMuted ? 'opacity-50' : ''
                  } ${
                    isSelected
                      ? 'border-l-2 border-l-accent-blue bg-surface-2'
                      : ''
                  }`}
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
                    <span className="text-sm text-gray-200 font-mono truncate flex-1 min-w-0">
                      {agent.name}
                    </span>
                    {/* Mute toggle */}
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleMute(agent.id);
                      }}
                      disabled={mutingId === agent.id}
                      title={isMuted ? 'Unmute agent' : 'Mute agent'}
                      className={`text-[10px] font-bold px-1.5 py-0.5 rounded border transition-colors shrink-0 ${
                        isMuted
                          ? 'text-accent-red border-red-500/30 bg-red-500/10 hover:bg-red-500/20'
                          : 'text-gray-600 border-surface-3 hover:text-gray-400 hover:border-gray-500'
                      }`}
                    >
                      {isMuted ? 'M' : 'M'}
                    </button>
                  </div>
                  <div className="flex items-center gap-2 mt-1 ml-4">
                    <span
                      className={`text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded ${display.color} ${display.bg}`}
                    >
                      {display.label}
                    </span>
                    {isMuted && (
                      <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded text-accent-red bg-red-500/20">
                        MUTED
                      </span>
                    )}
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
