/**
 * Unit tests for CommandBar helper logic and CommandTypes.
 *
 * Since the project does not have a DOM test runner (no jest/vitest with jsdom),
 * these tests exercise the pure logic: types, placeholder rotation, history
 * management, and fetch/response parsing helpers extracted here as testable units.
 *
 * Run via: npx tsx src/components/commandBar.test.ts
 */
import type { AgentTemplate, CommandResponse, TemplateUseRequest } from '../types/commandTypes';

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

// --- Type shape tests ---

console.log('commandTypes shape tests:');

{
  const template: AgentTemplate = {
    templateId: 'tpl-1',
    name: 'Test Writer',
    description: 'Writes unit tests',
    role: 'TESTER',
    defaultPrompt: 'Write comprehensive tests',
    tags: ['testing', 'quality'],
    usageCount: 5,
    createdAt: '2026-04-01T00:00:00Z',
    lastUsedAt: '2026-04-03T12:00:00Z',
  };
  assert(template.templateId === 'tpl-1', 'AgentTemplate: templateId');
  assert(template.name === 'Test Writer', 'AgentTemplate: name');
  assert(template.tags.length === 2, 'AgentTemplate: tags array');
  assert(template.usageCount === 5, 'AgentTemplate: usageCount');
  assert(template.lastUsedAt !== null, 'AgentTemplate: lastUsedAt not null');
}

{
  const templateNull: AgentTemplate = {
    templateId: 'tpl-2',
    name: 'Explorer',
    description: 'Explores codebase',
    role: 'GENERAL',
    defaultPrompt: 'Explore the project',
    tags: [],
    usageCount: 0,
    createdAt: '2026-04-01T00:00:00Z',
    lastUsedAt: null,
  };
  assert(templateNull.lastUsedAt === null, 'AgentTemplate: lastUsedAt nullable');
  assert(templateNull.tags.length === 0, 'AgentTemplate: empty tags');
  assert(templateNull.usageCount === 0, 'AgentTemplate: zero usageCount');
}

{
  const response: CommandResponse = {
    intent: {
      action: 'spawn_agent',
      originalText: 'spawn an agent to write tests',
      parameters: { role: 'TESTER', projectPath: '/myapp' },
      confidence: 0.95,
      reasoning: 'User wants to spawn a testing agent',
    },
    result: {
      success: true,
      message: 'Agent spawned successfully',
      data: { agentId: 'agent-123' },
    },
  };
  assert(response.intent.action === 'spawn_agent', 'CommandResponse: intent.action');
  assert(response.intent.confidence === 0.95, 'CommandResponse: intent.confidence');
  assert(response.result.success === true, 'CommandResponse: result.success');
  assert(response.result.message.includes('successfully'), 'CommandResponse: result.message');
}

{
  const errorResponse: CommandResponse = {
    intent: {
      action: 'unknown',
      originalText: 'blah blah',
      parameters: {},
      confidence: 0.2,
      reasoning: 'Could not parse intent',
    },
    result: {
      success: false,
      message: 'Could not understand command',
      data: null,
    },
  };
  assert(errorResponse.result.success === false, 'CommandResponse error: success false');
  assert(errorResponse.intent.confidence < 0.5, 'CommandResponse error: low confidence');
  assert(errorResponse.result.data === null, 'CommandResponse error: null data');
}

{
  const req: TemplateUseRequest = {
    projectPath: '/projects/myapp',
    promptOverride: 'Focus on auth module',
  };
  assert(req.projectPath === '/projects/myapp', 'TemplateUseRequest: projectPath');
  assert(req.promptOverride.includes('auth'), 'TemplateUseRequest: promptOverride');
}

// --- History helper logic ---

console.log('');
console.log('Command history logic tests:');

{
  // Simulates the history dedup + cap logic from CommandBar
  function addToHistory(history: string[], entry: string): string[] {
    const deduped = history.filter((h) => h !== entry);
    return [entry, ...deduped].slice(0, 20);
  }

  let history: string[] = [];
  history = addToHistory(history, 'spawn agent');
  assert(history.length === 1, 'history: first entry');
  assert(history[0] === 'spawn agent', 'history: first entry value');

  history = addToHistory(history, 'list agents');
  assert(history.length === 2, 'history: second entry');
  assert(history[0] === 'list agents', 'history: most recent first');

  // Duplicate moves to front
  history = addToHistory(history, 'spawn agent');
  assert(history.length === 2, 'history: dedup keeps length');
  assert(history[0] === 'spawn agent', 'history: dedup moves to front');
  assert(history[1] === 'list agents', 'history: dedup preserves order');

  // Cap at 20
  for (let i = 0; i < 25; i++) {
    history = addToHistory(history, `command-${i}`);
  }
  assert(history.length === 20, 'history: capped at 20');
  assert(history[0] === 'command-24', 'history: most recent at index 0');
}

// --- History navigation logic ---

console.log('');
console.log('History navigation tests:');

