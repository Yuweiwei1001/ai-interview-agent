<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NProgress, NSpin, NAlert, NButton } from 'naive-ui';
import { getReport, type InterviewReport } from '../api/interview';
import request from '../utils/request';

const route = useRoute();
const router = useRouter();
const report = ref<InterviewReport | null>(null);
const loading = ref(true);
const error = ref('');

/* 问答折叠面板：默认展开第一题 */
const expandedQs = ref<Set<number>>(new Set([0]));

function toggleQ(idx: number) {
  const s = new Set(expandedQs.value);
  if (s.has(idx)) s.delete(idx);
  else s.add(idx);
  expandedQs.value = s;
}

onMounted(async () => {
  try {
    const res = await getReport(route.params.id as string);
    report.value = res.data.data;
  } catch (e: any) {
    error.value = e.response?.data?.msg || '加载报告失败';
  } finally {
    loading.value = false;
  }
});

/* 分数颜色（文本/十六进制） */
function scoreColor(score: number): string {
  if (score >= 80) return 'text-green-600';
  if (score >= 60) return 'text-yellow-600';
  return 'text-red-600';
}

function scoreBg(score: number): string {
  if (score >= 80) return 'bg-green-50';
  if (score >= 60) return 'bg-yellow-50';
  return 'bg-red-50';
}

function scoreHex(score: number): string {
  if (score >= 80) return '#16a34a';
  if (score >= 60) return '#d97706';
  return '#dc2626';
}

/* 结论徽标（借鉴 ThinkVerse 报告页：🌟推荐 / ⏳待定 / ❌不推荐） */
const conclusion = computed(() => {
  if (!report.value) return { text: '', cls: '' };
  const s = report.value.overallScore;
  if (s >= 80) return { text: '🌟 推荐', cls: 'bg-green-50 text-green-700' };
  if (s >= 60) return { text: '⏳ 待定', cls: 'bg-amber-50 text-amber-600' };
  return { text: '❌ 不推荐', cls: 'bg-red-50 text-red-600' };
});

