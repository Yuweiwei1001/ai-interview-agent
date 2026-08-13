<script setup lang="ts">
import { ref, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { NButton, NSwitch, NSpin, NEmpty } from 'naive-ui';
import {
  listEvalCases, startEvalRun, getEvalRun,
  type EvalCaseSummary, type EvalRun
} from '../api/eval';

const router = useRouter();
const route = useRoute();

const cases = ref<EvalCaseSummary[]>([]);
const selected = ref<string[]>([]);
const skipLlmJudge = ref(false);
const runCalibrationFlag = ref(true);
const loadingCases = ref(true);

const running = ref(false);
const runId = ref('');
const runStatus = ref('');
const run = ref<EvalRun | null>(null);
const pollTimer = ref<number | null>(null);
const expandedTimeline = ref<string | null>(null);

async function loadCases() {
  try {
    const res = await listEvalCases();
    cases.value = res.data.data || [];
    selected.value = cases.value.map(c => c.caseId);
  } catch {
    /* 保持空态 */
  } finally {
    loadingCases.value = false;
  }
}
loadCases();

/* 支持通过 ?runId=xxx 直接查看运行中/已完成的评测 */
const queryRunId = route.query.runId as string | undefined;
if (queryRunId) {
  runId.value = queryRunId;
  running.value = true;
  runStatus.value = 'RUNNING';
  startPolling();
}

function toggleCase(caseId: string) {
  const i = selected.value.indexOf(caseId);
  if (i >= 0) selected.value.splice(i, 1);
  else selected.value.push(caseId);
}

async function startRun() {
  running.value = true;
  run.value = null;
  runStatus.value = 'RUNNING';
  try {
    const res = await startEvalRun({
      caseIds: selected.value.length === cases.value.length ? undefined : selected.value,
      skipLlmJudge: skipLlmJudge.value,
      runCalibration: runCalibrationFlag.value
    });
    runId.value = res.data.data.runId;
    startPolling();
  } catch {
    running.value = false;
    runStatus.value = 'ERROR';
  }
}

function startPolling() {
  stopPolling();
  pollTimer.value = window.setInterval(poll, 5000);
  poll();
}

function stopPolling() {
  if (pollTimer.value) {
    clearInterval(pollTimer.value);
    pollTimer.value = null;
  }
}

async function poll() {
  if (!runId.value) return;
  try {
    const res = await getEvalRun(runId.value);
    run.value = res.data.data;
    runStatus.value = run.value?.status || '';
    if (runStatus.value !== 'RUNNING') {
      running.value = false;
      stopPolling();
    }
  } catch {
    /* 轮询失败保持重试 */
  }
}

onUnmounted(stopPolling);

function pct(v: number): string {
  return `${Math.round(v * 100)}%`;
}

function fmtDuration(ms: number): string {
  const s = Math.round(ms / 1000);
  return s >= 60 ? `${Math.floor(s / 60)}分${s % 60}秒` : `${s}秒`;
}

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function levelMeta(level: string): { label: string; cls: string } {
  switch (level) {
    case 'GOOD': return { label: '强候选人', cls: 'bg-green-50 text-green-700' };
    case 'MEDIUM': return { label: '中等候选人', cls: 'bg-yellow-50 text-yellow-700' };
    case 'POOR': return { label: '弱候选人', cls: 'bg-red-50 text-red-600' };
    default: return { label: level, cls: 'bg-slate-100 text-slate-500' };
  }
}

function eventTypeMeta(type: string): string {
  switch (type) {
    case 'ANSWER_SUBMITTED': return 'text-blue-600';
    case 'CODE_SUBMITTED': return 'text-violet-600';
    case 'STATUS_CHANGED': return 'text-slate-400';
    case 'DRIVER_TIMEOUT': case 'CODING_SUBMISSIONS_EXHAUSTED': return 'text-orange-600';
    default: return 'text-slate-600';
  }
}

function metricCls(good: boolean): string {
  return good ? 'text-green-600' : 'text-red-600';
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <header class="sticky top-0 z-10 bg-white/80 backdrop-blur border-b border-slate-200/70">
      <div class="max-w-5xl mx-auto px-4 sm:px-6 h-16 flex items-center gap-4">
        <button @click="router.push('/home')"
          class="text-slate-400 hover:text-slate-600 text-sm flex items-center gap-1 transition-colors duration-200">
          ← 返回
        </button>
        <h1 class="text-lg font-bold text-slate-800 tracking-tight">Agent 评测</h1>
        <span class="text-xs text-slate-400">golden 数据集驱动模拟面试，评估编排 / 出题 / 评分质量</span>
      </div>
    </header>

    <main class="max-w-5xl mx-auto px-4 sm:px-6 py-8 space-y-6">

      <!-- 运行配置 -->
      <section class="bg-white rounded-2xl shadow-card p-5 sm:p-6">
        <h2 class="font-semibold text-slate-800 mb-4">评测用例</h2>
        <div v-if="loadingCases" class="flex justify-center py-8"><n-spin /></div>
        <div v-else-if="cases.length === 0">
          <n-empty description="未找到评测用例（resources/eval/dataset/）" />
        </div>
        <div v-else class="space-y-2.5">
          <label v-for="c in cases" :key="c.caseId"
            class="flex items-start gap-3 p-3.5 rounded-xl border cursor-pointer transition-colors duration-150"
            :class="selected.includes(c.caseId) ? 'border-blue-300 bg-blue-50/40' : 'border-slate-200 hover:border-slate-300'">
            <input type="checkbox" :checked="selected.includes(c.caseId)" @change="toggleCase(c.caseId)"
              class="mt-1 accent-blue-600" />
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-medium text-sm text-slate-800">{{ c.caseId }}</span>
                <span class="text-[11px] font-medium px-2 py-0.5 rounded-full" :class="levelMeta(c.answerLevel).cls">
                  {{ levelMeta(c.answerLevel).label }}
                </span>
                <span class="text-[11px] text-slate-400">{{ c.direction }} · {{ c.durationMinutes }}分钟 · {{ c.codingSubmissions }}次代码提交</span>
              </div>
              <p class="text-xs text-slate-500 mt-1">{{ c.description }}</p>
            </div>
          </label>

          <div class="flex items-center gap-6 pt-3 flex-wrap">
            <div class="flex items-center gap-2 text-sm text-slate-600">
              <n-switch v-model:value="skipLlmJudge" size="small" />
              跳过 LLM-Judge（更快、省调用）
            </div>
            <div class="flex items-center gap-2 text-sm text-slate-600">
              <n-switch v-model:value="runCalibrationFlag" size="small" />
              执行评分器校准
            </div>
            <div class="flex-1"></div>
            <n-button type="primary" :loading="running" :disabled="selected.length === 0" @click="startRun">
              {{ running ? '评测运行中…' : '开始评测' }}
            </n-button>
          </div>
          <p class="text-[11px] text-slate-400">运行会真实驱动完整模拟面试（含 LLM 与沙箱），单用例约 5-15 分钟。</p>
        </div>
      </section>

      <!-- 运行中状态 -->
      <section v-if="running" class="bg-white rounded-2xl shadow-card p-6 flex items-center gap-4">
        <n-spin size="small" />
        <div>
          <div class="text-sm font-medium text-slate-700">评测运行中 <span class="text-slate-400 font-mono text-xs">{{ runId }}</span></div>
          <div class="text-xs text-slate-400 mt-0.5">逐用例驱动模拟面试并采集轨迹，页面每 5 秒自动刷新进度</div>
        </div>
      </section>

      <!-- 失败 -->
      <section v-if="runStatus === 'FAILED'" class="bg-red-50 rounded-2xl p-5 text-sm text-red-600">
        评测失败：{{ run?.error || '未知错误' }}
      </section>

      <!-- 报告 -->
      <template v-if="run?.report">
        <!-- 汇总 -->
        <section class="bg-white rounded-2xl shadow-card p-5 sm:p-6">
          <div class="flex items-center justify-between mb-4">
            <h2 class="font-semibold text-slate-800">汇总指标</h2>
            <span class="text-xs text-slate-400 font-mono">{{ run.report.runId }}</span>
          </div>
          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">完成率</div>
              <div class="text-xl font-bold tabular-nums" :class="metricCls(run.report.aggregate.completionRate === 1)">{{ pct(run.report.aggregate.completionRate) }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">目标达成率</div>
              <div class="text-xl font-bold tabular-nums" :class="metricCls(run.report.aggregate.goalAchievedRate === 1)">{{ pct(run.report.aggregate.goalAchievedRate) }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">轮次达成率</div>
              <div class="text-xl font-bold tabular-nums text-slate-800">{{ pct(run.report.aggregate.avgRoundAdherence) }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">主题覆盖率</div>
              <div class="text-xl font-bold tabular-nums text-slate-800">{{ pct(run.report.aggregate.avgTopicCoverage) }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">题目重复率</div>
              <div class="text-xl font-bold tabular-nums" :class="metricCls(run.report.aggregate.avgQuestionDuplicateRate === 0)">{{ pct(run.report.aggregate.avgQuestionDuplicateRate) }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">编程题跑题数</div>
              <div class="text-xl font-bold tabular-nums" :class="metricCls(run.report.aggregate.totalCodingOffTopic === 0)">{{ run.report.aggregate.totalCodingOffTopic }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">出题相关性 (Judge)</div>
              <div class="text-xl font-bold tabular-nums text-slate-800">{{ run.report.aggregate.avgQuestionRelevance ? run.report.aggregate.avgQuestionRelevance.toFixed(1) + '/10' : '—' }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 p-3.5">
              <div class="text-[11px] text-slate-400 mb-1">追问针对性 (Judge)</div>
              <div class="text-xl font-bold tabular-nums text-slate-800">{{ run.report.aggregate.avgFollowUpQuality ? run.report.aggregate.avgFollowUpQuality.toFixed(1) + '/10' : '—' }}</div>
            </div>
          </div>
        </section>

        <!-- 校准 -->
        <section v-if="run.report.calibration" class="bg-white rounded-2xl shadow-card p-5 sm:p-6">
          <div class="flex items-center gap-3 mb-4">
            <h2 class="font-semibold text-slate-800">评分器校准（对齐人工标注）</h2>
            <span class="text-[11px] font-medium px-2.5 py-0.5 rounded-full"
              :class="run.report.calibration.relaxedAgreementRate >= 0.8 ? 'bg-green-50 text-green-700' : 'bg-orange-50 text-orange-600'">
              宽松一致率 {{ pct(run.report.calibration.relaxedAgreementRate) }}
            </span>
            <span class="text-[11px] text-slate-400">严格一致率 {{ pct(run.report.calibration.exactAgreementRate) }}</span>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-xs">
              <thead>
                <tr class="text-left text-slate-400 border-b border-slate-100">
                  <th class="py-2 pr-3">#</th>
                  <th class="py-2 pr-3">预期档位</th>
                  <th class="py-2 pr-3">实际评分</th>
                  <th class="py-2 pr-3">严格一致</th>
                  <th class="py-2 pr-3">宽松一致</th>
                  <th class="py-2">点评</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in run.report.calibration.details" :key="d.index" class="border-b border-slate-50">
                  <td class="py-2 pr-3 text-slate-400">{{ d.index + 1 }}</td>
                  <td class="py-2 pr-3 font-medium text-slate-700">{{ d.expectedLevel }}</td>
                  <td class="py-2 pr-3 tabular-nums font-semibold" :class="d.relaxedMatch ? 'text-green-600' : 'text-red-600'">{{ d.actualScore }}</td>
                  <td class="py-2 pr-3">{{ d.exactMatch ? '✓' : '✗' }}</td>
                  <td class="py-2 pr-3">{{ d.relaxedMatch ? '✓' : '✗' }}</td>
                  <td class="py-2 text-slate-500">{{ d.summary }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 逐用例 -->
        <section v-for="c in run.report.caseResults" :key="c.caseId" class="bg-white rounded-2xl shadow-card p-5 sm:p-6">
          <div class="flex items-center gap-2.5 mb-1 flex-wrap">
            <h3 class="font-semibold text-slate-800">{{ c.caseId }}</h3>
            <span class="text-[11px] font-medium px-2 py-0.5 rounded-full" :class="levelMeta(c.answerLevel).cls">{{ levelMeta(c.answerLevel).label }}</span>
            <span class="text-[11px] font-medium px-2 py-0.5 rounded-full"
              :class="c.ruleMetrics?.goalAchieved ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-600'">
              {{ c.ruleMetrics?.goalAchieved ? '目标达成' : '目标未达成' }}
            </span>
            <span class="text-[11px] font-medium px-2 py-0.5 rounded-full"
              :class="c.trace.finalStatus === 'completed' ? 'bg-green-50 text-green-700' : 'bg-orange-50 text-orange-600'">
              {{ c.trace.finalStatus }}
            </span>
            <span class="text-[11px] text-slate-400 ml-auto">耗时 {{ fmtDuration(c.trace.durationMs) }}</span>
          </div>
          <p class="text-xs text-slate-500 mb-4">{{ c.description }}</p>

          <div v-if="c.error" class="text-xs text-red-600 mb-3">驱动错误：{{ c.error }}</div>

          <div v-if="c.ruleMetrics" class="grid grid-cols-3 sm:grid-cols-6 gap-2.5 mb-4">
            <div class="rounded-lg bg-slate-50 p-2.5 text-center">
              <div class="text-[10px] text-slate-400">轮次</div>
              <div class="text-sm font-bold tabular-nums text-slate-800">{{ c.ruleMetrics.actualMainRounds }}/{{ c.ruleMetrics.planRounds }}</div>
            </div>
            <div class="rounded-lg bg-slate-50 p-2.5 text-center">
              <div class="text-[10px] text-slate-400">主题覆盖</div>
              <div class="text-sm font-bold tabular-nums text-slate-800">{{ pct(c.ruleMetrics.topicCoverageRatio) }}</div>
            </div>
            <div class="rounded-lg bg-slate-50 p-2.5 text-center">
              <div class="text-[10px] text-slate-400">追问次数</div>
              <div class="text-sm font-bold tabular-nums text-slate-800">{{ c.ruleMetrics.followUpCount }}</div>
            </div>
            <div class="rounded-lg bg-slate-50 p-2.5 text-center">
              <div class="text-[10px] text-slate-400">重复题对</div>
              <div class="text-sm font-bold tabular-nums" :class="metricCls(c.ruleMetrics.duplicateQuestionPairs === 0)">{{ c.ruleMetrics.duplicateQuestionPairs }}</div>
            </div>
            <div class="rounded-lg bg-slate-50 p-2.5 text-center">
              <div class="text-[10px] text-slate-400">降级轮次</div>
              <div class="text-sm font-bold tabular-nums" :class="metricCls(c.ruleMetrics.degradedRoundCount === 0)">{{ c.ruleMetrics.degradedRoundCount }}</div>
            </div>
            <div class="rounded-lg bg-slate-50 p-2.5 text-center">
              <div class="text-[10px] text-slate-400">平均评分</div>
              <div class="text-sm font-bold tabular-nums text-slate-800">{{ c.ruleMetrics.avgScore }}</div>
            </div>
          </div>

          <div v-if="c.judgeMetrics" class="flex items-center gap-4 text-xs text-slate-500 mb-4">
            <span>出题相关性 <b class="text-slate-700">{{ c.judgeMetrics.avgQuestionRelevance.toFixed(1) }}/10</b>（{{ c.judgeMetrics.judgedQuestionCount }}题）</span>
            <span>追问针对性 <b class="text-slate-700">{{ c.judgeMetrics.avgFollowUpQuality.toFixed(1) }}/10</b>（{{ c.judgeMetrics.judgedFollowUpCount }}次）</span>
            <span v-if="c.judgeMetrics.judgeDegradedCount > 0" class="text-orange-600">judge 降级 {{ c.judgeMetrics.judgeDegradedCount }} 次</span>
          </div>

          <div v-if="c.ruleMetrics?.uncoveredTopics?.length" class="text-xs text-slate-400 mb-4">
            未覆盖主题：{{ c.ruleMetrics.uncoveredTopics.join('、') }}
          </div>

          <button class="text-xs text-blue-600 hover:underline" @click="expandedTimeline = expandedTimeline === c.caseId ? null : c.caseId">
            {{ expandedTimeline === c.caseId ? '收起' : '展开' }}执行时间线（{{ c.trace.timeline.length }} 事件）
          </button>
          <div v-if="expandedTimeline === c.caseId" class="mt-3 space-y-1.5 max-h-72 overflow-y-auto pr-2">
            <div v-for="(e, i) in c.trace.timeline" :key="i" class="flex gap-2 text-xs">
              <span class="text-slate-300 tabular-nums shrink-0">{{ fmtTime(e.ts) }}</span>
              <span class="font-mono shrink-0" :class="eventTypeMeta(e.type)">[{{ e.type }}]</span>
              <span class="text-slate-600 break-all">{{ e.detail }}</span>
            </div>
          </div>
        </section>
      </template>
    </main>
  </div>
</template>
