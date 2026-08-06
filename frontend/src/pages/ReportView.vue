<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NProgress, NSpin, NAlert, NButton } from 'naive-ui';
import { getReport, type InterviewReport } from '../api/interview';

const route = useRoute();
const router = useRouter();
const report = ref<InterviewReport | null>(null);
const loading = ref(true);
const error = ref('');

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

/* 美化：进度条/环形图使用的十六进制色值，与文字色阶一致 */
function scoreHex(score: number): string {
  if (score >= 80) return '#16a34a';
  if (score >= 60) return '#d97706';
  return '#dc2626';
}
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

      <!-- 美化：加载/错误状态组件化 -->
      <div v-if="loading" class="flex justify-center py-24">
        <n-spin size="large" description="报告加载中..." />
      </div>
      <n-alert v-else-if="error" type="error" :bordered="false" class="rounded-xl">{{ error }}</n-alert>

      <template v-else-if="report">
        <!-- 美化：总分升级为环形进度图 -->
        <div class="bg-white rounded-2xl shadow-card p-8 mb-6 flex flex-col items-center">
          <h3 class="text-lg font-semibold text-slate-600 mb-6">综合评分</h3>
          <n-progress type="circle" :percentage="Math.round(report.overallScore)"
            :color="scoreHex(report.overallScore)" :stroke-width="8" :size="160">
            <div>
              <div class="text-5xl font-bold" :class="scoreColor(report.overallScore)">
                {{ Math.round(report.overallScore) }}
              </div>
              <p class="text-slate-400 text-sm mt-1">/ 100</p>
            </div>
          </n-progress>
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

        <!-- 美化：维度分升级进度条 -->
        <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 mb-6">
          <h3 class="text-lg font-bold text-slate-800 mb-4">维度评分</h3>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div v-for="(score, dim) in report.dimensionScores" :key="dim"
              class="p-4 rounded-xl" :class="scoreBg(score)">
              <div class="flex items-center justify-between mb-2">
                <p class="text-sm text-slate-600 capitalize">{{ dim }}</p>
                <p class="text-xl font-bold" :class="scoreColor(score)">{{ Math.round(score) }}</p>
              </div>
              <n-progress type="line" :percentage="Math.round(score)" :color="scoreHex(score)"
                :height="6" border-radius="3px" :show-indicator="false" />
            </div>
          </div>
        </div>

        <!-- 优势与不足 -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
          <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8">
            <h3 class="text-lg font-bold text-green-600 mb-3">优势</h3>
            <ul class="space-y-2">
              <li v-for="(s, i) in report.strengths" :key="i" class="text-slate-700 text-sm leading-relaxed flex gap-2">
                <span class="text-green-500 shrink-0">✓</span> {{ s }}
              </li>
            </ul>
          </div>
          <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8">
            <h3 class="text-lg font-bold text-red-600 mb-3">待改进</h3>
            <ul class="space-y-2">
              <li v-for="(w, i) in report.weaknesses" :key="i" class="text-slate-700 text-sm leading-relaxed flex gap-2">
                <span class="text-red-500 shrink-0">✗</span> {{ w }}
              </li>
            </ul>
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

        <!-- 逐题反馈 -->
        <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 mb-6">
          <h3 class="text-lg font-bold text-slate-800 mb-4">逐题反馈</h3>
          <div v-for="q in report.perQuestionFeedback" :key="q.roundNumber"
            class="border border-slate-200/80 bg-slate-50/50 rounded-xl p-4 mb-3 last:mb-0">
            <div class="flex items-center justify-between mb-2">
              <span class="text-sm font-semibold text-slate-600">第 {{ q.roundNumber }} 题</span>
              <span class="text-sm font-bold" :class="scoreColor(q.score)">{{ Math.round(q.score) }} 分</span>
            </div>
            <p class="text-sm text-slate-800 mb-1 leading-relaxed"><strong>题目：</strong> {{ q.question }}</p>
            <p class="text-sm text-slate-600 mb-1 leading-relaxed"><strong>回答：</strong> {{ q.answer }}</p>
            <p class="text-sm text-slate-500 leading-relaxed"><strong>评价：</strong> {{ q.feedback }}</p>
          </div>
        </div>

        <!-- 下载报告 -->
        <div class="text-center mb-6">
          <a :href="`/api/interviews/sessions/${route.params.id}/report.pdf`" download class="inline-block">
            <n-button type="primary" size="large" tag="span">下载报告</n-button>
          </a>
        </div>
      </template>
    </div>
  </div>
</template>
