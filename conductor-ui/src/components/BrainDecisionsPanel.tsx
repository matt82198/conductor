import { useState, useCallback, useRef, useEffect } from 'react';
import { useBrainDecisionStore } from '../stores/brainDecisionStore';
import type { BrainDecisionEntry, BrainFeedbackRating } from '../types/brainDecisionTypes';

const API_BASE = 'http://localhost:8090';

/**
 * Format a Date to a compact HH:MM:SS timestamp string.
 */
function formatTime(date: Date): string {
  return date.toLocaleTimeString('en-US', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * Confidence percentage badge with color coding.
 * Green >= 80%, yellow >= 50%, gray < 50%.
 */
function ConfidenceBadge({ confidence }: { confidence: number }) {
  const pct = Math.round(confidence * 100);
  const color =
    pct >= 80
      ? 'bg-green-500/20 text-accent-green'
      : pct >= 50
        ? 'bg-yellow-500/20 text-accent-yellow'
        : 'bg-gray-500/20 text-accent-gray';
  return (
    <span className={`text-[10px] font-mono font-bold px-1.5 py-0.5 rounded ${color}`}>
      {pct}%
    </span>
  );
}

/**
 * Action badge: "AUTO-RESPONDED" in green or "ESCALATED" in yellow.
 */
function ActionBadge({ action }: { action: 'RESPOND' | 'ESCALATE' }) {
  if (action === 'RESPOND') {
    return (
      <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-green-500/20 text-accent-green">
        AUTO-RESPONDED
      </span>
    );
  }
  return (
    <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-yellow-500/20 text-accent-yellow">
      ESCALATED
    </span>
  );
}

/**
 * Thumbs up / thumbs down feedback buttons.
 *
 * When feedback is null, both buttons are shown in neutral state.
 * When GOOD is given, thumbs up stays highlighted green, thumbs down fades.
 * When BAD is given, a correction text input appears.
 */
function FeedbackButtons({
  decision,
  onFeedback,
}: {
  decision: BrainDecisionEntry;
  onFeedback: (id: string, rating: BrainFeedbackRating, correction: string | null) => void;
}) {
  const [showCorrection, setShowCorrection] = useState(false);
  const [correctionText, setCorrectionText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Focus correction input when it appears
  useEffect(() => {
    if (showCorrection && inputRef.current) {
      inputRef.current.focus();
    }
  }, [showCorrection]);

  const submitFeedback = useCallback(
    async (rating: BrainFeedbackRating, correction: string | null) => {
      setSubmitting(true);
      setError(null);
      try {
        const res = await fetch(`${API_BASE}/api/brain/feedback`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            requestId: decision.requestId,
            decision: decision.action,
            brainResponse: decision.response,
            rating,
            correction,
          }),
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || `HTTP ${res.status}`);
        }
        onFeedback(decision.id, rating, correction);
        setShowCorrection(false);
        setCorrectionText('');
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to submit feedback');
      } finally {
        setSubmitting(false);
      }
    },
    [decision, onFeedback],
  );

  const handleThumbsUp = useCallback(() => {
    if (decision.feedback !== null) return;
    submitFeedback('GOOD', null);
  }, [decision.feedback, submitFeedback]);

  const handleThumbsDown = useCallback(() => {
    if (decision.feedback !== null) return;
    setShowCorrection(true);
  }, [decision.feedback]);

  const handleCorrectionSubmit = useCallback(() => {
    submitFeedback('BAD', correctionText.trim() || null);
  }, [correctionText, submitFeedback]);

  const handleCorrectionKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleCorrectionSubmit();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setShowCorrection(false);
        setCorrectionText('');
      }
    },
    [handleCorrectionSubmit],
  );

  const alreadyRated = decision.feedback !== null;

  return (
    <div className="mt-2">
      <div className="flex items-center gap-2">
        {/* Thumbs up */}
        <button
          onClick={handleThumbsUp}
          disabled={alreadyRated || submitting}
          title="Good decision"
          className={`flex items-center gap-1 px-2 py-1 rounded text-xs font-bold transition-all ${
            decision.feedback === 'GOOD'
              ? 'bg-green-500/30 text-accent-green ring-1 ring-accent-green/40'
              : alreadyRated
                ? 'text-gray-700 cursor-not-allowed opacity-30'
                : 'text-gray-500 hover:text-accent-green hover:bg-green-500/10'
          }`}
        >
          <ThumbsUpIcon />
        </button>

        {/* Thumbs down */}
        <button
          onClick={handleThumbsDown}
          disabled={alreadyRated || submitting}
          title="Bad decision"
          className={`flex items-center gap-1 px-2 py-1 rounded text-xs font-bold transition-all ${
            decision.feedback === 'BAD'
              ? 'bg-red-500/30 text-accent-red ring-1 ring-accent-red/40'
              : alreadyRated
                ? 'text-gray-700 cursor-not-allowed opacity-30'
                : 'text-gray-500 hover:text-accent-red hover:bg-red-500/10'
          }`}
        >
          <ThumbsDownIcon />
        </button>

        {decision.feedback === 'GOOD' && (
          <span className="text-[10px] text-accent-green">Approved</span>
        )}
        {decision.feedback === 'BAD' && (
          <span className="text-[10px] text-accent-red">
            Corrected{decision.correction ? `: ${decision.correction}` : ''}
          </span>
        )}
      </div>

      {/* Correction text input (shown when thumbs down clicked) */}
      {showCorrection && !alreadyRated && (
        <div className="flex gap-1.5 mt-1.5">
          <input
            ref={inputRef}
            type="text"
            placeholder="What should it have done? (optional)"
            value={correctionText}
            onChange={(e) => setCorrectionText(e.target.value)}
            onKeyDown={handleCorrectionKeyDown}
            disabled={submitting}
            className="flex-1 min-w-0 bg-surface-2 text-gray-200 text-xs px-2 py-1 rounded border border-surface-3 focus:border-accent-red focus:outline-none font-mono"
          />
          <button
            onClick={handleCorrectionSubmit}
            disabled={submitting}
            className="bg-accent-red text-white text-[10px] font-bold px-2 py-1 rounded hover:bg-red-600 disabled:opacity-40 transition-colors shrink-0"
          >
            {submitting ? '...' : 'Submit'}
          </button>
          <button
            onClick={() => {
              setShowCorrection(false);
              setCorrectionText('');
            }}
            disabled={submitting}
            className="text-[10px] text-gray-600 hover:text-gray-400 px-1 transition-colors shrink-0"
          >
            Cancel
          </button>
        </div>
      )}

      {error && <p className="text-accent-red text-[10px] mt-1">{error}</p>}
    </div>
  );
}

