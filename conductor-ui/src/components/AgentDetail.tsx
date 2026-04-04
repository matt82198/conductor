import { useEffect, useRef, useCallback, useState } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import { useAgentDetailStore, type OutputEntry } from '../stores/agentDetailStore';
import type { AgentState } from '../types/events';

const API_BASE = 'http://localhost:8090';

/**
 * Color and label for each agent state — matches AgentList convention.
 */
const STATE_DISPLAY: Record<
  AgentState,
  { dot: string; color: string; bg: string; label: string }
> = {
  LAUNCHING: {
    dot: 'bg-accent-blue',
    color: 'text-accent-blue',
    bg: 'bg-blue-500/20',
    label: 'LAUNCHING',
  },
  ACTIVE: {
    dot: 'bg-accent-green',
    color: 'text-accent-green',
    bg: 'bg-green-500/20',
    label: 'ACTIVE',
  },
  THINKING: {
    dot: 'bg-accent-yellow animate-pulse',
    color: 'text-accent-yellow',
    bg: 'bg-yellow-500/20',
    label: 'THINKING',
  },
  USING_TOOL: {
    dot: 'bg-accent-green',
    color: 'text-accent-green',
    bg: 'bg-green-500/20',
    label: 'TOOL USE',
  },
  BLOCKED: {
    dot: 'bg-accent-red',
    color: 'text-accent-red',
    bg: 'bg-red-500/20',
    label: 'BLOCKED',
  },
  COMPLETED: {
    dot: 'bg-accent-gray',
    color: 'text-accent-gray',
    bg: 'bg-gray-500/20',
    label: 'COMPLETED',
  },
  FAILED: {
    dot: 'bg-accent-red',
    color: 'text-accent-red',
    bg: 'bg-red-500/20',
    label: 'FAILED',
  },
};

/**
 * Format ISO timestamp to compact HH:MM:SS.
 */
function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString('en-US', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return iso;
  }
}

/**
 * Type badge styling for output entries.
 */
function typeBadge(type: string): { color: string; bg: string; label: string } {
  switch (type) {
    case 'thinking':
      return { color: 'text-yellow-400', bg: 'bg-yellow-500/20', label: 'THINKING' };
    case 'text':
      return { color: 'text-gray-300', bg: 'bg-gray-500/20', label: 'TEXT' };
    case 'tool_use':
      return { color: 'text-blue-400', bg: 'bg-blue-500/20', label: 'TOOL USE' };
    case 'tool_result':
      return { color: 'text-cyan-400', bg: 'bg-cyan-500/20', label: 'RESULT' };
    case 'error':
      return { color: 'text-red-400', bg: 'bg-red-500/20', label: 'ERROR' };
    case 'system':
      return { color: 'text-gray-500', bg: 'bg-gray-500/10', label: 'SYSTEM' };
    default:
      return { color: 'text-gray-400', bg: 'bg-gray-500/10', label: type.toUpperCase() };
  }
}

/**
 * Collapsible thinking block -- click header to expand/collapse.
 */
function ThinkingBlock({ content }: { content: string }) {
  const [expanded, setExpanded] = useState(false);
  const previewLen = 120;
  const needsCollapse = content.length > previewLen;

  return (
    <div
      className="cursor-pointer select-none"
      onClick={() => setExpanded((v) => !v)}
    >
      <span className="text-yellow-400 italic whitespace-pre-wrap break-words">
        {expanded || !needsCollapse
          ? content
          : content.slice(0, previewLen) + '...'}
      </span>
      {needsCollapse && (
        <span className="ml-2 text-[10px] text-yellow-600 font-bold">
          {expanded ? '[collapse]' : '[expand]'}
        </span>
      )}
    </div>
  );
}

/**
 * Tool use block -- tool name badge + input content in code block.
 */
function ToolUseBlock({
  toolName,
  content,
}: {
  toolName: string | null;
  content: string;
}) {
  return (
    <div>
      {toolName && (
        <span className="inline-block text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-blue-500/30 text-blue-300 mb-1 mr-2">
          {toolName}
        </span>
      )}
      <pre className="text-blue-400 whitespace-pre-wrap break-words text-xs bg-surface-2 rounded px-2 py-1.5 mt-1 border border-surface-3 overflow-x-auto">
        {content}
      </pre>
    </div>
  );
}

