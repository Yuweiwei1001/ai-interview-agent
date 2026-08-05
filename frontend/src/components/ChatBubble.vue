<script setup lang="ts">
defineProps<{
  role: 'assistant' | 'user';
  content: string;
  timestamp?: string;
}>();
</script>

<template>
  <div class="flex" :class="role === 'user' ? 'justify-end' : 'justify-start'">
    <div class="max-w-[80%] rounded-2xl px-4 py-3"
      :class="role === 'user'
        ? 'bg-blue-600 text-white rounded-br-md'
        : 'bg-white border border-slate-200 text-slate-800 rounded-bl-md shadow-sm'">
      <div class="text-sm whitespace-pre-wrap">{{ content }}</div>
      <div v-if="timestamp" class="text-xs mt-1"
        :class="role === 'user' ? 'text-blue-200' : 'text-slate-400'">
        {{ timestamp }}
      </div>
    </div>
  </div>
</template>
