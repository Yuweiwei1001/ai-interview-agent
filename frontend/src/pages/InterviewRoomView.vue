<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NInput, NButton, NAlert, useDialog } from 'naive-ui';
import { SseClient, type SseEvent } from '../utils/sse';
import ChatBubble from '../components/ChatBubble.vue';

const route = useRoute();
const router = useRouter();
const dialog = useDialog();

const messages = ref<{ role: 'assistant' | 'user'; content: string; timestamp: string }[]>([]);
const answer = ref('');
const sessionId = ref('');
const connected = ref(false);
const completed = ref(false);
const thinking = ref(false);
const error = ref('');

/* 美化：消息容器引用，用于新消息自动滚动到底部 */
const messagesEl = ref<HTMLElement>();

let sseClient: SseClient | null = null;

onMounted(() => {
  startInterview();
});

onUnmounted(() => {
  sseClient?.disconnect();
});

/* 美化：新消息到达自动滚动到底部（纯体验优化，不改变交互逻辑） */
watch(() => messages.value.length, async () => {
  await nextTick();
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
  }
});

function handleSseEvent(event: SseEvent) {
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
    case 'WAITING_CODE':
      // 进入编码环节：展示题目并跳转编码页
      messages.value.push({
        role: 'assistant',
        content: '【编程题】' + event.data,
        timestamp: new Date().toLocaleTimeString()
      });
      setTimeout(() => {
        router.push({ name: 'CodingRoom', query: { sessionId: sessionId.value, question: event.data } });
      }, 800);
      break;
    case 'FOLLOW_UP':
      messages.value.push({
        role: 'assistant',
        content: event.data,
        timestamp: new Date().toLocaleTimeString()
      });
      thinking.value = false;
      break;
    case 'CODE_SUBMITTED':
      messages.value.push({
        role: 'assistant',
        content: '代码已提交，评估中...',
        timestamp: new Date().toLocaleTimeString()
      });
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
}

function startInterview() {
  const existingSessionId = route.query.sessionId as string | undefined;

  // 如果是从编码页返回且携带了题目数据，先展示
  const incomingQuestion = route.query.question as string | undefined;

  // 已有会话（编码环节返回/刷新页面）：重连 SSE 流而非新建会话
  if (existingSessionId) {
    sessionId.value = existingSessionId;
    connected.value = true;
    // 如果有传入的题目，作为第一条消息
    if (incomingQuestion) {
      messages.value.push({
        role: 'assistant',
        content: incomingQuestion,
        timestamp: new Date().toLocaleTimeString()
      });
    }
    loadHistory(existingSessionId);
    sseClient = new SseClient();
    sseClient.connectGet(`/api/interviews/${existingSessionId}/stream`, handleSseEvent, () => {
      error.value = '连接失败';
      thinking.value = false;
    });
    return;
  }

  const body: any = {
    resumeId: route.query.resumeId ? Number(route.query.resumeId) : null,
    jdId: route.query.jdId ? Number(route.query.jdId) : null,
    direction: route.query.direction || null,
    persona: route.query.persona || 'neutral',
    durationMinutes: Number(route.query.durationMinutes) || 30
  };

  sseClient = new SseClient();
  sseClient.connect('/api/interviews/start', body, handleSseEvent, () => {
    error.value = '连接失败';
    thinking.value = false;
  });
}

