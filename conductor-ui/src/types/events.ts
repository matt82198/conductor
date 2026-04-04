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

export interface HumanInputRequest {
  requestId: string;
  agentId: string;
  agentName: string;
  question: string;
  suggestedOptions: string[];
  context: string;
  urgency: string;
  detectedAt: string;
  detectionMethod: string;
  confidenceScore: number;
}

export interface QueuedMessage {
  agentId: string;
  agentName: string;
  text: string;
  urgency: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NOISE';
  category: string;
  timestamp: string;
  dedupHash: string;
  batchId: string | null;
}

/** Raw WebSocket message from the server */
export type ServerWsMessage =
  | { agentId: string; eventType: 'system' | 'assistant' | 'user' | 'rate_limit_event' | 'result' | 'parse_error'; event: any }
  | { type: 'human_input_needed'; request: HumanInputRequest }
  | { type: 'queued_message'; message: QueuedMessage }
  | { type: 'brain_response'; requestId: string; agentId: string; response: string; confidence: number; reasoning: string }
  | { type: 'brain_escalation'; requestId: string; agentId: string; reason: string; recommendation: string | null; confidence: number };

export interface BrainStatus {
  enabled: boolean;
  model: string;
  confidenceThreshold: number;
  behaviorLogSize: number;
  projectsIndexed: number;
}

export interface BrainResponseEvent {
  requestId: string;
  agentId: string;
  response: string;
  confidence: number;
  reasoning: string;
}

export interface BrainEscalationEvent {
  requestId: string;
  agentId: string;
  reason: string;
  recommendation: string | null;
  confidence: number;
}

export interface SpawnAgentRequest {
  name: string;
  role: string;
  projectPath: string;
  prompt: string;
}
