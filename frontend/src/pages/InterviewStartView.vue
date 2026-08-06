<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { getResumes, type Resume } from '../api/resume';
import { getJds, type Jd } from '../api/jd';
import { createPlan, type InterviewPlan } from '../api/interview';

const router = useRouter();
const resumes = ref<Resume[]>([]);
const jds = ref<Jd[]>([]);
const resumeId = ref<number | undefined>();
const jdId = ref<number | undefined>();
const direction = ref('');
const persona = ref('neutral');
const durationMinutes = ref(30);
const loading = ref(false);
const plan = ref<InterviewPlan | null>(null);
const error = ref('');

onMounted(async () => {
  try {
    const [res, jdRes] = await Promise.all([getResumes(), getJds()]);
    resumes.value = res.data.data;
    jds.value = jdRes.data.data;
  } catch {}
});

async function handleGeneratePlan() {
  loading.value = true;
  error.value = '';
  plan.value = null;
  try {
    const res = await createPlan({
      resumeId: resumeId.value,
      jdId: jdId.value,
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
      direction: direction.value,
      persona: persona.value,
      durationMinutes: durationMinutes.value
    }
  });
}
</script>

<template>
  <div class="p-6 max-w-3xl mx-auto">
    <h2 class="text-2xl font-bold text-slate-800 mb-6">开始新面试</h2>

    <div class="bg-white rounded-xl shadow-sm p-6 space-y-4">
      <div>
        <label class="block text-sm font-medium text-slate-700 mb-1">选择简历</label>
        <select v-model="resumeId" class="w-full px-3 py-2 border border-slate-300 rounded-lg">
          <option :value="undefined">不选（手动输入）</option>
          <option v-for="r in resumes" :key="r.id" :value="r.id">{{ r.fileName }}</option>
        </select>
      </div>

      <div>
        <label class="block text-sm font-medium text-slate-700 mb-1">选择 JD（可选）</label>
        <select v-model="jdId" class="w-full px-3 py-2 border border-slate-300 rounded-lg">
          <option :value="undefined">不选</option>
          <option v-for="j in jds" :key="j.id" :value="j.id">{{ j.title }}</option>
        </select>
      </div>

      <div>
        <label class="block text-sm font-medium text-slate-700 mb-1">面试方向</label>
        <input v-model="direction" placeholder="如：Java 后端开发" class="w-full px-3 py-2 border border-slate-300 rounded-lg" />
      </div>

      <div>
        <label class="block text-sm font-medium text-slate-700 mb-1">面试风格</label>
        <select v-model="persona" class="w-full px-3 py-2 border border-slate-300 rounded-lg">
          <option value="neutral">中性</option>
          <option value="gentle">温和</option>
          <option value="pressure">压力</option>
        </select>
      </div>

      <div>
        <label class="block text-sm font-medium text-slate-700 mb-1">时长（分钟）</label>
        <input v-model.number="durationMinutes" type="number" min="10" max="120"
          class="w-full px-3 py-2 border border-slate-300 rounded-lg" />
      </div>

      <p v-if="error" class="text-red-500 text-sm">{{ error }}</p>

      <div class="flex gap-3">
        <button @click="handleGeneratePlan" :disabled="loading"
          class="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors">
          {{ loading ? '生成中...' : '生成计划' }}
        </button>
        <button v-if="plan" @click="startInterview"
          class="px-6 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors">
          开始面试
        </button>
      </div>
    </div>

    <!-- 计划预览 -->
    <div v-if="plan" class="mt-6 bg-white rounded-xl shadow-sm p-6">
      <h3 class="text-lg font-bold text-slate-800 mb-3">面试计划</h3>
      <p class="text-slate-600 mb-4">{{ plan.overallStrategy }}</p>
      <div class="grid gap-3">
        <div v-for="(ass, name) in plan.agentAssignments" :key="name"
          class="border border-slate-200 rounded-lg p-3">
          <h4 class="font-medium text-slate-800 capitalize">{{ name }}</h4>
          <p class="text-sm text-slate-600">主题：{{ Array.isArray(ass.topics) ? ass.topics.join('、') : ass.topics }}</p>
          <p class="text-sm text-slate-600">难度：{{ ass.difficulty }} | 预计轮次：{{ ass.estimatedRounds }}</p>
        </div>
      </div>
      <p class="text-sm text-slate-500 mt-3">预计总轮次：{{ plan.estimatedTotalRounds }}</p>
    </div>
  </div>
</template>
