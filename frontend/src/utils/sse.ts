export interface SseEvent {
  event: string;
  data: string;
}

export class SseClient {
  private abortController: AbortController | null = null;

  async connect(url: string, body: any, onEvent: (event: SseEvent) => void, onError?: (err: any) => void) {
    this.abortController = new AbortController();
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` },
        body: JSON.stringify(body),
        signal: this.abortController.signal
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      await this.parseStream(response, onEvent);
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        onError?.(err);
      }
    }
  }

  async connectGet(url: string, onEvent: (event: SseEvent) => void, onError?: (err: any) => void) {
    this.abortController = new AbortController();
    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` },
        signal: this.abortController.signal
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      await this.parseStream(response, onEvent);
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        onError?.(err);
      }
    }
  }

  private async parseStream(response: Response, onEvent: (event: SseEvent) => void) {
    const reader = response.body?.getReader();
    if (!reader) throw new Error('No reader');
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
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
    this.abortController?.abort();
  }
}
