/**
 * Standalone unit tests for the project store.
 *
 * Since the project does not have a test runner configured, these tests
 * can be run via: npx tsx src/stores/projectStore.test.ts
 *
 * They exercise the Zustand store's pure synchronous logic: setProjects,
 * addProject, addProjects, setKnowledge, setAllKnowledge, setAnalyzing,
 * and setInitialized. Async methods (fetchAll, registerProject, etc.)
 * are not tested here as they require a running server.
 */
import { useProjectStore } from './projectStore';
import type { ProjectRecord, ProjectKnowledge } from '../types/projectTypes';

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
  useProjectStore.setState({
    projects: [],
    knowledge: new Map(),
    analyzingProjects: new Set(),
    initialized: false,
  });
}

function makeProject(overrides: Partial<ProjectRecord> = {}): ProjectRecord {
  return {
    id: 'proj-1',
    name: 'conductor',
    path: 'C:/Users/matt8/TRDev/conductor',
    gitRemote: 'git@github.com:matt82198/conductor.git',
    agentCount: 2,
    registeredAt: '2026-04-03T10:00:00Z',
    ...overrides,
  };
}

function makeKnowledge(overrides: Partial<ProjectKnowledge> = {}): ProjectKnowledge {
  return {
    projectId: 'proj-1',
    projectName: 'conductor',
    projectPath: 'C:/Users/matt8/TRDev/conductor',
    techStack: 'Java 21, Spring Boot, React, TypeScript',
    patterns: [
      {
        name: 'Domain CLAUDE.md',
        description: 'Each domain maintains its own CLAUDE.md with contracts in/out',
        sourceFile: 'CLAUDE.md',
        tags: ['architecture', 'documentation'],
      },
      {
        name: 'Zustand Store',
        description: 'State management via Zustand create() pattern',
        sourceFile: 'src/stores/conductorStore.ts',
        tags: ['state', 'react'],
      },
    ],
    keyFiles: ['CLAUDE.md', 'pom.xml', 'conductor-ui/src/App.tsx'],
    architectureSummary: 'Multi-module Maven project with Electron+React frontend',
    analyzedAt: '2026-04-03T10:05:00Z',
    ...overrides,
  };
}

// --- Tests ---

console.log('projectStore tests:');

// setProjects
resetStore();
{
  const projects = [makeProject(), makeProject({ id: 'proj-2', name: 'other' })];
  useProjectStore.getState().setProjects(projects);
  assert(useProjectStore.getState().projects.length === 2, 'setProjects: sets all projects');
  assert(useProjectStore.getState().projects[0].id === 'proj-1', 'setProjects: first project correct');
  assert(useProjectStore.getState().projects[1].id === 'proj-2', 'setProjects: second project correct');
}

// setProjects replaces existing
resetStore();
{
  useProjectStore.getState().setProjects([makeProject()]);
  useProjectStore.getState().setProjects([makeProject({ id: 'proj-new', name: 'new' })]);
  assert(useProjectStore.getState().projects.length === 1, 'setProjects: replaces existing list');
  assert(useProjectStore.getState().projects[0].id === 'proj-new', 'setProjects: replaced with new');
}

// addProject
resetStore();
{
  useProjectStore.getState().addProject(makeProject());
  assert(useProjectStore.getState().projects.length === 1, 'addProject: adds one project');
  assert(useProjectStore.getState().projects[0].name === 'conductor', 'addProject: name correct');
}

// addProject dedup
resetStore();
{
  useProjectStore.getState().addProject(makeProject());
  useProjectStore.getState().addProject(makeProject());
  assert(useProjectStore.getState().projects.length === 1, 'addProject: deduplicates by id');
}

// addProject different ids
resetStore();
{
  useProjectStore.getState().addProject(makeProject({ id: 'a' }));
  useProjectStore.getState().addProject(makeProject({ id: 'b' }));
  assert(useProjectStore.getState().projects.length === 2, 'addProject: allows different ids');
}

// addProjects
resetStore();
{
  useProjectStore.getState().addProjects([
    makeProject({ id: 'a' }),
    makeProject({ id: 'b' }),
    makeProject({ id: 'c' }),
  ]);
  assert(useProjectStore.getState().projects.length === 3, 'addProjects: adds all projects');
}

// addProjects dedup
resetStore();
{
  useProjectStore.getState().addProject(makeProject({ id: 'a' }));
  useProjectStore.getState().addProjects([
    makeProject({ id: 'a' }),
    makeProject({ id: 'b' }),
  ]);
  assert(useProjectStore.getState().projects.length === 2, 'addProjects: deduplicates existing');
}

// addProjects empty
resetStore();
{
  useProjectStore.getState().addProject(makeProject({ id: 'a' }));
  useProjectStore.getState().addProjects([makeProject({ id: 'a' })]);
  assert(useProjectStore.getState().projects.length === 1, 'addProjects: no-op when all exist');
}

