/**
 * Standalone unit tests for the unified WebSocket message routing logic.
 *
 * Since the project does not have a test runner configured, these tests
 * can be run via: npx tsx src/hooks/useUnifiedWebSocket.test.ts
 *
 * These tests verify that the message routing logic in useUnifiedWebSocket
 * correctly dispatches different message types to the right stores. We test
 * at the store level since the hook itself requires a React context, but the
 * routing decisions are the critical part to validate.
 *
 * We simulate what the hook's onmessage handler does: parse JSON, check type,
 * route to the appropriate store method.
 */
import { useConductorStore } from '../stores/conductorStore';
import { useTaskStore } from '../stores/taskStore';
import { useBrainDecisionStore } from '../stores/brainDecisionStore';
import type { BrainDecisionEntry } from '../types/brainDecisionTypes';
import type { TaskProgressWsMessage } from '../types/taskTypes';
import type { ServerWsMessage } from '../types/events';

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

function resetAllStores(): void {
  useConductorStore.setState({
    connected: false,
    agents: new Map(),
    events: [],
    totalCostUsd: 0,
    humanInputRequests: [],
    mutedAgents: new Set(),
    brainStatus: null,
    brainStartupLog: [],
  });
  useTaskStore.setState({
    activePlans: [],
    taskEvents: [],
  });
  useBrainDecisionStore.setState({
    brainDecisions: [],
    unratedCount: 0,
  });
}

/**
 * Simulates the message routing logic from useUnifiedWebSocket's onmessage handler.
 * This is a pure-logic extraction of what the hook does with each parsed message.
 */
function routeMessage(data: any): void {
  // task_progress -> taskStore only
  if (data.type === 'task_progress') {
    useTaskStore.getState().processTaskProgress(data as TaskProgressWsMessage);
    return;
  }

  // brain_response -> conductorStore + brainDecisionStore
  if (data.type === 'brain_response') {
    useConductorStore.getState().processWsMessage(data as ServerWsMessage);
    const agents = useConductorStore.getState().agents;
    const agentName = agents.get(data.agentId)?.name ?? data.agentId.slice(0, 8);
    const entry: BrainDecisionEntry = {
      id: `test-bd-${Date.now()}`,
      requestId: data.requestId,
      agentId: data.agentId,
      agentName,
      action: 'RESPOND',
      response: data.response,
      reasoning: data.reasoning,
      confidence: data.confidence,
      timestamp: new Date(),
      feedback: null,
      correction: null,
    };
    useBrainDecisionStore.getState().addBrainDecision(entry);
    return;
  }

  // brain_escalation -> conductorStore + brainDecisionStore
  if (data.type === 'brain_escalation') {
    useConductorStore.getState().processWsMessage(data as ServerWsMessage);
    const agents = useConductorStore.getState().agents;
    const agentName = agents.get(data.agentId)?.name ?? data.agentId.slice(0, 8);
    const entry: BrainDecisionEntry = {
      id: `test-bd-${Date.now()}`,
      requestId: data.requestId,
      agentId: data.agentId,
      agentName,
      action: 'ESCALATE',
      response: null,
      reasoning: data.reason,
      confidence: data.confidence,
      timestamp: new Date(),
      feedback: null,
      correction: null,
    };
    useBrainDecisionStore.getState().addBrainDecision(entry);
    return;
  }

  // Agent stream events (original format)
  if (data.agentId && data.eventType) {
    useConductorStore.getState().processWsMessage(data as ServerWsMessage);
    return;
  }

  // Other typed messages (human_input_needed, queued_message)
  if (data.type === 'human_input_needed' || data.type === 'queued_message') {
    useConductorStore.getState().processWsMessage(data as ServerWsMessage);
    return;
  }
}

// --- Tests ---

console.log('useUnifiedWebSocket routing tests:');

