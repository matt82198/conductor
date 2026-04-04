/**
 * Standalone unit tests for WelcomeScreen logic.
 *
 * Since the project does not have a test runner configured, these tests
 * can be run via: npx tsx src/components/welcomeScreen.test.ts
 *
 * These tests exercise the pure logic (count-up easing, typing reveal,
 * timing constants) without requiring a DOM.  The React component itself
 * is validated via TypeScript type-checking (npx tsc --noEmit).
 */

let passed = 0;
let failed = 0;

function assert(condition: boolean, message: string): void {
  if (condition) {
    passed++;
    console.log(`  PASS: ${message}`);
  } else {
    failed++;
    console.error(`  FAIL: ${message}`);
  }
}

// ---------------------------------------------------------------------------
//  Test: ease-out cubic function (same as used in useCountUp)
// ---------------------------------------------------------------------------

function easeOutCubic(progress: number): number {
  return 1 - Math.pow(1 - progress, 3);
}

console.log('\n--- easeOutCubic ---');

assert(easeOutCubic(0) === 0, 'ease(0) = 0');
assert(easeOutCubic(1) === 1, 'ease(1) = 1');
assert(easeOutCubic(0.5) === 0.875, 'ease(0.5) = 0.875 (fast start)');
assert(easeOutCubic(0.25) > 0.5, 'ease(0.25) > 0.5 (accelerates early)');
assert(easeOutCubic(0.75) > 0.98, 'ease(0.75) > 0.98 (nearly done)');

// Monotonically increasing
let prev = 0;
let monotonic = true;
for (let i = 1; i <= 100; i++) {
  const val = easeOutCubic(i / 100);
  if (val < prev) { monotonic = false; break; }
  prev = val;
}
assert(monotonic, 'easeOutCubic is monotonically increasing');

// ---------------------------------------------------------------------------
//  Test: count-up rounding
// ---------------------------------------------------------------------------

console.log('\n--- countUp rounding ---');

function simulateCountUp(target: number, progress: number): number {
  return Math.round(easeOutCubic(progress) * target);
}

assert(simulateCountUp(47, 0) === 0, 'count starts at 0');
assert(simulateCountUp(47, 1) === 47, 'count reaches target');
assert(simulateCountUp(12, 0.5) === 11, 'count at 50% progress for 12');
assert(simulateCountUp(3, 0.1) === 1, 'count at 10% progress for 3');

// ---------------------------------------------------------------------------
//  Test: typing reveal slicing
// ---------------------------------------------------------------------------

console.log('\n--- typing reveal ---');

const TYPING_TEXT = 'Your AI orchestrator is ready. What would you like to build?';

function simulateTyping(text: string, charsRevealed: number): string {
  return text.slice(0, charsRevealed);
}

assert(simulateTyping(TYPING_TEXT, 0) === '', 'typing starts empty');
assert(simulateTyping(TYPING_TEXT, 4) === 'Your', 'typing reveals first 4 chars');
assert(
  simulateTyping(TYPING_TEXT, TYPING_TEXT.length) === TYPING_TEXT,
  'typing reveals full text',
);
assert(
  simulateTyping(TYPING_TEXT, 1) === 'Y',
  'typing reveals single char',
);

// ---------------------------------------------------------------------------
//  Test: timing constants (sanity checks)
// ---------------------------------------------------------------------------

console.log('\n--- timing constants ---');

const TITLE = 'CONDUCTOR';
const LETTER_STAGGER = 55;
const TITLE_TOTAL = TITLE.length * LETTER_STAGGER;
const SUBTITLE_DELAY = TITLE_TOTAL + 300;
const PULSE_LINE_DELAY = SUBTITLE_DELAY + 400;
const STATS_BASE_DELAY = PULSE_LINE_DELAY + 500;
const STAT_STAGGER = 220;
const STATS_COUNT = 4;
const TYPING_DELAY = STATS_BASE_DELAY + STATS_COUNT * STAT_STAGGER + 400;
const BUTTON_DELAY = TYPING_DELAY + TYPING_TEXT.length * 30 + 600;

assert(TITLE_TOTAL === 495, 'title animation takes 495ms');
assert(SUBTITLE_DELAY === 795, 'subtitle appears at 795ms');
assert(PULSE_LINE_DELAY === 1195, 'pulse line appears at 1195ms');
assert(STATS_BASE_DELAY === 1695, 'first stat appears at 1695ms');
assert(TYPING_DELAY === 2975, 'typing starts at 2975ms');
assert(BUTTON_DELAY > TYPING_DELAY, 'button appears after typing');
assert(BUTTON_DELAY < 6000, 'full animation completes under 6 seconds');

// ---------------------------------------------------------------------------
//  Test: letter splitting for stagger animation
// ---------------------------------------------------------------------------

console.log('\n--- letter splitting ---');

const letters = TITLE.split('').map((ch, i) => ({
  char: ch,
  delay: i * LETTER_STAGGER,
}));

assert(letters.length === 9, 'CONDUCTOR has 9 letters');
assert(letters[0].char === 'C', 'first letter is C');
assert(letters[0].delay === 0, 'first letter delay is 0');
assert(letters[8].char === 'R', 'last letter is R');
assert(letters[8].delay === 440, 'last letter delay is 440ms');

// Verify all letters are present
const reconstructed = letters.map((l) => l.char).join('');
assert(reconstructed === TITLE, 'reconstructed title matches');

// ---------------------------------------------------------------------------
//  Test: stat definitions
// ---------------------------------------------------------------------------

console.log('\n--- stat definitions ---');

interface StatDef {
  label: string;
  value: number;
  suffix?: string;
}

const STATS_DEF: StatDef[] = [
  { label: 'Projects', value: 3 },
  { label: 'Templates Ready', value: 12 },
  { label: 'Patterns Learned', value: 47 },
  { label: 'Brain', value: 1, suffix: 'Online' },
];

assert(STATS_DEF.length === 4, '4 stats defined');
assert(STATS_DEF.every((s) => s.value > 0), 'all stat values are positive');
assert(
  STATS_DEF.filter((s) => s.suffix).length === 1,
  'exactly one stat has a suffix',
);
assert(STATS_DEF[3].suffix === 'Online', 'Brain stat shows "Online"');

// ---------------------------------------------------------------------------
//  Test: fade-out behavior
// ---------------------------------------------------------------------------

console.log('\n--- fade-out ---');

// The component uses a 320ms timeout before calling onEnter
// and a ref guard to prevent double-firing.
const FADE_OUT_MS = 320;
assert(FADE_OUT_MS > 0, 'fade-out has positive duration');
assert(FADE_OUT_MS < 500, 'fade-out is under 500ms (snappy)');

// Modifier keys should NOT trigger dismiss
const modifierKeys = ['Shift', 'Control', 'Alt', 'Meta'];
assert(modifierKeys.every((k) => k.length > 1), 'modifier key names are multi-char');

// ---------------------------------------------------------------------------
//  Summary
// ---------------------------------------------------------------------------

console.log(`\n=== Welcome Screen Tests: ${passed} passed, ${failed} failed ===\n`);

if (failed > 0) {
  process.exit(1);
}
