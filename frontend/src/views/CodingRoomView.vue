<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NSelect, NButton, NAlert, NProgress } from 'naive-ui';
import CodeEditor from '../components/CodeEditor.vue';
import { runCode, submitCode, type TestRunResult } from '../api/coding';
import { getSession } from '../api/interview';
import { SseClient } from '../utils/sse';

const route = useRoute();
const router = useRouter();

const sessionId = route.query.sessionId as string;
const code = ref('// 请在此处编写代码\nimport java.util.*;\n\npublic class Solution {\n    public static void main(String[] args) {\n        System.out.println("Hello, Interview!");\n    }\n}');
const language = ref('java');
const output = ref('');
const testResults = ref<{ name: string; passed: boolean; detail: string; source?: string }[]>([]);
const passRate = ref<number | null>(null);
const running = ref(false);
const submitting = ref(false);
const question = ref((route.query.question as string) || '编程题');
const sseStatus = ref('');
const retryHint = ref('');
const errorMsg = ref('');
const questionCollapsed = ref(false);

/* 美化：语言下拉选项 */
const languageOptions = [
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' }
];

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

/* 提交后轮询兜底：SSE 事件若被中间层静默吞掉（曾致提交后永久卡在“评估中”），
   通过周期查询会话状态保证页面必然流转 */
let pollTimer: ReturnType<typeof setInterval> | null = null;
let pollCount = 0;

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

async function pollSessionStatus() {
  pollCount++;
  try {
    const res = await getSession(sessionId);
    const status = res.data.data?.status;
    if (status === 'completed') {
      stopPolling();
      router.push(`/report/${sessionId}`);
    } else if (status === 'waiting_code' && sseStatus.value) {
      // 评估未通过的提示事件未到达：退出“评估中”，允许修改后重新提交
      sseStatus.value = '';
      retryHint.value = retryHint.value || '代码未通过评估，请修改后重新提交';
      stopPolling();
    } else if (status === 'in_progress' && sseStatus.value && res.data.data?.currentQuestion) {
      // 评估通过且下一题已就绪，但 QUESTION 事件未到达（SSE 断流）：带题目回到面试间继续作答
      stopPolling();
      router.push({ name: 'InterviewRoom', query: { sessionId, question: res.data.data.currentQuestion } });
    } else if (status === 'in_progress' && sseStatus.value && pollCount >= 10) {
      // 约 40 秒仍在评估且无下一题信息：提示可重进（进度由 checkpoint 保存，不会丢失）
      errorMsg.value = '等待响应时间较长，可能是连接中断。可返回首页重新进入面试，进度不会丢失。';
    }
  } catch {
    /* 单次轮询失败静默，等待下一轮 */
  }
}

function startPolling() {
  stopPolling();
  pollCount = 0;
  pollTimer = setInterval(pollSessionStatus, 4000);
}

