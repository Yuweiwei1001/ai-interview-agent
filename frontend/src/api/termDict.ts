import request from '../utils/request';

export interface TermDictItem {
  id: number;
  term: string;
  pinyin: string;
  category?: string | null;
  /** JSON 数组字符串，如 "["springboot","spring boot"]"；可为 null */
  aliases?: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface TermDictSavePayload {
  term: string;
  pinyin: string;
  category?: string;
  aliases: string[];
  enabled: boolean;
}

type R<T> = { code: number; msg: string; data: T };

export function getTermDicts() {
  return request.get<R<TermDictItem[]>>('/api/term-dict');
}

export function createTermDict(data: TermDictSavePayload) {
  return request.post<R<TermDictItem>>('/api/term-dict', data);
}

export function updateTermDict(id: number, data: TermDictSavePayload) {
  return request.put<R<TermDictItem>>(`/api/term-dict/${id}`, data);
}

export function deleteTermDict(id: number) {
  return request.delete<R<null>>(`/api/term-dict/${id}`);
}

/** 解析别名 JSON 字符串为数组（空/异常返回空数组） */
export function parseAliases(aliases?: string | null): string[] {
  if (!aliases) return [];
  try {
    const list = JSON.parse(aliases);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}