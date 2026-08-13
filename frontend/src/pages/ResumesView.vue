<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { NModal, NEmpty, NSpin, NButton, NInput, useMessage, useDialog } from 'naive-ui';
import { getResumes, deleteResume, uploadResume, updateResume, type Resume } from '../api/resume';

const message = useMessage();
const dialog = useDialog();

const resumes = ref<Resume[]>([]);
const loading = ref(false);
const uploadLoading = ref(false);
const previewResume = ref<Resume | null>(null);

/* 编辑简历 */
const editingResume = ref<Resume | null>(null);
const editText = ref('');
const saving = ref(false);

function openEdit(r: Resume) {
  editingResume.value = r;
  editText.value = r.rawText;
  previewResume.value = null;
}

async function handleSaveEdit() {
  if (!editingResume.value) return;
  if (!editText.value.trim()) {
    message.warning('简历内容不能为空');
    return;
  }
  saving.value = true;
  try {
    await updateResume(editingResume.value.id, editText.value);
    message.success('保存成功');
    editingResume.value = null;
    await loadResumes();
  } catch (e: any) {
    message.error(e.response?.data?.msg || '保存失败，请重试');
  } finally {
    saving.value = false;
  }
}

onMounted(() => loadResumes());

async function loadResumes() {
  loading.value = true;
  try {
    const res = await getResumes();
    resumes.value = res.data.data;
  } catch {
    message.error('加载简历列表失败');
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
    message.success('上传成功');
    await loadResumes();
  } catch {
    message.error('上传失败，请重试');
  } finally {
    uploadLoading.value = false;
    input.value = '';
  }
}

/* 美化：原生 confirm 迁移为 n-dialog，交互流程不变 */
function handleDelete(id: number) {
  dialog.warning({
    title: '确认删除',
    content: '删除后不可恢复，确定删除该简历吗？',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteResume(id);
        message.success('已删除');
        await loadResumes();
      } catch {
        message.error('删除失败');
      }
    }
  });
}

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + 'B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB';
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB';
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-4xl mx-auto px-4 sm:px-6 py-8">
      <!-- 美化：页头返回导航 -->
      <div class="flex items-center gap-4 mb-6">
        <router-link to="/home" class="text-slate-400 hover:text-slate-600 text-sm transition-colors duration-200">← 返回首页</router-link>
      </div>

      <div class="flex items-center justify-between mb-6">
        <h2 class="text-2xl font-bold text-slate-800 tracking-tight">简历管理</h2>
        <!-- 美化：上传按钮（保留隐藏 file input 逻辑不变） -->
        <label
          class="inline-flex items-center px-4 h-9 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 cursor-pointer shadow-sm transition-all duration-200"
          :class="{ 'opacity-60 pointer-events-none': uploadLoading }">
          {{ uploadLoading ? '上传中...' : '上传简历' }}
          <input type="file" accept=".pdf,.docx,.doc,.txt" class="hidden" @change="handleUpload" />
        </label>
      </div>

      <!-- 美化：加载/空状态升级为组件化展示 -->
      <div v-if="loading" class="flex justify-center py-16">
        <n-spin size="large" description="加载中..." />
      </div>
      <div v-else-if="resumes.length === 0" class="bg-white rounded-2xl shadow-card py-16">
        <n-empty description="暂无简历，点击右上角上传" />
      </div>

      <div v-else class="grid gap-4">
        <!-- 美化：列表卡片 hover 阴影加深 -->
        <div v-for="r in resumes" :key="r.id"
          class="bg-white rounded-2xl shadow-card p-4 sm:p-5 flex items-center justify-between hover:shadow-card-hover transition-all duration-200">
          <div class="flex-1 min-w-0">
            <h3 class="font-semibold text-slate-800 truncate">{{ r.fileName }}</h3>
            <p class="text-sm text-slate-400 mt-0.5">{{ formatSize(r.fileSize) }} · {{ r.createdAt }}</p>
          </div>
          <div class="flex gap-2 ml-4 shrink-0">
            <n-button size="small" tertiary type="primary" @click="previewResume = r">预览</n-button>
            <n-button size="small" tertiary type="info" @click="openEdit(r)">编辑</n-button>
            <n-button size="small" tertiary type="error" @click="handleDelete(r.id)">删除</n-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 美化：预览弹窗迁移为 n-modal（自带 ESC 关闭/遮罩动画） -->
    <n-modal :show="!!previewResume" preset="card" :title="previewResume?.fileName"
      class="max-w-2xl mx-4" :bordered="false" @update:show="previewResume = null">
      <div class="max-h-[60vh] overflow-y-auto whitespace-pre-wrap text-sm leading-relaxed text-slate-700">
        {{ previewResume?.rawText }}
      </div>
    </n-modal>

    <!-- 编辑简历弹窗 -->
    <n-modal :show="!!editingResume" preset="card" :title="`编辑简历：${editingResume?.fileName ?? ''}`"
      class="max-w-3xl mx-4" :bordered="false" @update:show="(v: boolean) => { if (!v) editingResume = null }">
      <n-input v-model:value="editText" type="textarea" placeholder="简历文本内容"
        :autosize="{ minRows: 10, maxRows: 20 }" />
      <template #footer>
        <div class="flex justify-end gap-3">
          <n-button @click="editingResume = null">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSaveEdit">
            {{ saving ? '保存中...' : '保存' }}
          </n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>
