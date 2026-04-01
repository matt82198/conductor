import { useState, useRef, useEffect, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import type { SpawnAgentRequest } from '../types/events';

const API_BASE = 'http://localhost:8090';

/**
 * Top bar form to spawn a new Claude Code agent. Sends a POST to the
 * Conductor server's /api/agents/spawn endpoint.
 */
export function SpawnForm() {
  const [name, setName] = useState('');
  const [role, setRole] = useState('FEATURE_ENGINEER');
  const [projectPath, setProjectPath] = useState('');
  const [prompt, setPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const nameRef = useRef<HTMLInputElement>(null);

  // Listen for Ctrl+N shortcut from Electron main process
  useEffect(() => {
    const focus = () => nameRef.current?.focus();
    window.conductor?.onFocusSpawn(focus);

    // Also handle plain browser context (dev mode without Electron)
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
        e.preventDefault();
        focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  // Listen for Escape to clear the form
  useEffect(() => {
    const clearForm = () => {
      setName('');
      setProjectPath('');
      setPrompt('');
      setError(null);
      nameRef.current?.blur();
    };
    window.conductor?.onEscape(clearForm);

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        clearForm();
      }
    };
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  const addAgent = useConductorStore((s) => s.addAgent);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!name.trim() || !projectPath.trim()) return;

      setSubmitting(true);
      setError(null);

      const body: SpawnAgentRequest = {
        name: name.trim(),
        role,
        projectPath: projectPath.trim(),
        prompt: prompt.trim() || 'Hello',
      };

      try {
        const res = await fetch(`${API_BASE}/api/agents/spawn`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });

        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || `HTTP ${res.status}`);
        }

        // Add spawned agent to store from REST response
        const agent = await res.json();
        addAgent(agent);

        // Clear form
        setName('');
        setProjectPath('');
        setPrompt('');
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to spawn agent');
      } finally {
        setSubmitting(false);
      }
    },
    [name, role, projectPath, prompt, addAgent],
  );

  const roles = [
    'FEATURE_ENGINEER',
    'TESTER',
    'REFACTORER',
    'REVIEWER',
    'GENERAL',
  ];

  return (
    <form
      onSubmit={handleSubmit}
      className="flex items-center gap-2 px-4 py-2 bg-surface-1 border-b border-surface-3"
    >
      <span className="text-xs text-gray-500 font-mono shrink-0">SPAWN</span>

      <input
        ref={nameRef}
        type="text"
        placeholder="name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        className="bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none w-32 font-mono"
      />

      <select
        value={role}
        onChange={(e) => setRole(e.target.value)}
        className="bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
      >
        {roles.map((r) => (
          <option key={r} value={r}>
            {r}
          </option>
        ))}
      </select>

      <input
        type="text"
        placeholder="project path"
        value={projectPath}
        onChange={(e) => setProjectPath(e.target.value)}
        className="bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none flex-1 min-w-0 font-mono"
      />

      <input
        type="text"
        placeholder="prompt (optional)"
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        className="bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none flex-1 min-w-0 font-mono"
      />

      <button
        type="submit"
        disabled={submitting || !name.trim() || !projectPath.trim()}
        className="bg-accent-blue text-white text-sm font-bold px-4 py-1 rounded hover:bg-blue-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
      >
        {submitting ? '...' : 'GO'}
      </button>

      {error && (
        <span className="text-accent-red text-xs truncate max-w-48">
          {error}
        </span>
      )}
    </form>
  );
}