// task_progress routes to taskStore only
resetAllStores();
{
  // Add a plan so processTaskProgress has something to match
  useTaskStore.getState().addPlan({
    planId: 'plan-1',
    originalPrompt: 'test',
    projectPath: '/test',
    subtasks: [],
    createdAt: new Date().toISOString(),
    status: 'RUNNING',
  });

  routeMessage({
    type: 'task_progress',
    planId: 'plan-1',
    completed: 3,
    total: 5,
    currentPhase: 'executing',
  });

  const taskState = useTaskStore.getState();
  assert(taskState.taskEvents.length === 1, 'task_progress: creates task event');
  assert(taskState.taskEvents[0].content.includes('3/5'), 'task_progress: event content shows progress');
  assert(useConductorStore.getState().events.length === 0, 'task_progress: does NOT add to conductor events');
  assert(useBrainDecisionStore.getState().brainDecisions.length === 0, 'task_progress: does NOT add brain decisions');
}

// brain_response routes to BOTH conductorStore and brainDecisionStore
resetAllStores();
{
  routeMessage({
    type: 'brain_response',
    requestId: 'req-1',
    agentId: 'agent-1',
    response: 'Approved.',
    confidence: 0.92,
    reasoning: 'High confidence match.',
  });

  const conductorState = useConductorStore.getState();
  assert(conductorState.events.length === 1, 'brain_response: adds conductor event');
  assert(conductorState.events[0].type === 'brain', 'brain_response: conductor event type is brain');
  assert(conductorState.events[0].content.includes('Auto-responded'), 'brain_response: content mentions auto-responded');

  const brainState = useBrainDecisionStore.getState();
  assert(brainState.brainDecisions.length === 1, 'brain_response: adds brain decision');
  assert(brainState.brainDecisions[0].action === 'RESPOND', 'brain_response: decision action is RESPOND');
  assert(brainState.brainDecisions[0].response === 'Approved.', 'brain_response: decision response matches');
  assert(brainState.brainDecisions[0].confidence === 0.92, 'brain_response: confidence matches');
  assert(brainState.unratedCount === 1, 'brain_response: unrated count increments');

  assert(useTaskStore.getState().taskEvents.length === 0, 'brain_response: does NOT add task events');
}

// brain_escalation routes to BOTH conductorStore and brainDecisionStore
resetAllStores();
{
  routeMessage({
    type: 'brain_escalation',
    requestId: 'req-2',
    agentId: 'agent-2',
    reason: 'Low confidence, needs human.',
    recommendation: null,
    confidence: 0.3,
  });

  const conductorState = useConductorStore.getState();
  assert(conductorState.events.length === 1, 'brain_escalation: adds conductor event');
  assert(conductorState.events[0].content.includes('Escalating'), 'brain_escalation: content mentions escalating');

  const brainState = useBrainDecisionStore.getState();
  assert(brainState.brainDecisions.length === 1, 'brain_escalation: adds brain decision');
  assert(brainState.brainDecisions[0].action === 'ESCALATE', 'brain_escalation: decision action is ESCALATE');
  assert(brainState.brainDecisions[0].response === null, 'brain_escalation: response is null');
  assert(brainState.brainDecisions[0].reasoning === 'Low confidence, needs human.', 'brain_escalation: reasoning matches');
  assert(brainState.unratedCount === 1, 'brain_escalation: unrated count increments');
}

// human_input_needed routes to conductorStore only
resetAllStores();
{
  routeMessage({
    type: 'human_input_needed',
    request: {
      requestId: 'hir-1',
      agentId: 'agent-1',
      agentName: 'test-agent',
      question: 'Proceed with delete?',
      suggestedOptions: ['yes', 'no'],
      context: 'file cleanup',
      urgency: 'CRITICAL',
      detectedAt: new Date().toISOString(),
      detectionMethod: 'pattern',
      confidenceScore: 0.95,
    },
  });

  const conductorState = useConductorStore.getState();
  assert(conductorState.humanInputRequests.length === 1, 'human_input_needed: adds human input request');
  assert(conductorState.humanInputRequests[0].requestId === 'hir-1', 'human_input_needed: request id matches');
  assert(conductorState.events.length === 1, 'human_input_needed: adds conductor event');
  assert(useBrainDecisionStore.getState().brainDecisions.length === 0, 'human_input_needed: no brain decisions');
  assert(useTaskStore.getState().taskEvents.length === 0, 'human_input_needed: no task events');
}

