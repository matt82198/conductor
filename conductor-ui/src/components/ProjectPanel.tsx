import { useState, useEffect, useCallback } from 'react';
import { useProjectStore } from '../stores/projectStore';
import type { ProjectRecord, ProjectKnowledge, PatternEntry } from '../types/projectTypes';

/**
 * Tag chip for pattern entries — small colored pills similar
 * to detection method badges in HumanInputPanel.
 */
function TagChip({ tag }: { tag: string }) {
  return (
    <span className="text-[9px] font-bold tracking-wider px-1 py-0.5 rounded bg-blue-500/20 text-accent-blue">
      {tag}
    </span>
  );
}

/**
 * Tech stack badge in accent blue.
 */
function TechStackBadge({ techStack }: { techStack: string }) {
  return (
    <span className="text-[10px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-blue-500/20 text-accent-blue">
      {techStack}
    </span>
  );
}

/**
 * Purple pulsing indicator for analysis-in-progress state.
 */
function AnalyzingIndicator() {
  return (
    <div className="flex items-center gap-1.5">
      <span className="inline-block w-2 h-2 rounded-full bg-purple-400 animate-pulse" />
      <span className="text-[10px] text-purple-300 font-mono">Analyzing...</span>
    </div>
  );
}

/**
 * Expanded pattern list showing all extracted patterns.
 */
