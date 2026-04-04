/**
 * Standalone unit tests for the brain decision store.
 *
 * Since the project does not have a test runner configured, these tests
 * can be run via: npx tsx src/stores/brainDecisionStore.test.ts
 *
 * They exercise the Zustand store's pure logic: addBrainDecision,
 * setFeedback, unratedCount tracking, and the 50-entry cap.
 */
import { useBrainDecisionStore } from './brainDecisionStore';
import type { BrainDecisionEntry } from '../types/brainDecisionTypes';

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

function resetStore(): void {
  useBrainDecisionStore.setState({
    brainDecisions: [],
    unratedCount: 0,
  });
}

function makeDecision(overrides: Partial<BrainDecisionEntry> = {}): BrainDecisionEntry {
  return {
    id: 'bd-1',
    requestId: 'req-1',
    agentId: 'agent-1',
    agentName: 'feature-agent',
    action: 'RESPOND',
    response: 'Yes, approve the changes.',
    reasoning: 'Pattern matches previous user approval behavior.',
    confidence: 0.85,
    timestamp: new Date('2026-04-03T10:00:00Z'),
    feedback: null,
    correction: null,
    ...overrides,
  };
}

// --- Tests ---

console.log('brainDecisionStore tests:');

// addBrainDecision: basic add
resetStore();
{
  const entry = makeDecision();
  useBrainDecisionStore.getState().addBrainDecision(entry);
  const state = useBrainDecisionStore.getState();
  assert(state.brainDecisions.length === 1, 'addBrainDecision: adds one entry');
  assert(state.brainDecisions[0].id === 'bd-1', 'addBrainDecision: id matches');
  assert(state.brainDecisions[0].action === 'RESPOND', 'addBrainDecision: action matches');
  assert(state.brainDecisions[0].confidence === 0.85, 'addBrainDecision: confidence matches');
}

// addBrainDecision: multiple entries
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-a' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-b' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-c' }));
  assert(useBrainDecisionStore.getState().brainDecisions.length === 3, 'addBrainDecision: adds multiple entries');
}

// addBrainDecision: escalation type
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(
    makeDecision({ id: 'bd-esc', action: 'ESCALATE', response: null, reasoning: 'Low confidence, needs human.' }),
  );
  const d = useBrainDecisionStore.getState().brainDecisions[0];
  assert(d.action === 'ESCALATE', 'addBrainDecision: escalation action stored');
  assert(d.response === null, 'addBrainDecision: escalation has null response');
}

// addBrainDecision: capped at 50
resetStore();
{
  for (let i = 0; i < 60; i++) {
    useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: `bd-${i}` }));
  }
  const state = useBrainDecisionStore.getState();
  assert(state.brainDecisions.length === 50, 'addBrainDecision: capped at 50 entries');
  assert(state.brainDecisions[0].id === 'bd-10', 'addBrainDecision: oldest entries dropped (first is bd-10)');
  assert(state.brainDecisions[49].id === 'bd-59', 'addBrainDecision: newest entry is last');
}

// unratedCount: tracks unrated entries
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-2' }));
  assert(useBrainDecisionStore.getState().unratedCount === 2, 'unratedCount: two unrated');
}

// unratedCount: decrements when feedback given
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-2' }));
  useBrainDecisionStore.getState().setFeedback('bd-1', 'GOOD', null);
  assert(useBrainDecisionStore.getState().unratedCount === 1, 'unratedCount: one after rating one GOOD');
}

// unratedCount: zero when all rated
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-2' }));
  useBrainDecisionStore.getState().setFeedback('bd-1', 'GOOD', null);
  useBrainDecisionStore.getState().setFeedback('bd-2', 'BAD', 'Should have escalated');
  assert(useBrainDecisionStore.getState().unratedCount === 0, 'unratedCount: zero when all rated');
}

