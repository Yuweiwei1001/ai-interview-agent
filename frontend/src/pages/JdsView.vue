<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { getJds, deleteJd, createJd, type Jd } from '../api/jd';

const jds = ref<Jd[]>([]);
const loading = ref(false);
const showForm = ref(false);
const formTitle = ref('');
const formRawText = ref('');
const formSourceUrl = ref('');
const submitting = ref(false);

onMounted(() => loadJds());

async function loadJds() {
  loading.value = true;
  try {
    const res = await getJds();
    jds.value = res.data.data;
  } finally {
    loading.value = false;
  }
}

async function handleCreate() {
  submitting.value = true;
  try {
    await createJd({ title: formTitle.value, rawText: formRawText.value, sourceUrl: formSourceUrl.value || undefined });
    showForm.value = false;
    formTitle.value = '';
    formRawText.value = '';
    formSourceUrl.value = '';
    await loadJds();
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(id: number) {
  if (!confirm('确认删除？')) return;
  await deleteJd(id);
  await loadJds();
}
</script>

<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <h2 class="text-2xl font-bold text-slate-800">职位描述管理</h2>
      <button @click="showForm = true"
        class="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors">
        新建 JD
      </button>
    </div>

    <div v-if="loading" class="text-center py-8 text-slate-500">加载中...</div>
    <div v-else-if="jds.length === 0" class="text-center py-8 text-slate-400">暂无 JD，请新建</div>
    <div v-else class="grid gap-4">
      <div v-for="jd in jds" :key="jd.id"
        class="bg-white rounded-xl shadow-sm p-4 hover:shadow-md transition-shadow">
        <div class="flex items-start justify-between">
          <div class="flex-1 min-w-0">
            <h3 class="font-medium text-slate-800">{{ jd.title }}</h3>
            <p class="text-sm text-slate-500 mt-1">{{ jd.createdAt }}</p>
            <p class="text-sm text-slate-600 mt-2 line-clamp-3">{{ jd.rawText }}</p>
          </div>
          <button @click="handleDelete(jd.id)"
            class="px-3 py-1 text-sm text-red-500 hover:bg-red-50 rounded-lg transition-colors ml-4 shrink-0">删除</button>
        </div>
      </div>
    </div>

    <!-- 新建表单弹窗 -->
    <div v-if="showForm"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="showForm = false">
      <div class="bg-white rounded-2xl w-full max-w-lg m-4 p-6">
        <h3 class="text-lg font-bold mb-4">新建 JD</h3>
        <form @submit.prevent="handleCreate" class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1">职位标题</label>
            <input v-model="formTitle" required
              class="w-full px-3 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none" />
          </div>
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1">职位描述</label>
            <textarea v-model="formRawText" required rows="6"
              class="w-full px-3 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none resize-none"></textarea>
          </div>
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1">来源链接（可选）</label>
            <input v-model="formSourceUrl"
              class="w-full px-3 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none" />
          </div>
          <div class="flex justify-end gap-3">
            <button type="button" @click="showForm = false"
              class="px-4 py-2 text-slate-600 hover:bg-slate-100 rounded-lg transition-colors">取消</button>
            <button type="submit" :disabled="submitting"
              class="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors">
              {{ submitting ? '提交中...' : '创建' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
