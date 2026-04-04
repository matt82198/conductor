import { useState, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import type { HumanInputRequest } from '../types/events';

const API_BASE = 'http://localhost:8090';

/**
 * Badge for detection method (e.g., TOOL_ERROR, PERMISSION_PROMPT, etc.)
 */
function MethodBadge({ method }: { method: string }) {
  return (
    <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-blue-500/20 text-accent-blue">
      {method}
    </span>
  );
}

/**
 * Confidence score rendered as a small percentage indicator.
 */
function ConfidenceIndicator({ score }: { score: number }) {
  const pct = Math.round(score * 100);
  const color =
    pct >= 80 ? 'text-accent-green' : pct >= 50 ? 'text-accent-yellow' : 'text-accent-gray';
  return (
    <span className={`text-[10px] font-mono ${color}`}>
      {pct}%
    </span>
  );
}

/**
 * Single human input request card with respond/dismiss actions.
 */
function RequestCard({ request }: { request: HumanInputRequest }) {
  const [response, setResponse] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const removeHumanInput = useConductorStore((s) => s.removeHumanInput);

  const handleSend = useCallback(async () => {
    if (!response.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/humaninput/${request.requestId}/respond`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: response.trim() }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      removeHumanInput(request.requestId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send response');
    } finally {
      setSubmitting(false);
    }
  }, [response, request.requestId, removeHumanInput]);

  const handleDismiss = useCallback(async () => {
    setSubmitting(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/humaninput/${request.requestId}/dismiss`, {
        method: 'POST',
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      removeHumanInput(request.requestId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to dismiss');
    } finally {
      setSubmitting(false);
    }
  }, [request.requestId, removeHumanInput]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  return (
    <div className="px-3 py-3 border-b border-surface-3">
      {/* Agent name + badges */}
      <div className="flex items-center gap-2 mb-1.5">
        <span className="text-sm text-accent-blue font-mono truncate">
          {request.agentName}
        </span>
        <MethodBadge method={request.detectionMethod} />
        <ConfidenceIndicator score={request.confidenceScore} />
      </div>

      {/* Question */}
      <p className="text-sm text-gray-200 mb-2 leading-relaxed">
        {request.question}
      </p>

      {/* Context (if present) */}
      {request.context && (
        <p className="text-xs text-gray-500 mb-2 leading-relaxed">
          {request.context}
        </p>
      )}

      {/* Suggested options as clickable chips */}
      {request.suggestedOptions.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mb-2">
          {request.suggestedOptions.map((option, i) => (
            <button
              key={i}
              onClick={() => setResponse(option)}
              className="text-xs px-2 py-1 rounded bg-surface-2 border border-surface-3 text-gray-300 hover:border-accent-blue hover:text-accent-blue transition-colors"
            >
              {option}
            </button>
          ))}
        </div>
      )}

      {/* Response input */}
      <div className="flex gap-1.5">
        <input
          type="text"
          placeholder="Type response..."
          value={response}
          onChange={(e) => setResponse(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={submitting}
          className="flex-1 min-w-0 bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
        />
        <button
          onClick={handleSend}
          disabled={submitting || !response.trim()}
          className="bg-accent-blue text-white text-xs font-bold px-3 py-1 rounded hover:bg-blue-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
        >
          {submitting ? '...' : 'Send'}
        </button>
        <button
          onClick={handleDismiss}
          disabled={submitting}
          className="text-xs text-gray-500 hover:text-gray-300 px-2 py-1 rounded border border-surface-3 hover:border-gray-500 transition-colors shrink-0"
        >
          Dismiss
        </button>
      </div>

      {error && (
        <p className="text-accent-red text-xs mt-1.5">{error}</p>
      )}
    </div>
  );
}

/**
 * Right-side panel showing pending human input requests.
 * Requests arrive pre-sorted by confidence (highest first).
 */
export function HumanInputPanel() {
  const requests = useConductorStore((s) => s.humanInputRequests);

  return (
    <div className="flex flex-col h-full bg-surface-1 border-l border-surface-3">
      {/* Header */}
      <div className="px-3 py-2 border-b border-surface-3">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          HUMAN INPUT
          {requests.length > 0 && (
            <span className="ml-2 text-accent-red font-normal">
              ({requests.length})
            </span>
          )}
        </h2>
      </div>

      {/* Request list */}
      <div className="flex-1 overflow-y-auto">
        {requests.length === 0 ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            No agents need input.
          </div>
        ) : (
          requests.map((req) => (
            <RequestCard key={req.requestId} request={req} />
          ))
        )}
      </div>
    </div>
  );
}
