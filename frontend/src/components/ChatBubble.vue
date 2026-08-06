<script setup lang="ts">
defineProps<{
  role: 'assistant' | 'user';
  content: string;
  timestamp?: string;
}>();
</script>

<template>
  <!-- 美化：气泡入场动画 + 行高优化 + 用户气泡微阴影 -->
  <div class="flex bubble-enter" :class="role === 'user' ? 'justify-end' : 'justify-start'">
    <div class="max-w-[85%] sm:max-w-[80%] rounded-2xl px-4 py-3"
      :class="role === 'user'
        ? 'bg-blue-600 text-white rounded-br-md shadow-sm'
        : 'bg-white border border-slate-200/80 text-slate-800 rounded-bl-md shadow-card'">
      <div class="text-sm leading-relaxed whitespace-pre-wrap">{{ content }}</div>
      <div v-if="timestamp" class="text-xs mt-1"
        :class="role === 'user' ? 'text-blue-200' : 'text-slate-400'">
        {{ timestamp }}
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