// queued_message routes to conductorStore only
resetAllStores();
{
  routeMessage({
    type: 'queued_message',
    message: {
      agentId: 'agent-1',
      agentName: 'test-agent',
      text: 'Build complete',
      urgency: 'LOW' as const,
      category: 'build',
      timestamp: new Date().toISOString(),
      dedupHash: 'abc123',
      batchId: null,
    },
  });

  const conductorState = useConductorStore.getState();
  assert(conductorState.events.length === 1, 'queued_message: adds conductor event');
  assert(conductorState.events[0].content.includes('Build complete'), 'queued_message: event content matches');
  assert(useBrainDecisionStore.getState().brainDecisions.length === 0, 'queued_message: no brain decisions');
  assert(useTaskStore.getState().taskEvents.length === 0, 'queued_message: no task events');
}

// Agent stream event (original format) routes to conductorStore only
resetAllStores();
{
  // Seed an agent so the store can find the name
  useConductorStore.getState().addAgent({
    id: 'agent-x',
    name: 'stream-agent',
    role: 'worker',
    projectPath: '/proj',
    state: 'ACTIVE',
    sessionId: null,
    costUsd: 0,
    spawnedAt: new Date().toISOString(),
    lastActivityAt: new Date().toISOString(),
  });

  routeMessage({
    agentId: 'agent-x',
    eventType: 'result',
    event: {
      totalCostUsd: 0.05,
      durationMs: 12000,
      numTurns: 4,
      isError: false,
    },
  });

  const conductorState = useConductorStore.getState();
  assert(conductorState.events.length === 1, 'agent stream result: adds conductor event');
  assert(conductorState.events[0].content.includes('Completed'), 'agent stream result: content shows completed');
  assert(conductorState.totalCostUsd === 0.05, 'agent stream result: cost updated');
  assert(useBrainDecisionStore.getState().brainDecisions.length === 0, 'agent stream result: no brain decisions');
  assert(useTaskStore.getState().taskEvents.length === 0, 'agent stream result: no task events');
}

// Unknown message type is silently ignored
resetAllStores();
{
  routeMessage({ type: 'unknown_future_event', data: 'something' });
  assert(useConductorStore.getState().events.length === 0, 'unknown type: no conductor events');
  assert(useTaskStore.getState().taskEvents.length === 0, 'unknown type: no task events');
  assert(useBrainDecisionStore.getState().brainDecisions.length === 0, 'unknown type: no brain decisions');
}

// Multiple brain decisions accumulate correctly
resetAllStores();
{
  routeMessage({
    type: 'brain_response',
    requestId: 'req-a',
    agentId: 'agent-a',
    response: 'Yes.',
    confidence: 0.9,
    reasoning: 'Sure.',
  });
  routeMessage({
    type: 'brain_escalation',
    requestId: 'req-b',
    agentId: 'agent-b',
    reason: 'Not sure.',
    recommendation: null,
    confidence: 0.2,
  });
  routeMessage({
    type: 'brain_response',
    requestId: 'req-c',
    agentId: 'agent-c',
    response: 'Done.',
    confidence: 0.95,
    reasoning: 'Clear pattern.',
  });

  const brainState = useBrainDecisionStore.getState();
  assert(brainState.brainDecisions.length === 3, 'multiple: three brain decisions');
  assert(brainState.unratedCount === 3, 'multiple: three unrated');
  assert(brainState.brainDecisions[0].action === 'RESPOND', 'multiple: first is RESPOND');
  assert(brainState.brainDecisions[1].action === 'ESCALATE', 'multiple: second is ESCALATE');
  assert(brainState.brainDecisions[2].action === 'RESPOND', 'multiple: third is RESPOND');

  assert(useConductorStore.getState().events.length === 3, 'multiple: three conductor events');
}

// --- Summary ---
console.log('');
console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failed > 0) {
  process.exit(1);
}
