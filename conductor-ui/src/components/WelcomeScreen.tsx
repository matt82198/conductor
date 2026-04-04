import { useState, useEffect, useCallback, useRef, useMemo } from 'react';

/* -----------------------------------------------------------------------
 * WelcomeScreen
 *
 * A premium full-screen landing experience shown each time Conductor
 * launches (after LoadingScreen finishes).  It is designed to feel like
 * opening a high-end developer tool -- understated confidence, smooth
 * motion, dark & atmospheric.  Any key press or click dismisses it
 * instantly.
 *
 * All animations are pure CSS @keyframes injected via a <style> tag --
 * zero external dependencies.
 * --------------------------------------------------------------------- */

// ---------------------------------------------------------------------------
//  Configuration
// ---------------------------------------------------------------------------

const COLORS = {
  bg1: '#050510',
  bg2: '#0a0a1a',
  text: '#e2e8f0',
  accent: '#8b5cf6',
  muted: '#64748b',
  glow: 'rgba(139, 92, 246, 0.35)',
  glowSoft: 'rgba(139, 92, 246, 0.12)',
  gridLine: 'rgba(139, 92, 246, 0.04)',
  gridLineBright: 'rgba(139, 92, 246, 0.08)',
} as const;

const TITLE = 'CONDUCTOR';
const SUBTITLE = 'Unseen Productivity';
const TYPING_TEXT = 'Your AI orchestrator is ready. What would you like to build?';

interface StatDef {
  label: string;
  value: number;
  suffix?: string;
}

const STATS: StatDef[] = [
  { label: 'Projects', value: 3 },
  { label: 'Templates Ready', value: 12 },
  { label: 'Patterns Learned', value: 47 },
  { label: 'Brain', value: 1, suffix: 'Online' },
];

// Timing constants (ms)
const LETTER_STAGGER = 55;
const TITLE_TOTAL = TITLE.length * LETTER_STAGGER;
const SUBTITLE_DELAY = TITLE_TOTAL + 300;
const PULSE_LINE_DELAY = SUBTITLE_DELAY + 400;
const STATS_BASE_DELAY = PULSE_LINE_DELAY + 500;
const STAT_STAGGER = 220;
const TYPING_DELAY = STATS_BASE_DELAY + STATS.length * STAT_STAGGER + 400;
const BUTTON_DELAY = TYPING_DELAY + TYPING_TEXT.length * 30 + 600;
const COUNT_DURATION = 1200;

// ---------------------------------------------------------------------------
//  Keyframes (injected once)
// ---------------------------------------------------------------------------

const KEYFRAMES = `
@keyframes wc-fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes wc-fadeInSoft {
  from { opacity: 0; }
  to   { opacity: 1; }
}
@keyframes wc-glow {
  0%, 100% { text-shadow: 0 0 20px ${COLORS.glowSoft}, 0 0 40px ${COLORS.glowSoft}; }
  50%      { text-shadow: 0 0 30px ${COLORS.glow}, 0 0 60px ${COLORS.glowSoft}; }
}
@keyframes wc-pulseLine {
  0%   { width: 0; opacity: 0; }
  15%  { opacity: 1; }
  100% { width: min(420px, 60vw); opacity: 0; }
}
@keyframes wc-btnPulse {
  0%, 100% { box-shadow: 0 0 0 0 ${COLORS.glow}; }
  50%      { box-shadow: 0 0 20px 4px ${COLORS.glowSoft}; }
}
@keyframes wc-cursor {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}
@keyframes wc-gridDrift {
  0%   { background-position: 0 0, 0 0; }
  100% { background-position: 40px 40px, 40px 40px; }
}
@keyframes wc-screenFadeOut {
  from { opacity: 1; }
  to   { opacity: 0; }
}
@keyframes wc-radialPulse {
  0%   { transform: translate(-50%, -50%) scale(0.8); opacity: 0.18; }
  50%  { transform: translate(-50%, -50%) scale(1.2); opacity: 0.06; }
  100% { transform: translate(-50%, -50%) scale(0.8); opacity: 0.18; }
}
`;

// ---------------------------------------------------------------------------
//  Hooks
// ---------------------------------------------------------------------------

/** Smoothly counts from 0 to `target` over `duration` ms, starting at `delay`. */
function useCountUp(target: number, delay: number, duration: number): number {
  const [current, setCurrent] = useState(0);

  useEffect(() => {
    let raf: number;
    const timer = setTimeout(() => {
      const start = performance.now();
      function tick(now: number) {
        const elapsed = now - start;
        const progress = Math.min(elapsed / duration, 1);
        // ease-out cubic
        const eased = 1 - Math.pow(1 - progress, 3);
        setCurrent(Math.round(eased * target));
        if (progress < 1) {
          raf = requestAnimationFrame(tick);
        }
      }
      raf = requestAnimationFrame(tick);
    }, delay);

    return () => {
      clearTimeout(timer);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [target, delay, duration]);

  return current;
}

/** Reveals `text` one character at a time starting at `delay`. */
function useTyping(text: string, delay: number, speed = 30): string {
  const [revealed, setRevealed] = useState(0);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let idx = 0;

    const startTimer = setTimeout(() => {
      function next() {
        idx += 1;
        setRevealed(idx);
        if (idx < text.length) {
          timer = setTimeout(next, speed);
        }
      }
      timer = setTimeout(next, speed);
    }, delay);

    return () => {
      clearTimeout(startTimer);
      clearTimeout(timer);
    };
  }, [text, delay, speed]);

  return text.slice(0, revealed);
}

