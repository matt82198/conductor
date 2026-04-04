import { useState, useRef, useEffect, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import type { HumanInputRequest } from '../types/events';

const API_BASE = 'http://localhost:8090';

/**
 * Modal that pops up immediately when any agent needs human input.
 * Takes focus, shows the question, user types and hits Enter. Done.
 */
export function InputModal() {
  const requests = useConductorStore((s) => s.humanInputRequests);
  const removeHumanInput = useConductorStore((s) => s.removeHumanInput);
  const addEvent = useConductorStore((s) => s.addEvent);
  const [input, setInput] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const activeRequest: HumanInputRequest | null = requests[0] ?? null;

  // Auto-focus when modal appears
  useEffect(() => {
    if (activeRequest) {
      setInput('');
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [activeRequest?.requestId]);

  const handleSend = useCallback(async () => {
    if (!input.trim() || !activeRequest) return;
    const text = input.trim();
    setInput('');

    // Show in feed
    addEvent({
      id: `user-${Date.now()}`,
      agentId: activeRequest.agentId,
      agentName: 'you',
      type: 'text',
      content: text,
      timestamp: new Date(),
    });

    try {
      await fetch(`${API_BASE}/api/humaninput/${activeRequest.requestId}/respond`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text }),
      });
    } catch { /* shown in feed */ }
    removeHumanInput(activeRequest.requestId);
  }, [input, activeRequest, removeHumanInput, addEvent]);

  if (!activeRequest) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="w-[560px] max-w-[90vw] bg-surface-1 border border-surface-3 rounded-lg shadow-2xl">
        {/* Header */}
        <div className="px-4 py-3 border-b border-surface-3 flex items-center gap-2">
          <span className="inline-block w-2 h-2 rounded-full bg-accent-red animate-pulse" />
          <span className="text-sm font-bold text-gray-200 font-mono">{activeRequest.agentName}</span>
          <span className="text-xs text-gray-500">needs your input</span>
          {requests.length > 1 && (
            <span className="ml-auto text-[10px] text-gray-500">+{requests.length - 1} more waiting</span>
          )}
        </div>

        {/* Question */}
        <div className="px-4 py-3">
          <div className="text-sm text-gray-200 font-mono whitespace-pre-wrap leading-relaxed max-h-60 overflow-y-auto">
            {activeRequest.question}
          </div>
          {activeRequest.suggestedOptions.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mt-3">
              {activeRequest.suggestedOptions.map((opt, i) => (
                <button key={i} onClick={() => setInput(opt)}
                  className="text-xs px-2 py-1 rounded bg-surface-2 border border-surface-3 text-gray-300 hover:border-accent-blue hover:text-accent-blue font-mono">
                  {opt}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Input */}
        <div className="px-4 py-3 border-t border-surface-3">
          <div className="flex items-center gap-2 font-mono text-sm">
            <span className="text-accent-green">{'>'}</span>
            <input
              ref={inputRef}
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleSend(); }}
              placeholder="Type your response and press Enter"
              className="flex-1 bg-transparent text-gray-200 focus:outline-none placeholder-gray-600"
              autoFocus
            />
          </div>
        </div>
      </div>
    </div>
  );
}
