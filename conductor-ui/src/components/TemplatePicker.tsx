import { useState, useEffect, useCallback, useRef } from 'react';
import type { AgentTemplate, TemplateUseResponse } from '../types/commandTypes';

const API_BASE = 'http://localhost:8090';

interface TemplatePickerProps {
  /** Callback to display a result message in the command bar's result area. */
  onResult: (result: { success: boolean; message: string }) => void;
}

/**
 * A horizontally-scrollable row of template chips rendered below the command bar.
 *
 * On mount fetches templates from GET /api/templates. Each chip shows the template
 * name and a usage-count badge. Clicking a chip expands an inline form to fill in
 * projectPath and an optional prompt override, then POSTs to /api/templates/{id}/use.
 */
export function TemplatePicker({ onResult }: TemplatePickerProps) {
  const [templates, setTemplates] = useState<AgentTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Which template chip is currently expanded (null = none)
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [projectPath, setProjectPath] = useState('');
  const [promptOverride, setPromptOverride] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const pathRef = useRef<HTMLInputElement>(null);

  // Fetch templates on mount
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`${API_BASE}/api/templates`);
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        const data: AgentTemplate[] = await res.json();
        if (!cancelled) {
          // Sort by usage count descending (most used first)
          data.sort((a, b) => b.usageCount - a.usageCount);
          setTemplates(data);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load templates');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Focus project-path input when a chip is expanded
  useEffect(() => {
    if (expandedId) {
      pathRef.current?.focus();
    }
  }, [expandedId]);

  const handleChipClick = useCallback((templateId: string) => {
    setExpandedId((prev) => (prev === templateId ? null : templateId));
    setProjectPath('');
    setPromptOverride('');
  }, []);

  const handleUse = useCallback(
    async (templateId: string) => {
      if (!projectPath.trim() || submitting) return;
      setSubmitting(true);
      try {
        const res = await fetch(`${API_BASE}/api/templates/${templateId}/use`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            projectPath: projectPath.trim(),
            promptOverride: promptOverride.trim(),
          }),
        });
        const data: TemplateUseResponse = await res.json();
        onResult({
          success: data.success,
          message: data.message ?? (data.success ? 'Template applied' : 'Failed to use template'),
        });
        if (data.success) {
          setExpandedId(null);
          setProjectPath('');
          setPromptOverride('');
          // Bump usage count locally
          setTemplates((prev) =>
            prev
              .map((t) => (t.templateId === templateId ? { ...t, usageCount: t.usageCount + 1 } : t))
              .sort((a, b) => b.usageCount - a.usageCount),
          );
        }
      } catch (err) {
        onResult({ success: false, message: err instanceof Error ? err.message : 'Failed to use template' });
      } finally {
        setSubmitting(false);
      }
    },
    [projectPath, promptOverride, submitting, onResult],
  );

  // Don't render anything while loading or if there are no templates
  if (loading || error || templates.length === 0) return null;

  return (
    <div className="px-4 py-1.5 bg-surface-1 border-b border-surface-3">
      {/* Chip row: horizontally scrollable */}
      <div className="flex items-center gap-1.5 overflow-x-auto scrollbar-thin pb-0.5">
        {templates.map((t) => {
          const isExpanded = expandedId === t.templateId;
          return (
            <button
              key={t.templateId}
              onClick={() => handleChipClick(t.templateId)}
              className={`shrink-0 flex items-center gap-1 px-2 py-0.5 rounded text-xs font-mono transition-colors
                ${
                  isExpanded
                    ? 'bg-surface-2 border border-purple-400 text-purple-300'
                    : 'bg-surface-2 border border-surface-3 text-gray-300 hover:border-accent-blue hover:text-gray-200'
                }`}
              title={t.description}
            >
              {t.name}
              {t.usageCount > 0 && (
                <span className="text-[9px] text-gray-500 bg-surface-0 px-1 rounded-full">
                  {t.usageCount}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* Expanded inline form */}
      {expandedId && (
        <div className="flex items-center gap-2 mt-1.5 animate-slide-in">
          <span className="text-[10px] text-purple-400 font-mono shrink-0 uppercase tracking-wider">
            {templates.find((t) => t.templateId === expandedId)?.name}
          </span>
          <input
            ref={pathRef}
            type="text"
            placeholder="project path"
            value={projectPath}
            onChange={(e) => setProjectPath(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleUse(expandedId);
              if (e.key === 'Escape') setExpandedId(null);
            }}
            className="bg-surface-0 text-gray-200 text-xs px-2 py-1 rounded border border-surface-3 focus:border-purple-400 focus:outline-none flex-1 min-w-0 font-mono"
          />
          <input
            type="text"
            placeholder="prompt override (optional)"
            value={promptOverride}
            onChange={(e) => setPromptOverride(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleUse(expandedId);
              if (e.key === 'Escape') setExpandedId(null);
            }}
            className="bg-surface-0 text-gray-200 text-xs px-2 py-1 rounded border border-surface-3 focus:border-purple-400 focus:outline-none flex-1 min-w-0 font-mono"
          />
          <button
            onClick={() => handleUse(expandedId)}
            disabled={submitting || !projectPath.trim()}
            className="text-xs font-bold px-3 py-1 rounded bg-purple-600 text-white hover:bg-purple-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0 font-mono"
          >
            {submitting ? '...' : 'GO'}
          </button>
          <button
            onClick={() => setExpandedId(null)}
            className="text-gray-500 hover:text-gray-300 text-xs shrink-0 font-mono"
          >
            ESC
          </button>
        </div>
      )}
    </div>
  );
}
