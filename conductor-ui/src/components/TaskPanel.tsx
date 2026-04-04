import { useState, useCallback } from 'react';
import { useTaskStore } from '../stores/taskStore';
import type { DecompositionPlan, Subtask, SubtaskStatus, DecomposeTaskRequest } from '../types/taskTypes';

const API_BASE = 'http://localhost:8090';

/** Status dot colors for subtask states. */
const STATUS_COLORS: Record<SubtaskStatus, string> = {
  PENDING: 'bg-gray-600',
  RUNNING: 'bg-accent-green animate-pulse',
  COMPLETED: 'bg-accent-green',
  FAILED: 'bg-accent-red',
  CANCELLED: 'bg-gray-500',
};

/** Status label colors for text badges. */
const STATUS_TEXT: Record<SubtaskStatus, { color: string; bg: string }> = {
  PENDING: { color: 'text-gray-400', bg: 'bg-gray-500/20' },
  RUNNING: { color: 'text-accent-green', bg: 'bg-green-500/20' },
  COMPLETED: { color: 'text-accent-green', bg: 'bg-green-500/20' },
  FAILED: { color: 'text-accent-red', bg: 'bg-red-500/20' },
  CANCELLED: { color: 'text-gray-500', bg: 'bg-gray-500/20' },
};

/** Role badge colors matching AgentList conventions. */
const ROLE_COLORS: Record<string, { color: string; bg: string }> = {
  FEATURE_ENGINEER: { color: 'text-accent-blue', bg: 'bg-blue-500/20' },
  TESTER: { color: 'text-accent-yellow', bg: 'bg-yellow-500/20' },
  REFACTORER: { color: 'text-purple-300', bg: 'bg-purple-500/20' },
  REVIEWER: { color: 'text-cyan-400', bg: 'bg-cyan-500/20' },
  GENERAL: { color: 'text-gray-400', bg: 'bg-gray-500/20' },
  CONDUCTOR: { color: 'text-purple-300', bg: 'bg-purple-500/20' },
};

/**
 * Returns role-specific colors, with a fallback for unknown roles.
 */
function getRoleColors(role: string): { color: string; bg: string } {
  return ROLE_COLORS[role] ?? { color: 'text-gray-400', bg: 'bg-gray-500/20' };
}

/**
 * Format an ISO timestamp to a compact HH:MM:SS string.
 */
function formatTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleTimeString('en-US', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * Truncate a string to a max length with ellipsis.
 */
function truncate(text: string, max: number): string {
  return text.length > max ? text.slice(0, max) + '...' : text;
}

/* ---------- Task Submission Form ---------- */

function TaskSubmitForm() {
  const [prompt, setPrompt] = useState('');
  const [projectPath, setProjectPath] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const addPlan = useTaskStore((s) => s.addPlan);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!prompt.trim() || !projectPath.trim()) return;

      setSubmitting(true);
      setError(null);

      const body: DecomposeTaskRequest = {
        prompt: prompt.trim(),
        projectPath: projectPath.trim(),
      };

      try {
        const res = await fetch(`${API_BASE}/api/brain/tasks`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });

        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || `HTTP ${res.status}`);
        }

        const plan: DecompositionPlan = await res.json();
        addPlan(plan);

        // Clear form
        setPrompt('');
        setProjectPath('');
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to decompose task');
      } finally {
        setSubmitting(false);
      }
    },
    [prompt, projectPath, addPlan],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSubmit(e as unknown as React.FormEvent);
      }
    },
    [handleSubmit],
  );

  return (
    <form onSubmit={handleSubmit} className="px-3 py-3 border-b border-surface-3">
      <input
        type="text"
        placeholder="Describe the task to decompose..."
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        onKeyDown={handleKeyDown}
        className="w-full bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono mb-1.5"
      />
      <div className="flex gap-1.5">
        <input
          type="text"
          placeholder="Project path"
          value={projectPath}
          onChange={(e) => setProjectPath(e.target.value)}
          className="flex-1 min-w-0 bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
        />
        <button
          type="submit"
          disabled={submitting || !prompt.trim() || !projectPath.trim()}
          className="bg-purple-600 text-white text-xs font-bold px-3 py-1 rounded hover:bg-purple-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
        >
          {submitting ? '...' : 'Decompose'}
        </button>
      </div>
      {error && (
        <p className="text-accent-red text-xs mt-1.5">{error}</p>
      )}
    </form>
  );
}

