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
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      let currentEvent = '';
      for (const line of lines) {
        if (line.startsWith('event: ')) {
          currentEvent = line.slice(7).trim();
        } else if (line.startsWith('data: ')) {
          const data = line.slice(6);
          onEvent({ event: currentEvent, data });
        }
      }
    }
  }

  disconnect() {
    this.abortController?.abort();
  }
}
