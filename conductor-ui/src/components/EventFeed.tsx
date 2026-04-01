import { useEffect, useRef, useCallback, useState } from 'react';
import { useConductorStore, type FeedEvent } from '../stores/conductorStore';

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
 * Color class for different event types in the feed.
 */
function eventTypeColor(type: string): string {
  switch (type) {
    case 'thinking':
      return 'text-yellow-400';
    case 'tool_use':
      return 'text-blue-400';
    case 'tool_result':
      return 'text-cyan-400';
    case 'text':
      return 'text-gray-300';
    case 'error':
      return 'text-red-400';
    case 'completed':
      return 'text-green-400';
    case 'spawned':
      return 'text-purple-400';
    case 'state':
      return 'text-gray-500';
    default:
      return 'text-gray-400';
  }
}

/**
 * Single event row in the feed.
 */
function EventRow({ event }: { event: FeedEvent }) {
  return (
    <div className="flex gap-2 px-3 py-0.5 hover:bg-surface-2 font-mono text-xs leading-relaxed">
      <span className="text-gray-600 shrink-0">
        [{formatTime(event.timestamp)}]
      </span>
      <span className="text-accent-blue shrink-0 truncate max-w-32">
        {event.agentName}:
      </span>
      <span className={`${eventTypeColor(event.type)} break-words min-w-0`}>
        {event.content}
      </span>
    </div>
  );
}

/**
 * Center panel: scrolling feed of agent events. Auto-scrolls to bottom
 * unless the user has scrolled up to review history.
 */
export function EventFeed() {
  const events = useConductorStore((s) => s.events);
  const clearEvents = useConductorStore((s) => s.clearEvents);

  const containerRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  // Detect when user scrolls away from bottom
  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    setAutoScroll(atBottom);
  }, []);

  // Auto-scroll to bottom when new events arrive (if auto-scroll is on)
  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  }, [events, autoScroll]);

  return (
    <div className="flex flex-col h-full bg-surface-0">
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-surface-3 bg-surface-1">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          EVENT FEED
          <span className="ml-2 text-gray-600 font-normal">
            {events.length} events
          </span>
        </h2>
        <div className="flex items-center gap-2">
          {!autoScroll && (
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
          )}
          <button
            onClick={clearEvents}
            className="text-[10px] text-gray-600 hover:text-gray-400 transition-colors"
          >
            Clear
          </button>
        </div>
      </div>

      {/* Scrollable event area */}
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto"
      >
        {events.length === 0 ? (
          <div className="flex items-center justify-center h-full text-gray-700 text-sm">
            Waiting for agent events...
          </div>
        ) : (
          <div className="py-1">
            {events.map((event) => (
              <EventRow key={event.id} event={event} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