/* ---------- Subtask Row ---------- */

function SubtaskRow({ subtask }: { subtask: Subtask }) {
  const statusColor = STATUS_COLORS[subtask.status] ?? 'bg-gray-600';
  const statusText = STATUS_TEXT[subtask.status] ?? STATUS_TEXT.PENDING;
  const roleColors = getRoleColors(subtask.role);

  return (
    <div className="px-3 py-1.5 border-b border-surface-3/50 last:border-b-0">
      <div className="flex items-center gap-2">
        {/* Status dot */}
        <span className={`inline-block w-2 h-2 rounded-full shrink-0 ${statusColor}`} />

        {/* Name */}
        <span className="text-xs text-gray-200 font-mono truncate flex-1 min-w-0">
          {subtask.name}
        </span>

        {/* Role badge */}
        <span
          className={`text-[9px] font-bold tracking-wider px-1 py-0.5 rounded shrink-0 ${roleColors.color} ${roleColors.bg}`}
        >
          {subtask.role}
        </span>

        {/* Status badge */}
        <span
          className={`text-[9px] font-bold tracking-wider px-1 py-0.5 rounded shrink-0 ${statusText.color} ${statusText.bg}`}
        >
          {subtask.status}
        </span>
      </div>

      {/* Description */}
      <p className="text-[10px] text-gray-500 ml-4 mt-0.5 leading-relaxed">
        {truncate(subtask.description, 120)}
      </p>

      {/* Dependencies */}
      {subtask.dependsOn.length > 0 && (
        <p className="text-[9px] text-gray-600 ml-4 mt-0.5 font-mono">
          depends on: {subtask.dependsOn.join(', ')}
        </p>
      )}

      {/* Running agent info */}
      {subtask.status === 'RUNNING' && subtask.agentId && (
        <p className="text-[9px] text-accent-green ml-4 mt-0.5 font-mono">
          agent: {subtask.agentId.slice(0, 12)}
        </p>
      )}

      {/* Completed result */}
      {subtask.status === 'COMPLETED' && subtask.result && (
        <p className="text-[9px] text-green-400/70 ml-4 mt-0.5 font-mono">
          result: {truncate(subtask.result, 80)}
        </p>
      )}

      {/* Failed result */}
      {subtask.status === 'FAILED' && subtask.result && (
        <p className="text-[9px] text-accent-red ml-4 mt-0.5 font-mono">
          error: {truncate(subtask.result, 80)}
        </p>
      )}
    </div>
  );
}

/* ---------- Plan Card ---------- */

