<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NModal, NEmpty, NSpin, NButton, NInput, NTag, useMessage, useDialog } from 'naive-ui';
import {
  getKbs, createKb, deleteKb,
  listDocuments, deleteDocument,
  type KnowledgeBase, type KnowledgeDocument
} from '../api/knowledge';

const message = useMessage();
const dialog = useDialog();
const route = useRoute();
const router = useRouter();

const kbs = ref<KnowledgeBase[]>([]);
const loading = ref(false);
const showKbForm = ref(false);
const kbName = ref('');
const kbDesc = ref('');
const submittingKb = ref(false);

/* 文档管理：当前展开的知识库 */
const activeKb = ref<KnowledgeBase | null>(null);
const docs = ref<KnowledgeDocument[]>([]);
const loadingDocs = ref(false);
let docPollTimer: ReturnType<typeof setInterval> | null = null;

onMounted(async () => {
  await loadKbs();
  // 从文档编辑页跳回时自动展开对应知识库
  const kbParam = Number(route.query.kb);
  if (kbParam) {
    const target = kbs.value.find((k) => k.id === kbParam);
    if (target) await openDocs(target);
  }
});

async function loadKbs() {
  loading.value = true;
  try {
    const res = await getKbs();
    kbs.value = res.data.data;
  } catch {
    message.error('加载知识库列表失败');
  } finally {
    loading.value = false;
  }
}

async function handleCreateKb() {
  submittingKb.value = true;
  try {
    await createKb({ name: kbName.value, description: kbDesc.value || undefined });
    message.success('创建成功');
    showKbForm.value = false;
    kbName.value = '';
    kbDesc.value = '';
    await loadKbs();
  } catch {
    message.error('创建失败，请重试');
  } finally {
    submittingKb.value = false;
  }
}

function handleDeleteKb(kb: KnowledgeBase) {
  dialog.warning({
    title: '确认删除',
    content: `删除知识库「${kb.name}」后其全部文档与向量不可恢复，确定删除吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteKb(kb.id);
        message.success('已删除');
        if (activeKb.value?.id === kb.id) closeDocs();
        await loadKbs();
      } catch {
        message.error('删除失败');
      }
    }
  });
}

async function openDocs(kb: KnowledgeBase) {
  activeKb.value = kb;
  await loadDocs();
}

/* 展开/收起切换：再次点击已展开知识库的按钮时收起文档列表 */
function toggleDocs(kb: KnowledgeBase) {
  if (activeKb.value?.id === kb.id) {
    closeDocs();
  } else {
    openDocs(kb);
  }
}

async function loadDocs() {
  if (!activeKb.value) return;
  loadingDocs.value = true;
  try {
    const res = await listDocuments(activeKb.value.id);
    docs.value = res.data.data;
    // 有向量化中的文档时轮询状态
    const hasPending = docs.value.some(d => d.status === 'VECTORIZING');
    if (hasPending && !docPollTimer) {
      docPollTimer = setInterval(loadDocs, 3000);
    } else if (!hasPending && docPollTimer) {
      clearInterval(docPollTimer);
      docPollTimer = null;
    }
  } catch {
    message.error('加载文档失败');
  } finally {
    loadingDocs.value = false;
  }
}

function closeDocs() {
  activeKb.value = null;
  docs.value = [];
  if (docPollTimer) {
    clearInterval(docPollTimer);
    docPollTimer = null;
  }
}

async function handleAddDoc() {
  if (!activeKb.value) return;
  router.push(`/knowledge-bases/${activeKb.value.id}/documents/new`);
}

function handleEditDoc(doc: KnowledgeDocument) {
  if (!activeKb.value) return;
  router.push(`/knowledge-bases/${activeKb.value.id}/documents/${doc.id}`);
}

function handleDeleteDoc(doc: KnowledgeDocument) {
  if (!activeKb.value) return;
  const kbId = activeKb.value.id;
  dialog.warning({
    title: '确认删除',
    content: `删除文档「${doc.title}」后不可恢复，确定删除吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteDocument(kbId, doc.id);
        message.success('已删除');
        await loadDocs();
        await loadKbs();
      } catch {
        message.error('删除失败');
      }
    }
  });
}

