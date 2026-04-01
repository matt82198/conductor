/**
 * Types matching the ACTUAL server WebSocket format.
 * Server sends: { agentId, eventType, event }
 * where event is a raw StreamJsonEvent from Claude CLI.
 */

export type AgentState =
  | 'LAUNCHING'
  | 'ACTIVE'
  | 'THINKING'
  | 'USING_TOOL'
  | 'BLOCKED'
  | 'COMPLETED'
  | 'FAILED';

export interface AgentInfo {
  id: string;
  name: string;
  role: string;
  projectPath: string;
  state: AgentState;
  sessionId: string | null;
  costUsd: number;
  spawnedAt: string;
  lastActivityAt: string;
}

/** Raw WebSocket message from the server */
export interface ServerWsMessage {
  agentId: string;
  eventType: 'system' | 'assistant' | 'user' | 'rate_limit_event' | 'result' | 'parse_error';
  event: any;
}

export interface SpawnAgentRequest {
  name: string;
  role: string;
  projectPath: string;
  prompt: string;
}