// setKnowledge
resetStore();
{
  const k = makeKnowledge();
  useProjectStore.getState().setKnowledge('proj-1', k);
  assert(useProjectStore.getState().knowledge.size === 1, 'setKnowledge: adds entry');
  assert(useProjectStore.getState().knowledge.get('proj-1')?.techStack === 'Java 21, Spring Boot, React, TypeScript', 'setKnowledge: correct data');
}

// setKnowledge overwrites
resetStore();
{
  useProjectStore.getState().setKnowledge('proj-1', makeKnowledge());
  useProjectStore.getState().setKnowledge('proj-1', makeKnowledge({ techStack: 'Python' }));
  assert(useProjectStore.getState().knowledge.get('proj-1')?.techStack === 'Python', 'setKnowledge: overwrites existing');
  assert(useProjectStore.getState().knowledge.size === 1, 'setKnowledge: still one entry after overwrite');
}

// setKnowledge multiple projects
resetStore();
{
  useProjectStore.getState().setKnowledge('proj-1', makeKnowledge({ projectId: 'proj-1' }));
  useProjectStore.getState().setKnowledge('proj-2', makeKnowledge({ projectId: 'proj-2', techStack: 'Go' }));
  assert(useProjectStore.getState().knowledge.size === 2, 'setKnowledge: supports multiple projects');
}

// setAllKnowledge
resetStore();
{
  const knowledgeList = [
    makeKnowledge({ projectId: 'proj-1' }),
    makeKnowledge({ projectId: 'proj-2', techStack: 'Rust' }),
  ];
  useProjectStore.getState().setAllKnowledge(knowledgeList);
  assert(useProjectStore.getState().knowledge.size === 2, 'setAllKnowledge: sets all entries');
  assert(useProjectStore.getState().knowledge.get('proj-2')?.techStack === 'Rust', 'setAllKnowledge: correct data');
}

// setAllKnowledge replaces existing
resetStore();
{
  useProjectStore.getState().setKnowledge('old', makeKnowledge({ projectId: 'old' }));
  useProjectStore.getState().setAllKnowledge([makeKnowledge({ projectId: 'new' })]);
  assert(useProjectStore.getState().knowledge.size === 1, 'setAllKnowledge: replaces all');
  assert(useProjectStore.getState().knowledge.has('new'), 'setAllKnowledge: has new entry');
  assert(!useProjectStore.getState().knowledge.has('old'), 'setAllKnowledge: old entry gone');
}

// setAnalyzing add
resetStore();
{
  useProjectStore.getState().setAnalyzing('proj-1', true);
  assert(useProjectStore.getState().analyzingProjects.has('proj-1'), 'setAnalyzing: adds project');
  assert(useProjectStore.getState().analyzingProjects.size === 1, 'setAnalyzing: one entry');
}

// setAnalyzing remove
resetStore();
{
  useProjectStore.getState().setAnalyzing('proj-1', true);
  useProjectStore.getState().setAnalyzing('proj-1', false);
  assert(!useProjectStore.getState().analyzingProjects.has('proj-1'), 'setAnalyzing: removes project');
  assert(useProjectStore.getState().analyzingProjects.size === 0, 'setAnalyzing: empty after remove');
}

// setAnalyzing multiple
resetStore();
{
  useProjectStore.getState().setAnalyzing('proj-1', true);
  useProjectStore.getState().setAnalyzing('proj-2', true);
  assert(useProjectStore.getState().analyzingProjects.size === 2, 'setAnalyzing: multiple projects');
  useProjectStore.getState().setAnalyzing('proj-1', false);
  assert(useProjectStore.getState().analyzingProjects.size === 1, 'setAnalyzing: selective removal');
  assert(useProjectStore.getState().analyzingProjects.has('proj-2'), 'setAnalyzing: other still present');
}

// setInitialized
resetStore();
{
  assert(!useProjectStore.getState().initialized, 'setInitialized: starts false');
  useProjectStore.getState().setInitialized();
  assert(useProjectStore.getState().initialized, 'setInitialized: becomes true');
}

// knowledge patterns structure
resetStore();
{
  const k = makeKnowledge();
  useProjectStore.getState().setKnowledge('proj-1', k);
  const stored = useProjectStore.getState().knowledge.get('proj-1')!;
  assert(stored.patterns.length === 2, 'patterns: has two patterns');
  assert(stored.patterns[0].name === 'Domain CLAUDE.md', 'patterns: first pattern name correct');
  assert(stored.patterns[0].tags.length === 2, 'patterns: first pattern has two tags');
  assert(stored.patterns[1].tags.includes('react'), 'patterns: second pattern has react tag');
  assert(stored.keyFiles.length === 3, 'patterns: three key files');
  assert(stored.architectureSummary !== null, 'patterns: architecture summary present');
}

// --- Summary ---
console.log('');
console.log(`Results: ${passed} passed, ${failed} failed, ${passed + failed} total`);
if (failed > 0) {
  process.exit(1);
}
