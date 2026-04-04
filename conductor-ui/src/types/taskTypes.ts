/**
 * Types for the Task Decomposition feature (Phase 4).
 *
 * These types model the decomposition plans, subtasks, and progress
 * events that flow from the conductor-server decomposer domain.
 */

export type SubtaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface Subtask {
  subtaskId: string;
  name: string;
  description: string;
  role: string;
  dependsOn: string[];
  contextFrom: string[];
  projectPath: string;
  prompt: string;
  successCriteria: string | null;
  status: SubtaskStatus;
  agentId: string | null;
  result: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface DecompositionPlan {
  planId: string;
  originalPrompt: string;
  projectPath: string;
  subtasks: Subtask[];
  createdAt: string;
  status: string;
}

export interface TaskProgressEvent {
  planId: string;
  completed: number;
  total: number;
  currentPhase: string;
}

/**
 * WebSocket message type for task progress updates.
 * This extends the server message format with a new discriminant.
 */
export interface TaskProgressWsMessage {
  type: 'task_progress';
  planId: string;
  completed: number;
  total: number;
  currentPhase: string;
}

/**
 * Request body for creating a new decomposition task.
 */
export interface DecomposeTaskRequest {
  prompt: string;
  projectPath: string;
}
