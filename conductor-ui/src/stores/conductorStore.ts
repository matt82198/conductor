import { create } from 'zustand';
import type { AgentInfo, AgentState, BrainStatus, HumanInputRequest, ServerWsMessage } from '../types/events';

export interface FeedEvent {
  id: string;
  agentId: string;
  agentName: string;
  type: string;
  content: string;
  timestamp: Date;
  urgency?: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NOISE';
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

  humanInputRequests: HumanInputRequest[];
  addHumanInput: (req: HumanInputRequest) => void;
  removeHumanInput: (requestId: string) => void;

  mutedAgents: Set<string>;
  setMuted: (agentId: string, muted: boolean) => void;

  brainStatus: BrainStatus | null;
  setBrainStatus: (status: BrainStatus) => void;
  brainStartupLog: string[];
  addBrainStartupMessage: (msg: string) => void;

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

  humanInputRequests: [],
  addHumanInput: (req) =>
    set((s) => ({
      humanInputRequests: [...s.humanInputRequests, req],
    })),
  removeHumanInput: (requestId) =>
    set((s) => ({
      humanInputRequests: s.humanInputRequests.filter((r) => r.requestId !== requestId),
    })),

  mutedAgents: new Set(),
  setMuted: (agentId, muted) =>
    set((s) => {
      const next = new Set(s.mutedAgents);
      if (muted) {
        next.add(agentId);
      } else {
        next.delete(agentId);
      }
      return { mutedAgents: next };
    }),

  brainStatus: null,
  setBrainStatus: (status) => set({ brainStatus: status }),
  brainStartupLog: [],
  addBrainStartupMessage: (msg) =>
    set((s) => ({
      brainStartupLog: [...s.brainStartupLog, msg],
    })),

  processWsMessage: (msg: ServerWsMessage) => {
    const s = get();
    const now = new Date();

    // New format: { type: "human_input_needed", request: ... }
    if ('type' in msg && msg.type === 'human_input_needed') {
      const req = msg.request;
      s.addHumanInput(req);
      s.updateAgent(req.agentId, { state: 'BLOCKED' as AgentState });
      const agentName = s.agents.get(req.agentId)?.name ?? req.agentName;
      s.addEvent({ id: eid(), agentId: req.agentId, agentName, type: 'error', content: `Agent needs input: ${req.question}`, timestamp: now, urgency: 'CRITICAL' });
      return;
    }

    // New format: { type: "queued_message", message: ... }
    if ('type' in msg && msg.type === 'queued_message') {
      const qm = msg.message;
      const agentName = s.agents.get(qm.agentId)?.name ?? qm.agentName;
      s.addEvent({ id: eid(), agentId: qm.agentId, agentName, type: 'text', content: `[${qm.urgency}] ${qm.text}`, timestamp: now, urgency: qm.urgency });
      return;
    }

    // Brain auto-response: remove the human input request (Brain handled it)
    if ('type' in msg && msg.type === 'brain_response') {
      const agentName = s.agents.get(msg.agentId)?.name ?? msg.agentId.slice(0, 8);
      s.removeHumanInput(msg.requestId);
      s.addEvent({
        id: eid(),
        agentId: msg.agentId,
        agentName,
        type: 'brain',
        content: `[BRAIN] Auto-responded to ${agentName}: ${msg.response} (confidence: ${Math.round(msg.confidence * 100)}%)`,
        timestamp: now,
      });
      return;
    }

    // Brain escalation: keep the human input request (human needs to handle it)
    if ('type' in msg && msg.type === 'brain_escalation') {
      const agentName = s.agents.get(msg.agentId)?.name ?? msg.agentId.slice(0, 8);
      s.addEvent({
        id: eid(),
        agentId: msg.agentId,
        agentName,
        type: 'brain',
        content: `[BRAIN] Escalating to human: ${msg.reason}`,
        timestamp: now,
      });
      return;
    }

    // Original format: { agentId, eventType, event }
    if ('eventType' in msg) {
      const { agentId, eventType, event } = msg;
      const name = s.agents.get(agentId)?.name ?? agentId.slice(0, 8);

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
            s.addEvent({ id: eid(), agentId, agentName: name, type: 'thinking', content: block.thinking ?? 'thinking...', timestamp: now });
          } else if (block.type === 'tool_use') {
            s.updateAgent(agentId, { state: 'USING_TOOL' as AgentState });
            const toolDetail = block.input ? `${block.name ?? 'tool'}: ${typeof block.input === 'string' ? block.input : JSON.stringify(block.input)}` : `Using ${block.name ?? 'tool'}`;
            s.addEvent({ id: eid(), agentId, agentName: name, type: 'tool_use', content: toolDetail, timestamp: now });
          } else if (block.type === 'text') {
            s.updateAgent(agentId, { state: 'ACTIVE' as AgentState });
            s.addEvent({ id: eid(), agentId, agentName: name, type: 'text', content: block.text ?? '', timestamp: now });
          }
          break;
        }
        case 'user': {
          s.updateAgent(agentId, { state: 'ACTIVE' as AgentState });
          const toolContent = event.message?.content?.[0];
          const isErr = toolContent?.is_error;
          const resultText = toolContent?.content ?? toolContent?.text ?? (isErr ? 'Tool error' : 'Tool result received');
          s.addEvent({ id: eid(), agentId, agentName: name, type: 'tool_result', content: typeof resultText === 'string' ? resultText : JSON.stringify(resultText), timestamp: now });
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
    }
  },
}));
