<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { NSelect, NInput, NInputNumber, NButton, NAlert, NTag } from 'naive-ui';
import { getResumes, type Resume } from '../api/resume';
import { getJds, type Jd } from '../api/jd';
import { getKbs, type KnowledgeBase } from '../api/knowledge';
import { createPlan, type InterviewPlan } from '../api/interview';

const router = useRouter();
const resumes = ref<Resume[]>([]);
const jds = ref<Jd[]>([]);
const kbs = ref<KnowledgeBase[]>([]);
const resumeId = ref<number | null>(null);
const jdId = ref<number | null>(null);
const knowledgeBaseId = ref<number | null>(null);
const direction = ref('');
const persona = ref('neutral');
const durationMinutes = ref(30);
const loading = ref(false);
const plan = ref<InterviewPlan | null>(null);
const error = ref('');

/* 美化：naive-ui 下拉选项映射 */
const resumeOptions = computed(() => resumes.value.map(r => ({ label: r.fileName, value: r.id })));
const jdOptions = computed(() => jds.value.map(j => ({ label: j.title, value: j.id })));
const kbOptions = computed(() => kbs.value.map(k => ({ label: k.name, value: k.id })));
const personaOptions = [
  { label: '中性', value: 'neutral' },
  { label: '温和', value: 'gentle' },
  { label: '压力', value: 'pressure' }
];

onMounted(async () => {
  try {
    const [res, jdRes, kbRes] = await Promise.all([getResumes(), getJds(), getKbs()]);
    resumes.value = res.data.data;
    jds.value = jdRes.data.data;
    kbs.value = kbRes.data.data;
  } catch {}
});

async function handleGeneratePlan() {
  loading.value = true;
  error.value = '';
  plan.value = null;
  try {
    const res = await createPlan({
      resumeId: resumeId.value ?? undefined,
      jdId: jdId.value ?? undefined,
      direction: direction.value || undefined,
      persona: persona.value,
      durationMinutes: durationMinutes.value
    });
    plan.value = res.data.data;
  } catch (e: any) {
    error.value = e.response?.data?.msg || '生成计划失败';
  } finally {
    loading.value = false;
  }
}

function startInterview() {
  router.push({
    name: 'InterviewRoom',
    query: {
      resumeId: resumeId.value,
      jdId: jdId.value,
      knowledgeBaseId: knowledgeBaseId.value,
      direction: direction.value,
      persona: persona.value,
      durationMinutes: durationMinutes.value
    }
  });
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-3xl mx-auto px-4 sm:px-6 py-8">
      <!-- 美化：页头返回导航 -->
      <div class="flex items-center gap-4 mb-6">
        <router-link to="/home" class="text-slate-400 hover:text-slate-600 text-sm transition-colors duration-200">← 返回首页</router-link>
        <h2 class="text-2xl font-bold text-slate-800 tracking-tight">开始新面试</h2>
      </div>

      <!-- 美化：表单卡片，统一 16px 圆角与层级阴影 -->
      <div class="bg-white rounded-2xl shadow-card p-6 sm:p-8 space-y-5">
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">选择简历</label>
          <n-select v-model:value="resumeId" :options="resumeOptions" clearable
            placeholder="不选（手动输入方向）" size="large" />
        </div>

        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">选择 JD（可选）</label>
          <n-select v-model:value="jdId" :options="jdOptions" clearable placeholder="不选" size="large" />
        </div>

        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">关联知识库（可选）</label>
          <n-select v-model:value="knowledgeBaseId" :options="kbOptions" clearable
            placeholder="不选（出题与评估不注入知识库内容）" size="large" />
        </div>

        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">面试方向</label>
          <n-input v-model:value="direction" placeholder="如：Java 后端开发" size="large" />
        </div>

        <!-- 美化：风格与时长并排，窄屏自动换行 -->
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1.5">面试风格</label>
            <n-select v-model:value="persona" :options="personaOptions" size="large" />
          </div>
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1.5">时长（分钟）</label>
            <n-input-number v-model:value="durationMinutes" :min="10" :max="120" size="large" class="w-full" />
          </div>
        </div>

        <n-alert v-if="error" type="error" :bordered="false" class="rounded-lg">{{ error }}</n-alert>

        <div class="flex gap-3 pt-1">
          <n-button type="primary" size="large" :loading="loading" @click="handleGeneratePlan">
            {{ loading ? '生成中...' : '生成计划' }}
          </n-button>
          <n-button v-if="plan" type="success" size="large" @click="startInterview">开始面试</n-button>
        </div>
      </div>

      <!-- 计划预览 -->
      <div v-if="plan" class="mt-6 bg-white rounded-2xl shadow-card p-6 sm:p-8">
        <h3 class="text-lg font-bold text-slate-800 mb-3">面试计划</h3>
        <p class="text-slate-600 mb-4 leading-relaxed">{{ plan.overallStrategy }}</p>
        <div class="grid gap-3">
          <!-- 美化：Agent 分配卡片层次化背景，标签化元信息 -->
          <div v-for="(ass, name) in plan.agentAssignments" :key="name"
            class="border border-slate-200/80 bg-slate-50/60 rounded-xl p-4">
            <div class="flex items-center justify-between mb-2">
              <h4 class="font-semibold text-slate-800 capitalize">{{ name }}</h4>
              <div class="flex gap-2">
                <n-tag size="small" :bordered="false" type="info">难度 {{ ass.difficulty }}</n-tag>
                <n-tag size="small" :bordered="false">约 {{ ass.estimatedRounds }} 轮</n-tag>
              </div>
            </div>
            <p class="text-sm text-slate-600">主题：{{ Array.isArray(ass.topics) ? ass.topics.join('、') : ass.topics }}</p>
          </div>
        </div>
        <p class="text-sm text-slate-400 mt-4">预计总轮次：{{ plan.estimatedTotalRounds }}</p>
      </div>
    </div>
  </div>
</template>
