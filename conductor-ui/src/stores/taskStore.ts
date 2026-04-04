import { create } from 'zustand';
import type { DecompositionPlan, TaskProgressWsMessage } from '../types/taskTypes';

/**
 * Feed event compatible with the main conductor store's FeedEvent shape.
 * We maintain our own to avoid coupling to the main store's internals.
 */
export interface TaskFeedEvent {
  id: string;
  agentId: string;
  agentName: string;
  type: string;
  content: string;
  timestamp: Date;
}

interface TaskState {
  /** All active decomposition plans. */
  activePlans: DecompositionPlan[];

  /** Add a new plan to the store. */
  addPlan: (plan: DecompositionPlan) => void;

  /** Update an existing plan by merging a partial patch. */
  updatePlan: (planId: string, patch: Partial<DecompositionPlan>) => void;

  /** Remove a plan from the store. */
  removePlan: (planId: string) => void;

  /** Task-specific feed events for progress tracking. */
  taskEvents: TaskFeedEvent[];

  /** Process a task_progress WebSocket message. */
  processTaskProgress: (msg: TaskProgressWsMessage) => void;
}

let counter = 0;
const eid = () => `te-${++counter}-${Date.now()}`;

export const useTaskStore = create<TaskState>((set, get) => ({
  activePlans: [],

  addPlan: (plan) =>
    set((s) => ({ activePlans: [...s.activePlans, plan] })),

  updatePlan: (planId, patch) =>
    set((s) => ({
      activePlans: s.activePlans.map((p) =>
        p.planId === planId ? { ...p, ...patch } : p,
      ),
    })),

  removePlan: (planId) =>
    set((s) => ({
      activePlans: s.activePlans.filter((p) => p.planId !== planId),
    })),

  taskEvents: [],

  processTaskProgress: (msg: TaskProgressWsMessage) => {
    const now = new Date();
    const s = get();

    // Update subtask counts on the matching plan if we have one
    const plan = s.activePlans.find((p) => p.planId === msg.planId);
    if (plan) {
      // Update plan status based on progress
      const newStatus =
        msg.completed >= msg.total ? 'COMPLETED' : plan.status;
      s.updatePlan(msg.planId, { status: newStatus });
    }

    // Add a task event to the local feed
    set((prev) => ({
      taskEvents: [
        ...prev.taskEvents,
        {
          id: eid(),
          agentId: '',
          agentName: 'conductor',
          type: 'brain',
          content: `Task progress: ${msg.completed}/${msg.total} subtasks (${msg.currentPhase})`,
          timestamp: now,
        },
      ].slice(-200),
    }));
  },
}));
