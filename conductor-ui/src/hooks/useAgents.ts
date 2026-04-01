import { useMemo } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import type { AgentInfo, AgentState } from '../types/events';

/**
 * Derived agent data for UI consumption.
 */
export interface AgentStats {
  total: number;
  active: number;
  thinking: number;
  blocked: number;
  completed: number;
  failed: number;
}

/**
 * Hook that provides a sorted agent list and aggregate stats derived
 * from the Zustand store. Agents are sorted by state priority: blocked
 * first (needs attention), then active/thinking, then completed/failed.
 */
export function useAgents(): {
  agents: AgentInfo[];
  stats: AgentStats;
} {
  const agentsMap = useConductorStore((s) => s.agents);

  return useMemo(() => {
    const agents = Array.from(agentsMap.values());

    const statePriority: Record<AgentState, number> = {
      BLOCKED: 0,
      THINKING: 1,
      USING_TOOL: 2,
      ACTIVE: 3,
      LAUNCHING: 4,
      COMPLETED: 5,
      FAILED: 6,
    };

    agents.sort(
      (a, b) =>
        (statePriority[a.state] ?? 99) - (statePriority[b.state] ?? 99),
    );

    const stats: AgentStats = {
      total: agents.length,
      active: agents.filter(
        (a) =>
          a.state === 'ACTIVE' ||
          a.state === 'THINKING' ||
          a.state === 'USING_TOOL' ||
          a.state === 'LAUNCHING',
      ).length,
      thinking: agents.filter((a) => a.state === 'THINKING').length,
      blocked: agents.filter((a) => a.state === 'BLOCKED').length,
      completed: agents.filter((a) => a.state === 'COMPLETED').length,
      failed: agents.filter((a) => a.state === 'FAILED').length,
    };

    return { agents, stats };
  }, [agentsMap]);
}
