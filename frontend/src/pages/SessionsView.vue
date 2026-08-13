<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { NButton, NEmpty, NSpin } from 'naive-ui';
import { getSessions, type InterviewSession } from '../api/interview';
import { toDate } from '../utils/datetime';

const router = useRouter();
const sessions = ref<InterviewSession[]>([]);
const loading = ref(true);

onMounted(async () => {
  try {
    const res = await getSessions();
    sessions.value = res.data.data || [];
  } catch {
    /* 列表加载失败保持空态 */
  } finally {
    loading.value = false;
  }
});

/* 状态徽标映射 */
function statusMeta(status: string): { label: string; cls: string } {
  switch (status) {
    case 'completed': return { label: '已完成', cls: 'bg-green-50 text-green-700' };
    case 'in_progress': return { label: '进行中', cls: 'bg-blue-50 text-blue-700' };
    case 'waiting_code': return { label: '编程环节', cls: 'bg-violet-50 text-violet-700' };
    case 'interrupted': return { label: '已中断', cls: 'bg-red-50 text-red-600' };
    case 'cancelled': return { label: '已取消', cls: 'bg-slate-100 text-slate-500' };
    default: return { label: '待开始', cls: 'bg-slate-100 text-slate-500' };
  }
}

function formatTime(t: string | null): string {
  const d = toDate(t);
  if (!d) return '-';
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function goReport(id: string) { router.push(`/report/${id}`); }
function continueInterview(s: InterviewSession) {
  router.push({ name: 'InterviewRoom', query: { sessionId: s.id } });
}
function goCoding(s: InterviewSession) {
  router.push({ name: 'CodingRoom', query: { sessionId: s.id, question: s.currentQuestion || '' } });
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <header class="sticky top-0 z-10 bg-white/80 backdrop-blur border-b border-slate-200/70">
      <div class="max-w-4xl mx-auto px-4 sm:px-6 h-16 flex items-center gap-4">
        <button @click="router.push('/home')"
          class="text-slate-400 hover:text-slate-600 text-sm flex items-center gap-1 transition-colors duration-200">
          ← 返回
        </button>
        <h1 class="text-lg font-bold text-slate-800 tracking-tight">面试记录</h1>
        <span class="text-sm text-slate-400">{{ sessions.length ? `${sessions.length} 场` : '' }}</span>
        <div class="flex-1"></div>
        <n-button size="small" type="primary" secondary @click="router.push('/interview/start')">+ 开始新面试</n-button>
      </div>
    </header>

    <main class="max-w-4xl mx-auto px-4 sm:px-6 py-8">
      <div v-if="loading" class="flex justify-center py-24">
        <n-spin size="large" />
      </div>

      <div v-else-if="sessions.length === 0" class="py-16">
        <n-empty description="暂无面试记录，去开启第一场模拟面试吧！">
          <template #extra>
            <n-button type="primary" secondary @click="router.push('/interview/start')">开始第一场面试</n-button>
          </template>
        </n-empty>
      </div>

      <div v-else class="space-y-4">
        <div v-for="s in sessions" :key="s.id"
          class="bg-white rounded-2xl shadow-card p-5 sm:p-6 hover:shadow-card-hover transition-all duration-200">
          <div class="flex items-start justify-between gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2.5 mb-2 flex-wrap">
                <h3 class="font-semibold text-slate-800 truncate">{{ s.direction || '综合面试' }}</h3>
                <span class="text-[11px] font-medium px-2.5 py-0.5 rounded-full bg-slate-100 text-slate-500">
                  {{ s.persona === 'gentle' ? '温和' : s.persona === 'pressure' ? '压力' : '中性' }}人格
                </span>
                <span class="text-[11px] font-medium px-2.5 py-0.5 rounded-full" :class="statusMeta(s.status).cls">
                  {{ statusMeta(s.status).label }}
                </span>
              </div>
              <div class="flex items-center flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
                <span>🕐 {{ formatTime(s.startedAt || s.createdAt) }}</span>
                <span v-if="s.durationMinutes">⏱ 计划 {{ s.durationMinutes }} 分钟</span>
              </div>
            </div>
            <div class="flex-shrink-0 text-right">
              <div v-if="s.status === 'completed' && s.overallScore != null" class="text-right">
                <div class="text-[11px] text-slate-400 mb-0.5">综合评分</div>
                <div class="text-2xl font-bold tabular-nums"
                  :class="s.overallScore >= 80 ? 'text-green-600' : s.overallScore >= 60 ? 'text-yellow-600' : 'text-red-600'">
                  {{ Math.round(s.overallScore) }}
                </div>
              </div>
            </div>
          </div>

          <div class="mt-4 pt-3.5 border-t border-slate-100 flex items-center gap-2.5 flex-wrap">
            <n-button v-if="s.status === 'completed'" size="small" type="primary" @click="goReport(s.id)">
              查看报告
            </n-button>
            <n-button v-if="s.status === 'in_progress'" size="small" type="primary" secondary @click="continueInterview(s)">
              继续面试
            </n-button>
            <n-button v-if="s.status === 'waiting_code'" size="small" type="primary" secondary @click="goCoding(s)">
              去完成编程题
            </n-button>
            <n-button v-if="s.status === 'completed'" size="small" secondary @click="continueInterview(s)">
              回顾问答
            </n-button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>
