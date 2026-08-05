import request from '../utils/request';

export interface InterviewPlan {
  overallStrategy: string;
  agentAssignments: Record<string, { topics: string; difficulty: string; estimatedRounds: number }>;
  weakPointPriority: string[];
  estimatedTotalRounds: number;
}

export interface InterviewSession {
  id: string;
  userId: number;
  resumeId: number | null;
  jdId: number | null;
  direction: string;
  persona: string;
  durationMinutes: number;
  status: string;
  overallScore: number | null;
  report: string | null;
  startedAt: string;
  completedAt: string;
  createdAt: string;
}

export interface InterviewReport {
  overallScore: number;
  dimensionScores: {
    technical: number;
    project: number;
    coding: number;
    communication: number;
  };
  strengths: string[];
  weaknesses: string[];
  suggestions: string[];
  perQuestionFeedback: {
    roundNumber: number;
    question: string;
    answer: string;
    score: number;
    feedback: string;
  }[];
  growthComparison?: {
    previousScore: number;
    currentScore: number;
    improvement: number;
  };
}

export function createPlan(data: {
  resumeId?: number;
  jdId?: number;
  direction?: string;
  persona?: string;
  durationMinutes?: number;
}) {
  return request.post<{ code: number; msg: string; data: InterviewPlan }>('/api/interviews/plan', data);
}

export function getSessions() {
  return request.get<{ code: number; msg: string; data: InterviewSession[] }>('/api/interviews/sessions');
}

export function getSession(id: string) {
  return request.get<{ code: number; msg: string; data: InterviewSession }>(`/api/interviews/sessions/${id}`);
}

export function getReport(id: string) {
  return request.get<{ code: number; msg: string; data: InterviewReport }>(`/api/interviews/sessions/${id}/report`);
}
