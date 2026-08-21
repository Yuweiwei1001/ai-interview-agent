<script setup lang="ts">
import { ref } from 'vue';
import {
  NButton, NInput, NSpin, NCard, NTag, NAlert, NEmpty, NText, NSpace
} from 'naive-ui';
import { runAsrEval, type AsrEvalResult } from '../api/asrEval';
import BackButton from '../components/BackButton.vue';

const file = ref<File | null>(null);
const expectedText = ref('');
const hotwords = ref('');
const loading = ref(false);
const result = ref<AsrEvalResult | null>(null);
const error = ref('');
const fileInputRef = ref<HTMLInputElement | null>(null);

function pickFile() {
  fileInputRef.value?.click();
}

function onNativeFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  if (f) file.value = f;
}

function onDrop(e: DragEvent) {
  const f = e.dataTransfer?.files?.[0];
  if (f) file.value = f;
}

/**
 * 用浏览器原生 WebAudio 把任意音频（mp3/m4a/wav…）解码并重采样为 16kHz mono WAV。
 * 后端 Fun-ASR 非流式调用对压缩格式（mp3/m4a）存在 task_group 协议缺陷（报 500），
 * wav/pcm 直传稳定，故统一在浏览器侧转码。
 */
async function transcodeToWav(file: File): Promise<File> {
  const arrayBuffer = await file.arrayBuffer();
  const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();
  try {
    const audioBuffer = await ctx.decodeAudioData(arrayBuffer);
    const sampleRate = 16000;
    const length = Math.max(1, Math.ceil(audioBuffer.duration * sampleRate));
    const offline = new OfflineAudioContext(1, length, sampleRate);
    const source = offline.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(offline.destination);
    source.start(0);
    const rendered = await offline.startRendering();
    const pcm = rendered.getChannelData(0);
    const wav = encodeWav(pcm, sampleRate);
    return new File([wav], file.name.replace(/\.[^.]+$/, '') + '.wav', { type: 'audio/wav' });
  } finally {
    ctx.close();
  }
}

function encodeWav(samples: Float32Array, sampleRate: number): ArrayBuffer {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const writeString = (o: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i));
  };
  writeString(0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  writeString(8, 'WAVE');
  writeString(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeString(36, 'data');
  view.setUint32(40, samples.length * 2, true);
  let offset = 44;
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    offset += 2;
  }
  return buffer;
}

async function submit() {
  if (!file.value) {
    error.value = '请先上传音频文件';
    return;
  }
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    // 浏览器侧统一转码为 16kHz mono WAV（避免后端压缩格式 500）
    const wav = await transcodeToWav(file.value);
    const res = await runAsrEval(wav, expectedText.value || undefined, hotwords.value || undefined);
    result.value = res.data.data;
  } catch (e: any) {
    error.value = e?.response?.data?.msg || '评测失败，请确认音频格式（mp3/wav 等）且时长 ≤ 3 分钟';
  } finally {
    loading.value = false;
  }
}

const verdictMap: Record<string, { label: string; type: 'success' | 'error' | 'default' }> = {
  IMPROVED: { label: '纠错改善', type: 'success' },
  DEGRADED: { label: '纠错退化', type: 'error' },
  NEUTRAL: { label: '纠错持平', type: 'default' },
  NO_EXPECTED: { label: '未填期望文本', type: 'default' }
};

function scoreText(s: number) {
  return s < 0 ? '—' : (s * 100).toFixed(1) + '%';
}

function confTag(conf: string) {
  return conf === 'high'
    ? { label: '高置信·自动替换', type: 'success' as const }
    : { label: '低置信·需确认', type: 'warning' as const };
}
</script>

