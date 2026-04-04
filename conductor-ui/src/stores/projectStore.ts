import { create } from 'zustand';
import type { ProjectRecord, ProjectKnowledge } from '../types/projectTypes';

const API_BASE = 'http://localhost:8090';

interface ProjectState {
  /** All registered projects. */
  projects: ProjectRecord[];

  /** Knowledge extracted by the Brain, keyed by projectId. */
  knowledge: Map<string, ProjectKnowledge>;

  /** Project IDs currently being analyzed by the Brain. */
  analyzingProjects: Set<string>;

  /** Whether initial data has been fetched from server. */
  initialized: boolean;

  /** Set all projects (replace). */
  setProjects: (projects: ProjectRecord[]) => void;

  /** Add a single project to the list. */
  addProject: (project: ProjectRecord) => void;

  /** Add multiple projects at once (from scan). */
  addProjects: (projects: ProjectRecord[]) => void;

  /** Store knowledge for a specific project. */
  setKnowledge: (projectId: string, knowledge: ProjectKnowledge) => void;

  /** Set all knowledge from a bulk fetch. */
  setAllKnowledge: (knowledgeList: ProjectKnowledge[]) => void;

  /** Mark a project as analyzing or not. */
  setAnalyzing: (projectId: string, analyzing: boolean) => void;

  /** Mark store as initialized after first fetch. */
  setInitialized: () => void;

  /** Fetch all projects and knowledge from the server. */
  fetchAll: () => Promise<void>;

  /** Register a new project by path. */
  registerProject: (path: string) => Promise<ProjectRecord>;

  /** Scan a root directory for projects. */
  scanProjects: (rootPath: string) => Promise<ProjectRecord[]>;

  /** Trigger Brain analysis for a project. */
  analyzeProject: (projectId: string) => Promise<void>;
}

export const useProjectStore = create<ProjectState>((set, get) => ({
  projects: [],
  knowledge: new Map(),
  analyzingProjects: new Set(),
  initialized: false,

  setProjects: (projects) => set({ projects }),

  addProject: (project) =>
    set((s) => {
      // Avoid duplicates
      if (s.projects.some((p) => p.id === project.id)) return s;
      return { projects: [...s.projects, project] };
    }),

  addProjects: (projects) =>
    set((s) => {
      const existingIds = new Set(s.projects.map((p) => p.id));
      const newProjects = projects.filter((p) => !existingIds.has(p.id));
      if (newProjects.length === 0) return s;
      return { projects: [...s.projects, ...newProjects] };
    }),

  setKnowledge: (projectId, knowledge) =>
    set((s) => {
      const next = new Map(s.knowledge);
      next.set(projectId, knowledge);
      return { knowledge: next };
    }),

  setAllKnowledge: (knowledgeList) =>
    set(() => {
      const map = new Map<string, ProjectKnowledge>();
      for (const k of knowledgeList) {
        map.set(k.projectId, k);
      }
      return { knowledge: map };
    }),

  setAnalyzing: (projectId, analyzing) =>
    set((s) => {
      const next = new Set(s.analyzingProjects);
      if (analyzing) {
        next.add(projectId);
      } else {
        next.delete(projectId);
      }
      return { analyzingProjects: next };
    }),

  setInitialized: () => set({ initialized: true }),

  fetchAll: async () => {
    try {
      const [projectsRes, knowledgeRes] = await Promise.all([
        fetch(`${API_BASE}/api/projects`),
        fetch(`${API_BASE}/api/brain/knowledge`),
      ]);

      if (projectsRes.ok) {
        const projects: ProjectRecord[] = await projectsRes.json();
        set({ projects });
      }

      if (knowledgeRes.ok) {
        const knowledgeList: ProjectKnowledge[] = await knowledgeRes.json();
        const map = new Map<string, ProjectKnowledge>();
        for (const k of knowledgeList) {
          map.set(k.projectId, k);
        }
        set({ knowledge: map });
      }
    } catch {
      // Non-critical: panel will show empty state
    } finally {
      set({ initialized: true });
    }
  },

  registerProject: async (path: string): Promise<ProjectRecord> => {
    const res = await fetch(`${API_BASE}/api/projects/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path }),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(text || `HTTP ${res.status}`);
    }

    const project: ProjectRecord = await res.json();
    get().addProject(project);
    return project;
  },

  scanProjects: async (rootPath: string): Promise<ProjectRecord[]> => {
    const res = await fetch(`${API_BASE}/api/projects/scan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rootPath }),
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(text || `HTTP ${res.status}`);
    }

    const projects: ProjectRecord[] = await res.json();
    get().addProjects(projects);
    return projects;
  },

  analyzeProject: async (projectId: string): Promise<void> => {
    const s = get();
    s.setAnalyzing(projectId, true);

    try {
      const res = await fetch(`${API_BASE}/api/brain/analyze/${projectId}`, {
        method: 'POST',
      });

      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }

      const knowledge: ProjectKnowledge = await res.json();
      s.setKnowledge(projectId, knowledge);
    } finally {
      s.setAnalyzing(projectId, false);
    }
  },
}));
