<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';

const props = defineProps<{
  role: 'assistant' | 'user';
  content: string;
  timestamp?: string;
  /* 题号：面试官题目气泡显示"第N题"徽标 */
  questionNumber?: number;
  /* 追问标记：显示橙色"追问"徽标 */
  isFollowUp?: boolean;
  /* 编程题标记：显示蓝色"编程题"徽标 */
  isCoding?: boolean;
}>();

/* 完整 Markdown 渲染（marked 库），支持加粗/行内代码/代码块/引用/列表/标题等 */
const renderedContent = computed(() => {
  return marked.parse(props.content, { breaks: true, gfm: true }) as string;
});
</script>

<template>
  <div class="flex bubble-enter" :class="role === 'user' ? 'justify-end' : 'justify-start'">
    <!-- AI 头像 -->
    <div v-if="role === 'assistant'" class="flex-shrink-0 mr-3 mt-1">
      <div class="w-9 h-9 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white text-xs font-bold shadow-card">
        AI
      </div>
    </div>

    <div class="max-w-[85%] sm:max-w-[80%] min-w-0">
      <!-- 标签行：第N题 / 编程题 / 追问 -->
      <div v-if="questionNumber || isFollowUp || isCoding" class="flex items-center gap-1.5 mb-1.5">
        <span v-if="isCoding"
          class="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-blue-100 text-blue-700">编程题</span>
        <span v-else-if="questionNumber"
          class="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700">第 {{ questionNumber }} 题</span>
        <span v-if="isFollowUp"
          class="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-orange-100 text-orange-600">追问</span>
      </div>

      <!-- 气泡 -->
      <div class="rounded-2xl px-4 py-3"
        :class="role === 'user'
          ? 'bg-blue-600 text-white rounded-br-md shadow-sm'
          : 'bg-white border border-slate-200/80 text-slate-800 rounded-bl-md shadow-card'">
        <div class="text-sm leading-relaxed whitespace-pre-wrap break-words" v-html="renderedContent"></div>
        <div v-if="timestamp" class="text-xs mt-1"
          :class="role === 'user' ? 'text-blue-200' : 'text-slate-400'">
          {{ timestamp }}
        </div>
      </div>
    </div>

    <!-- 用户头像 -->
    <div v-if="role === 'user'" class="flex-shrink-0 ml-3 mt-1">
      <div class="w-9 h-9 rounded-full bg-gradient-to-br from-slate-600 to-slate-800 flex items-center justify-center text-white text-sm shadow-card">
        👤
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 美化：气泡入场动画，200ms ease-out 与全局过渡体系一致 */
.bubble-enter {
  animation: bubble-in 200ms ease-out;
}

@keyframes bubble-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
