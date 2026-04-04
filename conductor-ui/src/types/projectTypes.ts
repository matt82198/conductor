/**
 * Types for the Project Onboarding feature.
 *
 * These types model registered projects, their analysis state,
 * and the extracted knowledge (patterns, tech stack, architecture)
 * that the Brain produces during project onboarding.
 */

export interface PatternEntry {
  name: string;
  description: string;
  sourceFile: string;
  tags: string[];
}

export interface ProjectKnowledge {
  projectId: string;
  projectName: string;
  projectPath: string;
  techStack: string | null;
  patterns: PatternEntry[];
  keyFiles: string[];
  architectureSummary: string | null;
  analyzedAt: string;
}

export interface ProjectRecord {
  id: string;
  name: string;
  path: string;
  gitRemote: string | null;
  agentCount: number;
  registeredAt: string;
}
