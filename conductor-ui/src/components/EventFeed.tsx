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
    default: return 'text-gray-400';
  }
}

function EventRow({ event }: { event: FeedEvent }) {
  return (
    <div className="px-3 py-0.5 hover:bg-surface-2 font-mono text-xs leading-relaxed">
      <span className="text-gray-600">[{formatTime(event.timestamp)}] </span>
      <span className={event.agentName === 'you' ? 'text-accent-green font-bold' : 'text-accent-blue'}>{event.agentName}: </span>
      <span className={`${eventTypeColor(event.type)} whitespace-pre-wrap`}>{event.content}</span>
    </div>
  );
}

export function EventFeed() {
  const events = useConductorStore((s) => s.events);
  const clearEvents = useConductorStore((s) => s.clearEvents);
  const humanInputRequests = useConductorStore((s) => s.humanInputRequests);
  const removeHumanInput = useConductorStore((s) => s.removeHumanInput);
  const addEvent = useConductorStore((s) => s.addEvent);
  const agents = useConductorStore((s) => s.agents);

  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [input, setInput] = useState('');

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

  useEffect(() => {
    if (humanInputRequests.length > 0) inputRef.current?.focus();
  }, [humanInputRequests.length]);

  const lastAgentId = events.length > 0 ? events[events.length - 1].agentId : null;

  const handleSend = useCallback(async () => {
    if (!input.trim()) return;
    const text = input.trim();
    setInput(''); // Always clear immediately

    // Show in feed
    addEvent({
      id: `user-${Date.now()}`,
      agentId: humanInputRequests[0]?.agentId ?? lastAgentId ?? '',
      agentName: 'you',
      type: 'text',
      content: text,
      timestamp: new Date(),
    });

    // Try to respond to the first pending request
    if (humanInputRequests.length > 0) {
      const req = humanInputRequests[0];
      try {
        await fetch(`${API_BASE}/api/humaninput/${req.requestId}/respond`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text }),
        });
        removeHumanInput(req.requestId);
      } catch { /* already shown in feed */ }
    } else if (lastAgentId) {
      try {
        await fetch(`${API_BASE}/api/agents/${lastAgentId}/message`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text }),
        });
      } catch { /* already shown in feed */ }
    }
  }, [input, humanInputRequests, lastAgentId, removeHumanInput, addEvent]);

  return (
    <div className="flex flex-col h-full bg-surface-0">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-surface-3 bg-surface-1 shrink-0">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          EVENTS <span className="text-gray-600 font-normal">{events.length}</span>
          {humanInputRequests.length > 0 && (
            <span className="ml-2 text-accent-red animate-pulse">
              {humanInputRequests.length} awaiting input
            </span>
          )}
        </h2>
        <button onClick={clearEvents} className="text-[10px] text-gray-600 hover:text-gray-400">Clear</button>
      </div>

      {/* Event stream */}
      <div ref={containerRef} onScroll={handleScroll} className="flex-1 overflow-y-auto">
        {events.length === 0 ? (
          <div className="flex items-center justify-center h-full text-gray-700 text-sm font-mono">
            Waiting for agent events...
          </div>
        ) : (
          <div className="py-1">
            {events.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </div>
        )}

        {/* Show ALL pending questions, not just the first */}
        {humanInputRequests.map((req, i) => (
          <div key={req.requestId} className={`px-3 py-2 border-t ${i === 0 ? 'border-accent-red/30 bg-red-500/5' : 'border-surface-3 bg-surface-1'}`}>
            <div className="font-mono text-xs">
              <span className={i === 0 ? 'text-accent-red font-bold' : 'text-accent-yellow font-bold'}>{req.agentName}</span>
              <span className="text-gray-400"> {i === 0 ? 'asks' : 'also waiting'}: </span>
              <span className="text-gray-200 whitespace-pre-wrap">{req.question}</span>
            </div>
            {req.suggestedOptions.length > 0 && (
              <div className="font-mono text-xs text-gray-500 mt-1">
                options: {req.suggestedOptions.join(' | ')}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Input — always visible, always enabled, always clears */}
      <div className="flex items-center gap-2 px-3 py-2 border-t border-surface-3 bg-surface-1 shrink-0 font-mono text-xs">
        <span className={humanInputRequests.length > 0 ? 'text-accent-green' : 'text-gray-600'}>
          {humanInputRequests.length > 0 ? '>' : '$'}
        </span>
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') handleSend(); }}
          placeholder={humanInputRequests.length > 0 ? `Respond to ${humanInputRequests[0].agentName}...` : 'Message agent...'}
          className="flex-1 bg-transparent text-gray-200 focus:outline-none placeholder-gray-700"
        />
      </div>
    </div>
  );
}
