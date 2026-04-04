import { create } from 'zustand';
import type { BrainDecisionEntry, BrainFeedbackRating } from '../types/brainDecisionTypes';

/**
 * Maximum number of brain decisions to retain in the store.
 * Oldest entries are dropped when this limit is exceeded.
 */
const MAX_DECISIONS = 50;

export interface BrainDecisionState {
  /** List of brain decisions, most recent last. Capped at MAX_DECISIONS. */
  brainDecisions: BrainDecisionEntry[];

  /** Count of decisions that have not yet received user feedback. */
  unratedCount: number;

  /** Add a new brain decision entry. Trims to MAX_DECISIONS. */
  addBrainDecision: (entry: BrainDecisionEntry) => void;

  /** Set feedback on a decision by its id. Updates unratedCount. */
  setFeedback: (id: string, rating: BrainFeedbackRating, correction: string | null) => void;
}

/**
 * Zustand store for Brain decision history.
 *
 * Brain decisions are populated from brain_response and brain_escalation
 * WebSocket events. The store tracks user feedback (thumbs up/down) and
 * keeps a running count of unrated decisions for tab badge display.
 */
export const useBrainDecisionStore = create<BrainDecisionState>((set) => ({
  brainDecisions: [],
  unratedCount: 0,

  addBrainDecision: (entry) =>
    set((s) => {
      const updated = [...s.brainDecisions, entry].slice(-MAX_DECISIONS);
      const unratedCount = updated.filter((d) => d.feedback === null).length;
      return { brainDecisions: updated, unratedCount };
    }),

  setFeedback: (id, rating, correction) =>
    set((s) => {
      const updated = s.brainDecisions.map((d) =>
        d.id === id ? { ...d, feedback: rating, correction } : d,
      );
      const unratedCount = updated.filter((d) => d.feedback === null).length;
      return { brainDecisions: updated, unratedCount };
    }),
}));
