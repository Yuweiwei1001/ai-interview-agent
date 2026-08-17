# 编程页面力扣风格重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 CodingRoomView 重构为力扣经典双栏布局（左题目/右编辑器+底部结果面板），题目 markdown 结构化渲染，零新增依赖。

**Architecture:** 重写 CodingRoomView 为布局骨架（NSplit 分栏 + SSE + API 状态），抽出 QuestionPanel（题目解析渲染）与 CodingResultPanel（结果展示）两个子组件；CodeEditor 增加 bare prop 去边框。交互逻辑（SSE 事件分支、runCode/submitCode、路由跳转）逐一保留。

**Tech Stack:** Vue 3 `<script setup>` + TS、naive-ui（NSplit/NSelect/NButton/NAlert/NProgress）、marked、Monaco、Tailwind 4 + scoped CSS。

**Worktree:** `D:\IdeaProjects\ai-interview-agent-coding-redesign`（分支 `feature/coding-room-redesign`）。**所有命令均在 `D:\IdeaProjects\ai-interview-agent-coding-redesign\frontend` 下执行（git 命令在 worktree 根目录）。**

**Spec:** `docs/superpowers/specs/2026-08-17-coding-room-redesign-design.md`

**测试说明:** 项目无前端单测基础设施（spec 已确认），每个任务的验证 = `npx vue-tsc -b` 类型检查 + 最终手动端到端验证（Task 6）。

---

### Task 1: CodeEditor 增加 bare prop

**Files:**
- Modify: `frontend/src/components/CodeEditor.vue`

- [ ] **Step 1: 修改 props 与容器样式**

在 `<script setup>` 的 props 定义中加入 `bare`，并修改模板容器 class：

```vue
<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';

const props = defineProps<{
  modelValue: string;
  language?: string;
  readonly?: boolean;
  bare?: boolean;
}>();
```

（script 其余部分不变。）

模板容器改为动态 class：

```vue
<template>
  <!-- bare 模式：无外框装饰，用于贴合力扣式编辑器 Tab 条的一体化布局 -->
  <div ref="editorContainer" class="h-full w-full overflow-hidden"
    :class="bare ? '' : 'border border-slate-200 rounded-xl shadow-card'"></div>
</template>
```

- [ ] **Step 2: 类型检查**

Run: `npx vue-tsc -b`
Expected: 无错误退出（exit code 0）

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/CodeEditor.vue
git commit -m "feat(coding): CodeEditor 支持 bare 无边框模式"
```

---

### Task 2: 新增 QuestionPanel 组件

**Files:**
- Create: `frontend/src/components/QuestionPanel.vue`

- [ ] **Step 1: 编写组件完整代码**

```vue
<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';

const props = defineProps<{
  question: string;
}>();

/* ---------- 标题提取 ----------
 * 首行含「：」且分隔位 ≤ 20 → 冒号前为标题；
 * 首行形如「1. 两数之和」（≤30 字）→ 整行为标题；
 * 首行 ≤ 16 字 → 整行为标题；
 * 否则标题「编程题」，全部文本作为正文。 */
function splitTitle(raw: string): { title: string; body: string } {
  const text = raw.trim();
  if (!text) return { title: '编程题', body: '' };
  const nl = text.indexOf('\n');
  const firstLine = (nl === -1 ? text : text.slice(0, nl)).trim();
  const rest = nl === -1 ? '' : text.slice(nl + 1).trim();

  if (firstLine.length <= 40) {
    const colonIdx = firstLine.indexOf('：');
    if (colonIdx > 0 && colonIdx <= 20) {
      return { title: firstLine.slice(0, colonIdx).trim(), body: rest };
    }
    if (/^\d{1,3}[.、]\s*\S/.test(firstLine) && firstLine.length <= 30) {
      return { title: firstLine.replace('、', '.'), body: rest };
    }
    if (firstLine.length <= 16) {
      return { title: firstLine, body: rest };
    }
  }
  return { title: '编程题', body: text };
}

/* ---------- 结构化解析 ----------
 * 「示例 N」→ 示例卡片；「输入：/输出：/解释：」等 → 卡片内键值行；
 * 「提示/约束/数据范围/进阶」→ 小节；其余原样交给 marked。 */
interface IoLine { key: string; value: string }
type Block =
  | { kind: 'md'; html: string }
  | { kind: 'sample'; title: string; lines: IoLine[] }
  | { kind: 'sub'; title: string; html: string };

const SAMPLE_HEAD = /^(?:示例\s*\d*|example\s*\d*)\s*[：:]?$/i;
const IO_LINE = /^(输入|输出|解释|说明|返回|结果)\s*[：:]\s*(.+)$/;
const SUB_HEAD = /^(提示|约束|数据范围|进阶|注意|要求)\s*[：:]?$/;

