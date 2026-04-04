import { useState, useEffect, useRef, useCallback } from 'react';
import { TemplatePicker } from './TemplatePicker';
import type { CommandResponse } from '../types/commandTypes';

const API_BASE = 'http://localhost:8090';

/**
 * Rotating placeholder examples shown when the input is empty.
 * Cycles every 4 seconds to suggest different command patterns.
 */
const PLACEHOLDERS = [
  'spawn an agent to write tests for the auth module',
  'register project at C:/Users/matt8/myapp',
  'what are my agents doing?',
  'break down: add OAuth2 to the API',
  'analyze medallioGenAi project',
];

/**
 * CommandBar replaces the SpawnForm as the primary way users interact with Conductor.
 *
 * Users type natural-language commands that are sent to the Brain for interpretation.
 * The bar includes: a single text input with a purple prompt, command history
 * (up/down arrows, last 20), a processing spinner, and transient result messages.
 * Below the input, a TemplatePicker renders quick-action chips for saved templates.
 *
 * Keyboard shortcuts:
 *  - Enter: submit command
 *  - Ctrl+K: focus the command bar from anywhere
 *  - Up/Down: navigate command history
 *  - Escape: clear input and blur
 */
export function CommandBar() {
  const [input, setInput] = useState('');
  const [processing, setProcessing] = useState(false);
  const [result, setResult] = useState<{ success: boolean; message: string } | null>(null);
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const [placeholderIndex, setPlaceholderIndex] = useState(0);

  const inputRef = useRef<HTMLInputElement>(null);
  const resultTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Keep a ref to the input value that was active before history navigation
  const draftRef = useRef('');

  // Rotate placeholder text
  useEffect(() => {
    const interval = setInterval(() => {
      setPlaceholderIndex((prev) => (prev + 1) % PLACEHOLDERS.length);
    }, 4000);
    return () => clearInterval(interval);
  }, []);

  // Global Ctrl+K to focus the command bar
  useEffect(() => {
    const handleGlobalKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handleGlobalKeyDown);
    return () => window.removeEventListener('keydown', handleGlobalKeyDown);
  }, []);

  // Also listen for Electron's onFocusSpawn bridge (backwards compat for Ctrl+N)
  useEffect(() => {
    const focus = () => inputRef.current?.focus();
    window.conductor?.onFocusSpawn(focus);
  }, []);

  // Clear result after a timeout
  const flashResult = useCallback((res: { success: boolean; message: string }) => {
    setResult(res);
    if (resultTimerRef.current) clearTimeout(resultTimerRef.current);
    resultTimerRef.current = setTimeout(() => setResult(null), 4000);
  }, []);

  // Clean up the timer on unmount
  useEffect(() => {
    return () => {
      if (resultTimerRef.current) clearTimeout(resultTimerRef.current);
    };
  }, []);

  const handleSubmit = useCallback(async () => {
    const text = input.trim();
    if (!text || processing) return;

    setProcessing(true);
    setResult(null);

    // Push to history (dedup consecutive identical entries)
    setHistory((prev) => {
      const deduped = prev.filter((h) => h !== text);
      return [text, ...deduped].slice(0, 20);
    });
    setHistoryIndex(-1);
    draftRef.current = '';

    try {
      const res = await fetch(`${API_BASE}/api/brain/command`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text }),
      });

      if (!res.ok) {
        const errText = await res.text();
        throw new Error(errText || `HTTP ${res.status}`);
      }

      const data: CommandResponse = await res.json();
      const success = data.result?.success ?? false;
      const message = data.result?.message ?? 'Command executed';
      flashResult({ success, message });
      if (success) setInput('');
    } catch (err) {
      flashResult({
        success: false,
        message: err instanceof Error ? err.message : 'Failed to execute command',
      });
    } finally {
      setProcessing(false);
    }
  }, [input, processing, flashResult]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleSubmit();
        return;
      }

      if (e.key === 'Escape') {
        e.preventDefault();
        setInput('');
        setHistoryIndex(-1);
        draftRef.current = '';
        inputRef.current?.blur();
        return;
      }

      if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (history.length === 0) return;
        setHistoryIndex((prev) => {
          const next = prev + 1;
          if (next >= history.length) return prev; // at oldest entry
          if (prev === -1) draftRef.current = input; // save current draft
          setInput(history[next]);
          return next;
        });
        return;
      }

      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setHistoryIndex((prev) => {
          if (prev <= -1) return -1;
          const next = prev - 1;
          if (next === -1) {
            setInput(draftRef.current);
          } else {
            setInput(history[next]);
          }
          return next;
        });
        return;
      }
    },
    [handleSubmit, history, input],
  );

  // Handle result from TemplatePicker
  const handleTemplateResult = useCallback(
    (res: { success: boolean; message: string }) => {
      flashResult(res);
    },
    [flashResult],
  );

  return (
    <div className="bg-surface-1 border-b border-surface-3">
      {/* Main command input row */}
      <div className="flex items-center gap-2 px-4 py-2">
        {/* Purple prompt character */}
        <span className="text-purple-400 font-mono text-sm shrink-0 select-none" aria-hidden>
          {processing ? '' : '\u25B6'}
        </span>

        {/* Processing spinner — purple pulse dot */}
        {processing && (
          <span className="shrink-0 flex items-center justify-center w-4 h-4">
            <span className="block w-2.5 h-2.5 rounded-full bg-purple-400 animate-pulse" />
          </span>
        )}

        {/* Input */}
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
            setHistoryIndex(-1);
          }}
          onKeyDown={handleKeyDown}
          placeholder={PLACEHOLDERS[placeholderIndex]}
          disabled={processing}
          className="flex-1 min-w-0 bg-surface-0 text-gray-200 text-sm px-3 py-1.5 rounded border border-surface-3 focus:border-purple-400 focus:outline-none font-mono placeholder:text-gray-600 disabled:opacity-60"
          aria-label="Command input"
        />

        {/* Keyboard hint */}
        <span className="text-[10px] text-gray-600 font-mono shrink-0 hidden sm:inline select-none">
          Ctrl+K
        </span>
      </div>

      {/* Result flash message */}
      {result && (
        <div className="px-4 pb-1.5 -mt-0.5">
          <span
            className={`text-xs font-mono inline-block transition-opacity duration-300 ${
              result.success ? 'text-accent-green' : 'text-accent-red'
            }`}
          >
            {result.success ? '\u2713' : '\u2717'} {result.message}
          </span>
        </div>
      )}

      {/* Template quick-picker row */}
      <TemplatePicker onResult={handleTemplateResult} />
    </div>
  );
}
