import request from '../utils/request';
import { SseClient } from '../utils/sse';

export interface ChatSession {
  id: number;
  userId: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  updatedTime?: string;
}

export interface ChatSource {
  docId: number;
  title: string;
  excerpt: string;
}

export interface ChatMessage {
  id?: number;
  sessionId?: number;
  role: 'user' | 'assistant';
  content: string;
  /** assistant 消息的引用来源 JSON 字符串 */
  sources?: string | null;
}

type R<T> = { code: number; msg: string; data: T };

export function createChatSession() {
  return request.post<R<ChatSession>>('/api/chat/sessions');
}

export function listChatSessions() {
  return request.get<R<ChatSession[]>>('/api/chat/sessions');
}

export function getChatMessages(sessionId: number) {
  return request.get<R<ChatMessage[]>>(`/api/chat/sessions/${sessionId}/messages`);
}

export function deleteChatSession(sessionId: number) {
  return request.delete<R<null>>(`/api/chat/sessions/${sessionId}`);
}

/**
 * SSE 流式提问：事件 delta（增量文本）/ sources（引用来源 JSON）/ refusal（拒答文案）/ done / error。
 * 一问一答的短连接流，复用面试间的 SseClient（POST，不自动重连）。
 */
export function askStream(sessionId: number, question: string, onEvent: { delta?: (t: string) => void; sources?: (s: ChatSource[]) => void; refusal?: (msg: string) => void; done?: () => void; error?: (msg: string) => void }) {
  const client = new SseClient();
  client.connect(`/api/chat/sessions/${sessionId}/ask`, { question }, (evt) => {
    if (evt.event === 'delta' && evt.data) onEvent.delta?.(evt.data);
    else if (evt.event === 'sources') {
      try { onEvent.sources?.(JSON.parse(evt.data)); } catch { /* 忽略解析失败 */ }
    }
    else if (evt.event === 'refusal') onEvent.refusal?.(evt.data || '该问题超出了你当前知识库范围，我无法回答。');
    else if (evt.event === 'done') onEvent.done?.();
    else if (evt.event === 'error') onEvent.error?.(evt.data || '回答生成失败');
  }, () => onEvent.error?.('连接失败，请重试'));
  return client;
}
