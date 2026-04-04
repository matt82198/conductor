import { useState, useRef, useEffect, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import type { HumanInputRequest } from '../types/events';

const API_BASE = 'http://localhost:8090';

export function HumanInputPanel() {
  const requests = useConductorStore((s) => s.humanInputRequests);
  const removeHumanInput = useConductorStore((s) => s.removeHumanInput);
  const events = useConductorStore((s) => s.events);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Auto-scroll to bottom
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [events, requests]);

  // Auto-focus input when a request comes in
  useEffect(() => {
    if (requests.length > 0) inputRef.current?.focus();
  }, [requests.length]);

  const activeRequest = requests[0] ?? null;

  const handleSend = useCallback(async () => {
    if (!input.trim() || !activeRequest || sending) return;
    setSending(true);
    try {
      const res = await fetch(`${API_BASE}/api/humaninput/${activeRequest.requestId}/respond`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: input.trim() }),
      });
      if (res.ok) {
        removeHumanInput(activeRequest.requestId);
        setInput('');
      }
    } catch { /* ignore */ }
    setSending(false);
  }, [input, activeRequest, sending, removeHumanInput]);

  // Recent events for the active agent (or all if no active request)
  const agentId = activeRequest?.agentId;
  const relevantEvents = agentId
    ? events.filter((e) => e.agentId === agentId).slice(-30)
    : events.slice(-30);

  return (
    <div className="flex flex-col h-full bg-surface-0 font-mono text-xs">
      {/* Header */}
      <div className="px-3 py-2 border-b border-surface-3 bg-surface-1 shrink-0">
        <span className="text-gray-400 font-bold tracking-wider text-[10px]">
          TERMINAL
        </span>
        {activeRequest && (
          <span className="ml-2 text-accent-red animate-pulse">
            awaiting input
          </span>
        )}
      </div>

      {/* Event stream (terminal-style) */}
      <div className="flex-1 overflow-y-auto px-3 py-1">
        {relevantEvents.map((e) => (
          <div key={e.id} className="py-0.5 leading-relaxed">
            <span className="text-gray-600">[{e.timestamp.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}] </span>
            <span className="text-accent-blue">{e.agentName}: </span>
            <span className={
              e.type === 'error' ? 'text-accent-red' :
              e.type === 'thinking' ? 'text-accent-yellow' :
              e.type === 'tool_use' ? 'text-blue-400' :
              e.type === 'completed' ? 'text-accent-green' :
              'text-gray-300'
            }>{e.content}</span>
          </div>
        ))}

        {/* The question prompt */}
        {activeRequest && (
          <div className="py-1 mt-1 border-t border-surface-3">
            <span className="text-accent-red font-bold">{activeRequest.agentName}</span>
            <span className="text-gray-400"> is asking:</span>
            <div className="text-gray-200 mt-1 whitespace-pre-wrap">{activeRequest.question}</div>
            {activeRequest.suggestedOptions.length > 0 && (
              <div className="text-gray-500 mt-1">
                options: {activeRequest.suggestedOptions.join(' | ')}
              </div>
            )}
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Input prompt */}
      <div className="flex items-center gap-1 px-3 py-2 border-t border-surface-3 bg-surface-1 shrink-0">
        <span className={activeRequest ? 'text-accent-green' : 'text-gray-600'}>
          {activeRequest ? '>' : '$'}
        </span>
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') handleSend(); }}
          placeholder={activeRequest ? 'Type your response...' : 'No agent waiting for input'}
          disabled={!activeRequest || sending}
          className="flex-1 bg-transparent text-gray-200 focus:outline-none placeholder-gray-700 disabled:opacity-40"
        />
      </div>
    </div>
  );
}
