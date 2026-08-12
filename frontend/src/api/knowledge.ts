import request from '../utils/request';

export interface KnowledgeBase {
  id: number;
  name: string;
  description: string;
  userId: number;
  documentCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument {
  id: number;
  knowledgeBaseId: number;
  title: string;
  contentMd?: string;
  chunkCount: number;
  /** DRAFT / VECTORIZING / ACTIVE / FAILED */
  status: string;
  createdAt: string;
  updatedAt: string;
}

type R<T> = { code: number; msg: string; data: T };

export function createKb(data: { name: string; description?: string }) {
  return request.post<R<KnowledgeBase>>('/api/knowledge-bases', data);
}

export function getKbs() {
  return request.get<R<KnowledgeBase[]>>('/api/knowledge-bases');
}

export function deleteKb(id: number) {
  return request.delete<R<null>>(`/api/knowledge-bases/${id}`);
}

export function addDocument(kbId: number, data: { title: string; contentMd: string; vectorize: boolean }) {
  return request.post<R<KnowledgeDocument>>(`/api/knowledge-bases/${kbId}/documents`, data);
}

export function listDocuments(kbId: number) {
  return request.get<R<KnowledgeDocument[]>>(`/api/knowledge-bases/${kbId}/documents`);
}

export function deleteDocument(kbId: number, docId: number) {
  return request.delete<R<null>>(`/api/knowledge-bases/${kbId}/documents/${docId}`);
}
