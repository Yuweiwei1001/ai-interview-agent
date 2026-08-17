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

/* 默认折叠；运行发起 / 重试提示 / 错误出现时自动展开；immediate 兼容运行中跨断点重建场景 */
watch(() => props.running, (v) => { if (v) expanded.value = true; }, { immediate: true });
/* v-if 重建后 expanded 会重置；immediate 兜底：初挂载时若提示已非空则自动展开 */
watch([() => props.retryHint, () => props.errorMsg], ([rh, em]) => {
  if (rh || em) expanded.value = true;
}, { immediate: true });

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

    <!-- 展开内容：总高不超过 #2 pane 的 60%，保证编辑器至少 40% 可用 -->
    <div v-show="expanded" class="max-h-[60%] overflow-y-auto">
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
          <div v-if="passRate !== null && testResults.length > 0" class="mb-3">
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