// ---------------------------------------------------------------------------
//  Sub-components
// ---------------------------------------------------------------------------

function AnimatedTitle() {
  const letters = useMemo(
    () =>
      TITLE.split('').map((ch, i) => ({
        char: ch,
        delay: i * LETTER_STAGGER,
      })),
    [],
  );

  return (
    <div style={{ display: 'flex', justifyContent: 'center', gap: '0.18em' }}>
      {letters.map((l, i) => (
        <span
          key={i}
          style={{
            display: 'inline-block',
            fontSize: 'clamp(2rem, 5vw, 3.5rem)',
            fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
            fontWeight: 700,
            letterSpacing: '0.18em',
            color: COLORS.text,
            opacity: 0,
            animation: `wc-fadeInSoft 400ms ease-out ${l.delay}ms forwards, wc-glow 4s ease-in-out ${l.delay + 400}ms infinite`,
          }}
        >
          {l.char}
        </span>
      ))}
    </div>
  );
}

function Subtitle() {
  return (
    <p
      style={{
        marginTop: '0.75rem',
        fontSize: 'clamp(0.8rem, 1.4vw, 1rem)',
        fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
        color: COLORS.muted,
        letterSpacing: '0.22em',
        textTransform: 'uppercase',
        opacity: 0,
        animation: `wc-fadeIn 600ms ease-out ${SUBTITLE_DELAY}ms forwards`,
      }}
    >
      {SUBTITLE}
    </p>
  );
}

function PulseLine() {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        marginTop: '1.5rem',
        height: '2px',
        overflow: 'visible',
      }}
    >
      <div
        style={{
          height: '1px',
          background: `linear-gradient(90deg, transparent, ${COLORS.accent}, transparent)`,
          borderRadius: '1px',
          opacity: 0,
          animation: `wc-pulseLine 2s ease-out ${PULSE_LINE_DELAY}ms forwards`,
        }}
      />
    </div>
  );
}

function StatItem({ stat, index }: { stat: StatDef; index: number }) {
  const delay = STATS_BASE_DELAY + index * STAT_STAGGER;
  const count = useCountUp(stat.value, delay, COUNT_DURATION);
  const isBrain = stat.suffix === 'Online';

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '0.3rem',
        opacity: 0,
        animation: `wc-fadeIn 500ms ease-out ${delay}ms forwards`,
        minWidth: '100px',
      }}
    >
      <span
        style={{
          fontSize: 'clamp(1.4rem, 2.5vw, 2rem)',
          fontWeight: 700,
          fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
          color: isBrain ? COLORS.accent : COLORS.text,
          lineHeight: 1,
        }}
      >
        {isBrain ? '' : count}
        {stat.suffix && (
          <span style={{ fontSize: '0.55em', marginLeft: '0.3em', color: COLORS.accent }}>
            {stat.suffix}
          </span>
        )}
      </span>
      <span
        style={{
          fontSize: '0.65rem',
          color: COLORS.muted,
          letterSpacing: '0.12em',
          textTransform: 'uppercase',
          fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
        }}
      >
        {stat.label}
      </span>
    </div>
  );
}

function StatsRow() {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        gap: 'clamp(1.5rem, 4vw, 3rem)',
        marginTop: '2.5rem',
        flexWrap: 'wrap',
      }}
    >
      {STATS.map((s, i) => (
        <StatItem key={s.label} stat={s} index={i} />
      ))}
    </div>
  );
}

function TypingTagline() {
  const text = useTyping(TYPING_TEXT, TYPING_DELAY, 30);
  const done = text.length === TYPING_TEXT.length;

  return (
    <div
      style={{
        marginTop: '2.5rem',
        minHeight: '1.8rem',
        textAlign: 'center',
        fontSize: 'clamp(0.75rem, 1.2vw, 0.9rem)',
        fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
        color: COLORS.muted,
        opacity: 0,
        animation: `wc-fadeInSoft 200ms ease-out ${TYPING_DELAY}ms forwards`,
      }}
    >
      <span>{text}</span>
      <span
        style={{
          display: 'inline-block',
          width: '2px',
          height: '1em',
          marginLeft: '2px',
          verticalAlign: 'text-bottom',
          backgroundColor: done ? COLORS.accent : COLORS.muted,
          animation: 'wc-cursor 800ms step-end infinite',
        }}
      />
    </div>
  );
}

