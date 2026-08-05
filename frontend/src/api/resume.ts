import request from '../utils/request';

export interface Resume {
  id: number;
  userId: number;
  fileName: string;
  fileType: string;
  fileSize: number;
  rawText: string;
  contentHash: string;
  createdAt: string;
  updatedAt: string;
}

export interface ResumeUploadVO {
  id: number;
  fileName: string;
  fileType: string;
  fileSize: number;
  rawTextPreview: string;
  createdAt: string;
}

export function uploadResume(file: File) {
  const form = new FormData();
  form.append('file', file);
  return request.post<{ code: number; msg: string; data: ResumeUploadVO }>('/api/resumes/upload', form);
}

export function getResumes() {
  return request.get<{ code: number; msg: string; data: Resume[] }>('/api/resumes');
}

export function getResume(id: number) {
  return request.get<{ code: number; msg: string; data: Resume }>(`/api/resumes/${id}`);
}

export function deleteResume(id: number) {
  return request.delete<{ code: number; msg: string; data: null }>(`/api/resumes/${id}`);
}