function statusType(status: string): 'default' | 'info' | 'success' | 'error' {
  switch (status) {
    case 'ACTIVE': return 'success';
    case 'VECTORIZING': return 'info';
    case 'FAILED': return 'error';
    default: return 'default';
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'ACTIVE': return '可检索';
    case 'VECTORIZING': return '向量化中';
    case 'FAILED': return '向量化失败';
    default: return '草稿';
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-4xl mx-auto px-4 sm:px-6 py-8">
      <div class="flex items-center gap-4 mb-6">
        <router-link to="/home" class="text-slate-400 hover:text-slate-600 text-sm transition-colors duration-200">← 返回首页</router-link>
      </div>

      <div class="flex items-center justify-between mb-6">
        <h2 class="text-2xl font-bold text-slate-800 tracking-tight">知识库管理</h2>
        <n-button type="primary" @click="showKbForm = true">新建知识库</n-button>
      </div>

      <div v-if="loading" class="flex justify-center py-16">
        <n-spin size="large" description="加载中..." />
      </div>
      <div v-else-if="kbs.length === 0" class="bg-white rounded-2xl shadow-card py-16">
        <n-empty description="暂无知识库，点击右上角新建" />
      </div>

      <div v-else class="grid gap-4">
        <div v-for="kb in kbs" :key="kb.id"
          class="bg-white rounded-2xl shadow-card p-4 sm:p-5 hover:shadow-card-hover transition-all duration-200 cursor-pointer"
          @click="toggleDocs(kb)">
          <div class="flex items-start justify-between">
            <div class="flex-1 min-w-0">
              <h3 class="font-semibold text-slate-800">{{ kb.name }}</h3>
              <p class="text-sm text-slate-400 mt-0.5">{{ kb.createdAt }} · 文档 {{ kb.documentCount }} 篇</p>
              <p v-if="kb.description" class="text-sm text-slate-600 mt-2 leading-relaxed">{{ kb.description }}</p>
            </div>
            <div class="flex gap-2 ml-4 shrink-0">
              <n-button size="small" secondary type="primary" @click.stop="toggleDocs(kb)">
                {{ activeKb?.id === kb.id ? '收起' : '文档管理' }}
              </n-button>
              <n-button size="small" tertiary type="error" @click.stop="handleDeleteKb(kb)">删除</n-button>
            </div>
          </div>

          <!-- 文档列表（展开区）：阻止冒泡，点击列表内部不会误收起 -->
          <div v-if="activeKb?.id === kb.id" class="mt-4 border-t border-slate-100 pt-4" @click.stop>
            <div class="flex items-center justify-between mb-3">
              <h4 class="text-sm font-semibold text-slate-700">文档列表</h4>
              <n-button size="small" type="primary" @click="handleAddDoc">添加文档</n-button>
            </div>
            <div v-if="loadingDocs" class="flex justify-center py-6">
              <n-spin description="加载中..." />
            </div>
            <n-empty v-else-if="docs.length === 0" description="暂无文档，点击右上角添加" size="small" class="py-4" />
            <div v-else class="grid gap-2">
              <div v-for="doc in docs" :key="doc.id"
                class="flex items-center justify-between border border-slate-200/80 bg-slate-50/60 rounded-xl px-4 py-3">
                <div class="min-w-0">
                  <p class="text-sm font-medium text-slate-700 truncate">{{ doc.title }}</p>
                  <p class="text-xs text-slate-400 mt-0.5">分片 {{ doc.chunkCount }} · {{ doc.updatedAt }}</p>
                </div>
                <div class="flex items-center gap-2 shrink-0 ml-3">
                  <n-tag size="small" :bordered="false" :type="statusType(doc.status)">{{ statusLabel(doc.status) }}</n-tag>
                  <n-button size="tiny" secondary type="primary" @click="handleEditDoc(doc)">编辑</n-button>
                  <n-button size="tiny" tertiary type="error" @click="handleDeleteDoc(doc)">删除</n-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建知识库弹窗 -->
    <n-modal v-model:show="showKbForm" preset="card" title="新建知识库" class="max-w-lg mx-4" :bordered="false">
      <form @submit.prevent="handleCreateKb" class="space-y-4">
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">名称</label>
          <n-input v-model:value="kbName" placeholder="如：Java 面试知识" size="large" />
        </div>
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">描述（可选）</label>
          <n-input v-model:value="kbDesc" placeholder="该知识库的用途说明" size="large" />
        </div>
        <div class="flex justify-end gap-3 pt-2">
          <n-button @click="showKbForm = false">取消</n-button>
          <n-button type="primary" attr-type="submit" :loading="submittingKb" :disabled="!kbName">
            {{ submittingKb ? '提交中...' : '创建' }}
          </n-button>
        </div>
      </form>
    </n-modal>
  </div>
</template>
