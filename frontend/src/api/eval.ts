import request from '../utils/request';

/* ===== 评测模块 API ===== */

export interface EvalCaseSummary {
  caseId: string;
  description: string;
  direction: string;
  answerLevel: string;
  durationMinutes: number;
  codingSubmissions: number;
}

export interface EvalRunRequest {
  caseIds?: string[];
  skipLlmJudge?: boolean;
  runCalibration?: boolean;
}

export interface TimelineEvent {
  ts: number;
  type: string;
  detail: string;
}

export interface RuleMetrics {
  sessionId: string;
  finalStatus: string;
  completed: boolean;
  driverTimeout: boolean;
  planRounds: number;
  actualMainRounds: number;
  roundAdherence: number;
  goalAchieved: boolean;
  codingRoundCount: number;
  codingOffTopicCount: number;
  followUpCount: number;
  followUpRate: number;
  duplicateQuestionPairs: number;
  questionDuplicateRate: number;
  topicCoverageRatio: number;
  uncoveredTopics: string[];
  degradedRoundCount: number;
  degradedRate: number;
  timeoutAnswerCount: number;
  avgScore: number;
  durationMs: number;
}

export interface JudgeMetrics {
  avgQuestionRelevance: number;
  judgedQuestionCount: number;
  avgFollowUpQuality: number;
  judgedFollowUpCount: number;
  judgeDegradedCount: number;
}

export interface CalibrationDetail {
  index: number;
  expectedLevel: string;
  expectedBucket: number;
  actualScore: number;
  actualBucket: number;
  exactMatch: boolean;
  relaxedMatch: boolean;
  summary: string;
}

export interface CalibrationResult {
  sampleCount: number;
  exactAgreementRate: number;
  relaxedAgreementRate: number;
  details: CalibrationDetail[];
}

export interface CaseResult {
  caseId: string;
  description: string;
  answerLevel: string;
  trace: {
    sessionId: string;
    finalStatus: string;
    driverTimeout: boolean;
    error: string | null;
    durationMs: number;
    timeline: TimelineEvent[];
  };
  ruleMetrics: RuleMetrics;
  judgeMetrics: JudgeMetrics | null;
  error: string | null;
}

export interface EvalAggregate {
  totalCases: number;
  completionRate: number;
  goalAchievedRate: number;
  avgRoundAdherence: number;
  avgQuestionDuplicateRate: number;
  avgTopicCoverage: number;
  totalCodingOffTopic: number;
  avgDegradedRate: number;
  avgQuestionRelevance: number;
  avgFollowUpQuality: number;
}

export interface EvalReport {
  runId: string;
  startedAt: string;
  finishedAt: string;
  evalUsername: string;
  caseResults: CaseResult[];
  calibration: CalibrationResult | null;
  aggregate: EvalAggregate;
}

export interface EvalRun {
  runId: string;
  status: string;
  error?: string;
  report?: EvalReport;
}

export function listEvalCases() {
  return request.get('/api/eval/cases');
}

export function startEvalRun(body: EvalRunRequest) {
  return request.post('/api/eval/run', body);
}

export function getEvalRun(runId: string) {
  return request.get(`/api/eval/runs/${runId}`);
}

export function runCalibration() {
  return request.post('/api/eval/calibrate');
}
