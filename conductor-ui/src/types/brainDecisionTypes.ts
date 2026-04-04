/**
 * Types for the Brain Decisions panel.
 *
 * BrainDecisionEntry represents a single decision made by the Brain,
 * either an auto-response (RESPOND) or an escalation to the human (ESCALATE).
 * Each entry supports user feedback (thumbs up/down) for teaching the Brain.
 */

export type BrainDecisionAction = 'RESPOND' | 'ESCALATE';

export type BrainFeedbackRating = 'GOOD' | 'BAD';

export interface BrainDecisionEntry {
  /** Unique identifier for this decision entry */
  id: string;
  /** The original human-input request ID that triggered this decision */
  requestId: string;
  /** ID of the agent that triggered the decision */
  agentId: string;
  /** Display name of the agent */
  agentName: string;
  /** What the Brain decided: auto-respond or escalate to human */
  action: BrainDecisionAction;
  /** The response text (only present for RESPOND actions) */
  response: string | null;
  /** The Brain's reasoning for this decision */
  reasoning: string;
  /** Confidence score from 0.0 to 1.0 */
  confidence: number;
  /** When the decision was made */
  timestamp: Date;
  /** User's feedback rating, or null if not yet rated */
  feedback: BrainFeedbackRating | null;
  /** Optional correction text provided when user rates BAD */
  correction: string | null;
}

export interface BrainFeedbackPayload {
  requestId: string;
  decision: BrainDecisionAction;
  brainResponse: string | null;
  rating: BrainFeedbackRating;
  correction: string | null;
}