function mdToHtml(lines: string[]): string {
  return marked.parse(lines.join('\n'), { breaks: true, gfm: true }) as string;
}

function parseBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let md: string[] = [];
  let sample: { title: string; lines: IoLine[] } | null = null;
  let sub: { title: string; lines: string[] } | null = null;

  const flushMd = () => {
    if (md.length) { blocks.push({ kind: 'md', html: mdToHtml(md) }); md = []; }
  };
  const flushSample = () => {
    if (sample && sample.lines.length) blocks.push({ kind: 'sample', title: sample.title, lines: sample.lines });
    sample = null;
  };
  const flushSub = () => {
    if (sub) blocks.push({ kind: 'sub', title: sub.title, html: mdToHtml(sub.lines) });
    sub = null;
  };

  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (SAMPLE_HEAD.test(line)) {
      flushMd(); flushSub(); flushSample();
      sample = { title: line.replace(/[：:]$/, ''), lines: [] };
      continue;
    }
    const io = line.match(IO_LINE);
    if (io) {
      flushMd(); flushSub();
      if (!sample) sample = { title: '', lines: [] };
      sample.lines.push({ key: io[1], value: io[2] });
      continue;
    }
    if (SUB_HEAD.test(line)) {
      flushMd(); flushSample();
      sub = { title: line.replace(/[：:]$/, ''), lines: [] };
      continue;
    }
    if (sub) {
      if (line === '') flushSub();
      else sub.lines.push(rawLine);
      continue;
    }
    if (sample) {
      if (line === '') { flushSample(); continue; }
      sample.lines.push({ key: '', value: line });
      continue;
    }
    md.push(rawLine);
  }
  flushMd(); flushSample(); flushSub();
  return blocks;
}

const parsed = computed(() => {
  const { title, body } = splitTitle(props.question);
  return { title, blocks: parseBlocks(body) };
});
</script>

