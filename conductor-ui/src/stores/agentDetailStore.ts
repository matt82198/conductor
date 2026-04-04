import { create } from 'zustand';

/**
 * A single output entry from the agent's conversation history.
 * Matches the shape returned by GET /api/agents/{id}/output.
 */
export interface OutputEntry {
  timestamp: string;
  type: string;
  content: string;
  toolName: string | null;
  isError: boolean;
}

interface AgentDetailState {
  /** Currently selected agent ID, or null when no agent is selected. */
  selectedAgentId: string | null;

  /** Set the selected agent (or null to deselect). */
  selectAgent: (id: string | null) => void;

  /** Cached output entries for the selected agent. */
  output: OutputEntry[];

  /** Whether we are currently fetching output. */
  loading: boolean;

  /** Error message from the last fetch attempt, if any. */
  fetchError: string | null;

  /** Replace the output list. */
  setOutput: (entries: OutputEntry[]) => void;

  /** Set the loading flag. */
  setLoading: (loading: boolean) => void;

  /** Set the fetch error. */
  setFetchError: (error: string | null) => void;

  /** Clear all state (deselect + clear output). */
  clear: () => void;
}

export const useAgentDetailStore = create<AgentDetailState>((set) => ({
  selectedAgentId: null,
  selectAgent: (id) =>
    set({
      selectedAgentId: id,
      output: [],
      loading: id !== null,
      fetchError: null,
    }),

  output: [],
  loading: false,
  fetchError: null,

  setOutput: (entries) => set({ output: entries }),
  setLoading: (loading) => set({ loading }),
  setFetchError: (error) => set({ fetchError: error }),
  clear: () =>
    set({
      selectedAgentId: null,
      output: [],
      loading: false,
      fetchError: null,
    }),
}));