<template>
  <div class="min-h-screen bg-slate-50 p-6">
    <div class="max-w-3xl mx-auto">
      <BackButton to="/home" />
      <h1 class="text-xl font-bold text-slate-800 mb-1">ASR 转写评测</h1>
      <p class="text-sm text-slate-500 mb-6">
        上传一段面试录音，自动转写并做术语纠错。可填期望转写文本自动量化（原始转写 vs 纠错后 vs 期望）。
      </p>

      <n-card class="shadow-card rounded-2xl mb-4" :bordered="false">
        <input
          ref="fileInputRef"
          type="file"
          accept="audio/*"
          class="hidden"
          @change="onNativeFileChange"
        />
        <div
          class="flex flex-col items-center gap-2 rounded-2xl border-2 border-dashed border-slate-300 bg-slate-50/50 p-6 cursor-pointer hover:border-blue-400 hover:bg-blue-50/50 transition-colors"
          @click="pickFile"
          @dragover.prevent
          @drop.prevent="onDrop"
        >
          <svg class="w-10 h-10 text-slate-300" fill="none" stroke="currentColor" stroke-width="1.5" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
          </svg>
          <n-text class="text-sm">点击选择或拖拽上传音频（≤3 分钟）</n-text>
          <n-text class="text-xs text-slate-400">{{ file ? '已选择：' + file.name : '支持 mp3 / wav / m4a 等常见格式' }}</n-text>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-4">
          <div>
            <label class="text-xs text-slate-500 block mb-1">期望转写文本（可选，用于量化）</label>
            <n-input
              v-model:value="expectedText"
              type="textarea"
              :rows="2"
              placeholder="如：我用 Raft 做共识算法"
            />
          </div>
          <div>
            <label class="text-xs text-slate-500 block mb-1">热词（可选，逗号分隔）</label>
            <n-input
              v-model:value="hotwords"
              placeholder="如：Raft, Redis, 缓存雪崩"
            />
          </div>
        </div>

        <n-button
          class="mt-4 w-full"
          type="primary"
          size="large"
          :loading="loading"
          :disabled="!file"
          @click="submit"
        >
          开始评测
        </n-button>

        <n-alert v-if="error" type="error" class="mt-4" :show-icon="true">{{ error }}</n-alert>
      </n-card>

      <n-spin :show="loading">
        <template v-if="result">
          <n-card class="shadow-card rounded-2xl mb-4" :bordered="false">
            <div class="flex items-center justify-between mb-4">
              <h2 class="font-semibold text-slate-800">评测结果</h2>
              <n-tag :type="verdictMap[result.verdict]?.type || 'default'" :bordered="false" round>
                {{ verdictMap[result.verdict]?.label || result.verdict }}
              </n-tag>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-4">
              <div class="rounded-xl border border-slate-200 p-4">
                <div class="text-xs text-slate-400 mb-1">转写原文</div>
                <div class="text-sm text-slate-700 break-words">{{ result.raw }}</div>
                <div class="text-right text-xs text-slate-500 mt-2">相似度 {{ scoreText(result.rawScore) }}</div>
              </div>
              <div class="rounded-xl border border-emerald-200 bg-emerald-50/50 p-4">
                <div class="text-xs text-emerald-500 mb-1">纠错后</div>
                <div class="text-sm text-slate-700 break-words">{{ result.corrected }}</div>
                <div class="text-right text-xs text-emerald-600 mt-2">相似度 {{ scoreText(result.correctedScore) }}</div>
              </div>
            </div>

            <div class="text-xs text-slate-400 mb-2">
              纠错明细（{{ result.corrections.length }} 处）
            </div>
            <n-empty v-if="result.corrections.length === 0" description="未产生纠错" size="small" />
            <n-space v-else vertical :size="8">
              <div
                v-for="(c, i) in result.corrections"
                :key="i"
                class="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2"
              >
                <span class="text-sm text-rose-500 line-through">{{ c.from }}</span>
                <span class="text-slate-400">→</span>
                <span class="text-sm text-emerald-600 font-medium">{{ c.to }}</span>
                <n-tag size="small" :type="confTag(c.confidence).type" :bordered="false" round class="ml-auto">
                  {{ confTag(c.confidence).label }}
                </n-tag>
              </div>
            </n-space>
          </n-card>
        </template>

        <n-empty v-else-if="!loading" description="上传音频后点击开始评测" class="py-16" />
      </n-spin>
    </div>
  </div>
</template>