/* 维度名中文化 */
const dimensionLabels: Record<string, string> = {
  technical: '技术基础',
  project: '项目经验',
  coding: '编码能力',
  communication: '沟通表达'
};
/* 下载报告（axios 自动携带 JWT token，避免浏览器直接导航被拦截） */
const downloadReport = async () => {
  try {
    const res = await request.get(`/api/interviews/sessions/${route.params.id}/report.pdf`, {
      responseType: 'blob'
    });
    const url = URL.createObjectURL(new Blob([res.data]));
    const a = document.createElement('a');
    a.href = url;
    a.download = `interview-report-${route.params.id}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e: any) {
    error.value = e.response?.data?.msg || '下载失败';
  }
};
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-4xl mx-auto px-4 sm:px-6 py-8">
      <div class="flex items-center gap-4 mb-6">
        <button @click="router.push('/home')"
          class="text-slate-400 hover:text-slate-600 text-sm flex items-center gap-1 transition-colors duration-200">
          ← 返回
        </button>
        <h2 class="text-2xl font-bold text-slate-800 tracking-tight">面试报告</h2>
      </div>

      <div v-if="loading" class="flex justify-center py-24">
        <n-spin size="large" description="报告加载中..." />
      </div>
      <n-alert v-else-if="error" type="error" :bordered="false" class="rounded-xl">{{ error }}</n-alert>

      <template v-else-if="report">
        <!-- 顶部概览：环形分 + 结论徽标 + 题数 -->
        <div class="bg-white rounded-2xl shadow-card p-8 mb-6">
          <div class="flex flex-col md:flex-row items-center gap-8">
            <div class="relative flex-shrink-0">
              <n-progress type="circle" :percentage="Math.round(report.overallScore)"
                :color="scoreHex(report.overallScore)" :stroke-width="8" :size="150">
                <div>
                  <div class="text-4xl font-bold tabular-nums" :class="scoreColor(report.overallScore)">
                    {{ Math.round(report.overallScore) }}
                  </div>
                  <p class="text-slate-400 text-xs mt-1">综合得分 /100</p>
                </div>
              </n-progress>
            </div>
            <div class="flex-1 text-center md:text-left">
              <span :class="['inline-block px-4 py-1.5 rounded-full text-sm font-bold mb-3', conclusion.cls]">
                {{ conclusion.text }}
              </span>
              <h3 class="text-lg font-bold text-slate-800 mb-2">本次面试评估</h3>
              <div class="grid grid-cols-2 gap-x-8 gap-y-2.5 text-sm max-w-md mx-auto md:mx-0">
                <div><span class="text-slate-400">题目数量：</span><span class="font-medium text-slate-700">{{ report.perQuestionFeedback.length }} 题</span></div>
                <div><span class="text-slate-400">面试状态：</span><span class="font-medium text-green-600">已完成</span></div>
              </div>
            </div>
          </div>
        </div>

        <!-- 成长对比 -->
        <div v-if="report.growthComparison" class="bg-white rounded-2xl shadow-card p-6 sm:p-8 mb-6">
          <h3 class="text-lg font-bold text-slate-800 mb-4">成长对比</h3>
          <div class="grid grid-cols-3 gap-4 text-center">
            <div class="rounded-xl bg-slate-50 py-4">
              <p class="text-sm text-slate-500">历史成绩</p>
              <p class="text-2xl font-bold text-slate-600 mt-1">{{ Math.round(report.growthComparison.previousScore) }}</p>
            </div>
            <div class="rounded-xl bg-slate-50 py-4">
              <p class="text-sm text-slate-500">当前成绩</p>
              <p class="text-2xl font-bold mt-1" :class="scoreColor(report.growthComparison.currentScore)">{{ Math.round(report.growthComparison.currentScore) }}</p>
            </div>
            <div class="rounded-xl bg-slate-50 py-4">
              <p class="text-sm text-slate-500">进步</p>
              <p class="text-2xl font-bold mt-1" :class="report.growthComparison.improvement >= 0 ? 'text-green-600' : 'text-red-600'">
                {{ report.growthComparison.improvement >= 0 ? '+' : '' }}{{ Math.round(report.growthComparison.improvement) }}
              </p>
            </div>
          </div>
        </div>

        <!-- 维度评分 -->
        <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 mb-6">
          <h3 class="text-lg font-bold text-slate-800 mb-4">维度评分</h3>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div v-for="(score, dim) in report.dimensionScores" :key="dim"
              class="p-4 rounded-xl" :class="scoreBg(score)">
              <div class="flex items-center justify-between mb-2">
                <p class="text-sm text-slate-600">{{ dimensionLabels[dim] || dim }}</p>
                <p class="text-xl font-bold" :class="scoreColor(score)">{{ Math.round(score) }}</p>
              </div>
              <n-progress type="line" :percentage="Math.round(score)" :color="scoreHex(score)"
                :height="6" border-radius="3px" :show-indicator="false" />
            </div>
          </div>
        </div>

        <!-- 优势与不足：双列 -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
          <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 border-l-4 border-l-green-500">
            <h3 class="text-lg font-bold text-green-600 mb-3">✔ 核心优势</h3>
            <ul class="space-y-2">
              <li v-for="(s, i) in report.strengths" :key="i" class="text-slate-700 text-sm leading-relaxed flex gap-2">
                <span class="text-green-500 shrink-0">✓</span> {{ s }}
              </li>
            </ul>
            <p v-if="!report.strengths.length" class="text-sm text-slate-400">核心优势：无</p>
          </div>
          <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 border-l-4 border-l-orange-400">
            <h3 class="text-lg font-bold text-orange-600 mb-3">↑ 待提升项</h3>
            <ul class="space-y-2">
              <li v-for="(w, i) in report.weaknesses" :key="i" class="text-slate-700 text-sm leading-relaxed flex gap-2">
                <span class="text-orange-500 shrink-0">✗</span> {{ w }}
              </li>
            </ul>
            <p v-if="!report.weaknesses.length" class="text-sm text-slate-400">待提升项：无</p>
          </div>
        </div>

        <!-- 建议 -->
        <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 mb-6">
          <h3 class="text-lg font-bold text-blue-600 mb-3">学习建议</h3>
          <ul class="space-y-2">
            <li v-for="(s, i) in report.suggestions" :key="i" class="text-slate-700 text-sm leading-relaxed flex gap-2">
              <span class="text-blue-500 shrink-0">→</span> {{ s }}
            </li>
          </ul>
        </div>

        <!-- 问答回顾：折叠面板 -->
        <div class="mb-6">
          <h3 class="text-lg font-bold text-slate-800 mb-4 flex items-center gap-2">
            <span class="w-1 h-4.5 bg-blue-500 rounded-full"></span>问答回顾与点评（{{ report.perQuestionFeedback.length }}）
          </h3>
          <div class="space-y-3">
            <div v-for="(q, idx) in report.perQuestionFeedback" :key="idx"
              class="bg-white rounded-2xl shadow-card overflow-hidden">
              <button
                class="w-full px-5 py-4 flex items-center justify-between gap-3 cursor-pointer hover:bg-slate-50/70 transition text-left"
                @click="toggleQ(idx)">
                <div class="flex items-center gap-2.5 min-w-0">
                  <span class="text-xs font-bold text-white bg-blue-500 px-2 py-0.5 rounded-full flex-shrink-0">Q{{ q.roundNumber }}</span>
                  <span class="text-sm font-medium text-slate-700 truncate">{{ q.question }}</span>
                </div>
                <div class="flex items-center gap-3 flex-shrink-0">
                  <span v-if="q.score != null" class="text-sm font-bold tabular-nums" :class="scoreColor(q.score)">
                    {{ Math.round(q.score) }}<span class="text-xs font-medium text-slate-400">/100</span>
                  </span>
                  <span class="text-slate-400 text-xs transition-transform duration-200"
                    :class="expandedQs.has(idx) ? 'rotate-180' : ''">▼</span>
                </div>
              </button>
              <div v-show="expandedQs.has(idx)" class="px-5 pb-5 border-t border-slate-100">
                <div class="flex items-start gap-2.5 mt-4 mb-3">
                  <span class="flex-shrink-0 w-6 h-6 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white text-[10px] font-bold mt-0.5">AI</span>
                  <p class="text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">{{ q.question }}</p>
                </div>
                <div class="flex items-start gap-2.5 ml-8">
                  <span class="flex-shrink-0 w-6 h-6 rounded-full bg-slate-700 flex items-center justify-center text-white text-[10px] font-bold mt-0.5">你</span>
                  <p class="flex-1 text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">{{ q.answer || '(未回答)' }}</p>
                </div>
                <div v-if="q.score != null" class="mt-3.5 ml-8">
                  <div class="w-full h-2 bg-slate-100 rounded-full overflow-hidden">
                    <div class="h-full rounded-full transition-all duration-700"
                      :style="{ width: Math.min(100, Math.round(q.score)) + '%', backgroundColor: scoreHex(q.score) }"></div>
                  </div>
                </div>
                <div v-if="q.feedback" class="mt-3.5 ml-8 p-3.5 bg-amber-50 rounded-xl border border-amber-100">
                  <p class="text-xs text-amber-700 leading-relaxed">
                    <span class="font-bold">💡 AI 点评：</span>{{ q.feedback }}
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 下载报告 -->
        <div class="text-center mb-6">
          <n-button type="primary" size="large" @click="downloadReport">下载报告</n-button>
        </div>
      </template>
    </div>
  </div>
</template>
