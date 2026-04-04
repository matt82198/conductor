/**
 * Standalone unit tests for the agent detail store.
 *
 * Since the project does not have a test runner configured, these tests
 * can be run via: npx tsx src/stores/agentDetailStore.test.ts
 *
 * They exercise the Zustand store's pure logic: selectAgent, setOutput,
 * setLoading, setFetchError, clear, and state transitions.
 */
import { useAgentDetailStore, type OutputEntry } from './agentDetailStore';

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
  useAgentDetailStore.setState({
    selectedAgentId: null,
    output: [],
    loading: false,
    fetchError: null,
  });
}

function makeEntry(overrides: Partial<OutputEntry> = {}): OutputEntry {
  return {
    timestamp: '2026-04-03T10:00:00Z',
    type: 'text',
    content: 'Hello world',
    toolName: null,
    isError: false,
    ...overrides,
  };
}

// --- Tests ---

console.log('agentDetailStore tests:');

// Initial state
resetStore();
{
  const state = useAgentDetailStore.getState();
  assert(state.selectedAgentId === null, 'initial: selectedAgentId is null');
  assert(state.output.length === 0, 'initial: output is empty');
  assert(state.loading === false, 'initial: loading is false');
  assert(state.fetchError === null, 'initial: fetchError is null');
}

// selectAgent: sets agent ID and enters loading state
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-123');
  const state = useAgentDetailStore.getState();
  assert(state.selectedAgentId === 'agent-123', 'selectAgent: sets selectedAgentId');
  assert(state.loading === true, 'selectAgent: sets loading true');
  assert(state.output.length === 0, 'selectAgent: clears output');
  assert(state.fetchError === null, 'selectAgent: clears fetchError');
}

// selectAgent: deselect with null
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-123');
  useAgentDetailStore.getState().selectAgent(null);
  const state = useAgentDetailStore.getState();
  assert(state.selectedAgentId === null, 'selectAgent null: deselects');
  assert(state.loading === false, 'selectAgent null: loading is false');
  assert(state.output.length === 0, 'selectAgent null: output is empty');
}

// selectAgent: switching agents clears previous state
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-1');
  useAgentDetailStore.getState().setOutput([makeEntry({ content: 'old data' })]);
  useAgentDetailStore.getState().setFetchError('old error');
  useAgentDetailStore.getState().selectAgent('agent-2');
  const state = useAgentDetailStore.getState();
  assert(state.selectedAgentId === 'agent-2', 'selectAgent switch: updates to new ID');
  assert(state.output.length === 0, 'selectAgent switch: clears old output');
  assert(state.fetchError === null, 'selectAgent switch: clears old error');
  assert(state.loading === true, 'selectAgent switch: enters loading for new agent');
}

// setOutput: replaces output list
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-1');
  const entries = [
    makeEntry({ timestamp: '2026-04-03T10:00:00Z', type: 'system', content: 'Session started' }),
    makeEntry({ timestamp: '2026-04-03T10:00:01Z', type: 'thinking', content: 'reasoning...' }),
    makeEntry({ timestamp: '2026-04-03T10:00:02Z', type: 'text', content: 'Hello' }),
  ];
  useAgentDetailStore.getState().setOutput(entries);
  const state = useAgentDetailStore.getState();
  assert(state.output.length === 3, 'setOutput: sets three entries');
  assert(state.output[0].type === 'system', 'setOutput: first entry is system');
  assert(state.output[2].content === 'Hello', 'setOutput: third entry content correct');
}

// setOutput: replacing with new data
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-1');
  useAgentDetailStore.getState().setOutput([makeEntry({ content: 'first' })]);
  useAgentDetailStore.getState().setOutput([makeEntry({ content: 'second' }), makeEntry({ content: 'third' })]);
  const state = useAgentDetailStore.getState();
  assert(state.output.length === 2, 'setOutput replace: has two entries');
  assert(state.output[0].content === 'second', 'setOutput replace: first is second');
}

// setLoading: toggles loading state
resetStore();
{
  useAgentDetailStore.getState().setLoading(true);
  assert(useAgentDetailStore.getState().loading === true, 'setLoading: sets true');
  useAgentDetailStore.getState().setLoading(false);
  assert(useAgentDetailStore.getState().loading === false, 'setLoading: sets false');
}

// setFetchError: sets and clears error
resetStore();
{
  useAgentDetailStore.getState().setFetchError('Network error');
  assert(useAgentDetailStore.getState().fetchError === 'Network error', 'setFetchError: sets error message');
  useAgentDetailStore.getState().setFetchError(null);
  assert(useAgentDetailStore.getState().fetchError === null, 'setFetchError: clears to null');
}

// clear: resets all state
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-1');
  useAgentDetailStore.getState().setOutput([makeEntry()]);
  useAgentDetailStore.getState().setFetchError('error');
  useAgentDetailStore.getState().clear();
  const state = useAgentDetailStore.getState();
  assert(state.selectedAgentId === null, 'clear: selectedAgentId is null');
  assert(state.output.length === 0, 'clear: output is empty');
  assert(state.loading === false, 'clear: loading is false');
  assert(state.fetchError === null, 'clear: fetchError is null');
}

// OutputEntry type support: all types stored correctly
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-1');
  const entries: OutputEntry[] = [
    makeEntry({ type: 'thinking', content: 'pondering...', toolName: null }),
    makeEntry({ type: 'text', content: 'Here is my answer' }),
    makeEntry({ type: 'tool_use', content: '{"file": "test.ts"}', toolName: 'Read' }),
    makeEntry({ type: 'tool_result', content: 'file contents here' }),
    makeEntry({ type: 'error', content: 'Something went wrong', isError: true }),
    makeEntry({ type: 'system', content: 'Session started' }),
  ];
  useAgentDetailStore.getState().setOutput(entries);
  const state = useAgentDetailStore.getState();
  assert(state.output.length === 6, 'types: all six entry types stored');
  assert(state.output[2].toolName === 'Read', 'types: tool_use has toolName');
  assert(state.output[4].isError === true, 'types: error has isError flag');
}

// Edge case: setOutput on empty store (no agent selected)
resetStore();
{
  useAgentDetailStore.getState().setOutput([makeEntry()]);
  assert(useAgentDetailStore.getState().output.length === 1, 'edge: setOutput works without agent selected');
}

// Edge case: selectAgent with same ID resets state
resetStore();
{
  useAgentDetailStore.getState().selectAgent('agent-1');
  useAgentDetailStore.getState().setOutput([makeEntry(), makeEntry()]);
  useAgentDetailStore.getState().setLoading(false);
  useAgentDetailStore.getState().selectAgent('agent-1');
  const state = useAgentDetailStore.getState();
  assert(state.output.length === 0, 'edge: re-selecting same agent clears output');
  assert(state.loading === true, 'edge: re-selecting same agent re-enters loading');
}

// --- Summary ---
console.log('');
console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failed > 0) {
  process.exit(1);
}
