import { useState, useRef, useEffect, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import { useProjectStore } from '../stores/projectStore';
import type { SpawnAgentRequest } from '../types/events';

const API_BASE = 'http://localhost:8090';

/**
 * Extended spawn form that includes a dropdown of registered projects.
 *
 * This is a drop-in replacement for SpawnForm that adds project selection.
 * The user can either type a path manually or select from registered
 * projects. When a registered project is selected, its path fills the
 * text input. Manual entry still works for unregistered projects.
 *
 * Integration: Replace <SpawnForm /> with <ProjectSpawnForm /> in the
 * layout component, or use ProjectTaskMainLayout which wires this
 * automatically.
 */
export function ProjectSpawnForm() {
  const [name, setName] = useState('');
  const [role, setRole] = useState('FEATURE_ENGINEER');
  const [projectPath, setProjectPath] = useState('');
  const [prompt, setPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showProjectList, setShowProjectList] = useState(false);

  const nameRef = useRef<HTMLInputElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const projects = useProjectStore((s) => s.projects);
  const initialized = useProjectStore((s) => s.initialized);
  const fetchAll = useProjectStore((s) => s.fetchAll);

  // Ensure project store is loaded
  useEffect(() => {
    if (!initialized) {
      fetchAll();
    }
  }, [initialized, fetchAll]);

  // Listen for Ctrl+N shortcut from Electron main process
  useEffect(() => {
    const focus = () => nameRef.current?.focus();
    window.conductor?.onFocusSpawn(focus);

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
      setShowProjectList(false);
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

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setShowProjectList(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => {
      document.removeEventListener('mousedown', handleClick);
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

        const agent = await res.json();
        addAgent(agent);

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

  const selectProject = useCallback((path: string) => {
    setProjectPath(path);
    setShowProjectList(false);
  }, []);

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

      {/* Project path with dropdown */}
      <div className="relative flex-1 min-w-0" ref={dropdownRef}>
        <input
          type="text"
          placeholder="project path"
          value={projectPath}
          onChange={(e) => setProjectPath(e.target.value)}
          onFocus={() => {
            if (projects.length > 0) {
              setShowProjectList(true);
            }
          }}
          className="w-full bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
        />

        {/* Registered project dropdown */}
        {showProjectList && projects.length > 0 && (
          <div className="absolute top-full left-0 right-0 mt-0.5 z-50 bg-surface-2 border border-surface-3 rounded shadow-lg max-h-48 overflow-y-auto">
            {projects.map((project) => (
              <button
                key={project.id}
                type="button"
                onClick={() => selectProject(project.path)}
                className="w-full text-left px-2 py-1.5 hover:bg-surface-3 transition-colors border-b border-surface-3/50 last:border-b-0"
              >
                <span className="text-xs text-gray-200 font-mono font-bold block">
                  {project.name}
                </span>
                <span className="text-[10px] text-gray-500 font-mono block truncate">
                  {project.path}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

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
