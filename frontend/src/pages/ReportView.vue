<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { getReport, type InterviewReport } from '../api/interview';

const route = useRoute();
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
  if (score >= 80) return 'bg-green-100';
  if (score >= 60) return 'bg-yellow-100';
  return 'bg-red-100';
}
</script>

<template>
  <div class="p-6 max-w-4xl mx-auto">
    <h2 class="text-2xl font-bold text-slate-800 mb-6">面试报告</h2>

    <div v-if="loading" class="text-center py-12 text-slate-500">加载中...</div>
    <p v-else-if="error" class="text-red-500 text-center">{{ error }}</p>

    <template v-else-if="report">
      <!-- 总分 -->
      <div class="bg-white rounded-xl shadow-sm p-6 mb-6 text-center">
        <h3 class="text-lg font-medium text-slate-600 mb-2">综合评分</h3>
        <div class="text-6xl font-bold" :class="scoreColor(report.overallScore)">
          {{ Math.round(report.overallScore) }}
        </div>
        <p class="text-slate-500 mt-2">/ 100</p>
      </div>

      <!-- 维度分 -->
      <div class="bg-white rounded-xl shadow-sm p-6 mb-6">
        <h3 class="text-lg font-bold text-slate-800 mb-4">维度评分</h3>
        <div class="grid grid-cols-2 gap-4">
          <div v-for="(score, dim) in report.dimensionScores" :key="dim"
            class="p-3 rounded-lg" :class="scoreBg(score)">
            <p class="text-sm text-slate-600 capitalize">{{ dim }}</p>
            <p class="text-2xl font-bold" :class="scoreColor(score)">{{ Math.round(score) }}</p>
          </div>
        </div>
      </div>

      <!-- 优势与不足 -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
        <div class="bg-white rounded-xl shadow-sm p-6">
          <h3 class="text-lg font-bold text-green-600 mb-3">优势</h3>
          <ul class="space-y-2">
            <li v-for="(s, i) in report.strengths" :key="i" class="text-slate-700 text-sm flex gap-2">
              <span class="text-green-500 shrink-0">✓</span> {{ s }}
            </li>
          </ul>
        </div>
        <div class="bg-white rounded-xl shadow-sm p-6">
          <h3 class="text-lg font-bold text-red-600 mb-3">待改进</h3>
          <ul class="space-y-2">
            <li v-for="(w, i) in report.weaknesses" :key="i" class="text-slate-700 text-sm flex gap-2">
              <span class="text-red-500 shrink-0">✗</span> {{ w }}
            </li>
          </ul>
        </div>
      </div>

      <!-- 建议 -->
      <div class="bg-white rounded-xl shadow-sm p-6 mb-6">
        <h3 class="text-lg font-bold text-blue-600 mb-3">学习建议</h3>
        <ul class="space-y-2">
          <li v-for="(s, i) in report.suggestions" :key="i" class="text-slate-700 text-sm flex gap-2">
            <span class="text-blue-500 shrink-0">→</span> {{ s }}
          </li>
        </ul>
      </div>

      <!-- 逐题反馈 -->
      <div class="bg-white rounded-xl shadow-sm p-6 mb-6">
        <h3 class="text-lg font-bold text-slate-800 mb-4">逐题反馈</h3>
        <div v-for="q in report.perQuestionFeedback" :key="q.roundNumber"
          class="border border-slate-200 rounded-lg p-4 mb-3">
          <div class="flex items-center justify-between mb-2">
            <span class="text-sm font-medium text-slate-600">第 {{ q.roundNumber }} 题</span>
            <span class="text-sm font-bold" :class="scoreColor(q.score)">{{ Math.round(q.score) }} 分</span>
          </div>
          <p class="text-sm text-slate-800 mb-1"><strong>题目：</strong> {{ q.question }}</p>
          <p class="text-sm text-slate-600 mb-1"><strong>回答：</strong> {{ q.answer }}</p>
          <p class="text-sm text-slate-500"><strong>评价：</strong> {{ q.feedback }}</p>
        </div>
      </div>
    </template>
  </div>
</template>
