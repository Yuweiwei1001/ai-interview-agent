<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { getResumes, deleteResume, uploadResume, type Resume } from '../api/resume';

const resumes = ref<Resume[]>([]);
const loading = ref(false);
const uploadLoading = ref(false);
const previewResume = ref<Resume | null>(null);

onMounted(() => loadResumes());

async function loadResumes() {
  loading.value = true;
  try {
    const res = await getResumes();
    resumes.value = res.data.data;
  } finally {
    loading.value = false;
  }
}

async function handleUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  if (!input.files?.length) return;
  uploadLoading.value = true;
  try {
    await uploadResume(input.files[0]);
    await loadResumes();
  } finally {
    uploadLoading.value = false;
    input.value = '';
  }
}

async function handleDelete(id: number) {
  if (!confirm('确认删除？')) return;
  await deleteResume(id);
  await loadResumes();
}

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + 'B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB';
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB';
}
</script>

<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <h2 class="text-2xl font-bold text-slate-800">简历管理</h2>
      <label class="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 cursor-pointer transition-colors">
        {{ uploadLoading ? '上传中...' : '上传简历' }}
        <input type="file" accept=".pdf,.docx,.doc,.txt" class="hidden" @change="handleUpload" />
      </label>
    </div>

    <div v-if="loading" class="text-center py-8 text-slate-500">加载中...</div>
    <div v-else-if="resumes.length === 0" class="text-center py-8 text-slate-400">暂无简历，请上传</div>
    <div v-else class="grid gap-4">
      <div v-for="r in resumes" :key="r.id"
        class="bg-white rounded-xl shadow-sm p-4 flex items-center justify-between hover:shadow-md transition-shadow">
        <div class="flex-1 min-w-0">
          <h3 class="font-medium text-slate-800 truncate">{{ r.fileName }}</h3>
          <p class="text-sm text-slate-500">{{ formatSize(r.fileSize) }}  ·  {{ r.createdAt }}</p>
        </div>
        <div class="flex gap-2 ml-4">
          <button @click="previewResume = r"
            class="px-3 py-1 text-sm text-blue-600 hover:bg-blue-50 rounded-lg transition-colors">预览</button>
          <button @click="handleDelete(r.id)"
            class="px-3 py-1 text-sm text-red-500 hover:bg-red-50 rounded-lg transition-colors">删除</button>
        </div>
      </div>
    </div>

    <!-- 预览弹窗 -->
    <div v-if="previewResume"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="previewResume = null">
      <div class="bg-white rounded-2xl w-full max-w-2xl max-h-[80vh] m-4 flex flex-col">
        <div class="flex items-center justify-between p-4 border-b">
          <h3 class="font-bold text-lg">{{ previewResume.fileName }}</h3>
          <button @click="previewResume = null" class="text-slate-400 hover:text-slate-600 text-xl">&times;</button>
        </div>
        <div class="p-4 overflow-y-auto whitespace-pre-wrap text-sm text-slate-700">{{ previewResume.rawText }}</div>
      </div>
    </div>
  </div>
</template>