import { useConductorStore } from '../stores/conductorStore';
import { useAgents } from '../hooks/useAgents';

/**
 * Bottom status bar showing connection state, agent count, and total cost.
 */
export function StatusBar() {
  const connected = useConductorStore((s) => s.connected);
  const totalCost = useConductorStore((s) => s.totalCostUsd);
  const { stats } = useAgents();

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

      {/* Center: agent counts */}
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
      </div>

      {/* Right: cost */}
      <div className="text-gray-500">
        Cost:{' '}
        <span className="text-gray-300">${totalCost.toFixed(4)}</span>
      </div>
    </div>
  );
}
