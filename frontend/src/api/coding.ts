import request from '../utils/request';

export interface TestCaseResult {
  name: string;
  passed: boolean;
  detail: string;
  source?: string;
}

export interface TestRunResult {
  allPassed: boolean;
  passRate: number;
  results: TestCaseResult[];
  error: string | null;
  /** 无预设用例直接执行时的程序 stdout */
  stdout?: string | null;
}

export interface CodeEvaluationResult {
  correctness: number;
  codeQuality: number;
  edgeCaseHandling: number;
  timeComplexity: number;
  testPassRate: number;
  overallScore: number;
  suggestions: string[];
  summary: string;
}

export function runCode(code: string, language: string, input = '') {
  return request.post<{ code: number; msg: string; data: TestRunResult }>('/api/coding/run', {
    code, language, input, testCases: []
  });
}

export function submitCode(sessionId: string, code: string, language: string, questionTitle = '') {
  return request.post<{ code: number; msg: string; data: any }>(`/api/coding/submit/${sessionId}`, {
    code, language, questionTitle
  });
}
