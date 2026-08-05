<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { SseClient } from '../utils/sse';
import ChatBubble from '../components/ChatBubble.vue';

const route = useRoute();
const router = useRouter();

const messages = ref<{ role: 'assistant' | 'user'; content: string; timestamp: string }[]>([]);
const answer = ref('');
const sessionId = ref('');
const connected = ref(false);
const completed = ref(false);
const thinking = ref(false);
const error = ref('');

let sseClient: SseClient | null = null;

onMounted(() => {
  startInterview();
});

onUnmounted(() => {
  sseClient?.disconnect();
});

function startInterview() {
  const body: any = {
    resumeId: route.query.resumeId ? Number(route.query.resumeId) : null,
    jdId: route.query.jdId ? Number(route.query.jdId) : null,
    direction: route.query.direction || null,
    persona: route.query.persona || 'neutral',
    durationMinutes: Number(route.query.durationMinutes) || 30
  };

  sseClient = new SseClient();
  sseClient.connect('/api/interviews/start', body, (event) => {
    switch (event.event) {
      case 'CONNECTED':
        sessionId.value = event.data;
        connected.value = true;
        break;
      case 'QUESTION':
        messages.value.push({
          role: 'assistant',
          content: event.data,
          timestamp: new Date().toLocaleTimeString()
        });
        thinking.value = false;
        break;
      case 'THINKING':
        thinking.value = true;
        break;
      case 'COMPLETE':
        completed.value = true;
        thinking.value = false;
        break;
      case 'REPORT_READY':
        router.push(`/report/${sessionId.value}`);
        break;
      case 'ERROR':
        error.value = event.data;
        thinking.value = false;
        break;
    }
  }, () => {
    error.value = '连接失败';
    thinking.value = false;
  });
}

async function submitAnswer() {
  if (!answer.value.trim() || !sessionId.value) return;
  const text = answer.value;
  messages.value.push({
    role: 'user',
    content: text,
    timestamp: new Date().toLocaleTimeString()
  });
  answer.value = '';
  thinking.value = true;
  try {
    await fetch(`/api/interviews/${sessionId.value}/answer`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('accessToken')}`
      },
      body: JSON.stringify({ answer: text })
    });
  } catch {
    error.value = '提交失败';
  }
}

function endInterview() {
  if (!sessionId.value) return;
  fetch(`/api/interviews/${sessionId.value}/end`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` }
  });
  completed.value = true;
}
</script>

<template>
  <div class="flex flex-col h-[calc(100vh-64px)]">
    <!-- Header -->
    <header class="bg-white shadow-sm px-6 py-3 flex items-center justify-between shrink-0">
      <h2 class="text-lg font-bold text-slate-800">面试进行中</h2>
      <div class="flex items-center gap-3">
        <span v-if="connected" class="text-sm text-green-600">● 已连接</span>
        <span v-if="thinking" class="text-sm text-yellow-600">● 思考中...</span>
        <button v-if="!completed" @click="endInterview"
          class="px-3 py-1 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600 transition-colors">结束面试</button>
      </div>
    </header>

    <!-- Messages -->
    <div class="flex-1 overflow-y-auto p-6 space-y-4 bg-slate-50">
      <div v-if="messages.length === 0 && !error" class="text-center py-12 text-slate-400">
        {{ connected ? '等待第一道题目...' : '连接中...' }}
      </div>
      <ChatBubble v-for="(msg, i) in messages" :key="i" :role="msg.role" :content="msg.content" :timestamp="msg.timestamp" />
      <div v-if="thinking" class="flex justify-start">
        <div class="bg-white border border-slate-200 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm">
          <div class="flex gap-1">
            <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:0ms"></span>
            <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:150ms"></span>
            <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:300ms"></span>
          </div>
        </div>
      </div>
      <p v-if="error" class="text-red-500 text-center">{{ error }}</p>
      <p v-if="completed" class="text-green-600 text-center font-medium">面试已结束，报告生成中...</p>
    </div>

    <!-- Input -->
    <div class="bg-white border-t px-6 py-4 shrink-0">
      <div class="flex gap-3 max-w-4xl mx-auto">
        <textarea v-model="answer" :disabled="!connected || completed"
          placeholder="输入你的回答..."
          class="flex-1 px-4 py-2 border border-slate-300 rounded-lg resize-none focus:ring-2 focus:ring-blue-500 outline-none"
          rows="2" @keydown.ctrl.enter="submitAnswer"></textarea>
        <button @click="submitAnswer" :disabled="!answer.trim() || !connected || completed"
          class="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors self-end">
          发送
        </button>
      </div>
    </div>
  </div>
</template>
