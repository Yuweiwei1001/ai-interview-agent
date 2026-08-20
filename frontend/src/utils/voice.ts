import { ref } from 'vue';

/**
 * 语音面试 composable：封装 /ws/voice/{sessionId} 实时通道。
 *
 * 职责：
 * - 上行：AudioWorklet 采集麦克风 → 重采样 16kHz Int16 PCM → base64 over JSON → ASR
 * - 下行：ASR 字幕（partial/final，final 携带 seq 对齐序号与 suspect 幻觉标记）、
 *        异步纠错补发（asr_correction，同 seq 定位草稿句）、TTS WAV 音频播放
 * - AI 说话门控：TTS 播放期间及结束后 600ms 冷却期内暂停音频上行，
 *   防止外放场景下面试官语音被自家麦克风回收识别（借鉴对象同款方案）
 *
 * 回答提交不走本通道：字幕由组件累积为可编辑草稿，经 REST /answer 提交。
 */

/** 单处 ASR 纠错项（建议式，候选人终审） */
export interface CorrectionItem {
  from: string;
  to: string;
  confidence: 'high' | 'low';
}

/** 后端异步补发的纠错消息（携带同 seq，前端按 seq 定位草稿句） */
export interface AsrCorrection {
  seq: number;
  text: string;
  corrections: CorrectionItem[];
}

export interface VoiceSubtitleHandlers {
  /** final：一句话定稿（VAD 切段），组件累积为回答草稿；seq 用于纠错补发对齐，suspect 为疑似 corpus 幻觉标记 */
  onFinal?: (text: string, seq: number, suspect: boolean) => void;
  /** partial：实时片段，组件做识别中预览 */
  onPartial?: (text: string) => void;
  /** 异步纠错补发（P95 < 2s）：按竞态三规则处理（未触碰替换/已编辑仅提示/已提交丢弃） */
  onCorrection?: (correction: AsrCorrection) => void;
  /** 控制消息（connected/asr_ready/asr_reconnecting 等） */
  onControl?: (action: string, message?: string) => void;
  /** 错误消息 */
  onError?: (message: string) => void;
}

/** TTS 播放结束后的上行冷却期（毫秒），吸收扬声器尾部混响 */
const SPEAK_COOLDOWN_MS = 600;

