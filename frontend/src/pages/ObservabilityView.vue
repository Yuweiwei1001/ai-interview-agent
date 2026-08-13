<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue';
import { useRouter } from 'vue-router';
import { NDataTable, NDrawer, NDrawerContent, NSpin, NEmpty, NTag, type DataTableColumns } from 'naive-ui';
import {
  getTraceSessions, getTraces, getUsageSummary,
  type SessionTraceSummary, type LlmTrace, type UsageSummary
} from '../api/observability';
import { toDate } from '../utils/datetime';

const router = useRouter();
const loading = ref(true);
const summary = ref<UsageSummary | null>(null);
const sessions = ref<SessionTraceSummary[]>([]);
const drawerVisible = ref(false);
const drawerSessionId = ref('');
const tracesLoading = ref(false);
const traces = ref<LlmTrace[]>([]);

onMounted(async () => {
  try {
    const [sumRes, sesRes] = await Promise.all([getUsageSummary(7), getTraceSessions(50)]);
    summary.value = sumRes.data.data;
    sessions.value = sesRes.data.data || [];
  } catch {
    /* 加载失败保持空态 */
  } finally {
    loading.value = false;
  }
});

async function openTraces(sessionId: string) {
  drawerSessionId.value = sessionId;
  drawerVisible.value = true;
  tracesLoading.value = true;
  traces.value = [];
  try {
    const res = await getTraces(sessionId);
    traces.value = res.data.data || [];
  } catch {
    /* 保持空态 */
  } finally {
    tracesLoading.value = false;
  }
}

/* ---------- 展示辅助 ---------- */
function fmtCost(v: number | null | undefined): string {
  if (v == null) return '¥0.0000';
  return `¥${Number(v).toFixed(4)}`;
}
function fmtTime(t: string | null): string {
  const d = toDate(t);
  if (!d) return '-';
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}
function fmtDuration(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}
const errorRate = computed(() => {
  const s = summary.value;
  if (!s || s.callCount === 0) return '0%';
  return `${((s.errorCount / s.callCount) * 100).toFixed(1)}%`;
});

/* agent 中文名映射 */
const agentLabels: Record<string, string> = {
  plan: '计划生成', technical: '八股出题', project: '项目出题', coding: '编程出题',
  evaluator: '答案评估', followup: '追问生成', summarizer: '对话摘要',
  testcase: '测试用例', 'code-eval': '代码评估', unknown: '未知'
};
function agentLabel(agent: string | null): string {
  if (!agent) return '未归因';
  return agentLabels[agent] || agent;
}

/* ---------- 表格列定义 ---------- */
const agentColumns: DataTableColumns<any> = [
  { title: 'Agent', key: 'agent', render: row => agentLabel(row.agent) },
  { title: '调用次数', key: 'callCount' },
  { title: 'Tokens', key: 'totalTokens', render: row => Number(row.totalTokens).toLocaleString() },
  { title: '估算成本', key: 'estimatedCost', render: row => fmtCost(row.estimatedCost) },
  {
    title: '失败', key: 'errorCount',
    render: row => h(NTag, { size: 'small', type: row.errorCount > 0 ? 'error' : 'success' }, { default: () => row.errorCount })
  }
];

