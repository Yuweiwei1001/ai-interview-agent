<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { NModal, NEmpty, NSpin, NButton, NInput, useMessage, useDialog } from 'naive-ui';
import { getJds, deleteJd, createJd, updateJd, type Jd } from '../api/jd';

const message = useMessage();
const dialog = useDialog();

const jds = ref<Jd[]>([]);
const loading = ref(false);
const showForm = ref(false);
const formTitle = ref('');
const formRawText = ref('');
const formSourceUrl = ref('');
const submitting = ref(false);
const previewJd = ref<Jd | null>(null);

/* 编辑态：editingId 为 null 表示新建 */
const editingId = ref<number | null>(null);

function openCreate() {
  editingId.value = null;
  formTitle.value = '';
  formRawText.value = '';
  formSourceUrl.value = '';
  showForm.value = true;
}

function openEdit(jd: Jd) {
  editingId.value = jd.id;
  formTitle.value = jd.title;
  formRawText.value = jd.rawText;
  formSourceUrl.value = jd.sourceUrl || '';
  previewJd.value = null;
  showForm.value = true;
}

onMounted(() => loadJds());

async function loadJds() {
  loading.value = true;
  try {
    const res = await getJds();
    jds.value = res.data.data;
  } catch {
    message.error('加载 JD 列表失败');
  } finally {
    loading.value = false;
  }
}

async function handleSubmit() {
  submitting.value = true;
  try {
    const payload = { title: formTitle.value, rawText: formRawText.value, sourceUrl: formSourceUrl.value || undefined };
    if (editingId.value != null) {
      await updateJd(editingId.value, payload);
      message.success('修改成功');
    } else {
      await createJd(payload);
      message.success('创建成功');
    }
    showForm.value = false;
    formTitle.value = '';
    formRawText.value = '';
    formSourceUrl.value = '';
    await loadJds();
  } catch {
    message.error(editingId.value != null ? '修改失败，请重试' : '创建失败，请重试');
  } finally {
    submitting.value = false;
  }
}

/* 美化：原生 confirm 迁移为 n-dialog，交互流程不变 */
function handleDelete(id: number) {
  dialog.warning({
    title: '确认删除',
    content: '删除后不可恢复，确定删除该 JD 吗？',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteJd(id);
        message.success('已删除');
        await loadJds();
      } catch {
        message.error('删除失败');
      }
    }
  });
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
        <h2 class="text-2xl font-bold text-slate-800 tracking-tight">职位描述管理</h2>
        <n-button type="primary" @click="openCreate">新建 JD</n-button>
      </div>

      <!-- 美化：加载/空状态升级为组件化展示 -->
      <div v-if="loading" class="flex justify-center py-16">
        <n-spin size="large" description="加载中..." />
      </div>
      <div v-else-if="jds.length === 0" class="bg-white rounded-2xl shadow-card py-16">
        <n-empty description="暂无 JD，点击右上角新建" />
      </div>

      <div v-else class="grid gap-4">
        <!-- 美化：列表卡片 hover 阴影加深 -->
        <div v-for="jd in jds" :key="jd.id"
          class="bg-white rounded-2xl shadow-card p-4 sm:p-5 hover:shadow-card-hover transition-all duration-200">
          <div class="flex items-start justify-between">
            <div class="flex-1 min-w-0">
              <h3 class="font-semibold text-slate-800">{{ jd.title }}</h3>
              <p class="text-sm text-slate-400 mt-0.5">{{ jd.createdAt }}</p>
              <p class="text-sm text-slate-600 mt-2 line-clamp-3 leading-relaxed">{{ jd.rawText }}</p>
            </div>
            <div class="flex gap-2 ml-4 shrink-0">
              <n-button size="small" tertiary type="primary" @click="previewJd = jd">预览</n-button>
              <n-button size="small" tertiary type="info" @click="openEdit(jd)">编辑</n-button>
              <n-button size="small" tertiary type="error" @click="handleDelete(jd.id)">删除</n-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 美化：新建/编辑表单弹窗迁移为 n-modal（自带 ESC 关闭/遮罩动画） -->
    <n-modal v-model:show="showForm" preset="card" :title="editingId != null ? '编辑 JD' : '新建 JD'" class="max-w-lg mx-4" :bordered="false">
      <form @submit.prevent="handleSubmit" class="space-y-4">
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">职位标题</label>
          <n-input v-model:value="formTitle" placeholder="如：高级 Java 开发工程师" size="large" />
        </div>
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">职位描述</label>
          <n-input v-model:value="formRawText" type="textarea" placeholder="粘贴完整 JD 文本"
            :autosize="{ minRows: 5, maxRows: 12 }" />
        </div>
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">来源链接（可选）</label>
          <n-input v-model:value="formSourceUrl" placeholder="https://" size="large" />
        </div>
        <div class="flex justify-end gap-3 pt-2">
          <n-button @click="showForm = false">取消</n-button>
          <n-button type="primary" attr-type="submit" :loading="submitting"
            :disabled="!formTitle || !formRawText">
            {{ submitting ? '提交中...' : (editingId != null ? '保存' : '创建') }}
          </n-button>
        </div>
      </form>
    </n-modal>

    <!-- JD 预览弹窗 -->
    <n-modal :show="!!previewJd" preset="card" :title="previewJd?.title"
      class="max-w-2xl mx-4" :bordered="false" @update:show="previewJd = null">
      <div class="max-h-[60vh] overflow-y-auto whitespace-pre-wrap text-sm leading-relaxed text-slate-700">
        {{ previewJd?.rawText }}
      </div>
      <template #footer>
        <div class="flex justify-end gap-3">
          <n-button v-if="previewJd" type="primary" secondary @click="openEdit(previewJd)">编辑</n-button>
          <n-button @click="previewJd = null">关闭</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>
