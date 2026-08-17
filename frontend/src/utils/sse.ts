export interface SseEvent {
  event: string;
  data: string;
}

/**
 * SSE 客户端
 * - connect(): POST 启动新面试（不自动重连，避免重复创建会话）
 * - connectGet(): 重连已有面试流（连接意外断开时指数退避自动重连，最多 5 次）
 */
export class SseClient {
  private abortController: AbortController | null = null;
  private reconnectAttempts = 0;
  private maxReconnect = 5;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private manualClosed = false;

  async connect(url: string, body: any, onEvent: (event: SseEvent) => void, onError?: (err: any) => void, onActivity?: () => void) {
    this.abortController = new AbortController();
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` },
        body: JSON.stringify(body),
        signal: this.abortController.signal
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      await this.parseStream(response, onEvent, onActivity);
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        onError?.(err);
      }
    }
  }

  async connectGet(url: string, onEvent: (event: SseEvent) => void, onError?: (err: any) => void, onActivity?: () => void) {
    this.manualClosed = false;
    await this.connectGetOnce(url, onEvent, onError, onActivity);
  }

  private async connectGetOnce(url: string, onEvent: (event: SseEvent) => void, onError?: (err: any) => void, onActivity?: () => void) {
    this.abortController = new AbortController();
    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` },
        signal: this.abortController.signal
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      await this.parseStream(response, onEvent, onActivity);
      // 流正常结束（服务器主动 complete）：不重连
      this.reconnectAttempts = 0;
    } catch (err: any) {
      if (err.name !== 'AbortError' && !this.manualClosed) {
        onError?.(err);
        this.scheduleReconnect(url, onEvent, onError, onActivity);
      }
    }
  }

  /** 指数退避重连：1s → 2s → 4s → 8s → 16s */
  private scheduleReconnect(url: string, onEvent: (event: SseEvent) => void, onError?: (err: any) => void, onActivity?: () => void) {
    if (this.reconnectAttempts >= this.maxReconnect || this.manualClosed) return;
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 16000);
    this.reconnectAttempts++;
    this.reconnectTimer = setTimeout(() => {
      this.connectGetOnce(url, onEvent, onError, onActivity);
    }, delay);
  }

  private async parseStream(response: Response, onEvent: (event: SseEvent) => void, onActivity?: () => void) {
    const reader = response.body?.getReader();
    if (!reader) throw new Error('No reader');
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      // 活性信号：每收到一个 chunk 即触发（含注释帧心跳），用于驱动连接状态指示
      onActivity?.();
      // 统一换行符，按空行（SSE 事件分隔符）切分完整事件
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
      const chunks = buffer.split('\n\n');
      buffer = chunks.pop() || '';
      for (const chunk of chunks) {
        if (!chunk.trim()) continue;
        let eventName = '';
        const dataLines: string[] = [];
        for (const line of chunk.split('\n')) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim());
          }
        }
        // 一个事件的所有 data 行用换行拼接成一条完整消息
        if (dataLines.length > 0) {
          onEvent({ event: eventName, data: dataLines.join('\n') });
        }
      }
    }
  }

  disconnect() {
    this.manualClosed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.abortController?.abort();
  }
}