<template>
  <div class="question-panel">
    <h3 class="q-title">{{ parsed.title }}</h3>
    <template v-for="(b, i) in parsed.blocks" :key="i">
      <div v-if="b.kind === 'md'" class="q-md" v-html="b.html"></div>
      <div v-else-if="b.kind === 'sample'" class="q-sample">
        <p v-if="b.title" class="q-sample-title">{{ b.title }}</p>
        <div v-for="(l, j) in b.lines" :key="j" class="q-io">
          <span v-if="l.key" class="q-io-key">{{ l.key }}：</span>
          <pre class="q-io-val">{{ l.value }}</pre>
        </div>
      </div>
      <div v-else class="q-sub">
        <h4 class="q-sub-title">{{ b.title }}</h4>
        <div class="q-md" v-html="b.html"></div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.question-panel { padding: 16px 20px 24px; font-size: 14px; color: #334155; line-height: 1.75; }
.q-title { font-size: 16px; font-weight: 600; color: #1e293b; margin-bottom: 12px; }
.q-md :deep(p) { margin-bottom: 10px; }
.q-md :deep(p:last-child) { margin-bottom: 0; }
.q-md :deep(code) { background: #f1f5f9; border-radius: 4px; padding: 1px 5px; font-size: 13px; color: #be185d; }
.q-md :deep(pre) { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; overflow-x: auto; font-size: 13px; }
.q-md :deep(ul), .q-md :deep(ol) { padding-left: 20px; margin-bottom: 10px; }
.q-md :deep(strong) { color: #1e293b; }
.q-sample { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; margin: 6px 0 12px; }
.q-sample-title { font-weight: 600; color: #1e293b; margin-bottom: 4px; font-size: 13px; }
.q-io { display: flex; gap: 8px; align-items: baseline; }
.q-io-key { flex: none; color: #475569; font-weight: 600; font-size: 13px; }
.q-io-val { margin: 0; font-family: Consolas, 'Courier New', monospace; font-size: 13px; color: #0f172a; white-space: pre-wrap; word-break: break-all; }
.q-sub-title { font-weight: 600; color: #1e293b; margin: 14px 0 6px; font-size: 14px; }
</style>
```

- [ ] **Step 2: 类型检查**

Run: `npx vue-tsc -b`
Expected: 无错误（v-html 类型、marked.parse 返回 string 断言均无告警）

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/QuestionPanel.vue
git commit -m "feat(coding): 新增 QuestionPanel 题目结构化渲染组件"
```

---

### Task 3: 新增 CodingResultPanel 组件

**Files:**
- Create: `frontend/src/components/CodingResultPanel.vue`

- [ ] **Step 1: 编写组件完整代码**

```vue
<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { NAlert, NProgress } from 'naive-ui';
import type { TestCaseResult } from '../api/coding';

const props = defineProps<{
  testResults: TestCaseResult[];
  passRate: number | null;
  output: string;
  retryHint: string;
  errorMsg: string;
  running: boolean;
}>();

const expanded = ref(false);
const activeTab = ref<'tests' | 'console'>('tests');

/* 默认折叠；运行发起 / 重试提示 / 错误出现时自动展开 */
watch(() => props.running, (v) => { if (v) expanded.value = true; });
watch(() => props.retryHint, (v) => { if (v) expanded.value = true; });
watch(() => props.errorMsg, (v) => { if (v) expanded.value = true; });

const passedCount = computed(() => props.testResults.filter(t => t.passed).length);
const allPassed = computed(() => props.testResults.length > 0 && passedCount.value === props.testResults.length);
const statusText = computed(() => {
  if (props.testResults.length === 0) return '';
  const rate = props.passRate !== null ? ` · 通过率 ${Math.round(props.passRate)}%` : '';
  return `通过 ${passedCount.value}/${props.testResults.length}${rate}`;
});
</script>

<template>
  <div class="shrink-0 border-t border-slate-200 bg-white">
    <!-- 折叠条 -->
    <button class="w-full h-9 px-4 flex items-center gap-2 text-xs text-slate-500 hover:bg-slate-50 transition-colors"
      @click="expanded = !expanded">
      <span>{{ expanded ? '▾' : '▸' }} 执行结果</span>
      <span v-if="testResults.length > 0" class="font-medium"
        :class="allPassed ? 'text-emerald-600' : 'text-red-500'">
        {{ allPassed ? '✓' : '✗' }} {{ statusText }}
      </span>
      <span v-else-if="running" class="text-slate-400">运行中…</span>
    </button>

    <!-- 展开内容 -->
    <div v-show="expanded">
      <n-alert v-if="retryHint" type="warning" title="代码未通过评估，请修改后重新提交"
        :bordered="false" class="mx-4 mb-2 rounded-lg">
        {{ retryHint }}
      </n-alert>
      <n-alert v-if="errorMsg" type="error" :bordered="false" class="mx-4 mb-2 rounded-lg">{{ errorMsg }}</n-alert>

      <div class="px-4 flex items-center gap-1 border-b border-slate-100">
        <button class="tab-btn" :class="{ 'tab-on': activeTab === 'tests' }" @click="activeTab = 'tests'">测试结果</button>
        <button class="tab-btn" :class="{ 'tab-on': activeTab === 'console' }" @click="activeTab = 'console'">控制台</button>
      </div>

      <div class="max-h-56 overflow-y-auto p-4">
        <template v-if="activeTab === 'tests'">
          <div v-if="passRate !== null" class="mb-3">
            <n-progress type="line" :percentage="Math.min(passRate, 100)"
              :status="passRate >= 60 ? 'success' : 'error'" :height="8" border-radius="4px" />
          </div>
          <div v-if="testResults.length === 0" class="text-xs text-slate-400 py-4 text-center">
            点击「▷ 运行」查看测试结果
          </div>
          <div v-else class="space-y-2">
            <div v-for="(tr, i) in testResults" :key="i"
              class="flex items-start gap-2 p-2.5 rounded-lg border text-sm"
              :class="tr.passed ? 'bg-emerald-50/60 border-emerald-100' : 'bg-red-50/60 border-red-100'">
              <span class="leading-none mt-1" :class="tr.passed ? 'text-emerald-600' : 'text-red-600'">
                {{ tr.passed ? '✓' : '✗' }}
              </span>
              <div class="min-w-0">
                <p class="font-medium" :class="tr.passed ? 'text-emerald-800' : 'text-red-800'">
                  {{ tr.name }}
                  <span v-if="tr.source === 'dynamic'"
                    class="ml-1 text-[10px] px-1.5 py-0.5 rounded-full bg-purple-100 text-purple-600 align-middle">动态</span>
                </p>
                <p class="text-xs text-slate-500 break-words mt-0.5">{{ tr.detail }}</p>
              </div>
            </div>
          </div>
        </template>
        <template v-else>
          <pre class="console-pre">{{ output || '暂无输出' }}</pre>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab-btn { padding: 6px 14px; font-size: 13px; color: #64748b; border-radius: 6px 6px 0 0; }
.tab-btn.tab-on { color: #2563eb; font-weight: 600; background: #eff6ff; }
.console-pre { background: #0f172a; color: #4ade80; border-radius: 8px; padding: 10px 12px; font-size: 13px; font-family: Consolas, monospace; min-height: 60px; white-space: pre-wrap; word-break: break-all; margin: 0; }
</style>
```

- [ ] **Step 2: 类型检查**

Run: `npx vue-tsc -b`
Expected: 无错误（TestCaseResult 从 api/coding.ts 导入，字段 name/passed/detail/source 均存在）

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/CodingResultPanel.vue
git commit -m "feat(coding): 新增 CodingResultPanel 折叠式结果面板组件"
```

---

### Task 4: 重写 CodingRoomView

**Files:**
- Modify: `frontend/src/views/CodingRoomView.vue`（整文件替换）

- [ ] **Step 1: 用以下完整代码替换 CodingRoomView.vue 全文**

```vue
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NSelect, NButton, NSplit } from 'naive-ui';
import CodeEditor from '../components/CodeEditor.vue';
import BackButton from '../components/BackButton.vue';
import QuestionPanel from '../components/QuestionPanel.vue';
import CodingResultPanel from '../components/CodingResultPanel.vue';
import { runCode, submitCode, type TestRunResult } from '../api/coding';
import { SseClient } from '../utils/sse';

const route = useRoute();
const router = useRouter();

const sessionId = route.query.sessionId as string;
const code = ref('// 请在此处编写代码\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, Interview!");\n    }\n}');
const language = ref('java');
const output = ref('');
const testResults = ref<{ name: string; passed: boolean; detail: string; source?: string }[]>([]);
const passRate = ref<number | null>(null);
const running = ref(false);
const submitting = ref(false);
const question = ref((route.query.question as string) || '编程题');
const retryHint = ref('');
const errorMsg = ref('');
const sseConnected = ref(false);
const questionCollapsed = ref(false);

const languageOptions = [
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' }
];

/* 编辑器 Tab 条文件名：随语言切换（力扣惯例） */
const fileLabel = computed(() => (language.value === 'python' ? 'main.py' : 'Main.java'));

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
      sseConnected.value = true; // 收到事件即视为连接正常
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
      errorMsg.value = 'SSE 连接失败，请返回重进';
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
        {{ sseConnected ? '已连接' : '未连接' }}
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
```

**与旧版的行为差异说明（均为 spec 批准项）：**
- 运行/提交的 catch 分支同时写 `output` 与 `errorMsg`（旧版只写 output，错误在右侧栏不可见；新版错误进结果面板警告条并自动展开）
- SSE 状态：收到事件 → `sseConnected=true`；onError → false（SseClient 无 onopen 回调，以事件到达视为已连接）
- 默认代码模板类名 `Solution` → `Main`（与文件名标签 Main.java 一致；后端沙箱不依赖类名，`runCode` 直接执行整段代码）

- [ ] **Step 2: 类型检查**

Run: `npx vue-tsc -b`
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/CodingRoomView.vue
git commit -m "feat(coding): 编程页重构为力扣经典双栏布局"
```

---

### Task 5: 全量构建验证

- [ ] **Step 1: 生产构建**

Run: `npm run build`
Expected: `vue-tsc -b && vite build` 均成功，无类型错误、无构建错误

- [ ] **Step 2: 如有报错则修复后重跑并提交**

```bash
git add -A
git commit -m "fix(coding): 构建问题修复"
```

（无报错则跳过本步提交）

---

### Task 6: 端到端手动验证

**Files:** 无代码改动，验证清单。

- [ ] **Step 1: 启动依赖服务**

按项目惯例启动：Docker Desktop → `docker start ai-interview-mysql ai-interview-redis`（沙箱镜像需 Docker daemon）→ 后端（IDEA 或 java -jar）→ 前端 dev server。

Run: `npm run dev`（frontend 目录）
Expected: vite 启动，代理指向运行中的后端

- [ ] **Step 2: 走通编程环节**

登录 testuser/test123456 → 发起面试 → 完成八股/项目环节进入编程页，逐项确认：
1. 桌面端：左右分栏、分隔条可拖拽（25%–65% 之间）
2. 题目面板：标题正确提取，示例渲染为卡片，正文可滚动
3. 文件名标签随语言切换 Main.java / main.py，Monaco 语法高亮跟随
4. 点击「▷ 运行」：结果面板自动展开，通过率与用例列表正确；「控制台」Tab 可查看输出
5. 故意提交差代码触发 WAITING_CODE 重试：面板自动展开显示警告条
6. 修改后重新提交成功：跳转报告页
7. 浏览器 DevTools 切窄屏（<768px）：题目手风琴、编辑器 55vh、结果面板纵向堆叠可用
8. Console 无报错

Expected: 全部通过

- [ ] **Step 3: 完成验证后按用户工作流合入 master**

（此步需用户确认后执行：merge to master → `git worktree remove` 清理）

---

## Self-Review 记录

- Spec 覆盖：布局/题目渲染/结果面板/响应式/组件划分/不变项/验收标准均有对应 Task ✓
- 占位符扫描：无 TBD/TODO，所有代码完整 ✓
- 类型一致性：TestCaseResult（api/coding.ts 现有接口）在 Task 3 导入使用；props 命名 kebab-case 传递（`:retry-hint` ↔ `retryHint`）✓
- 已知偏离 spec 的细化：SSE 状态以事件到达判定（SseClient 无 onopen）；catch 分支补写 errorMsg —— 均已在 Task 4 说明
