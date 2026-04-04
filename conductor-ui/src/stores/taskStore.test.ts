/**
 * Standalone unit tests for the task store.
 *
 * Since the project does not have a test runner configured, these tests
 * can be run via: npx tsx src/stores/taskStore.test.ts
 *
 * They exercise the Zustand store's pure logic: addPlan, updatePlan,
 * removePlan, and processTaskProgress.
 */
import { useTaskStore } from './taskStore';
import type { DecompositionPlan } from '../types/taskTypes';

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
  useTaskStore.setState({
    activePlans: [],
    taskEvents: [],
  });
}

function makePlan(overrides: Partial<DecompositionPlan> = {}): DecompositionPlan {
  return {
    planId: 'plan-1',
    originalPrompt: 'Add user authentication',
    projectPath: '/projects/myapp',
    subtasks: [
      {
        subtaskId: 'st-1',
        name: 'Design auth schema',
        description: 'Create database schema for users',
        role: 'FEATURE_ENGINEER',
        dependsOn: [],
        contextFrom: [],
        projectPath: '/projects/myapp',
        prompt: 'Design auth schema',
        successCriteria: null,
        status: 'PENDING',
        agentId: null,
        result: null,
        startedAt: null,
        completedAt: null,
      },
      {
        subtaskId: 'st-2',
        name: 'Implement login endpoint',
        description: 'Build the login REST endpoint',
        role: 'FEATURE_ENGINEER',
        dependsOn: ['st-1'],
        contextFrom: ['st-1'],
        projectPath: '/projects/myapp',
        prompt: 'Implement login endpoint',
        successCriteria: 'Login works with valid credentials',
        status: 'PENDING',
        agentId: null,
        result: null,
        startedAt: null,
        completedAt: null,
      },
    ],
    createdAt: '2026-04-03T10:00:00Z',
    status: 'PLANNING',
    ...overrides,
  };
}

// --- Tests ---

console.log('taskStore tests:');

// addPlan
resetStore();
{
  const plan = makePlan();
  useTaskStore.getState().addPlan(plan);
  const plans = useTaskStore.getState().activePlans;
  assert(plans.length === 1, 'addPlan: should add one plan');
  assert(plans[0].planId === 'plan-1', 'addPlan: planId matches');
  assert(plans[0].subtasks.length === 2, 'addPlan: subtasks preserved');
}

// addPlan multiple
resetStore();
{
  useTaskStore.getState().addPlan(makePlan({ planId: 'plan-a' }));
  useTaskStore.getState().addPlan(makePlan({ planId: 'plan-b' }));
  assert(useTaskStore.getState().activePlans.length === 2, 'addPlan: can add multiple plans');
}

// updatePlan
resetStore();
{
  useTaskStore.getState().addPlan(makePlan());
  useTaskStore.getState().updatePlan('plan-1', { status: 'EXECUTING' });
  const plan = useTaskStore.getState().activePlans[0];
  assert(plan.status === 'EXECUTING', 'updatePlan: status updated');
  assert(plan.originalPrompt === 'Add user authentication', 'updatePlan: other fields unchanged');
}

// updatePlan non-existent plan (no-op)
resetStore();
{
  useTaskStore.getState().addPlan(makePlan());
  useTaskStore.getState().updatePlan('nonexistent', { status: 'FAILED' });
  const plan = useTaskStore.getState().activePlans[0];
  assert(plan.status === 'PLANNING', 'updatePlan: no-op for unknown planId');
}

// removePlan
resetStore();
{
  useTaskStore.getState().addPlan(makePlan({ planId: 'plan-a' }));
  useTaskStore.getState().addPlan(makePlan({ planId: 'plan-b' }));
  useTaskStore.getState().removePlan('plan-a');
  const plans = useTaskStore.getState().activePlans;
  assert(plans.length === 1, 'removePlan: one plan removed');
  assert(plans[0].planId === 'plan-b', 'removePlan: correct plan remains');
}

// removePlan non-existent (no-op)
resetStore();
{
  useTaskStore.getState().addPlan(makePlan());
  useTaskStore.getState().removePlan('nonexistent');
  assert(useTaskStore.getState().activePlans.length === 1, 'removePlan: no-op for unknown planId');
}

// processTaskProgress
resetStore();
{
  useTaskStore.getState().addPlan(makePlan());
  useTaskStore.getState().processTaskProgress({
    type: 'task_progress',
    planId: 'plan-1',
    completed: 1,
    total: 2,
    currentPhase: 'executing',
  });
  const events = useTaskStore.getState().taskEvents;
  assert(events.length === 1, 'processTaskProgress: adds feed event');
  assert(events[0].content.includes('1/2'), 'processTaskProgress: event content has progress');
  assert(events[0].content.includes('executing'), 'processTaskProgress: event content has phase');
  assert(events[0].type === 'brain', 'processTaskProgress: event type is brain');
}

// processTaskProgress completes plan
resetStore();
{
  useTaskStore.getState().addPlan(makePlan({ status: 'EXECUTING' }));
  useTaskStore.getState().processTaskProgress({
    type: 'task_progress',
    planId: 'plan-1',
    completed: 2,
    total: 2,
    currentPhase: 'completed',
  });
  const plan = useTaskStore.getState().activePlans[0];
  assert(plan.status === 'COMPLETED', 'processTaskProgress: marks plan completed when all done');
}

// processTaskProgress for unknown plan (still adds event)
resetStore();
{
  useTaskStore.getState().processTaskProgress({
    type: 'task_progress',
    planId: 'unknown-plan',
    completed: 1,
    total: 3,
    currentPhase: 'running',
  });
  assert(useTaskStore.getState().taskEvents.length === 1, 'processTaskProgress: event added even for unknown plan');
}

// processTaskProgress event cap
resetStore();
{
  for (let i = 0; i < 250; i++) {
    useTaskStore.getState().processTaskProgress({
      type: 'task_progress',
      planId: 'plan-1',
      completed: i,
      total: 250,
      currentPhase: 'running',
    });
  }
  assert(useTaskStore.getState().taskEvents.length <= 200, 'processTaskProgress: events capped at 200');
}

// --- Summary ---
console.log('');
console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failed > 0) {
  process.exit(1);
}
