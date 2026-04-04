import { useState, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import { useAgents } from '../hooks/useAgents';

const API_BASE = 'http://localhost:8090';

/**
 * Bottom status bar showing connection state, agent count, human input count,
 * DND toggle, and total cost.
 */
export function StatusBar() {
  const connected = useConductorStore((s) => s.connected);
  const totalCost = useConductorStore((s) => s.totalCostUsd);
  const humanInputCount = useConductorStore((s) => s.humanInputRequests.length);
  const brainStatus = useConductorStore((s) => s.brainStatus);
  const { stats } = useAgents();

  const [dndEnabled, setDndEnabled] = useState(false);
  const [togglingDnd, setTogglingDnd] = useState(false);

  const toggleDnd = useCallback(async () => {
    setTogglingDnd(true);
    try {
      const next = !dndEnabled;
      const res = await fetch(`${API_BASE}/api/notifications/dnd`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: next }),
      });
      if (res.ok) {
        setDndEnabled(next);
      }
    } catch {
      // Silently fail — DND is non-critical
    } finally {
      setTogglingDnd(false);
    }
  }, [dndEnabled]);

  return (
    <div className="flex items-center justify-between px-4 py-1.5 bg-surface-1 border-t border-surface-3 text-xs font-mono">
      {/* Left: connection status */}
      <div className="flex items-center gap-2">
        <span
          className={`inline-block w-2 h-2 rounded-full ${
            connected ? 'bg-accent-green' : 'bg-accent-red animate-pulse'
          }`}
        />
        <span className={connected ? 'text-gray-400' : 'text-accent-red'}>
          {connected ? 'Connected' : 'Disconnected'}
        </span>
      </div>

      {/* Center: agent counts + human input + DND */}
      <div className="flex items-center gap-4 text-gray-500">
        <span>
          Agents:{' '}
          <span className="text-gray-300">
            {stats.active}/{stats.total}
          </span>
        </span>
        {stats.blocked > 0 && (
          <span className="text-accent-red">
            {stats.blocked} blocked
          </span>
        )}
        {stats.thinking > 0 && (
          <span className="text-accent-yellow">
            {stats.thinking} thinking
          </span>
        )}
        {humanInputCount > 0 && (
          <span className="text-accent-red font-bold">
            {humanInputCount} need input
          </span>
        )}
        {brainStatus && (
          <div className="flex items-center gap-1.5">
            <span
              className={`inline-block w-2 h-2 rounded-full ${
                brainStatus.enabled ? 'bg-purple-400' : 'bg-gray-600'
              }`}
            />
            <span className={brainStatus.enabled ? 'text-purple-400' : 'text-gray-600'}>
              Brain {brainStatus.enabled ? 'ON' : 'OFF'}
            </span>
          </div>
        )}
        <button
          onClick={toggleDnd}
          disabled={togglingDnd}
          className={`px-2 py-0.5 rounded border transition-colors ${
            dndEnabled
              ? 'text-accent-red border-red-500/30 bg-red-500/10 hover:bg-red-500/20'
              : 'text-gray-600 border-surface-3 hover:text-gray-400 hover:border-gray-500'
          }`}
        >
          DND{dndEnabled ? ' ON' : ''}
        </button>
      </div>

      {/* Right: cost */}
      <div className="text-gray-500">
        Cost:{' '}
        <span className="text-gray-300">${totalCost.toFixed(4)}</span>
      </div>
    </div>
  );
}