export function useVoiceInterview() {
  const connected = ref(false);   // WS 已连接
  const asrReady = ref(false);    // ASR 就绪（可开口说话）
  const micActive = ref(false);   // 麦克风采集中
  const speaking = ref(false);    // 面试官 TTS 播放中
  const error = ref('');

  let ws: WebSocket | null = null;
  let audioContext: AudioContext | null = null;
  let mediaStream: MediaStream | null = null;
  let sourceNode: MediaStreamAudioSourceNode | null = null;
  let workletNode: AudioWorkletNode | null = null;
  let currentAudio: HTMLAudioElement | null = null;
  /** 上行静默截止时刻（冷却期用） */
  let muteUntil = 0;

  function buildWsUrl(sessionId: string): string {
    const token = localStorage.getItem('accessToken') || '';
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${proto}://${location.host}/ws/voice/${sessionId}?token=${encodeURIComponent(token)}`;
  }

  function arrayBufferToBase64(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    const chunkSize = 0x8000;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
    }
    return btoa(binary);
  }

  function base64ToArrayBuffer(base64: string): ArrayBuffer {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
  }

  async function startMic(): Promise<void> {
    mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
    audioContext = new AudioContext();
    await audioContext.audioWorklet.addModule('/audio-worklet/pcm-processor.js');
    sourceNode = audioContext.createMediaStreamSource(mediaStream);
    workletNode = new AudioWorkletNode(audioContext, 'pcm-processor');
    workletNode.port.onmessage = (e: MessageEvent<ArrayBuffer>) => {
      // AI 说话门控：TTS 播放中/冷却期内不上行（防外放回收）
      if (speaking.value || Date.now() < muteUntil) {
        return;
      }
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'audio', data: arrayBufferToBase64(e.data) }));
      }
    };
    sourceNode.connect(workletNode);
    // 不连接 destination：采集不出声
    micActive.value = true;
  }

  function playWav(base64: string): void {
    try {
      const blob = new Blob([base64ToArrayBuffer(base64)], { type: 'audio/wav' });
      const url = URL.createObjectURL(blob);
      stopAudio();
      const audio = new Audio(url);
      currentAudio = audio;
      speaking.value = true;
      const done = () => {
        speaking.value = false;
        // 冷却期：播放结束后再静默上行一段时间，吸收混响尾巴
        muteUntil = Date.now() + SPEAK_COOLDOWN_MS;
        URL.revokeObjectURL(url);
        if (currentAudio === audio) {
          currentAudio = null;
        }
      };
      audio.onended = done;
      audio.onerror = done;
      void audio.play().catch(done);
    } catch (e) {
      speaking.value = false;
    }
  }

  function stopAudio(): void {
    if (currentAudio) {
      try {
        currentAudio.pause();
      } catch {
        // ignore
      }
      currentAudio = null;
    }
    speaking.value = false;
  }

  /**
   * 建立语音通道（WS + 麦克风）。
   * 麦克风权限被拒绝时语音通道仍建立（可看字幕/TTS），仅无法上行。
   */
  async function connect(sessionId: string, handlers: VoiceSubtitleHandlers): Promise<void> {
    disconnect();
    error.value = '';

    ws = new WebSocket(buildWsUrl(sessionId));
    ws.onopen = () => {
      connected.value = true;
    };
    ws.onclose = () => {
      connected.value = false;
      asrReady.value = false;
    };
    ws.onerror = () => {
      error.value = '语音通道连接失败';
    };
    ws.onmessage = (event: MessageEvent<string>) => {
      let msg: {
        type?: string; text?: string; final?: boolean; seq?: number; suspect?: boolean;
        data?: string; action?: string; message?: string; corrections?: CorrectionItem[];
      };
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      switch (msg.type) {
        case 'subtitle':
          if (msg.text) {
            if (msg.final) {
              // final 携带 seq（后端统一分配，不靠前端计数）与 suspect（疑似 corpus 幻觉，弱化提示候选人核对）
              handlers.onFinal?.(msg.text, typeof msg.seq === 'number' ? msg.seq : -1, msg.suspect === true);
            } else {
              handlers.onPartial?.(msg.text);
            }
          }
          break;
        case 'asr_correction':
          // 异步纠错补发：与 subtitle final 同 seq，前端按 seq 定位草稿句
          if (typeof msg.seq === 'number' && msg.text) {
            handlers.onCorrection?.({
              seq: msg.seq,
              text: msg.text,
              corrections: Array.isArray(msg.corrections) ? msg.corrections : [],
            });
          }
          break;
        case 'audio':
          if (msg.data) {
            playWav(msg.data);
          }
          break;
        case 'control':
          if (msg.action === 'asr_ready') {
            asrReady.value = true;
          }
          handlers.onControl?.(msg.action || '', msg.message);
          break;
        case 'error':
          error.value = msg.message || '语音服务错误';
          handlers.onError?.(error.value);
          break;
      }
    };

    try {
      await startMic();
    } catch (e) {
      // 麦克风拒绝/无设备：降级为仅接收（字幕 + TTS 仍可用，回答可手打）
      error.value = '麦克风不可用，可继续收听并手动输入回答';
    }
  }

  function disconnect(): void {
    stopAudio();
    if (workletNode) {
      workletNode.port.onmessage = null;
      workletNode.disconnect();
      workletNode = null;
    }
    if (sourceNode) {
      sourceNode.disconnect();
      sourceNode = null;
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop());
      mediaStream = null;
    }
    if (audioContext) {
      void audioContext.close().catch(() => undefined);
      audioContext = null;
    }
    if (ws) {
      try {
        ws.close();
      } catch {
        // ignore
      }
      ws = null;
    }
    connected.value = false;
    asrReady.value = false;
    micActive.value = false;
    muteUntil = 0;
  }

  return {
    connected,
    asrReady,
    micActive,
    speaking,
    error,
    connect,
    disconnect,
    stopAudio,
  };
}
