/**
 * Types for the Command Bar and Template Picker features.
 *
 * AgentTemplate: represents a reusable agent configuration fetched from the server.
 * CommandResponse: the response shape returned by POST /api/brain/command.
 * TemplateUseRequest: the body sent when using a template.
 */

export interface AgentTemplate {
  templateId: string;
  name: string;
  description: string;
  role: string;
  defaultPrompt: string;
  tags: string[];
  usageCount: number;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface CommandResponse {
  intent: {
    action: string;
    originalText: string;
    parameters: Record<string, string>;
    confidence: number;
    reasoning: string;
  };
  result: {
    success: boolean;
    message: string;
    data: unknown;
  };
}

export interface TemplateUseRequest {
  projectPath: string;
  promptOverride: string;
}

export interface TemplateUseResponse {
  success: boolean;
  message: string;
  agentId?: string;
}