onMounted(() => {
  if (sessionId) {
    sseClient = new SseClient();
    sseClient.connectGet(`/api/interviews/${sessionId}/stream`, (event) => {
      switch (event.event) {
        case 'WAITING_CODE': {
          // 编码页收到的 WAITING_CODE 只会是「代码未达标，挂起等待重试」的提示
          const { question } = parseQuestionPayload(event.data);
          stopPolling();
          retryHint.value = question;
          sseStatus.value = '';
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
          sseStatus.value = '';
          break;
      }
    }, () => {
      errorMsg.value = 'SSE 连接失败，请返回重进';
      sseStatus.value = '';
    });
  }
});

onUnmounted(() => { sseClient?.disconnect(); stopPolling(); });

async function handleRun() {
  running.value = true;
  output.value = '';
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
  sseStatus.value = '代码已提交，评估中，请稍候...';
  try {
    await submitCode(sessionId, code.value, language.value, question.value);
    startPolling();
  } catch (e: any) {
    output.value = e.response?.data?.msg || '提交失败';
    sseStatus.value = '';
  } finally {
    submitting.value = false;
  }
}

function goBack() {
  router.push('/home');
}
</script>

<template>
  <!-- 美化：移动端允许页面滚动，桌面端锁定整屏 -->
  <div class="flex flex-col min-h-screen md:h-screen bg-slate-50">
    <!-- Header -->
    <!-- 美化：毛玻璃顶栏，窄屏自动换行 -->
    <header class="bg-white/80 backdrop-blur border-b border-slate-200/70 px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-2 shrink-0">
      <div class="flex items-center gap-4">
        <button @click="goBack"
          class="text-slate-400 hover:text-slate-600 text-sm flex items-center gap-1 whitespace-nowrap shrink-0 transition-colors duration-200">
          ← 返回
        </button>
        <h2 class="text-lg font-bold text-slate-800 tracking-tight whitespace-nowrap">编程题</h2>
        <n-select v-model:value="language" :options="languageOptions" size="small" class="w-28" />
      </div>
      <div class="flex gap-3">
        <n-button type="primary" secondary :loading="running" @click="handleRun">
          {{ running ? '运行中...' : '运行' }}
        </n-button>
        <n-button type="success" :loading="submitting" @click="handleSubmit">
          {{ submitting ? '提交中...' : '提交' }}
        </n-button>
      </div>
    </header>

    <!-- 题目内容（可折叠） -->
    <div class="bg-white border-b border-slate-200/70 shrink-0">
      <button @click="questionCollapsed = !questionCollapsed"
        class="w-full px-4 sm:px-6 py-2.5 flex items-center justify-between text-sm text-slate-500 hover:bg-slate-50 transition-colors duration-200">
        <span class="font-semibold text-slate-700">题目</span>
        <span class="text-xs">{{ questionCollapsed ? '展开 ▶' : '收起 ▼' }}</span>
      </button>
      <div v-show="!questionCollapsed" class="px-4 sm:px-6 pb-4 max-h-40 overflow-y-auto">
        <p class="text-sm text-slate-600 leading-relaxed whitespace-pre-wrap">{{ question }}</p>
      </div>
    </div>

    <!-- 美化：响应式双栏——桌面左右布局，窄屏纵向堆叠 -->
    <div class="flex-1 flex flex-col md:flex-row md:min-h-0">
      <!-- 编辑器区域 -->
      <div class="h-[55vh] md:h-auto md:flex-1 p-4 min-h-0">
        <div class="h-full">
          <CodeEditor v-model="code" :language="language" />
        </div>
      </div>

      <!-- 结果面板 -->
      <div class="w-full md:w-96 lg:w-[26rem] shrink-0 border-t md:border-t-0 md:border-l border-slate-200/70 bg-white p-4 sm:p-5 md:overflow-y-auto">
        <h3 class="font-bold text-slate-800 mb-4">运行结果</h3>

        <!-- 提交状态提示 -->
        <n-alert v-if="sseStatus" type="info" :bordered="false" class="rounded-xl mb-4">⏳ {{ sseStatus }}</n-alert>

        <!-- 代码未达标的重试提示 -->
        <n-alert v-if="retryHint" type="warning" title="代码未通过评估，请修改后重新提交" :bordered="false" class="rounded-xl mb-4">
          {{ retryHint }}
        </n-alert>

        <!-- 错误提示 -->
        <n-alert v-if="errorMsg" type="error" :bordered="false" class="rounded-xl mb-4">{{ errorMsg }}</n-alert>

        <!-- 输出 -->
        <div class="mb-5">
          <h4 class="text-sm font-medium text-slate-600 mb-2">控制台输出</h4>
          <pre class="bg-slate-900 text-green-400 p-3 rounded-xl text-sm overflow-x-auto min-h-[60px] max-h-[200px] overflow-y-auto leading-relaxed">{{ output || '点击"运行"查看输出' }}</pre>
        </div>

        <!-- 通过率 -->
        <div v-if="passRate !== null" class="mb-5">
          <h4 class="text-sm font-medium text-slate-600 mb-2">测试通过率</h4>
          <!-- 美化：通过率升级为 n-progress 组件 -->
          <n-progress type="line" :percentage="Math.min(passRate, 100)"
            :status="passRate >= 60 ? 'success' : 'error'" :height="10" border-radius="5px" />
        </div>

        <!-- 测试结果 -->
        <div v-if="testResults.length > 0">
          <h4 class="text-sm font-medium text-slate-600 mb-2">测试用例</h4>
          <div class="space-y-2">
            <!-- 美化：用例卡片统一描边 + 圆角 -->
            <div v-for="(tr, i) in testResults" :key="i"
              class="flex items-start gap-2 p-3 rounded-xl border"
              :class="tr.passed ? 'bg-green-50/70 border-green-100' : 'bg-red-50/70 border-red-100'">
              <span class="text-lg leading-none mt-0.5" :class="tr.passed ? 'text-green-600' : 'text-red-600'">
                {{ tr.passed ? '✓' : '✗' }}
              </span>
              <div class="min-w-0">
                <p class="text-sm font-medium" :class="tr.passed ? 'text-green-800' : 'text-red-800'">
                  {{ tr.name }}
                  <span v-if="tr.source === 'dynamic'"
                    class="ml-1 text-[10px] px-1.5 py-0.5 rounded-full bg-purple-100 text-purple-600 align-middle">动态</span>
                </p>
                <p class="text-xs text-slate-500 break-words mt-0.5">{{ tr.detail }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
