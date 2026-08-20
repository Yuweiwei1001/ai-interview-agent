<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NInput, NButton, NAlert, useDialog } from 'naive-ui';
import { SseClient, type SseEvent } from '../utils/sse';
import { useVoiceInterview, type AsrCorrection, type CorrectionItem } from '../utils/voice';
import { getSession } from '../api/interview';
import { toDate } from '../utils/datetime';
import ChatBubble from '../components/ChatBubble.vue';
import BackButton from '../components/BackButton.vue';

interface Message {
  role: 'assistant' | 'user';
  content: string;
  timestamp: string;
  questionNumber?: number;
  isFollowUp?: boolean;
  isCoding?: boolean;
}

const route = useRoute();
const router = useRouter();
const dialog = useDialog();

const messages = ref<Message[]>([]);
const answer = ref('');
const sessionId = ref('');
const connected = ref(false);
const completed = ref(false);
const thinking = ref(false);
const error = ref('');
const reportReady = ref(false);
/* 回顾模式：进入时会话已结束（completed/interrupted），不计时不连 SSE，仅展示历史问答 */
const isReview = ref(false);
const completeReason = ref('面试已结束');
/* 流式题目（打字机效果）：QUESTION_DELTA 逐块累积，完整 QUESTION 到达后清空 */
const streamingContent = ref('');
const isStreaming = ref(false);

/* 消息容器引用，用于新消息自动滚动到底部 */
const messagesEl = ref<HTMLElement>();

/* 面试计时器：从页面挂载起算，结束时定格 */
const pageMountTime = ref(Date.now());
const now = ref(Date.now());
let timerInterval: ReturnType<typeof setInterval> | null = null;

const elapsedSeconds = computed(() => {
  const end = completed.value ? pageMountTime.value : now.value;
  const elapsed = Math.max(0, Math.floor((end - pageMountTime.value) / 1000));
  const m = String(Math.floor(elapsed / 60)).padStart(2, '0');
  const s = String(elapsed % 60).padStart(2, '0');
  return `${m}:${s}`;
});

/* 当前进度：已出现的最大题号 */
const currentQuestionNumber = computed(() =>
  messages.value.reduce((max, m) => Math.max(max, m.questionNumber || 0), 0)
);

/* 语音面试模式：启动参数 phase=VOICE（或恢复会话时 DB phase=VOICE）。
   语音仅作输入/输出传输层：ASR 字幕累积为可编辑草稿，发送仍走 REST /answer */
const isVoiceMode = ref(route.query.phase === 'VOICE');
const partialSubtitle = ref('');
const {
  connected: voiceConnected,
  asrReady,
  micActive,
  speaking: interviewerSpeaking,
  error: voiceError,
  connect: connectVoiceChannel,
  disconnect: disconnectVoiceChannel,
} = useVoiceInterview();

function connectVoice(id: string) {
  if (!isVoiceMode.value || isReview.value) return;
  connectVoiceChannel(id, {
    onFinal: (text, seq, suspect) => {
      // 定稿字幕追加为回答草稿（可编辑，手动发送）；seq 记录句级状态供纠错补发对齐
      partialSubtitle.value = '';
      if (seq >= 0) {
        draftSentences.set(seq, { text, edited: false });
      }
      if (suspect) suspectCount.value += 1;
      answer.value = answer.value ? answer.value.replace(/[，。\s]*$/, '') + '，' + text : text;
    },
    onPartial: (text) => { partialSubtitle.value = text; },
    onCorrection: (correction) => handleAsrCorrection(correction),
  });
}

/* 语音草稿句级状态（ASR 热词纠错方案 4.4.3）：seq → 句子文本 + 是否已被候选人手动编辑。
   correction 异步补发（P95 < 2s）而候选人随时在编辑草稿，按竞态三规则处理：
   ① 未触碰句：high 置信自动替换 + 计数，low 悬浮候选点选；
   ② 已手动编辑句：跳过自动替换，仅提示“有可用纠错建议”（人工输入永远优先）；
   ③ 已提交/不存在句：直接丢弃 */