/**
 * A single output entry row.
 */
function OutputRow({ entry }: { entry: OutputEntry }) {
  const badge = typeBadge(entry.type);
  const isError = entry.isError || entry.type === 'error';

  return (
    <div
      className={`px-4 py-2 border-b border-surface-3 ${
        isError ? 'bg-red-500/5' : 'hover:bg-surface-2'
      } transition-colors`}
    >
      {/* Metadata row: timestamp + type badge */}
      <div className="flex items-center gap-2 mb-1">
        <span className="text-[10px] text-gray-600 font-mono shrink-0">
          {formatTime(entry.timestamp)}
        </span>
        <span
          className={`text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded ${badge.color} ${badge.bg}`}
        >
          {badge.label}
        </span>
      </div>

      {/* Content */}
      <div className="ml-0 text-xs font-mono leading-relaxed">
        {entry.type === 'thinking' ? (
          <ThinkingBlock content={entry.content} />
        ) : entry.type === 'tool_use' ? (
          <ToolUseBlock toolName={entry.toolName} content={entry.content} />
        ) : entry.type === 'tool_result' ? (
          <pre className="text-cyan-400 whitespace-pre-wrap break-words bg-surface-2 rounded px-2 py-1.5 border border-surface-3 overflow-x-auto">
            {entry.content}
          </pre>
        ) : entry.type === 'error' ? (
          <span className="text-red-400 whitespace-pre-wrap break-words">
            {entry.content}
          </span>
        ) : entry.type === 'system' ? (
          <span className="text-gray-500 whitespace-pre-wrap break-words">
            {entry.content}
          </span>
        ) : (
          <span className="text-gray-300 whitespace-pre-wrap break-words">
            {entry.content}
          </span>
        )}
      </div>
    </div>
  );
}

/**
 * Full agent detail panel -- shows complete output/conversation history
 * for the selected agent.
 *
 * Props:
 *   agentId  -- the ID of the agent to display
 *   onClose  -- callback to deselect/go back
 */