function PatternList({ patterns }: { patterns: PatternEntry[] }) {
  if (patterns.length === 0) {
    return (
      <p className="text-[10px] text-gray-600 px-3 py-2">
        No patterns extracted.
      </p>
    );
  }

  return (
    <div className="px-3 py-2 space-y-2">
      {patterns.map((pattern, i) => (
        <div key={i} className="bg-surface-2 rounded px-2 py-1.5 border border-surface-3/50">
          <div className="flex items-start gap-2">
            <span className="text-xs text-gray-200 font-mono font-bold flex-1 min-w-0">
              {pattern.name}
            </span>
          </div>
          <p className="text-[10px] text-gray-400 mt-0.5 leading-relaxed">
            {pattern.description}
          </p>
          <div className="flex items-center gap-2 mt-1">
            <span className="text-[9px] text-gray-600 font-mono truncate">
              {pattern.sourceFile}
            </span>
          </div>
          {pattern.tags.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-1">
              {pattern.tags.map((tag, j) => (
                <TagChip key={j} tag={tag} />
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

/**
 * Single project card with knowledge display and analysis controls.
 */
function ProjectCard({ project }: { project: ProjectRecord }) {
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const knowledge = useProjectStore((s) => s.knowledge.get(project.id));
  const isAnalyzing = useProjectStore((s) => s.analyzingProjects.has(project.id));
  const analyzeProject = useProjectStore((s) => s.analyzeProject);

  const handleAnalyze = useCallback(async () => {
    setError(null);
    try {
      await analyzeProject(project.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Analysis failed');
    }
  }, [project.id, analyzeProject]);

  return (
    <div className="border-b border-surface-3">
      <div className="px-3 py-2">
        {/* Project name and path */}
        <div className="flex items-center gap-2">
          {/* Expand/collapse toggle */}
          {knowledge && knowledge.patterns.length > 0 ? (
            <button
              onClick={() => setExpanded(!expanded)}
              className="text-[10px] text-gray-500 hover:text-gray-300 transition-colors shrink-0 w-3"
            >
              {expanded ? '\u25BC' : '\u25B6'}
            </button>
          ) : (
            <span className="w-3 shrink-0" />
          )}

          <span className="text-sm text-gray-200 font-mono font-bold truncate flex-1 min-w-0">
            {project.name}
          </span>

          {/* Agent count badge */}
          {project.agentCount > 0 && (
            <span className="text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded bg-green-500/20 text-accent-green shrink-0">
              {project.agentCount} agent{project.agentCount !== 1 ? 's' : ''}
            </span>
          )}
        </div>

        {/* Path */}
        <p className="text-[10px] text-gray-600 font-mono ml-5 mt-0.5 truncate">
          {project.path}
        </p>

        {/* Git remote */}
        {project.gitRemote && (
          <p className="text-[10px] text-gray-600 font-mono ml-5 mt-0.5 truncate">
            {project.gitRemote}
          </p>
        )}

        {/* Knowledge status */}
        <div className="ml-5 mt-1.5 flex items-center gap-2 flex-wrap">
          {isAnalyzing ? (
            <AnalyzingIndicator />
          ) : knowledge ? (
            <>
              {knowledge.techStack && (
                <TechStackBadge techStack={knowledge.techStack} />
              )}
              <span className="text-[10px] text-gray-500 font-mono">
                {knowledge.patterns.length} pattern{knowledge.patterns.length !== 1 ? 's' : ''}
              </span>
              <span className="text-[10px] text-gray-500 font-mono">
                {knowledge.keyFiles.length} key file{knowledge.keyFiles.length !== 1 ? 's' : ''}
              </span>
              {knowledge.patterns.length > 0 && (
                <button
                  onClick={() => setExpanded(!expanded)}
                  className="text-[10px] text-purple-400 hover:text-purple-300 transition-colors font-mono"
                >
                  {expanded ? 'Hide patterns' : 'View patterns'}
                </button>
              )}
            </>
          ) : (
            <button
              onClick={handleAnalyze}
              className="text-[10px] font-bold px-2 py-0.5 rounded border border-purple-500/30 text-purple-400 bg-purple-500/10 hover:bg-purple-500/20 transition-colors"
            >
              Analyze
            </button>
          )}
        </div>

        {error && (
          <p className="text-accent-red text-xs mt-1 ml-5">{error}</p>
        )}
      </div>

      {/* Expanded: patterns + architecture */}
      {expanded && knowledge && (
        <div className="bg-surface-0/50">
          {/* Architecture summary */}
          {knowledge.architectureSummary && (
            <div className="px-3 py-2 border-b border-surface-3/50">
              <p className="text-[10px] font-bold text-gray-400 tracking-wider mb-1">ARCHITECTURE</p>
              <p className="text-[10px] text-gray-400 leading-relaxed">
                {knowledge.architectureSummary}
              </p>
            </div>
          )}

          {/* Key files */}
          {knowledge.keyFiles.length > 0 && (
            <div className="px-3 py-2 border-b border-surface-3/50">
              <p className="text-[10px] font-bold text-gray-400 tracking-wider mb-1">KEY FILES</p>
              <div className="space-y-0.5">
                {knowledge.keyFiles.map((file, i) => (
                  <p key={i} className="text-[10px] text-gray-500 font-mono truncate">
                    {file}
                  </p>
                ))}
              </div>
            </div>
          )}

          {/* Patterns */}
          <div>
            <div className="px-3 pt-2 pb-0.5">
              <p className="text-[10px] font-bold text-gray-400 tracking-wider">PATTERNS</p>
            </div>
            <PatternList patterns={knowledge.patterns} />
          </div>
        </div>
      )}
    </div>
  );
}

/* ---------- Register Form ---------- */

function RegisterForm() {
  const [path, setPath] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const registerProject = useProjectStore((s) => s.registerProject);
  const analyzeProject = useProjectStore((s) => s.analyzeProject);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!path.trim()) return;

      setSubmitting(true);
      setError(null);

      try {
        const project = await registerProject(path.trim());
        setPath('');

        // Auto-trigger analysis after registration
        analyzeProject(project.id).catch(() => {
          // Analysis failure is shown on the card itself
        });
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to register project');
      } finally {
        setSubmitting(false);
      }
    },
    [path, registerProject, analyzeProject],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSubmit(e as unknown as React.FormEvent);
      }
    },
    [handleSubmit],
  );

  return (
    <form onSubmit={handleSubmit} className="px-3 py-2 border-b border-surface-3">
      <p className="text-[10px] font-bold text-gray-500 tracking-wider mb-1">REGISTER</p>
      <div className="flex gap-1.5">
        <input
          type="text"
          placeholder="Project path (e.g. C:/projects/my-app)"
          value={path}
          onChange={(e) => setPath(e.target.value)}
          onKeyDown={handleKeyDown}
          className="flex-1 min-w-0 bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
        />
        <button
          type="submit"
          disabled={submitting || !path.trim()}
          className="bg-purple-600 text-white text-xs font-bold px-3 py-1 rounded hover:bg-purple-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
        >
          {submitting ? '...' : 'Register'}
        </button>
      </div>
      {error && (
        <p className="text-accent-red text-xs mt-1">{error}</p>
      )}
    </form>
  );
}

/* ---------- Scan Form ---------- */

function ScanForm() {
  const [rootPath, setRootPath] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scanResult, setScanResult] = useState<string | null>(null);
  const scanProjects = useProjectStore((s) => s.scanProjects);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!rootPath.trim()) return;

      setSubmitting(true);
      setError(null);
      setScanResult(null);

      try {
        const projects = await scanProjects(rootPath.trim());
        setScanResult(`Found ${projects.length} project${projects.length !== 1 ? 's' : ''}`);
        setRootPath('');
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to scan');
      } finally {
        setSubmitting(false);
      }
    },
    [rootPath, scanProjects],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSubmit(e as unknown as React.FormEvent);
      }
    },
    [handleSubmit],
  );

  return (
    <form onSubmit={handleSubmit} className="px-3 py-2 border-b border-surface-3">
      <p className="text-[10px] font-bold text-gray-500 tracking-wider mb-1">SCAN DIRECTORY</p>
      <div className="flex gap-1.5">
        <input
          type="text"
          placeholder="Root directory to scan"
          value={rootPath}
          onChange={(e) => setRootPath(e.target.value)}
          onKeyDown={handleKeyDown}
          className="flex-1 min-w-0 bg-surface-2 text-gray-200 text-sm px-2 py-1 rounded border border-surface-3 focus:border-accent-blue focus:outline-none font-mono"
        />
        <button
          type="submit"
          disabled={submitting || !rootPath.trim()}
          className="bg-surface-2 text-gray-300 text-xs font-bold px-3 py-1 rounded border border-surface-3 hover:border-gray-500 hover:text-gray-200 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
        >
          {submitting ? '...' : 'Scan'}
        </button>
      </div>
      {error && (
        <p className="text-accent-red text-xs mt-1">{error}</p>
      )}
      {scanResult && (
        <p className="text-accent-green text-xs mt-1">{scanResult}</p>
      )}
    </form>
  );
}

