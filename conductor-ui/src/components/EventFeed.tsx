import { useEffect, useRef, useCallback, useState } from 'react';
import { useConductorStore, type FeedEvent } from '../stores/conductorStore';

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

  // Last message never truncates. Others truncate at 200 chars unless expanded.
  const shouldTruncate = !isLast && isLong && !expanded;
  const displayContent = shouldTruncate
    ? event.content.slice(0, 200) + '...'
    : event.content;

  return (
    <div
      className={`flex gap-2 px-3 py-0.5 hover:bg-surface-2 font-mono text-xs leading-relaxed ${isLow ? 'opacity-50' : ''} ${isLong && !isLast ? 'cursor-pointer' : ''}`}
      onClick={isLong && !isLast ? () => setExpanded(!expanded) : undefined}
    >
      <span className="text-gray-600 shrink-0">
        [{formatTime(event.timestamp)}]
      </span>
      <span className="text-accent-blue shrink-0 truncate max-w-32">
        {event.agentName}:
      </span>
      {event.urgency && (event.urgency === 'CRITICAL' || event.urgency === 'HIGH') && (
        <span className={`text-[10px] font-bold tracking-wider px-1 py-0.5 rounded shrink-0 ${
          event.urgency === 'CRITICAL' ? 'bg-red-500/20 text-accent-red' : 'bg-yellow-500/20 text-amber-400'
        }`}>
          {event.urgency === 'CRITICAL' ? 'CRIT' : 'HIGH'}
        </span>
      )}
      <span className={`${eventTypeColor(event.type)} min-w-0 ${isLast ? 'whitespace-pre-wrap' : shouldTruncate ? '' : 'whitespace-pre-wrap'}`}>
        {displayContent}
        {shouldTruncate && <span className="text-gray-600 ml-1">(click to expand)</span>}
      </span>
    </div>
  );
}

export function EventFeed() {
  const events = useConductorStore((s) => s.events);
  const clearEvents = useConductorStore((s) => s.clearEvents);
  const containerRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    setAutoScroll(el.scrollHeight - el.scrollTop - el.clientHeight < 40);
  }, []);

  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [events, autoScroll]);

  return (
    <div className="flex flex-col h-full bg-surface-0">
      <div className="flex items-center justify-between px-3 py-2 border-b border-surface-3 bg-surface-1">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          EVENT FEED
          <span className="ml-2 text-gray-600 font-normal">{events.length}</span>
        </h2>
        <div className="flex items-center gap-2">
          {!autoScroll && (
            <button onClick={() => { setAutoScroll(true); if (containerRef.current) containerRef.current.scrollTop = containerRef.current.scrollHeight; }}
              className="text-[10px] text-accent-blue hover:text-blue-300 transition-colors">
              Resume
            </button>
          )}
          <button onClick={clearEvents} className="text-[10px] text-gray-600 hover:text-gray-400 transition-colors">
            Clear
          </button>
        </div>
      </div>

      <div ref={containerRef} onScroll={handleScroll} className="flex-1 overflow-y-auto">
        {events.length === 0 ? (
          <div className="flex items-center justify-center h-full text-gray-700 text-sm">
            Waiting for agent events...
          </div>
        ) : (
          <div className="py-1">
            {events.map((event, i) => (
              <EventRow key={event.id} event={event} isLast={i === events.length - 1} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