interface DraftSentence {
  text: string;
  edited: boolean;
}
let draftSentences = new Map<number, DraftSentence>();
/* 已自动应用的纠错数（high 置信替换计数） */
const correctedCount = ref(0);
/* 待处理纠错候选（low 置信候选 + 已编辑句的建议，点选后才应用） */
const pendingSuggestions = ref<CorrectionItem[]>([]);
/* 疑似 corpus 幻觉的 final 计数（弱化展示，提示候选人核对） */
const suspectCount = ref(0);

function handleAsrCorrection(correction: AsrCorrection) {
  // 规则 ③：所在草稿已提交（句子已清理）→ 直接丢弃
  const sentence = draftSentences.get(correction.seq);
  if (!sentence) return;
  const highs = correction.corrections.filter(c => c.confidence === 'high');
  const lows = correction.corrections.filter(c => c.confidence === 'low');
  // 规则 ②：已被候选人手动编辑 → 不自动替换，仅提示（点选后才应用）
  if (sentence.edited) {
    if (correction.corrections.length > 0) {
      pendingSuggestions.value.push(...correction.corrections);
    }
    return;
  }
  // 规则 ①：未被触碰 → high 自动替换草稿中该句 + 计数；low 悬浮候选点选
  if (highs.length > 0 && correction.text) {
    applySentenceCorrection(sentence.text, correction.text);
    sentence.text = correction.text;
    correctedCount.value += highs.length;
  }
  if (lows.length > 0) {
    pendingSuggestions.value.push(...lows);
  }
}

/* 替换草稿中的整句文本（首次出现；句子已被用户改到找不到时保守跳过） */
function applySentenceCorrection(oldText: string, newText: string) {
  if (!oldText || !answer.value.includes(oldText)) return;
  answer.value = answer.value.replace(oldText, newText);
}

/* 应用/忽略一条候选建议（low 置信或已编辑句建议，点选后应用） */
function applySuggestion(index: number) {
  const s = pendingSuggestions.value[index];
  if (!s) return;
  if (s.from && answer.value.includes(s.from)) {
    answer.value = answer.value.replace(s.from, s.to);
    correctedCount.value += 1;
  }
  pendingSuggestions.value.splice(index, 1);
}

function dismissSuggestion(index: number) {
  pendingSuggestions.value.splice(index, 1);
}

/* 手动输入（仅用户键入触发，程序追加不触发 input 事件）：人工输入永远优先于机器修正，
   全部未提交句子标记为已编辑，后续 correction 不再自动替换 */
function handleAnswerInput() {
  if (!isVoiceMode.value) return;
  draftSentences.forEach(s => { s.edited = true; });
}

let sseClient: SseClient | null = null;

/* 轮询兜底：SSE 事件被静默吞掉时（连接看似存活但事件丢失），
   通过周期查询会话状态恢复下一题/状态流转，避免面试卡死 */
let pollTimer: ReturnType<typeof setInterval> | null = null;

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

async function pollSessionStatus() {
  if (!sessionId.value) return;
  try {
    const res = await getSession(sessionId.value);
    const s = res.data.data;
    if (!s) return;

    if (s.status === 'waiting_code') {
      stopPolling();
      if (s.currentQuestion) {
        router.push({ name: 'CodingRoom', query: { sessionId: sessionId.value, question: s.currentQuestion } });
      }
      return;
    }
    if (s.status === 'completed' || s.status === 'interrupted') {
      // 面试已结束：停止轮询；若已生成报告则展示报告入口，否则提示已结束
      stopPolling();
      completed.value = true;
      thinking.value = false;
      if (s.report) reportReady.value = true;
      if (s.status === 'interrupted') completeReason.value = '面试已结束';
      return;
    }

    // 进行中：检测是否有新题目就绪但 SSE 事件丢失
    // 若当前有流式输出进行中（QUESTION_DELTA 正在推送），跳过轮询避免重复推送
    if (isStreaming.value) return;
    if (s.currentQuestion) {
      const lastAssistant = [...messages.value].reverse().find(m => m.role === 'assistant');
      if (!lastAssistant || lastAssistant.content !== s.currentQuestion) {
        messages.value.push({
          role: 'assistant',
          content: s.currentQuestion,
          questionNumber: currentQuestionNumber.value + 1,
          timestamp: new Date().toLocaleTimeString()
        });
        thinking.value = false;
      }
    }
  } catch {
    /* 单次轮询失败静默，等待下一轮 */
  }
}