function EnterButton({ delay, onClick }: { delay: number; onClick: () => void }) {
  return (
    <div
      style={{
        marginTop: '2.5rem',
        opacity: 0,
        animation: `wc-fadeIn 600ms ease-out ${delay}ms forwards`,
      }}
    >
      <button
        onClick={onClick}
        style={{
          padding: '0.6rem 2.4rem',
          fontSize: '0.8rem',
          fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
          fontWeight: 600,
          letterSpacing: '0.2em',
          textTransform: 'uppercase',
          color: COLORS.text,
          backgroundColor: 'transparent',
          border: `1px solid ${COLORS.accent}`,
          borderRadius: '6px',
          cursor: 'pointer',
          transition: 'background-color 200ms ease, color 200ms ease',
          animation: `wc-btnPulse 3s ease-in-out infinite`,
        }}
        onMouseEnter={(e) => {
          (e.target as HTMLElement).style.backgroundColor = COLORS.accent;
          (e.target as HTMLElement).style.color = '#fff';
        }}
        onMouseLeave={(e) => {
          (e.target as HTMLElement).style.backgroundColor = 'transparent';
          (e.target as HTMLElement).style.color = COLORS.text;
        }}
      >
        Enter Conductor
      </button>
      <p
        style={{
          marginTop: '0.75rem',
          fontSize: '0.6rem',
          color: COLORS.muted,
          textAlign: 'center',
          fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
          letterSpacing: '0.08em',
          opacity: 0.6,
        }}
      >
        press any key to continue
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
//  Main Export
// ---------------------------------------------------------------------------

interface WelcomeScreenProps {
  onEnter: () => void;
}

export function WelcomeScreen({ onEnter }: WelcomeScreenProps) {
  const [exiting, setExiting] = useState(false);
  const exitingRef = useRef(false);

  const handleEnter = useCallback(() => {
    if (exitingRef.current) return;
    exitingRef.current = true;
    setExiting(true);
    // Allow 300ms for fade-out, then call parent
    setTimeout(() => onEnter(), 320);
  }, [onEnter]);

  // Dismiss on any key
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Ignore modifier-only presses
      if (['Shift', 'Control', 'Alt', 'Meta'].includes(e.key)) return;
      handleEnter();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [handleEnter]);

  return (
    <>
      {/* Inject keyframes */}
      <style>{KEYFRAMES}</style>

      <div
        onClick={handleEnter}
        style={{
          position: 'fixed',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          background: `linear-gradient(170deg, ${COLORS.bg1} 0%, ${COLORS.bg2} 50%, ${COLORS.bg1} 100%)`,
          cursor: 'default',
          overflow: 'hidden',
          userSelect: 'none',
          ...(exiting
            ? { animation: 'wc-screenFadeOut 300ms ease-out forwards' }
            : {}),
        }}
      >
        {/* Animated grid mesh background */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            backgroundImage: `
              linear-gradient(${COLORS.gridLine} 1px, transparent 1px),
              linear-gradient(90deg, ${COLORS.gridLine} 1px, transparent 1px)
            `,
            backgroundSize: '40px 40px',
            animation: 'wc-gridDrift 30s linear infinite',
            opacity: 0.8,
            pointerEvents: 'none',
          }}
        />

        {/* Brighter grid overlay that fades at edges */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            backgroundImage: `
              linear-gradient(${COLORS.gridLineBright} 1px, transparent 1px),
              linear-gradient(90deg, ${COLORS.gridLineBright} 1px, transparent 1px)
            `,
            backgroundSize: '80px 80px',
            animation: 'wc-gridDrift 45s linear infinite reverse',
            maskImage: 'radial-gradient(ellipse 50% 50% at center, black 10%, transparent 70%)',
            WebkitMaskImage: 'radial-gradient(ellipse 50% 50% at center, black 10%, transparent 70%)',
            pointerEvents: 'none',
          }}
        />

        {/* Radial glow behind title */}
        <div
          style={{
            position: 'absolute',
            top: '42%',
            left: '50%',
            width: '500px',
            height: '500px',
            borderRadius: '50%',
            background: `radial-gradient(circle, ${COLORS.glowSoft} 0%, transparent 70%)`,
            animation: 'wc-radialPulse 6s ease-in-out infinite',
            pointerEvents: 'none',
          }}
        />

        {/* Content */}
        <div
          style={{
            position: 'relative',
            zIndex: 1,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            maxWidth: '700px',
            padding: '0 1.5rem',
          }}
        >
          <AnimatedTitle />
          <Subtitle />
          <PulseLine />
          <StatsRow />
          <TypingTagline />
          <EnterButton delay={BUTTON_DELAY} onClick={handleEnter} />
        </div>
      </div>
    </>
  );
}