{
  const history = ['third', 'second', 'first'];
  let historyIndex = -1;
  let currentInput = 'draft';
  let draft = '';

  // Arrow up from -1 (browsing new command)
  function arrowUp(): void {
    const next = historyIndex + 1;
    if (next >= history.length) return;
    if (historyIndex === -1) draft = currentInput; // save draft
    historyIndex = next;
    currentInput = history[next];
  }

  function arrowDown(): void {
    if (historyIndex <= -1) return;
    const next = historyIndex - 1;
    historyIndex = next;
    if (next === -1) {
      currentInput = draft;
    } else {
      currentInput = history[next];
    }
  }

  arrowUp();
  assert(currentInput === 'third', 'nav: first up shows most recent');
  assert(historyIndex === 0, 'nav: historyIndex is 0');

  arrowUp();
  assert(currentInput === 'second', 'nav: second up shows second entry');
  assert(historyIndex === 1, 'nav: historyIndex is 1');

  arrowUp();
  assert(currentInput === 'first', 'nav: third up shows oldest');
  assert(historyIndex === 2, 'nav: historyIndex is 2');

  // Can't go further
  arrowUp();
  assert(historyIndex === 2, 'nav: clamped at oldest');

  arrowDown();
  assert(currentInput === 'second', 'nav: down returns to newer');
  assert(historyIndex === 1, 'nav: historyIndex back to 1');

  arrowDown();
  assert(currentInput === 'third', 'nav: down to most recent');

  arrowDown();
  assert(currentInput === 'draft', 'nav: down past history restores draft');
  assert(historyIndex === -1, 'nav: back to draft index');

  // Can't go below -1
  arrowDown();
  assert(historyIndex === -1, 'nav: clamped at -1');
}

// --- Placeholder rotation logic ---

console.log('');
console.log('Placeholder rotation tests:');

{
  const placeholders = [
    'spawn an agent to write tests for the auth module',
    'register project at C:/Users/matt8/myapp',
    'what are my agents doing?',
    'break down: add OAuth2 to the API',
    'analyze medallioGenAi project',
  ];

  assert(placeholders.length === 5, 'placeholders: 5 entries');

  // Simulates the rotation index logic
  let index = 0;
  for (let i = 0; i < 12; i++) {
    index = (index + 1) % placeholders.length;
  }
  assert(index === 2, 'placeholders: wraps around correctly (12 mod 5 = 2)');

  // Each placeholder is a non-empty string
  for (const p of placeholders) {
    assert(p.length > 10, `placeholders: "${p.slice(0, 20)}..." is non-empty`);
  }
}

// --- Template sorting logic ---

console.log('');
console.log('Template sorting tests:');

{
  const templates: AgentTemplate[] = [
    { templateId: 'a', name: 'Alpha', description: '', role: '', defaultPrompt: '', tags: [], usageCount: 2, createdAt: '', lastUsedAt: null },
    { templateId: 'b', name: 'Beta', description: '', role: '', defaultPrompt: '', tags: [], usageCount: 10, createdAt: '', lastUsedAt: null },
    { templateId: 'c', name: 'Gamma', description: '', role: '', defaultPrompt: '', tags: [], usageCount: 0, createdAt: '', lastUsedAt: null },
    { templateId: 'd', name: 'Delta', description: '', role: '', defaultPrompt: '', tags: [], usageCount: 5, createdAt: '', lastUsedAt: null },
  ];

  templates.sort((a, b) => b.usageCount - a.usageCount);

  assert(templates[0].name === 'Beta', 'sort: most used first (10)');
  assert(templates[1].name === 'Delta', 'sort: second most used (5)');
  assert(templates[2].name === 'Alpha', 'sort: third (2)');
  assert(templates[3].name === 'Gamma', 'sort: least used last (0)');

  // Bump usage and re-sort (simulates clicking a template)
  const gammaIdx = templates.findIndex((t) => t.templateId === 'c');
  templates[gammaIdx] = { ...templates[gammaIdx], usageCount: 100 };
  templates.sort((a, b) => b.usageCount - a.usageCount);
  assert(templates[0].name === 'Gamma', 'sort: after bump, Gamma is first (100)');
}

// --- Result flash logic ---

console.log('');
console.log('Result display logic tests:');

{
  // Test the success/error message formatting
  function formatResult(success: boolean, message: string): string {
    return `${success ? '\u2713' : '\u2717'} ${message}`;
  }

  const successMsg = formatResult(true, 'Agent spawned');
  assert(successMsg.startsWith('\u2713'), 'result: success starts with checkmark');
  assert(successMsg.includes('Agent spawned'), 'result: success includes message');

  const errorMsg = formatResult(false, 'Connection failed');
  assert(errorMsg.startsWith('\u2717'), 'result: error starts with X');
  assert(errorMsg.includes('Connection failed'), 'result: error includes message');
}

{
  // Test response parsing with nullable fields (matches CommandBar's parsing)
  function parseResponse(data: Partial<CommandResponse>): { success: boolean; message: string } {
    return {
      success: data.result?.success ?? false,
      message: data.result?.message ?? 'Command executed',
    };
  }

  const full = parseResponse({
    result: { success: true, message: 'Done!', data: null },
  });
  assert(full.success === true, 'parseResponse: full success');
  assert(full.message === 'Done!', 'parseResponse: full message');

  const partial = parseResponse({});
  assert(partial.success === false, 'parseResponse: missing result defaults to false');
  assert(partial.message === 'Command executed', 'parseResponse: missing message defaults');

  const nullResult = parseResponse({ result: undefined } as any);
  assert(nullResult.success === false, 'parseResponse: undefined result defaults to false');
}

// --- Summary ---
console.log('');
console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failed > 0) {
  process.exit(1);
}
