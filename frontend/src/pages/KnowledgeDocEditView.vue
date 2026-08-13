<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NInput, NButton, NSpin, useMessage } from 'naive-ui';
import { getDocument, addDocument, updateDocument, getKbs, type KnowledgeBase } from '../api/knowledge';
import MarkdownEditor from '../components/MarkdownEditor.vue';

/**
 * 文档编辑页（参考 ThinkVerse knowledge/[id]/documents/[docId].vue）
 * 路由参数 docId === 'new' 表示新建，否则编辑已有文档
 * 语雀式单栏所见即所得编辑器 + 「保存并向量化 / 仅保存」双操作
 */
const route = useRoute();
const router = useRouter();
const message = useMessage();

const kbId = Number(route.params.kbId);
const docIdParam = String(route.params.docId);
const isNew = computed(() => docIdParam === 'new');

const loading = ref(!isNew.value);
const kbLoading = ref(true);
const saving = ref(false);
const saveWithVectorize = ref(false);

const kb = ref<KnowledgeBase | null>(null);
const title = ref('');
const content = ref('');

onMounted(async () => {
  // 加载知识库信息（页面标题展示）
  try {
    const res = await getKbs();
    kb.value = res.data.data.find((k) => k.id === kbId) ?? null;
  } catch {
    /* 知识库信息加载失败不阻塞编辑 */
  } finally {
    kbLoading.value = false;
  }
  // 编辑态：加载文档内容
  if (isNew.value) return;
  try {
    const res = await getDocument(kbId, Number(docIdParam));
    title.value = res.data.data.title;
    content.value = res.data.data.contentMd || '';
    loading.value = false;
  } catch {
    message.error('加载文档失败');
    router.push({ path: '/knowledge-bases', query: { kb: kbId } });
  }
});

async function handleSave(vectorize: boolean) {
  if (!title.value.trim()) {
    message.warning('请输入文档标题');
    return;
  }
  if (!content.value.trim()) {
    message.warning('文档内容不能为空');
    return;
  }
  saveWithVectorize.value = vectorize;
  saving.value = true;
  try {
    const payload = { title: title.value.trim(), contentMd: content.value, vectorize };
    if (isNew.value) {
      await addDocument(kbId, payload);
    } else {
      await updateDocument(kbId, Number(docIdParam), payload);
    }
    message.success(vectorize ? '已保存，正在后台向量化…' : '已保存（未向量化，暂不可被检索引用）');
    router.push({ path: '/knowledge-bases', query: { kb: kbId } });
  } catch {
    message.error('保存失败，请重试');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-5xl mx-auto px-4 sm:px-6 py-6">
      <!-- 返回 -->
      <div class="flex items-center gap-4 mb-5">
        <router-link
          :to="{ path: '/knowledge-bases', query: { kb: kbId } }"
          class="text-slate-400 hover:text-slate-600 text-sm transition-colors duration-200"
        >
          ← 返回知识库
        </router-link>
      </div>

      <!-- 页头 -->
      <div class="flex items-center justify-between mb-5">
        <div>
          <h2 class="text-xl font-bold text-slate-800 tracking-tight">{{ isNew ? '添加文档' : '编辑文档' }}</h2>
          <p v-if="kb" class="text-xs text-slate-400 mt-0.5">知识库：{{ kb.name }}</p>
        </div>
      </div>

      <!-- 加载中 -->
      <div v-if="loading || kbLoading" class="flex justify-center py-24">
        <n-spin size="large" description="加载中..." />
      </div>

      <div v-else class="bg-white rounded-2xl shadow-card p-5 sm:p-6">
        <!-- 标题 -->
        <div class="mb-4">
          <label class="block text-sm font-medium text-slate-700 mb-1.5">文档标题 *</label>
          <n-input
            v-model:value="title"
            size="large"
            placeholder="例如：ConcurrentHashMap 原理"
            maxlength="200"
            show-count
          />
        </div>

        <!-- 正文（语雀式所见即所得 Markdown 编辑器，大编辑区） -->
        <div class="mb-4">
          <label class="block text-sm font-medium text-slate-700 mb-1.5">文档内容（Markdown，语法即时渲染）*</label>
          <MarkdownEditor
            v-if="!loading"
            v-model="content"
            height="calc(100vh - 330px)"
            placeholder="输入 Markdown 内容：支持 # 标题、- 列表、``` 代码块、| 表格、> 引用等，输入后即时渲染…"
          />
        </div>

        <!-- 操作 -->
        <div class="flex items-center gap-3 pt-1">
          <n-button
            type="primary"
            size="large"
            :loading="saving && saveWithVectorize"
            :disabled="saving"
            @click="handleSave(true)"
          >
            保存并向量化
          </n-button>
          <n-button
            size="large"
            :loading="saving && !saveWithVectorize"
            :disabled="saving"
            @click="handleSave(false)"
          >
            仅保存
          </n-button>
          <n-button size="large" quaternary :disabled="saving" @click="router.push({ path: '/knowledge-bases', query: { kb: kbId } })">
            取消
          </n-button>
        </div>
        <p class="text-xs text-slate-400 mt-3">
          「保存并向量化」会切分文档并生成向量，之后面试时 AI 可检索引用；「仅保存」仅落库为草稿，可稍后再次编辑保存。
        </p>
      </div>
    </div>
  </div>
</template>
