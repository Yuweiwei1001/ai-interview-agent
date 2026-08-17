<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NSelect, NButton, NSplit } from 'naive-ui';
import CodeEditor from '../components/CodeEditor.vue';
import BackButton from '../components/BackButton.vue';
import QuestionPanel from '../components/QuestionPanel.vue';
import CodingResultPanel from '../components/CodingResultPanel.vue';
import { runCode, submitCode, type TestRunResult, type TestCaseResult } from '../api/coding';
import { SseClient } from '../utils/sse';

const route = useRoute();
const router = useRouter();

const sessionId = route.query.sessionId as string;
const code = ref('// 请在此处编写代码\nimport java.util.*;\n\npublic class Solution {\n    public static void main(String[] args) {\n        System.out.println("Hello, Interview!");\n    }\n}');
const language = ref('java');
const output = ref('');
const testResults = ref<TestCaseResult[]>([]);
const passRate = ref<number | null>(null);
const running = ref(false);
const submitting = ref(false);
const question = ref((route.query.question as string) || '编程题');
const retryHint = ref('');
const errorMsg = ref('');
const sseConnected = ref(false);
const sseDisconnected = ref(false);
const questionCollapsed = ref(false);

const languageOptions = [
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' }
];

/* 编辑器 Tab 条文件名：随语言切换（力扣惯例；Java 侧与后端沙箱编译的 Solution.java 保持一致） */
const fileLabel = computed(() => (language.value === 'python' ? 'solution.py' : 'Solution.java'));

/* 窄屏（<768px）判定：纵向堆叠，不渲染 NSplit */
const mq = window.matchMedia('(max-width: 767px)');
const isNarrow = ref(mq.matches);
const onMqChange = (e: MediaQueryListEvent) => { isNarrow.value = e.matches; };

let sseClient: SseClient | null = null;

/* 解析题目事件负载：JSON {questionNumber, question, isFollowUp}，兼容旧版纯文本 */
function parseQuestionPayload(data: string): { question: string; questionNumber?: number } {
  try {
    const obj = JSON.parse(data);
    if (obj && typeof obj.question === 'string') {
      return { question: obj.question, questionNumber: typeof obj.questionNumber === 'number' ? obj.questionNumber : undefined };
    }
  } catch { /* 非 JSON，按纯文本处理 */ }
  return { question: data };
}

onMounted(() => {
  mq.addEventListener('change', onMqChange);
  if (sessionId) {
    sseClient = new SseClient();
    sseClient.connectGet(`/api/interviews/${sessionId}/stream`, (event) => {
      switch (event.event) {
        case 'WAITING_CODE': {
          // 编码页收到的 WAITING_CODE 只会是「代码未达标，挂起等待重试」的提示
          const { question } = parseQuestionPayload(event.data);
          retryHint.value = question;
          break;
        }
        case 'QUESTION': {
          // 回到文字面试环节，携带题目数据防止丢失
          const { question } = parseQuestionPayload(event.data);
          router.push({ name: 'InterviewRoom', query: { sessionId, question } });
          break;
        }
        case 'FOLLOW_UP':
          // 评估反馈在编码环节不展示
          break;
        case 'COMPLETE':
        case 'REPORT_READY':
          router.push(`/report/${sessionId}`);
          break;
        case 'ERROR':
          errorMsg.value = event.data || '面试流程出错';
          break;
      }
    }, () => {
      sseConnected.value = false;
      sseDisconnected.value = true;
      errorMsg.value = 'SSE 连接失败，正在尝试重连…若长时间无响应请返回重进';
    }, () => {
      // 活性信号：任意 chunk（含注释帧心跳）即视为连接正常
      sseConnected.value = true;
      sseDisconnected.value = false;
    });
  }
});

onUnmounted(() => {
  mq.removeEventListener('change', onMqChange);
  sseClient?.disconnect();
});

async function handleRun() {
  running.value = true;
  output.value = '';
  errorMsg.value = '';
  testResults.value = [];
  passRate.value = null;
  try {
    const res = await runCode(code.value, language.value);
    const data: TestRunResult = res.data.data;
    testResults.value = data.results || [];
    passRate.value = data.passRate;
    if (data.error) output.value = data.error;
  } catch (e: any) {
    output.value = e.response?.data?.msg || '运行失败';
    errorMsg.value = output.value;
  } finally {
    running.value = false;
  }
}

