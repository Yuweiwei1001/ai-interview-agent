<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import CodeEditor from '../components/CodeEditor.vue';
import { runCode, submitCode, type TestRunResult } from '../api/coding';
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

let sseClient: SseClient | null = null;

onMounted(() => {
  if (sessionId) {
    // 监听面试流：恢复后可能收到新题目或完成事件
    sseClient = new SseClient();
    sseClient.connectGet(`/api/interviews/${sessionId}/stream`, (event) => {
      switch (event.event) {
        case 'WAITING_CODE':
          question.value = event.data;
          break;
        case 'QUESTION':
          // 回到文字面试环节（携带 sessionId，InterviewRoom 将重连而非新建会话）
          router.push({ name: 'InterviewRoom', query: { sessionId, resumeId: '', jdId: '', direction: '', persona: 'neutral', durationMinutes: 30 } });
          break;
        case 'COMPLETE':
        case 'REPORT_READY':
          router.push(`/report/${sessionId}`);
          break;
      }
    });
  }
});

onUnmounted(() => sseClient?.disconnect());

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
  sseStatus.value = '代码已提交，评估中，请稍候...';
  try {
    await submitCode(sessionId, code.value, language.value, question.value);
  } catch (e: any) {
    output.value = e.response?.data?.msg || '提交失败';
    sseStatus.value = '';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col h-[calc(100vh-64px)]">
    <!-- Header -->
    <header class="bg-white shadow-sm px-6 py-3 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-4 min-w-0">
        <h2 class="text-lg font-bold text-slate-800 shrink-0">编程题</h2>
        <select v-model="language" class="px-3 py-1 border border-slate-300 rounded-lg text-sm">
          <option value="java">Java</option>
          <option value="python">Python</option>
        </select>
      </div>
      <div class="flex gap-3">
        <button @click="handleRun" :disabled="running"
          class="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors text-sm">
          {{ running ? '运行中...' : '运行' }}
        </button>
        <button @click="handleSubmit" :disabled="submitting"
          class="px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:opacity-50 transition-colors text-sm">
          {{ submitting ? '提交中...' : '提交' }}
        </button>
      </div>
    </header>

    <!-- 题目内容 -->
    <div class="bg-white border-b border-slate-200 px-6 py-3 shrink-0">
      <h3 class="text-sm font-semibold text-slate-700 mb-1">题目</h3>
      <p class="text-sm text-slate-600 whitespace-pre-wrap">{{ question }}</p>
    </div>

    <div class="flex-1 flex min-h-0">
      <!-- 编辑器区域 -->
      <div class="flex-1 p-4">
        <div class="h-full">
          <CodeEditor v-model="code" :language="language" />
        </div>
      </div>

      <!-- 结果面板 -->
      <div class="w-96 border-l border-slate-200 bg-white p-4 overflow-y-auto">
        <h3 class="font-bold text-slate-800 mb-3">运行结果</h3>

        <!-- 提交状态提示 -->
        <p v-if="sseStatus" class="text-sm text-blue-600 bg-blue-50 border border-blue-100 rounded-lg px-3 py-2 mb-4">
          ⏳ {{ sseStatus }}
        </p>

        <!-- 输出 -->
        <div class="mb-4">
          <h4 class="text-sm font-medium text-slate-600 mb-1">控制台输出</h4>
          <pre class="bg-slate-900 text-green-400 p-3 rounded-lg text-sm overflow-x-auto min-h-[60px]">{{ output || '点击"运行"查看输出' }}</pre>
        </div>

        <!-- 通过率 -->
        <div v-if="passRate !== null" class="mb-4">
          <h4 class="text-sm font-medium text-slate-600 mb-2">测试通过率</h4>
          <div class="flex items-center gap-2">
            <div class="flex-1 bg-slate-200 rounded-full h-2">
              <div class="h-2 rounded-full transition-all"
                :class="passRate >= 60 ? 'bg-green-500' : 'bg-red-500'"
                :style="{ width: Math.min(passRate, 100) + '%' }"></div>
            </div>
            <span class="text-sm font-bold" :class="passRate >= 60 ? 'text-green-600' : 'text-red-600'">
              {{ passRate }}%
            </span>
          </div>
        </div>

        <!-- 测试结果 -->
        <div v-if="testResults.length > 0">
          <h4 class="text-sm font-medium text-slate-600 mb-2">测试用例</h4>
          <div class="space-y-2">
            <div v-for="(tr, i) in testResults" :key="i"
              class="flex items-start gap-2 p-2 rounded-lg"
              :class="tr.passed ? 'bg-green-50' : 'bg-red-50'">
              <span class="text-lg leading-none mt-0.5" :class="tr.passed ? 'text-green-600' : 'text-red-600'">
                {{ tr.passed ? '✓' : '✗' }}
              </span>
              <div class="min-w-0">
                <p class="text-sm font-medium" :class="tr.passed ? 'text-green-800' : 'text-red-800'">
                  {{ tr.name }}
                  <span v-if="tr.source === 'dynamic'"
                    class="ml-1 text-[10px] px-1.5 py-0.5 rounded-full bg-purple-100 text-purple-600 align-middle">动态</span>
                </p>
                <p class="text-xs text-slate-500 break-words">{{ tr.detail }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