/* ---------- Project Panel (main export) ---------- */

/**
 * Panel for managing projects and viewing their Brain-extracted knowledge.
 * Shows register/scan forms at top and project cards below with analysis
 * status, tech stack badges, pattern counts, and expandable pattern views.
 */
export function ProjectPanel() {
  const projects = useProjectStore((s) => s.projects);
  const initialized = useProjectStore((s) => s.initialized);
  const fetchAll = useProjectStore((s) => s.fetchAll);

  // Fetch projects and knowledge on mount
  useEffect(() => {
    if (!initialized) {
      fetchAll();
    }
  }, [initialized, fetchAll]);

  return (
    <div className="flex flex-col h-full bg-surface-1 border-l border-surface-3">
      {/* Header */}
      <div className="px-3 py-2 border-b border-surface-3">
        <h2 className="text-xs font-bold text-gray-400 tracking-wider">
          PROJECTS
          {projects.length > 0 && (
            <span className="ml-2 text-purple-400 font-normal">
              ({projects.length})
            </span>
          )}
        </h2>
      </div>

      {/* Register form */}
      <RegisterForm />

      {/* Scan form */}
      <ScanForm />

      {/* Project cards */}
      <div className="flex-1 overflow-y-auto">
        {!initialized ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            <span className="inline-block w-2 h-2 rounded-full bg-purple-400 animate-pulse mr-1.5" />
            Loading projects...
          </div>
        ) : projects.length === 0 ? (
          <div className="px-3 py-8 text-center text-gray-600 text-xs">
            No projects registered.
            <br />
            <span className="text-gray-700">Register a project path above to get started.</span>
          </div>
        ) : (
          projects.map((project) => (
            <ProjectCard key={project.id} project={project} />
          ))
        )}
      </div>
    </div>
  );
}