function startPolling() {
  stopPolling();
  pollTimer = setInterval(pollSessionStatus, 5000);
}

onMounted(() => {
  if (!isReview.value) {
    timerInterval = setInterval(() => { now.value = Date.now(); }, 1000);
  }
  startInterview();
});

onUnmounted(() => {
  sseClient?.disconnect();
  disconnectVoiceChannel();
  stopPolling();
  if (timerInterval) clearInterval(timerInterval);
});

/* 新消息到达自动滚动到底部 */
watch(() => messages.value.length, async () => {
  await nextTick();
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
  }
});

/* 解析题目事件负载：JSON {questionNumber, question, isFollowUp}，兼容旧版纯文本 */
function parseQuestionPayload(data: string): { question: string; questionNumber?: number; isFollowUp?: boolean } {
  try {
    const obj = JSON.parse(data);
    if (obj && typeof obj.question === 'string') {
      return {
        question: obj.question,
        questionNumber: typeof obj.questionNumber === 'number' ? obj.questionNumber : undefined,
        isFollowUp: obj.isFollowUp === true
      };
    }
  } catch { /* 非 JSON，按纯文本处理 */ }
  return { question: data };
}

function handleSseEvent(event: SseEvent) {
  switch (event.event) {
    case 'CONNECTED':
      sessionId.value = event.data;
      connected.value = true;
      startPolling();
      connectVoice(event.data);
      break;
    case 'QUESTION_DELTA': {
      // 打字机增量：逐块追加到流式内容
      try {
        const { delta } = JSON.parse(event.data);
        if (delta) {
          streamingContent.value += delta;
          isStreaming.value = true;
        }
      } catch { /* 忽略解析失败 */ }
      break;
    }
    case 'QUESTION': {
      // 打字机结束：清空流式内容，收起流式区域
      streamingContent.value = '';
      isStreaming.value = false;
      const { question, questionNumber } = parseQuestionPayload(event.data);
      messages.value.push({
        role: 'assistant',
        content: question,
        questionNumber,
        timestamp: new Date().toLocaleTimeString()
      });
      thinking.value = false;
      break;
    }
    case 'THINKING':
      thinking.value = true;
      break;
    case 'WAITING_CODE': {
      // 进入编码环节：展示题目并跳转编码页
      const { question, questionNumber } = parseQuestionPayload(event.data);
      messages.value.push({
        role: 'assistant',
        content: question,
        questionNumber,
        isCoding: true,
        timestamp: new Date().toLocaleTimeString()
      });
      setTimeout(() => {
        router.push({ name: 'CodingRoom', query: { sessionId: sessionId.value, question } });
      }, 800);
      break;
    }
    case 'FOLLOW_UP': {
      // 打字机结束：清空流式内容
      streamingContent.value = '';
      isStreaming.value = false;
      const { question, questionNumber, isFollowUp } = parseQuestionPayload(event.data);
      messages.value.push({
        role: 'assistant',
        content: question,
        questionNumber,
        isFollowUp,
        timestamp: new Date().toLocaleTimeString()
      });
      thinking.value = false;
      break;
    }
    case 'CODE_SUBMITTED':
      messages.value.push({
        role: 'assistant',
        content: '代码已提交，评估中...',
        timestamp: new Date().toLocaleTimeString()
      });
      break;
    case 'COMPLETE': {
      completed.value = true;
      thinking.value = false;
      if (event.data && event.data.trim()) {
        completeReason.value = event.data.trim();
      }
      break;
    }
    case 'REPORT_READY':
      reportReady.value = true;
      break;
    case 'ERROR':
      error.value = event.data;
      thinking.value = false;
      break;
    case 'ANSWER_TIMEOUT':
      // 单题等待超时：后端已自动结束面试并生成报告，展示完成态（后续 COMPLETE 会补充结束原因）
      completed.value = true;
      thinking.value = false;
      completeReason.value = event.data || '单题等待超时，面试已结束';
      break;
  }
}