/**
 * SVG thumbs up icon (14px).
 */
function ThumbsUpIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M7 10v12" />
      <path d="M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z" />
    </svg>
  );
}

/**
 * SVG thumbs down icon (14px).
 */
function ThumbsDownIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M17 14V2" />
      <path d="M9 18.12L10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z" />
    </svg>
  );
}

/**
 * Single brain decision card.
 *
 * - Purple left border for auto-responses, gray for escalations.
 * - Shows agent name, action badge, confidence percentage.
 * - Response text (for RESPOND) and reasoning (for all).
 * - Feedback thumbs up/down buttons.
 */
function DecisionCard({ decision }: { decision: BrainDecisionEntry }) {
  const setFeedback = useBrainDecisionStore((s) => s.setFeedback);
  const borderColor =
    decision.action === 'RESPOND' ? 'border-l-purple-500' : 'border-l-gray-600';

  return (
    <div
      className={`px-3 py-3 border-b border-surface-3 border-l-2 ${borderColor}`}
    >
      {/* Top row: agent name + action badge + confidence + timestamp */}
      <div className="flex items-center gap-2 mb-1.5 flex-wrap">
        <span className="text-sm text-purple-400 font-mono truncate">
          {decision.agentName}
        </span>
        <ActionBadge action={decision.action} />
        <ConfidenceBadge confidence={decision.confidence} />
        <span className="text-[10px] text-gray-600 font-mono ml-auto shrink-0">
          {formatTime(decision.timestamp)}
        </span>
      </div>

      {/* Response text (only for RESPOND actions) */}
      {decision.action === 'RESPOND' && decision.response && (
        <p className="text-sm text-gray-200 mb-1.5 leading-relaxed">
          {decision.response}
        </p>
      )}

      {/* Reasoning text */}
      <p className="text-gray-500 text-xs leading-relaxed mb-0.5">
        {decision.reasoning}
      </p>

      {/* Feedback buttons */}
      <FeedbackButtons decision={decision} onFeedback={setFeedback} />
    </div>
  );
}

/**
 * Right-side panel showing Brain decision history with feedback buttons.
 *
 * Displays a scrollable list of brain decisions (auto-responses and
 * escalations) with thumbs up/down feedback for teaching the Brain.
 * Most recent decisions appear at the bottom with auto-scroll.
 */
export function BrainDecisionsPanel() {
  const decisions = useBrainDecisionStore((s) => s.brainDecisions);
  const unratedCount = useBrainDecisionStore((s) => s.unratedCount);
  const containerRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  // Detect when user scrolls away from bottom
  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    setAutoScroll(atBottom);
  }, []);

  // Auto-scroll to bottom when new decisions arrive
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [decisions, autoScroll]);

  return (
    <div className="flex flex-col h-full bg-surface-1 border-l border-surface-3">
      {/* Header */}
      <div className="px-3 py-2 border-b border-surface-3">
        <div className="flex items-center justify-between">
          <h2 className="text-xs font-bold text-gray-400 tracking-wider">
            BRAIN DECISIONS
            {decisions.length > 0 && (
              <span className="ml-2 text-purple-400 font-normal">
                ({decisions.length})
              </span>
            )}
          </h2>
          {unratedCount > 0 && (
            <span className="text-[10px] font-bold text-purple-400 bg-purple-500/20 px-1.5 py-0.5 rounded">
              {unratedCount} unrated
            </span>
          )}
        </div>
      </div>

      {/* Decision list */}
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto"
      >
        {decisions.length === 0 ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            No brain decisions yet. The Brain will appear here when it
            auto-responds to or escalates agent questions.
          </div>
        ) : (
          decisions.map((d) => <DecisionCard key={d.id} decision={d} />)
        )}
      </div>
    </div>
  );
}
