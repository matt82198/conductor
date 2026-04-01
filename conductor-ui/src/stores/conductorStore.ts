import { create } from 'zustand';
import type { AgentInfo, AgentState, ServerWsMessage } from '../types/events';

export interface FeedEvent {
  id: string;
  agentId: string;
  agentName: string;
  type: string;
  content: string;
  timestamp: Date;
}

interface ConductorState {
  connected: boolean;
  setConnected: (c: boolean) => void;

  agents: Map<string, AgentInfo>;
  addAgent: (agent: AgentInfo) => void;
  updateAgent: (id: string, patch: Partial<AgentInfo>) => void;

  events: FeedEvent[];
  addEvent: (e: FeedEvent) => void;
  clearEvents: () => void;

  totalCostUsd: number;

  processWsMessage: (msg: ServerWsMessage) => void;
}

let counter = 0;
const eid = () => `e-${++counter}-${Date.now()}`;

export const useConductorStore = create<ConductorState>((set, get) => ({
  connected: false,
  setConnected: (connected) => set({ connected }),

  agents: new Map(),
  addAgent: (agent) =>
    set((s) => {
      const m = new Map(s.agents);
      m.set(agent.id, agent);
      return { agents: m };
    }),
  updateAgent: (id, patch) =>
    set((s) => {
      const existing = s.agents.get(id);
      if (!existing) return s;
      const m = new Map(s.agents);
      m.set(id, { ...existing, ...patch });
      return { agents: m };
    }),

  events: [],
  addEvent: (event) =>
    set((s) => {
      const events = [...s.events, event].slice(-500);
      return { events };
    }),
  clearEvents: () => set({ events: [] }),

  totalCostUsd: 0,

  processWsMessage: (msg: ServerWsMessage) => {
    const { agentId, eventType, event } = msg;
    const s = get();
    const name = s.agents.get(agentId)?.name ?? agentId.slice(0, 8);
    const now = new Date();

    switch (eventType) {
      case 'system': {
        s.updateAgent(agentId, { state: 'ACTIVE' as AgentState, sessionId: event.sessionId });
        s.addEvent({ id: eid(), agentId, agentName: name, type: 'system', content: `Session started (model: ${event.model ?? 'unknown'})`, timestamp: now });
        break;
      }
      case 'assistant': {
        const content = event.content ?? [];
        const block = content[0];
        if (!block) break;

        if (block.type === 'thinking') {
          s.updateAgent(agentId, { state: 'THINKING' as AgentState });
          s.addEvent({ id: eid(), agentId, agentName: name, type: 'thinking', content: 'thinking...', timestamp: now });
        } else if (block.type === 'tool_use') {
          s.updateAgent(agentId, { state: 'USING_TOOL' as AgentState });
          s.addEvent({ id: eid(), agentId, agentName: name, type: 'tool_use', content: `Using ${block.name ?? 'tool'}`, timestamp: now });
        } else if (block.type === 'text') {
          s.updateAgent(agentId, { state: 'ACTIVE' as AgentState });
          const text = block.text?.length > 300 ? block.text.slice(0, 300) + '...' : (block.text ?? '');
          s.addEvent({ id: eid(), agentId, agentName: name, type: 'text', content: text, timestamp: now });
        }
        break;
      }
      case 'user': {
        s.updateAgent(agentId, { state: 'ACTIVE' as AgentState });
        const isErr = event.message?.content?.[0]?.is_error;
        s.addEvent({ id: eid(), agentId, agentName: name, type: 'tool_result', content: isErr ? 'Tool error' : 'Tool result received', timestamp: now });
        break;
      }
      case 'result': {
        const cost = event.totalCostUsd ?? event.total_cost_usd ?? 0;
        const dur = event.durationMs ?? event.duration_ms ?? 0;
        const turns = event.numTurns ?? event.num_turns ?? 0;
        const isErr = event.isError ?? event.is_error ?? false;
        s.updateAgent(agentId, {
          state: (isErr ? 'FAILED' : 'COMPLETED') as AgentState,
          costUsd: cost,
        });
        set((prev) => ({ totalCostUsd: prev.totalCostUsd + cost }));
        s.addEvent({ id: eid(), agentId, agentName: name, type: isErr ? 'error' : 'completed', content: `${isErr ? 'Failed' : 'Completed'} in ${(dur / 1000).toFixed(1)}s | $${cost.toFixed(4)} | ${turns} turns`, timestamp: now });
        break;
      }
      case 'rate_limit_event': {
        // Usually noise, skip feed
        break;
      }
      case 'parse_error': {
        s.addEvent({ id: eid(), agentId, agentName: name, type: 'error', content: `Parse error: ${event.errorMessage ?? 'unknown'}`, timestamp: now });
        break;
      }
    }
  },
}));