function startInterview() {
  const existingSessionId = route.query.sessionId as string | undefined;

  // 如果是从编码页返回且携带了题目数据，先展示
  const incomingQuestion = route.query.question as string | undefined;

  // 已有会话（编码环节返回/刷新页面/回顾问答）：先查会话状态，已结束则进入回顾模式
  if (existingSessionId) {
    sessionId.value = existingSessionId;
    connected.value = true;
    getSession(existingSessionId)
      .then(res => {
        const s = res.data?.data;
        if (s && (s.status === 'completed' || s.status === 'interrupted')) {
          // 回顾模式：不计时、不轮询、不连 SSE，仅加载历史问答
          isReview.value = true;
          completed.value = true;
          if (s.report) reportReady.value = true;
          if (timerInterval) { clearInterval(timerInterval); timerInterval = null; }
        } else {
          // 恢复进行中的面试：按 DB phase 判定是否重连语音通道（刷新/编码页返回场景）
          isVoiceMode.value = s?.phase === 'VOICE';
          startPolling();
          sseClient = new SseClient();
          sseClient.connectGet(`/api/interviews/${existingSessionId}/stream`, handleSseEvent, () => {
            error.value = '连接失败';
            thinking.value = false;
          });
          connectVoice(existingSessionId);
        }
      })
      .catch(() => {
        startPolling();
      });
    loadHistory(existingSessionId).finally(() => {
      // 历史恢复后再追加传入的当前题目，避免被历史覆盖
      if (incomingQuestion) {
        messages.value.push({
          role: 'assistant',
          content: incomingQuestion,
          timestamp: new Date().toLocaleTimeString()
        });
      }
    });
    return;
  }

  const body: any = {
    resumeId: route.query.resumeId ? Number(route.query.resumeId) : null,
    jdId: route.query.jdId ? Number(route.query.jdId) : null,
    direction: route.query.direction || null,
    persona: route.query.persona || 'neutral',
    durationMinutes: Number(route.query.durationMinutes) || 30,
    phase: isVoiceMode.value ? 'VOICE' : 'TEXT'
  };

  // 复用开始页预览生成的面试计划，保证实际出题与用户看到的计划一致；解析失败则回退由后端自行生成
  const pendingPlan = sessionStorage.getItem('pendingInterviewPlan');
  sessionStorage.removeItem('pendingInterviewPlan');
  if (pendingPlan) {
    try {
      const parsed = JSON.parse(pendingPlan);
      if (parsed && parsed.agentAssignments) body.plan = parsed;
    } catch { /* 忽略，后端会自行生成计划 */ }
  }

  sseClient = new SseClient();
  sseClient.connect('/api/interviews/start', body, handleSseEvent, () => {
    if (sessionId.value) {
      // 面试进行中 SSE 流断开（网络波动/代理断连等）：切 GET 重连通道继续收事件（指数退避自动重连），
      // 断连期间丢失的事件由轮询兜底（currentQuestion/会话状态）补偿，避免面试卡死
      sseClient?.connectGet(`/api/interviews/${sessionId.value}/stream`, handleSseEvent, () => {
        error.value = '连接失败';
        thinking.value = false;
      });
    } else {
      error.value = '连接失败';
      thinking.value = false;
    }
  });
}