const sessionColumns: DataTableColumns<SessionTraceSummary> = [
  { title: '会话 ID', key: 'sessionId', ellipsis: { tooltip: true } },
  { title: 'LLM 调用', key: 'callCount', width: 100 },
  { title: 'Tokens', key: 'totalTokens', width: 110, render: row => Number(row.totalTokens).toLocaleString() },
  { title: '估算成本', key: 'estimatedCost', width: 120, render: row => fmtCost(row.estimatedCost) },
  {
    title: '失败', key: 'errorCount', width: 80,
    render: row => h(NTag, { size: 'small', type: row.errorCount > 0 ? 'error' : 'success' }, { default: () => row.errorCount })
  },
  { title: '最后调用', key: 'lastAt', width: 150, render: row => fmtTime(row.lastAt) },
  {
    title: '', key: 'actions', width: 100,
    render: row => h('a', {
      class: 'text-blue-600 text-sm cursor-pointer hover:underline',
      onClick: () => openTraces(row.sessionId)
    }, '查看调用链')
  }
];
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <header class="sticky top-0 z-10 bg-white/80 backdrop-blur border-b border-slate-200/70">
      <div class="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center gap-4">
        <button @click="router.push('/home')"
          class="text-slate-400 hover:text-slate-600 text-sm flex items-center gap-1 transition-colors duration-200">
          ← 返回
        </button>
        <h1 class="text-lg font-bold text-slate-800 tracking-tight">LLM 观测台</h1>
        <span class="text-sm text-slate-400">调用追踪 · Token 与成本统计</span>
      </div>
    </header>

    <main class="max-w-6xl mx-auto px-4 sm:px-6 py-8">
      <div v-if="loading" class="flex justify-center py-24">
        <n-spin size="large" />
      </div>

      <template v-else>
        <!-- 概览卡片（近 7 天） -->
        <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
          <div class="bg-white rounded-2xl shadow-card p-6">
            <p class="text-sm text-slate-400 mb-1">LLM 调用次数（近7天）</p>
            <p class="text-2xl font-bold text-slate-800">{{ summary?.callCount ?? 0 }}</p>
            <p class="text-xs text-slate-400 mt-1">失败 {{ summary?.errorCount ?? 0 }} 次（{{ errorRate }}）</p>
          </div>
          <div class="bg-white rounded-2xl shadow-card p-6">
            <p class="text-sm text-slate-400 mb-1">Token 消耗</p>
            <p class="text-2xl font-bold text-slate-800">{{ Number(summary?.totalTokens ?? 0).toLocaleString() }}</p>
            <p class="text-xs text-slate-400 mt-1">输入 {{ Number(summary?.promptTokens ?? 0).toLocaleString() }} / 输出 {{ Number(summary?.completionTokens ?? 0).toLocaleString() }}</p>
          </div>
          <div class="bg-white rounded-2xl shadow-card p-6">
            <p class="text-sm text-slate-400 mb-1">估算成本</p>
            <p class="text-2xl font-bold text-blue-600">{{ fmtCost(summary?.estimatedCost) }}</p>
            <p class="text-xs text-slate-400 mt-1">按模型单价折算（元）</p>
          </div>
          <div class="bg-white rounded-2xl shadow-card p-6">
            <p class="text-sm text-slate-400 mb-1">追踪会话数</p>
            <p class="text-2xl font-bold text-slate-800">{{ sessions.length }}</p>
            <p class="text-xs text-slate-400 mt-1">最近 50 场</p>
          </div>
        </div>

        <!-- 按 Agent 拆分 -->
        <div class="bg-white rounded-2xl shadow-card p-6 mb-8">
          <h2 class="font-semibold text-slate-800 mb-4">按 Agent 拆分（近7天）</h2>
          <n-data-table v-if="summary?.byAgent?.length" :columns="agentColumns" :data="summary.byAgent"
            :bordered="false" size="small" />
          <n-empty v-else description="暂无调用数据" />
        </div>

        <!-- 会话列表 -->
        <div class="bg-white rounded-2xl shadow-card p-6">
          <h2 class="font-semibold text-slate-800 mb-4">会话调用汇总</h2>
          <n-data-table v-if="sessions.length" :columns="sessionColumns" :data="sessions"
            :bordered="false" size="small" :max-height="480" />
          <n-empty v-else description="暂无追踪数据，先跑一场面试试试" />
        </div>
      </template>
    </main>

    <!-- 单会话调用链抽屉 -->
    <n-drawer v-model:show="drawerVisible" :width="640" placement="right">
      <n-drawer-content :title="`调用链：${drawerSessionId}`" closable>
        <div v-if="tracesLoading" class="flex justify-center py-16">
          <n-spin />
        </div>
        <n-empty v-else-if="traces.length === 0" description="该会话暂无 LLM 调用记录" />
        <div v-else class="space-y-3">
          <div v-for="t in traces" :key="t.id"
            class="border border-slate-200/80 rounded-xl p-4 bg-slate-50/50">
            <div class="flex items-center gap-2 flex-wrap">
              <n-tag size="small" :type="t.status === 'success' ? 'success' : 'error'">
                {{ t.status === 'success' ? '成功' : '失败' }}
              </n-tag>
              <span class="font-medium text-slate-800 text-sm">{{ agentLabel(t.agent) }}</span>
              <span class="text-xs text-slate-400">{{ t.model || '-' }}</span>
              <span class="ml-auto text-xs text-slate-400">{{ fmtTime(t.createdAt) }}</span>
            </div>
            <div class="flex items-center gap-4 mt-2 text-xs text-slate-500">
              <span>Tokens: <b class="text-slate-700">{{ t.totalTokens.toLocaleString() }}</b>
                （入 {{ t.promptTokens }} / 出 {{ t.completionTokens }}）</span>
              <span>耗时: {{ fmtDuration(t.durationMs) }}</span>
              <span>成本: {{ fmtCost(t.estimatedCost) }}</span>
            </div>
            <div v-if="t.errorMsg" class="mt-2 text-xs text-red-500 break-all">{{ t.errorMsg }}</div>
            <details v-if="t.promptExcerpt" class="mt-2">
              <summary class="text-xs text-blue-600 cursor-pointer select-none">Prompt 摘录</summary>
              <pre class="mt-1 text-xs text-slate-600 whitespace-pre-wrap break-all bg-white rounded-lg p-2 border border-slate-100">{{ t.promptExcerpt }}</pre>
            </details>
            <details v-if="t.completionExcerpt" class="mt-1">
              <summary class="text-xs text-blue-600 cursor-pointer select-none">回复摘录</summary>
              <pre class="mt-1 text-xs text-slate-600 whitespace-pre-wrap break-all bg-white rounded-lg p-2 border border-slate-100">{{ t.completionExcerpt }}</pre>
            </details>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>
