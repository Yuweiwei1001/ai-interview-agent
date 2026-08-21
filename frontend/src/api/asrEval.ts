import request from '../utils/request';

export interface AsrEvalCorrection {
  from: string;
  to: string;
  confidence: string;
}

export interface AsrEvalResult {
  raw: string;
  corrected: string;
  corrections: AsrEvalCorrection[];
  rawScore: number;
  correctedScore: number;
  verdict: string;
}

type R<T> = { code: number; msg: string; data: T };

/** 上传音频 → 转写 + 纠错 + 量化（≤3 分钟短音频） */
export function runAsrEval(file: File, expectedText?: string, hotwords?: string) {
  const fd = new FormData();
  fd.append('file', file);
  if (expectedText && expectedText.trim()) fd.append('expectedText', expectedText.trim());
  if (hotwords && hotwords.trim()) fd.append('hotwords', hotwords.trim());
  return request.post<R<AsrEvalResult>>('/api/voice/asr-eval', fd, {
    timeout: 120000
  });
}
