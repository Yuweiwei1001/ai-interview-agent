import request from '../utils/request';

export interface Jd {
  id: number;
  userId: number;
  title: string;
  rawText: string;
  sourceUrl: string;
  createdAt: string;
  updatedAt: string;
}

export interface JdCreateDTO {
  title: string;
  rawText: string;
  sourceUrl?: string;
}

export function createJd(data: JdCreateDTO) {
  return request.post<{ code: number; msg: string; data: Jd }>('/api/jds', data);
}

export function getJds() {
  return request.get<{ code: number; msg: string; data: Jd[] }>('/api/jds');
}

export function getJd(id: number) {
  return request.get<{ code: number; msg: string; data: Jd }>(`/api/jds/${id}`);
}

export function updateJd(id: number, data: JdCreateDTO) {
  return request.put<{ code: number; msg: string; data: Jd }>(`/api/jds/${id}`, data);
}

export function deleteJd(id: number) {
  return request.delete<{ code: number; msg: string; data: null }>(`/api/jds/${id}`);
}
