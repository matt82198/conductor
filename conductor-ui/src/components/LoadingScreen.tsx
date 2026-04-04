import { useState, useEffect } from 'react';

const API_BASE = 'http://localhost:8090';

interface LoadingScreenProps {
  onReady: () => void;
}

/**
 * Full-screen overlay displayed when Conductor first starts.
 * Polls for server connectivity, checks Brain status, and triggers
 * context refresh before transitioning to the main UI.
 */
export function LoadingScreen({ onReady }: LoadingScreenProps) {
  const [phase, setPhase] = useState<'connecting' | 'loading' | 'ready'>('connecting');
  const [messages, setMessages] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;

    async function initialize() {
      // Phase 1: Wait for server connection
      setPhase('connecting');
      addMsg('Connecting to Conductor server...');

      let connected = false;
      while (!connected && !cancelled) {
        try {
          const res = await fetch(`${API_BASE}/api/agents`);
          if (res.ok) connected = true;
        } catch {
          await new Promise((r) => setTimeout(r, 1000));
        }
      }
      if (cancelled) return;
      addMsg('Connected to server.');

      // Phase 2: Check brain status
      setPhase('loading');
      addMsg('Checking Conductor Brain...');

      try {
        const res = await fetch(`${API_BASE}/api/brain/status`);
        if (res.ok) {
          const status = await res.json();
          if (status.enabled) {
            addMsg(`Brain enabled (model: ${status.model})`);
            addMsg(`Behavior log: ${status.behaviorLogSize} past interactions`);
            addMsg(`Projects indexed: ${status.projectsIndexed}`);

            // Trigger context refresh
            addMsg('Scanning project context...');
            await fetch(`${API_BASE}/api/brain/context/refresh`, { method: 'POST' });
            addMsg('Context scan complete.');
          } else {
            addMsg('Brain disabled. Running in manual mode.');
          }
        } else {
          addMsg('Brain not available. Running in manual mode.');
        }
      } catch {
        addMsg('Brain not available. Running in manual mode.');
      }

      if (cancelled) return;
      addMsg('Ready.');
      setPhase('ready');

      // Short delay so user can see "Ready" before transition
      setTimeout(() => {
        if (!cancelled) onReady();
      }, 800);
    }

    function addMsg(msg: string) {
      if (!cancelled) {
        setMessages((prev) => [...prev, msg]);
      }
    }

    initialize();

    return () => {
      cancelled = true;
    };
  }, [onReady]);

  return (
    <div className="flex flex-col items-center justify-center h-screen w-screen bg-surface-0">
      {/* Logo / Title */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-gray-200 font-mono tracking-wider">
          CONDUCTOR
        </h1>
        <p className="text-xs text-gray-600 text-center mt-1">
          Agent Orchestration Platform
        </p>
      </div>

      {/* Startup log */}
      <div className="w-96 bg-surface-1 border border-surface-3 rounded-lg p-4 font-mono text-xs">
        {messages.map((msg, i) => (
          <div key={i} className="flex gap-2 py-0.5">
            <span className="text-accent-green shrink-0">
              {i === messages.length - 1 && phase !== 'ready' ? '>' : '\u00A0'}
            </span>
            <span
              className={
                msg === 'Ready.'
                  ? 'text-accent-green'
                  : msg.includes('not available') || msg.includes('disabled')
                    ? 'text-accent-yellow'
                    : 'text-gray-400'
              }
            >
              {msg}
            </span>
          </div>
        ))}
        {phase !== 'ready' && (
          <div className="flex gap-2 py-0.5">
            <span className="text-accent-green animate-pulse">{'>'}</span>
            <span className="text-gray-600 animate-pulse">...</span>
          </div>
        )}
      </div>
    </div>
  );
}
