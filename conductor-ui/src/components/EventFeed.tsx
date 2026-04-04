import { useEffect, useRef, useCallback, useState } from 'react';
import { useConductorStore, type FeedEvent } from '../stores/conductorStore';

const API_BASE = 'http://localhost:8090';

function formatTime(date: Date): string {
  return date.toLocaleTimeString('en-US', {
    hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

function eventTypeColor(type: string): string {
  switch (type) {
    case 'thinking': return 'text-yellow-400';
    case 'tool_use': return 'text-blue-400';
    case 'tool_result': return 'text-cyan-400';
    case 'text': return 'text-gray-300';
    case 'error': return 'text-red-400';
    case 'completed': return 'text-green-400';
    case 'brain': return 'text-purple-400';
    case 'spawned': return 'text-purple-400';
    default: return 'text-gray-400';
  }
}

function EventRow({ event, isLast }: { event: FeedEvent; isLast: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const isLow = event.urgency === 'LOW' || event.urgency === 'NOISE';
  const isLong = event.content.length > 200;
  const shouldTruncate = !isLast && isLong && !expanded;
  const displayContent = shouldTruncate ? event.content.slice(0, 200) + '...' : event.content;

  return (
    <div
      className={`flex gap-2 px-3 py-0.5 hover:bg-surface-2 font-mono text-xs leading-relaxed ${isLow ? 'opacity-50' : ''} ${isLong && !isLast ? 'cursor-pointer' : ''}`}
      onClick={isLong && !isLast ? () => setExpanded(!expanded) : undefined}
    >
      <span className="text-gray-600 shrink-0">[{formatTime(event.timestamp)}]</span>
      <span className="text-accent-blue shrink-0 truncate max-w-32">{event.agentName}:</span>
      {event.urgency && (event.urgency === 'CRITICAL' || event.urgency === 'HIGH') && (
        <span className={`text-[10px] font-bold tracking-wider px-1 py-0.5 rounded shrink-0 ${
          event.urgency === 'CRITICAL' ? 'bg-red-500/20 text-accent-red' : 'bg-yellow-500/20 text-amber-400'
        }`}>{event.urgency === 'CRITICAL' ? 'CRIT' : 'HIGH'}</span>
      )}
      <span className={`${eventTypeColor(event.type)} min-w-0 whitespace-pre-wrap`}>
        {displayContent}
        {shouldTruncate && <span className="text-gray-600 ml-1">(click)</span>}
      </span>
    </div>
  );
}

export function EventFeed() {
  const events = useConductorStore((s) => s.events);
  const clearEvents = useConductorStore((s) => s.clearEvents);
  const humanInputRequests = useConductorStore((s) => s.humanInputRequests);
  const removeHumanInput = useConductorStore((s) => s.removeHumanInput);
  const agents = useConductorStore((s) => s.agents);

  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    setAutoScroll(el.scrollHeight - el.scrollTop - el.clientHeight < 40);
  }, []);

  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [events, humanInputRequests, autoScroll]);

  // Auto-focus when an agent needs input
  useEffect(() => {
    if (humanInputRequests.length > 0) inputRef.current?.focus();
  }, [humanInputRequests.length]);

  const activeRequest = humanInputRequests[0] ?? null;

  // Find most recent active agent (for direct messaging when no request pending)
  const lastAgentId = events.length > 0 ? events[events.length - 1].agentId : null;

  const handleSend = useCallback(async () => {
    if (!input.trim() || sending) return;
    setSending(true);

    try {
      if (activeRequest) {
        // Respond to human input request
        const res = await fetch(`${API_BASE}/api/humaninput/${activeRequest.requestId}/respond`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: input.trim() }),
        });
        if (res.ok) removeHumanInput(activeRequest.requestId);
      } else if (lastAgentId) {
        // Direct message to last active agent
        await fetch(`${API_BASE}/api/agents/${lastAgentId}/message`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: input.trim() }),
        });
      }
      setInput('');
    } catch { /* ignore */ }
    setSending(false);
  }, [input, sending, activeRequest, lastAgentId, removeHumanInput]);

  return (
    <div className="flex flex-col h-full bg-surface-0">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-surface-3 bg-surface-1 shrink-0">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          EVENTS
          <span className="ml-2 text-gray-600 font-normal">{events.length}</span>
          {activeRequest && <span className="ml-2 text-accent-red animate-pulse">input needed</span>}
        </h2>
        <div className="flex items-center gap-2">
          {!autoScroll && (
            <button onClick={() => { setAutoScroll(true); if (containerRef.current) containerRef.current.scrollTop = containerRef.current.scrollHeight; }}
              className="text-[10px] text-accent-blue hover:text-blue-300">Resume</button>
          )}
          <button onClick={clearEvents} className="text-[10px] text-gray-600 hover:text-gray-400">Clear</button>
        </div>
      </div>

      {/* Event stream */}
      <div ref={containerRef} onScroll={handleScroll} className="flex-1 overflow-y-auto">
        {events.length === 0 ? (
          <div className="flex items-center justify-center h-full text-gray-700 text-sm font-mono">
            Waiting for agent events...
          </div>
        ) : (
          <div className="py-1">
            {events.map((event, i) => (
              <EventRow key={event.id} event={event} isLast={i === events.length - 1} />
            ))}
          </div>
        )}

        {/* Inline question prompt */}
        {activeRequest && (
          <div className="px-3 py-2 border-t border-accent-red/30 bg-red-500/5">
            <div className="font-mono text-xs">
              <span className="text-accent-red font-bold">{activeRequest.agentName}</span>
              <span className="text-gray-400"> asks: </span>
              <span className="text-gray-200 whitespace-pre-wrap">{activeRequest.question}</span>
            </div>
            {activeRequest.suggestedOptions.length > 0 && (
              <div className="font-mono text-xs text-gray-500 mt-1">
                options: {activeRequest.suggestedOptions.join(' | ')}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Input bar — always visible */}
      <div className="flex items-center gap-2 px-3 py-2 border-t border-surface-3 bg-surface-1 shrink-0 font-mono text-xs">
        <span className={activeRequest ? 'text-accent-green' : 'text-gray-600'}>
          {activeRequest ? '>' : '$'}
        </span>
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') handleSend(); }}
          placeholder={activeRequest ? `Respond to ${activeRequest.agentName}...` : lastAgentId ? 'Message agent...' : 'No agents running'}
          disabled={sending || (!activeRequest && !lastAgentId)}
          className="flex-1 bg-transparent text-gray-200 focus:outline-none placeholder-gray-700 disabled:opacity-40"
        />
        {sending && <span className="text-purple-400 animate-pulse">sending...</span>}
      </div>
    </div>
  );
}