function PlanCard({ plan }: { plan: DecompositionPlan }) {
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(true);
  const removePlan = useTaskStore((s) => s.removePlan);

  const completed = plan.subtasks.filter((s) => s.status === 'COMPLETED').length;
  const failed = plan.subtasks.filter((s) => s.status === 'FAILED').length;
  const running = plan.subtasks.filter((s) => s.status === 'RUNNING').length;
  const total = plan.subtasks.length;
  const progressPct = total > 0 ? (completed / total) * 100 : 0;

  const handleCancel = useCallback(async () => {
    setCancelling(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/brain/tasks/${plan.planId}`, {
        method: 'DELETE',
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      removePlan(plan.planId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to cancel');
    } finally {
      setCancelling(false);
    }
  }, [plan.planId, removePlan]);

  /** Plan status badge color. */
  const planStatusColor = (): string => {
    switch (plan.status) {
      case 'COMPLETED':
        return 'text-accent-green bg-green-500/20';
      case 'FAILED':
        return 'text-accent-red bg-red-500/20';
      case 'CANCELLED':
        return 'text-gray-500 bg-gray-500/20';
      case 'RUNNING':
      case 'EXECUTING':
        return 'text-accent-green bg-green-500/20';
      case 'PLANNING':
        return 'text-purple-300 bg-purple-500/20';
      default:
        return 'text-gray-400 bg-gray-500/20';
    }
  };

  return (
    <div className="border-b border-surface-3">
      {/* Progress bar (thin strip at top of card) */}
      <div className="h-0.5 bg-surface-3">
        <div
          className="h-full bg-accent-green transition-all duration-300"
          style={{ width: `${progressPct}%` }}
        />
      </div>

      {/* Plan header */}
      <div className="px-3 py-2">
        <div className="flex items-center gap-2">
          {/* Expand/collapse toggle */}
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-[10px] text-gray-500 hover:text-gray-300 transition-colors shrink-0 w-3"
          >
            {expanded ? '\u25BC' : '\u25B6'}
          </button>

          {/* Prompt (truncated) */}
          <span className="text-sm text-gray-200 font-mono truncate flex-1 min-w-0">
            {truncate(plan.originalPrompt, 60)}
          </span>

          {/* Status badge */}
          <span
            className={`text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded shrink-0 ${planStatusColor()}`}
          >
            {plan.status}
          </span>
        </div>

        {/* Metadata row */}
        <div className="flex items-center gap-3 mt-1 ml-5">
          <span className="text-[10px] text-gray-600 font-mono">
            {formatTime(plan.createdAt)}
          </span>
          <span className="text-[10px] text-gray-500 font-mono">
            {completed}/{total} done
          </span>
          {running > 0 && (
            <span className="text-[10px] text-accent-green font-mono">
              {running} running
            </span>
          )}
          {failed > 0 && (
            <span className="text-[10px] text-accent-red font-mono">
              {failed} failed
            </span>
          )}
          <span className="text-[10px] text-gray-600 font-mono truncate">
            {truncate(plan.projectPath, 30)}
          </span>

          {/* Cancel button */}
          <button
            onClick={handleCancel}
            disabled={cancelling || plan.status === 'COMPLETED' || plan.status === 'CANCELLED'}
            className="text-[10px] text-gray-600 hover:text-accent-red px-1.5 py-0.5 rounded border border-surface-3 hover:border-red-500/30 transition-colors shrink-0 disabled:opacity-30 disabled:cursor-not-allowed ml-auto"
          >
            {cancelling ? '...' : 'Cancel'}
          </button>
        </div>

        {error && (
          <p className="text-accent-red text-xs mt-1 ml-5">{error}</p>
        )}
      </div>

      {/* Subtask list (collapsible) */}
      {expanded && plan.subtasks.length > 0 && (
        <div className="bg-surface-0/50">
          {plan.subtasks.map((subtask) => (
            <SubtaskRow key={subtask.subtaskId} subtask={subtask} />
          ))}
        </div>
      )}
    </div>
  );
}

/* ---------- Task Panel (main export) ---------- */

/**
 * Right-side panel for task decomposition. Shows a submission form
 * at the top and a list of active decomposition plans with their
 * subtask progress below.
 */
export function TaskPanel() {
  const activePlans = useTaskStore((s) => s.activePlans);

  return (
    <div className="flex flex-col h-full bg-surface-1 border-l border-surface-3">
      {/* Header */}
      <div className="px-3 py-2 border-b border-surface-3">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          TASKS
          {activePlans.length > 0 && (
            <span className="ml-2 text-purple-400 font-normal">
              ({activePlans.length})
            </span>
          )}
        </h2>
      </div>

      {/* Task submission form */}
      <TaskSubmitForm />

      {/* Plans list */}
      <div className="flex-1 overflow-y-auto">
        {activePlans.length === 0 ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            No active task plans.
            <br />
            <span className="text-gray-700">Submit a prompt above to decompose.</span>
          </div>
        ) : (
          activePlans.map((plan) => (
            <PlanCard key={plan.planId} plan={plan} />
          ))
        )}
      </div>
    </div>
  );
}
