import request from '../utils/request';

export interface LlmTrace {
  id: number;
  sessionId: string | null;
  agent: string | null;
  model: string | null;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  durationMs: number;
  /** success / error */
  status: string;
  errorMsg: string | null;
  estimatedCost: number;
  promptExcerpt: string | null;
  completionExcerpt: string | null;
  createdAt: string;
}

export interface SessionTraceSummary {
  sessionId: string;
  callCount: number;
  totalTokens: number;
  estimatedCost: number;
  errorCount: number;
  startedAt: string;
  lastAt: string;
}

export interface AgentUsage {
  agent: string;
  callCount: number;
  totalTokens: number;
  estimatedCost: number;
  errorCount: number;
}

export interface UsageSummary {
  callCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  estimatedCost: number;
  errorCount: number;
  days: number;
  byAgent: AgentUsage[];
}

type R<T> = { code: number; msg: string; data: T };

export function getTraceSessions(limit = 50) {
  return request.get<R<SessionTraceSummary[]>>('/api/observability/sessions', { params: { limit } });
}

export function getTraces(sessionId: string) {
  return request.get<R<LlmTrace[]>>('/api/observability/traces', { params: { sessionId } });
}

export function getUsageSummary(days = 7) {
  return request.get<R<UsageSummary>>('/api/observability/summary', { params: { days } });
}