async function handleSubmit() {
  if (!sessionId) { output.value = '缺少会话 ID'; return; }
  submitting.value = true;
  output.value = '';
  retryHint.value = '';
  errorMsg.value = '';
  try {
    await submitCode(sessionId, code.value, language.value, question.value);
    // 提交成功后立即进入报告页：评估与报告在服务端异步生成，
    // 报告页展示"生成中"并轮询结果，用户可直接离开
    router.push(`/report/${sessionId}`);
  } catch (e: any) {
    output.value = e.response?.data?.msg || '提交失败';
    errorMsg.value = output.value;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col h-screen bg-slate-100">
    <!-- 顶栏 -->
    <header class="h-12 shrink-0 flex items-center gap-3 px-4 bg-white border-b border-slate-200">
      <BackButton to="/home" />
      <h2 class="text-base font-semibold text-slate-800 whitespace-nowrap">编程题</h2>
      <span class="text-xs px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-500 whitespace-nowrap">面试环节 3/3</span>
      <span class="ml-auto flex items-center gap-1.5 text-xs whitespace-nowrap"
        :class="sseConnected ? 'text-emerald-600' : 'text-red-500'">
        <span class="w-2 h-2 rounded-full" :class="sseConnected ? 'bg-emerald-500' : 'bg-red-500'"></span>
        {{ sseConnected ? '已连接' : (sseDisconnected ? '连接中断，重连中' : '连接中…') }}
      </span>
    </header>

    <!-- 桌面端：NSplit 左右分栏 -->
    <div v-if="!isNarrow" class="flex-1 min-h-0">
      <n-split direction="horizontal" :default-size="0.42" :min="0.25" :max="0.65" class="h-full">
        <template #1>
          <div class="h-full bg-white overflow-y-auto">
            <QuestionPanel :question="question" />
          </div>
        </template>
        <template #2>
          <div class="h-full min-w-0 flex flex-col bg-slate-900">
            <!-- 编辑器 Tab 条 -->
            <div class="h-10 shrink-0 flex items-center gap-2 px-3 bg-slate-800 text-slate-200">
              <span class="self-stretch flex items-center px-3 bg-slate-900 text-white font-mono text-xs">{{ fileLabel }}</span>
              <n-select v-model:value="language" :options="languageOptions" size="small" class="w-28" />
              <div class="ml-auto flex gap-2">
                <n-button size="small" :loading="running" @click="handleRun">▷ 运行</n-button>
                <n-button size="small" type="success" :loading="submitting" @click="handleSubmit">提交</n-button>
              </div>
            </div>
            <div class="flex-1 min-h-0">
              <CodeEditor v-model="code" :language="language" bare />
            </div>
            <CodingResultPanel :test-results="testResults" :pass-rate="passRate" :output="output"
              :retry-hint="retryHint" :error-msg="errorMsg" :running="running" />
          </div>
        </template>
      </n-split>
    </div>

    <!-- 窄屏：纵向堆叠 -->
    <div v-else class="flex-1 min-h-0 overflow-y-auto flex flex-col">
      <div class="bg-white border-b border-slate-200 shrink-0">
        <button @click="questionCollapsed = !questionCollapsed"
          class="w-full px-4 py-2.5 flex items-center justify-between text-sm text-slate-600 hover:bg-slate-50 transition-colors">
          <span class="font-semibold">题目</span>
          <span class="text-xs text-slate-400">{{ questionCollapsed ? '展开 ▶' : '收起 ▼' }}</span>
        </button>
        <div v-show="!questionCollapsed" class="max-h-64 overflow-y-auto border-t border-slate-100">
          <QuestionPanel :question="question" />
        </div>
      </div>
      <div class="h-[55vh] shrink-0 flex flex-col bg-slate-900">
        <div class="h-10 shrink-0 flex items-center gap-2 px-3 bg-slate-800 text-slate-200">
          <span class="self-stretch flex items-center px-3 bg-slate-900 text-white font-mono text-xs">{{ fileLabel }}</span>
          <n-select v-model:value="language" :options="languageOptions" size="small" class="w-28" />
          <div class="ml-auto flex gap-2">
            <n-button size="small" :loading="running" @click="handleRun">▷ 运行</n-button>
            <n-button size="small" type="success" :loading="submitting" @click="handleSubmit">提交</n-button>
          </div>
        </div>
        <div class="flex-1 min-h-0">
          <CodeEditor v-model="code" :language="language" bare />
        </div>
      </div>
      <CodingResultPanel :test-results="testResults" :pass-rate="passRate" :output="output"
        :retry-hint="retryHint" :error-msg="errorMsg" :running="running" />
    </div>
  </div>
</template>