// 重连时拉取历史轮次，恢复对话上下文
async function loadHistory(id: string) {
  try {
    const res = await fetch(`/api/interviews/sessions/${id}/rounds`, {
      headers: { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` }
    });
    const json = await res.json();
    const rounds: any[] = json.data || [];
    const history: { role: 'assistant' | 'user'; content: string; timestamp: string }[] = [];
    for (const r of rounds) {
      if (r.question) {
        history.push({
          role: 'assistant',
          content: r.question,
          timestamp: new Date(r.createdAt || Date.now()).toLocaleTimeString()
        });
      }
      if (r.candidateAnswer) {
        history.push({ role: 'user', content: r.candidateAnswer, timestamp: '' });
      }
    }
    if (history.length > 0) messages.value = history;
  } catch {
    // 历史加载失败不影响面试流
  }
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

function goBack() {
  router.push('/home');
}

/* 美化：结束面试前增加确认，防止误触（确认后逻辑不变） */
function confirmEndInterview() {
  dialog.warning({
    title: '结束面试',
    content: '确定要结束当前面试吗？结束后将进入报告生成环节。',
    positiveText: '结束面试',
    negativeText: '继续面试',
    onPositiveClick: () => endInterview()
  });
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
  <!-- 美化：修复高度 bug（原 calc(100vh-64px) 减去了不存在的全局 header），改为 h-screen -->
  <div class="flex flex-col h-screen bg-slate-50">
    <!-- Header -->
    <!-- 美化：毛玻璃顶栏 + 状态指示灯 -->
    <header class="bg-white/80 backdrop-blur border-b border-slate-200/70 px-4 sm:px-6 py-3 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-4">
        <button @click="goBack"
          class="text-slate-400 hover:text-slate-600 text-sm flex items-center gap-1 whitespace-nowrap shrink-0 transition-colors duration-200">
          ← 返回
        </button>
        <h2 class="text-lg font-bold text-slate-800 tracking-tight whitespace-nowrap">面试进行中</h2>
      </div>
      <div class="flex items-center gap-3">
        <!-- 美化：连接状态呼吸灯 -->
        <span v-if="connected && !completed" class="flex items-center gap-1.5 text-sm text-green-600">
          <span class="relative flex w-2 h-2">
            <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
            <span class="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
          </span>
          已连接
        </span>
        <span v-if="thinking" class="text-sm text-amber-600">思考中...</span>
        <n-button v-if="!completed" size="small" type="error" secondary @click="confirmEndInterview">结束面试</n-button>
      </div>
    </header>

    <!-- Messages -->
    <div ref="messagesEl" class="flex-1 overflow-y-auto px-4 sm:px-6 py-6 space-y-4">
      <div v-if="messages.length === 0 && !error" class="text-center py-12 text-slate-400">
        {{ connected ? '等待第一道题目...' : '连接中...' }}
      </div>
      <div class="max-w-4xl mx-auto space-y-4">
        <ChatBubble v-for="(msg, i) in messages" :key="i" :role="msg.role" :content="msg.content" :timestamp="msg.timestamp" />
        <!-- 美化：思考中气泡 -->
        <div v-if="thinking" class="flex justify-start">
          <div class="bg-white border border-slate-200/80 rounded-2xl rounded-bl-md px-4 py-3 shadow-card">
            <div class="flex gap-1">
              <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:0ms"></span>
              <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:150ms"></span>
              <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:300ms"></span>
            </div>
          </div>
        </div>
        <n-alert v-if="error" type="error" :bordered="false" class="rounded-xl">{{ error }}</n-alert>
        <n-alert v-if="completed" type="success" :bordered="false" class="rounded-xl">面试已结束，报告生成中...</n-alert>
      </div>
    </div>

    <!-- Input -->
    <!-- 美化：输入区悬浮卡片化 -->
    <div class="bg-white/80 backdrop-blur border-t border-slate-200/70 px-4 sm:px-6 py-4 shrink-0">
      <div class="flex gap-3 max-w-4xl mx-auto items-end">
        <n-input v-model:value="answer" type="textarea" :disabled="!connected || completed"
          placeholder="输入你的回答...（Ctrl + Enter 发送）"
          :autosize="{ minRows: 2, maxRows: 6 }"
          class="flex-1"
          @keydown.ctrl.enter="submitAnswer" />
        <n-button type="primary" size="large" :disabled="!answer.trim() || !connected || completed"
          @click="submitAnswer">
          发送
        </n-button>
      </div>
    </div>
  </div>
</template>
