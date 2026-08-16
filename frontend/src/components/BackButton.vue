<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router';

/**
 * 美化：全站统一返回按钮
 * - SVG 左箭头替代「←」文本字符，跨字体/平台渲染一致
 * - 浅蓝药丸 hover（hover:bg-blue-50 hover:text-blue-600），与首页导航项风格统一
 * - 默认 slate-500 比原 slate-400 更可读，避免「禁用态」观感
 * - 负左边距抵消药丸内边距，文字起点与页面内容左缘对齐
 */
withDefaults(defineProps<{
  to: RouteLocationRaw;
  label?: string;
}>(), {
  label: '返回',
});
</script>

<template>
  <RouterLink
    :to="to"
    class="group inline-flex items-center gap-1.5 -ml-3 px-3 py-1.5 rounded-lg text-sm whitespace-nowrap shrink-0 text-slate-500 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200"
  >
    <!-- 美化：SVG 箭头 currentColor 跟随文字色，hover 轻微左移引导视线 -->
    <svg
      class="w-4 h-4 transition-transform duration-200 group-hover:-translate-x-0.5"
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path d="M12.5 15 L7.5 10 L12.5 5" />
    </svg>
    <span>{{ label }}</span>
  </RouterLink>
</template>