export function AgentDetail({
  agentId,
  onClose,
}: {
  agentId: string;
  onClose: () => void;
}) {
  const agent = useConductorStore((s) => s.agents.get(agentId));
  const output = useAgentDetailStore((s) => s.output);
  const loading = useAgentDetailStore((s) => s.loading);
  const fetchError = useAgentDetailStore((s) => s.fetchError);
  const setOutput = useAgentDetailStore((s) => s.setOutput);
  const setLoading = useAgentDetailStore((s) => s.setLoading);
  const setFetchError = useAgentDetailStore((s) => s.setFetchError);

  const containerRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [killing, setKilling] = useState(false);

  // Fetch output on mount and poll every 2 seconds for active agents
  useEffect(() => {
    let cancelled = false;

    const fetchOutput = async () => {
      try {
        const res = await fetch(`${API_BASE}/api/agents/${agentId}/output`);
        if (!res.ok) {
          if (!cancelled) setFetchError(`HTTP ${res.status}`);
          return;
        }
        const data: OutputEntry[] = await res.json();
        if (!cancelled) {
          setOutput(data);
          setLoading(false);
          setFetchError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setFetchError(err instanceof Error ? err.message : 'Network error');
          setLoading(false);
        }
      }
    };

    fetchOutput();
    const interval = setInterval(fetchOutput, 2000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [agentId, setOutput, setLoading, setFetchError]);

  // Auto-scroll to bottom when new output arrives
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [output, autoScroll]);

  // Detect scroll-away from bottom
  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    setAutoScroll(atBottom);
  }, []);

  // Kill agent
  const handleKill = useCallback(async () => {
    setKilling(true);
    try {
      const res = await fetch(`${API_BASE}/api/agents/${agentId}`, {
        method: 'DELETE',
      });
      if (!res.ok) {
        console.error('Kill failed:', res.status);
      }
    } catch (err) {
      console.error('Kill error:', err);
    } finally {
      setKilling(false);
    }
  }, [agentId]);

  // Send message
  const handleSend = useCallback(async () => {
    if (!message.trim()) return;
    setSending(true);
    setSendError(null);
    try {
      const res = await fetch(`${API_BASE}/api/agents/${agentId}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: message.trim() }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      setMessage('');
    } catch (err) {
      setSendError(err instanceof Error ? err.message : 'Failed to send');
    } finally {
      setSending(false);
    }
  }, [agentId, message]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
      if (e.key === 'Escape') {
        onClose();
      }
    },
    [handleSend, onClose],
  );

  const display = agent
    ? STATE_DISPLAY[agent.state] ?? STATE_DISPLAY.ACTIVE
    : STATE_DISPLAY.ACTIVE;

  const isTerminated =
    agent?.state === 'COMPLETED' || agent?.state === 'FAILED';

  return (
    <div className="flex flex-col h-full bg-surface-0">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-2 border-b border-surface-3 bg-surface-1 shrink-0">
        {/* Back button */}
        <button
          onClick={onClose}
          className="text-gray-500 hover:text-gray-300 transition-colors text-sm font-mono shrink-0"
          title="Back to event feed (Esc)"
        >
          &larr; Back
        </button>

        {/* Divider */}
        <div className="w-px h-4 bg-surface-3" />

        {/* Agent name */}
        <span className="text-sm text-gray-200 font-mono font-bold truncate">
          {agent?.name ?? agentId.slice(0, 12)}
        </span>

        {/* Role badge */}
        {agent?.role && (
          <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-purple-500/20 text-purple-300 shrink-0">
            {agent.role}
          </span>
        )}

        {/* State dot + label */}
        <span
          className={`inline-block w-2 h-2 rounded-full shrink-0 ${display.dot}`}
        />
        <span
          className={`text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded ${display.color} ${display.bg} shrink-0`}
        >
          {display.label}
        </span>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Cost */}
        {agent && agent.costUsd > 0 && (
          <span className="text-[10px] text-gray-500 font-mono shrink-0">
            ${agent.costUsd.toFixed(4)}
          </span>
        )}

        {/* Kill button */}
        {agent && !isTerminated && (
          <button
            onClick={handleKill}
            disabled={killing}
            className="text-[10px] font-bold px-2 py-1 rounded bg-red-500/20 text-accent-red border border-red-500/30 hover:bg-red-500/30 disabled:opacity-40 transition-colors shrink-0"
          >
            {killing ? 'Killing...' : 'Kill'}
          </button>
        )}
      </div>

      {/* Scrollable output area */}
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto min-h-0"
      >
        {loading ? (
          <div className="flex items-center justify-center h-full text-gray-700 text-sm">
            Loading agent output...
          </div>
        ) : fetchError ? (
          <div className="flex flex-col items-center justify-center h-full gap-2">
            <span className="text-accent-red text-sm">
              Failed to load output
            </span>
            <span className="text-gray-600 text-xs">{fetchError}</span>
          </div>
        ) : output.length === 0 ? (
          <div className="flex items-center justify-center h-full text-gray-700 text-sm">
            No output yet.
          </div>
        ) : (
          <div>
            {output.map((entry, i) => (
              <OutputRow key={`${entry.timestamp}-${i}`} entry={entry} />
            ))}
          </div>
        )}
      </div>

      {/* Auto-scroll resume */}
      {!autoScroll && output.length > 0 && (
        <div className="px-4 py-1 bg-surface-1 border-t border-surface-3 shrink-0">
          <button
            onClick={() => {
              setAutoScroll(true);
              if (containerRef.current) {
                containerRef.current.scrollTop =
                  containerRef.current.scrollHeight;
              }
            }}
            className="text-[10px] text-accent-blue hover:text-blue-300 transition-colors"
          >
            Resume auto-scroll
          </button>
        </div>
      )}

      {/* Message input -- only show for active (non-terminated) agents */}
      {agent && !isTerminated && (
        <div className="px-4 py-2 border-t border-surface-3 bg-surface-1 shrink-0">
          <div className="flex gap-2">
            <input
              type="text"
              placeholder="Send message to agent..."
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={sending}
              className="flex-1 min-w-0 bg-surface-2 text-gray-200 text-sm px-3 py-1.5 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
            />
            <button
              onClick={handleSend}
              disabled={sending || !message.trim()}
              className="bg-accent-blue text-white text-xs font-bold px-4 py-1.5 rounded hover:bg-blue-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
            >
              {sending ? '...' : 'Send'}
            </button>
          </div>
          {sendError && (
            <p className="text-accent-red text-xs mt-1">{sendError}</p>
          )}
        </div>
      )}
    </div>
  );
}