// unratedCount: includes pre-rated entries from cap overflow
resetStore();
{
  // Add 50 entries, rate the first 10, then add 5 more (pushes 5 unrated out)
  for (let i = 0; i < 50; i++) {
    useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: `bd-${i}` }));
  }
  for (let i = 0; i < 10; i++) {
    useBrainDecisionStore.getState().setFeedback(`bd-${i}`, 'GOOD', null);
  }
  assert(useBrainDecisionStore.getState().unratedCount === 40, 'unratedCount: 40 after rating 10 of 50');
  // Adding 5 more pushes out the oldest 5 (which were rated)
  for (let i = 50; i < 55; i++) {
    useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: `bd-${i}` }));
  }
  // Now: bd-5 through bd-54, bd-0..4 dropped (rated), bd-5..9 rated, rest unrated
  assert(useBrainDecisionStore.getState().brainDecisions.length === 50, 'unratedCount cap: still 50 entries');
  assert(useBrainDecisionStore.getState().unratedCount === 45, 'unratedCount cap: 45 after overflow (5 rated survived, 5 new unrated)');
}

// setFeedback: sets GOOD feedback
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().setFeedback('bd-1', 'GOOD', null);
  const d = useBrainDecisionStore.getState().brainDecisions[0];
  assert(d.feedback === 'GOOD', 'setFeedback: sets GOOD');
  assert(d.correction === null, 'setFeedback: GOOD has null correction');
}

// setFeedback: sets BAD feedback with correction
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().setFeedback('bd-1', 'BAD', 'Should have asked about the file path.');
  const d = useBrainDecisionStore.getState().brainDecisions[0];
  assert(d.feedback === 'BAD', 'setFeedback: sets BAD');
  assert(d.correction === 'Should have asked about the file path.', 'setFeedback: correction stored');
}

// setFeedback: BAD with null correction
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().setFeedback('bd-1', 'BAD', null);
  const d = useBrainDecisionStore.getState().brainDecisions[0];
  assert(d.feedback === 'BAD', 'setFeedback: BAD with null correction');
  assert(d.correction === null, 'setFeedback: null correction preserved');
}

// setFeedback: non-existent id (no-op, no crash)
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().setFeedback('nonexistent', 'GOOD', null);
  const d = useBrainDecisionStore.getState().brainDecisions[0];
  assert(d.feedback === null, 'setFeedback: no-op for unknown id');
  assert(useBrainDecisionStore.getState().unratedCount === 1, 'setFeedback: unratedCount unchanged for unknown id');
}

// setFeedback: only affects target entry
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-2' }));
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-3' }));
  useBrainDecisionStore.getState().setFeedback('bd-2', 'BAD', 'Wrong call');
  const decisions = useBrainDecisionStore.getState().brainDecisions;
  assert(decisions[0].feedback === null, 'setFeedback: bd-1 unaffected');
  assert(decisions[1].feedback === 'BAD', 'setFeedback: bd-2 updated');
  assert(decisions[2].feedback === null, 'setFeedback: bd-3 unaffected');
}

// addBrainDecision: preserves existing feedback when adding new entries
resetStore();
{
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-1' }));
  useBrainDecisionStore.getState().setFeedback('bd-1', 'GOOD', null);
  useBrainDecisionStore.getState().addBrainDecision(makeDecision({ id: 'bd-2' }));
  const decisions = useBrainDecisionStore.getState().brainDecisions;
  assert(decisions[0].feedback === 'GOOD', 'addBrainDecision: existing feedback preserved after adding new entry');
  assert(decisions[1].feedback === null, 'addBrainDecision: new entry has null feedback');
}

// Edge case: empty store operations
resetStore();
{
  useBrainDecisionStore.getState().setFeedback('anything', 'GOOD', null);
  assert(useBrainDecisionStore.getState().brainDecisions.length === 0, 'edge: setFeedback on empty store is no-op');
  assert(useBrainDecisionStore.getState().unratedCount === 0, 'edge: unratedCount stays 0 on empty store');
}

// --- Summary ---
console.log('');
console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failed > 0) {
  process.exit(1);
}