// 重连时拉取历史轮次，恢复对话上下文（面试进行中每轮已增量落库，刷新不丢历史）
async function loadHistory(id: string) {
  try {
    const res = await fetch(`/api/interviews/sessions/${id}/rounds`, {
      headers: { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` }
    });
    const json = await res.json();
    const rounds: any[] = json.data || [];
    const history: Message[] = [];
    for (const r of rounds) {
      if (r.question) {
        history.push({
          role: 'assistant',
          content: r.question,
          questionNumber: r.roundNumber,
          isFollowUp: r.isFollowup === true,
          timestamp: (toDate(r.createdAt) || new Date()).toLocaleTimeString()
        });
      }
      if (r.candidateAnswer) {
        history.push({ role: 'user', content: r.candidateAnswer, timestamp: '' });
      }
    }
    if (history.length > 0) {
      messages.value = history;
      await nextTick();
      if (messagesEl.value) {
        messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
      }
    }
  } catch {
    // 历史加载失败不影响面试流
  }
}

function handleInputKeydown(e: KeyboardEvent) {
  // 输入法组词中（如中文拼音确认）不触发发送
  if (e.isComposing) return;
  // Shift + Enter 换行，保持默认行为
  if (e.shiftKey) return;
  // Enter 发送
  e.preventDefault();
  if (!connected.value || completed.value) return;
  submitAnswer();
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
  partialSubtitle.value = '';
  // 草稿已提交：句级状态清空，后续补发的 correction 按竞态规则③直接丢弃
  draftSentences.clear();
  pendingSuggestions.value = [];
  correctedCount.value = 0;
  suspectCount.value = 0;
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

/* 结束面试前增加确认，防止误触（确认后逻辑不变） */
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

function goToReport() {
  if (sessionId.value) {
    router.push(`/report/${sessionId.value}`);
  }
}
</script>

<template>
  <div class="flex flex-col h-screen bg-slate-50">
    <!-- Header：毛玻璃顶栏 + 计时器 + 进度 + 状态 -->
    <header class="bg-white/80 backdrop-blur border-b border-slate-200/70 px-4 sm:px-6 py-3 shrink-0">
      <div class="max-w-4xl mx-auto">
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-center gap-4 min-w-0">
            <!-- 美化：统一 BackButton 组件 -->
            <BackButton to="/home" />
            <h2 class="text-lg font-bold text-slate-800 tracking-tight whitespace-nowrap">{{ isReview ? '面试回顾' : '面试进行中' }}</h2>
            <span v-if="!isReview" class="inline-flex items-center gap-1 text-xs font-semibold text-blue-700 bg-blue-50 px-2.5 py-1 rounded-lg tabular-nums shrink-0">
              ⏱ {{ elapsedSeconds }}
            </span>
            <span v-if="currentQuestionNumber > 0" class="text-xs font-medium text-slate-500 shrink-0 hidden sm:inline">
              已完成 {{ currentQuestionNumber }} 题
            </span>
          </div>
          <div class="flex items-center gap-3 shrink-0">
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
        </div>
        <!-- 进度条 -->
        <div class="mt-3 h-1.5 bg-slate-100 rounded-full overflow-hidden">
          <div class="h-full bg-gradient-to-r from-blue-500 to-indigo-500 rounded-full transition-all duration-500 ease-out"
            :style="{ width: completed ? '100%' : '0%' }"></div>
        </div>
      </div>
    </header>

    <!-- Messages -->
    <div ref="messagesEl" class="flex-1 overflow-y-auto px-4 sm:px-6 py-6 space-y-4">
      <div v-if="messages.length === 0 && !error" class="text-center py-12 text-slate-400">
        {{ connected ? '等待第一道题目...' : '连接中...' }}
      </div>
      <div class="max-w-4xl mx-auto space-y-4">
        <ChatBubble v-for="(msg, i) in messages" :key="i" :role="msg.role" :content="msg.content"
          :timestamp="msg.timestamp" :question-number="msg.questionNumber" :is-follow-up="msg.isFollowUp"
          :is-coding="msg.isCoding" />

        <!-- 思考中气泡 -->
        <div v-if="thinking" class="flex justify-start">
          <div class="bg-white border border-slate-200/80 rounded-2xl rounded-bl-md px-4 py-3 shadow-card">
            <div class="flex gap-1">
              <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:0ms"></span>
              <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:150ms"></span>
              <span class="w-2 h-2 bg-slate-400 rounded-full animate-bounce" style="animation-delay:300ms"></span>
            </div>
          </div>
        </div>

        <!-- 流式题目（打字机效果）：QUESTION_DELTA 逐块累积，光标闪烁 -->
        <div v-if="isStreaming" class="flex justify-start">
          <div class="bg-white border border-slate-200/80 rounded-2xl rounded-bl-md px-4 py-3 shadow-card">
            <div class="text-sm leading-relaxed whitespace-pre-wrap break-words">{{ streamingContent }}<span class="inline-block w-0.5 h-4 bg-blue-500 align-middle ml-0.5 typing-cursor" /></div>
          </div>
        </div>

        <n-alert v-if="error" type="error" :bordered="false" class="rounded-xl">{{ error }}</n-alert>

        <!-- 面试完成卡片 -->
        <div v-if="completed" class="mt-4 text-center">
          <div class="inline-block bg-white border border-blue-100 rounded-2xl px-7 py-5 shadow-card">
            <div class="text-blue-700 text-lg font-bold mb-1">{{ isReview ? '📄 问答回顾' : '🎉 面试已结束' }}</div>
            <p class="text-sm text-slate-500">{{ isReview ? '以上为本次面试的完整问答记录' : completeReason }}</p>
            <div v-if="!reportReady" class="mt-3 flex items-center justify-center gap-2 text-sm text-slate-400">
              <span v-if="!isReview" class="w-3.5 h-3.5 border-2 border-blue-200 border-t-blue-500 rounded-full animate-spin"></span>
              {{ isReview ? '本次面试暂无可用报告' : '正在生成面试报告，请稍候…' }}
            </div>
            <n-button v-else type="primary" class="mt-3" @click="goToReport">
              查看面试报告 →
            </n-button>
          </div>
        </div>
      </div>
    </div>

    <!-- Input：回顾模式下隐藏回答输入区 -->
    <div v-if="!isReview" class="bg-white/80 backdrop-blur border-t border-slate-200/70 px-4 sm:px-6 py-4 shrink-0">
      <div class="max-w-4xl mx-auto">
        <!-- 语音模式状态条 -->
        <div v-if="isVoiceMode" class="mb-2 flex items-center gap-4 text-xs">
          <span class="flex items-center gap-1.5" :class="voiceConnected ? 'text-green-600' : 'text-slate-400'">
            <span class="w-1.5 h-1.5 rounded-full" :class="voiceConnected ? 'bg-green-500' : 'bg-slate-300'"></span>
            语音通道
          </span>
          <span class="flex items-center gap-1.5" :class="asrReady ? 'text-green-600' : 'text-amber-600'">
            <span class="w-1.5 h-1.5 rounded-full" :class="asrReady ? 'bg-green-500' : 'bg-amber-400'"></span>
            {{ asrReady ? '语音识别就绪' : '语音识别初始化中…' }}
          </span>
          <span v-if="micActive" class="text-slate-500">🎙 麦克风开启</span>
          <span v-if="interviewerSpeaking" class="text-blue-600 animate-pulse">🔊 面试官说话中…</span>
          <span v-if="voiceError" class="text-red-500">{{ voiceError }}</span>
        </div>
        <!-- 实时识别预览（partial 字幕，未定稿） -->
        <div v-if="isVoiceMode && partialSubtitle" class="mb-2 text-sm text-slate-500 italic truncate">
          识别中：{{ partialSubtitle }}
        </div>
        <!-- ASR 术语纠错提示条（方案 4.4.4）：自动修正计数 + 疑似幻觉弱化提示 + 候选点选 -->
        <div v-if="isVoiceMode && (correctedCount > 0 || suspectCount > 0 || pendingSuggestions.length > 0)"
          class="mb-2 flex flex-wrap items-center gap-2 text-xs">
          <span v-if="correctedCount > 0"
            class="inline-flex items-center gap-1 text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded-lg">
            ✅ 已自动修正 {{ correctedCount }} 处术语
          </span>
          <span v-if="suspectCount > 0"
            class="inline-flex items-center gap-1 text-amber-700 bg-amber-50 px-2 py-0.5 rounded-lg">
            ⚠️ 有 {{ suspectCount }} 句识别结果疑似异常，请核对后提交
          </span>
          <span v-for="(s, i) in pendingSuggestions" :key="`${s.from}-${i}`"
            class="inline-flex items-center gap-1.5 text-blue-700 bg-blue-50 px-2 py-0.5 rounded-lg">
            「{{ s.from }}」→「{{ s.to }}」?
            <button type="button" class="underline hover:text-blue-900" @click="applySuggestion(i)">应用</button>
            <button type="button" class="underline hover:text-slate-500" @click="dismissSuggestion(i)">忽略</button>
          </span>
        </div>
        <div class="flex gap-3 items-end">
          <n-input v-model:value="answer" type="textarea" :disabled="!connected || completed"
            :placeholder="isVoiceMode ? '开口说话自动转写为文字，可编辑后发送...（Enter 发送，Shift + Enter 换行）' : '输入你的回答...（Enter 发送，Shift + Enter 换行）'"
            :autosize="{ minRows: 2, maxRows: 6 }"
            class="flex-1"
            @input="handleAnswerInput"
            @keydown.enter="handleInputKeydown" />
          <n-button type="primary" size="large" :disabled="!answer.trim() || !connected || completed"
            @click="submitAnswer">
            发送
          </n-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.typing-cursor {
  animation: cursor-blink 0.8s infinite ease-in-out;
}

@keyframes cursor-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>
