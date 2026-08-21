<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue';
import {
  NModal, NInput, NButton, NSpin, NTag, NSwitch, NDataTable,
  useMessage, useDialog, type DataTableColumns,
} from 'naive-ui';
import BackButton from '../components/BackButton.vue';
import {
  getTermDicts, createTermDict, updateTermDict, deleteTermDict,
  type TermDictItem, type TermDictSavePayload,
} from '../api/termDict';

const message = useMessage();
const dialog = useDialog();

const items = ref<TermDictItem[]>([]);
const loading = ref(false);
const keyword = ref('');

const showForm = ref(false);
const submitting = ref(false);
const editingId = ref<number | null>(null);
const form = ref<{
  term: string; pinyin: string; category: string; aliasesText: string; enabled: boolean;
}>({ term: '', pinyin: '', category: '', aliasesText: '', enabled: true });

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return items.value;
  return items.value.filter(i =>
    i.term.toLowerCase().includes(k)
    || (i.pinyin || '').toLowerCase().includes(k)
    || (i.category || '').toLowerCase().includes(k)
  );
});

const columns: DataTableColumns<TermDictItem> = [
  { title: '术语', key: 'term', width: 180,
    render: r => h('div', { class: 'font-semibold text-slate-800' }, r.term) },
  { title: '拼音', key: 'pinyin', ellipsis: { tooltip: true } },
  { title: '分类', key: 'category', width: 120,
    render: r => r.category ? h(NTag, { size: 'small', bordered: false }, { default: () => r.category }) : '-' },
  { title: '别名', key: 'aliases', ellipsis: { tooltip: true },
    render: r => {
      if (!r.aliases) return '-';
      let list: string[] = [];
      try { const v = JSON.parse(r.aliases); list = Array.isArray(v) ? v : []; } catch { /* 忽略 */ }
      return list.join(' / ');
    }
  },
  { title: '状态', key: 'enabled', width: 100,
    render: r => h(NSwitch, {
      value: r.enabled, checkedValue: true, uncheckedValue: false,
      onUpdateValue: (v) => toggleEnabled(r, v),
    }) },
  { title: '操作', key: 'actions', width: 160,
    render: r => h('div', { class: 'flex gap-2' }, [
      h(NButton, { size: 'small', tertiary: true, type: 'info', onClick: () => openEdit(r) },
        { default: () => '编辑' }),
      h(NButton, { size: 'small', tertiary: true, type: 'error', onClick: () => handleDelete(r.id) },
        { default: () => '删除' }),
    ])
  },
];

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const res = await getTermDicts();
    items.value = res.data.data;
  } catch {
    message.error('加载词库失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  form.value = { term: '', pinyin: '', category: '', aliasesText: '', enabled: true };
  showForm.value = true;
}

function openEdit(row: TermDictItem) {
  editingId.value = row.id;
  let aliases: string[] = [];
  try {
    const v = JSON.parse(row.aliases || '');
    aliases = Array.isArray(v) ? v : [];
  } catch { /* 忽略非法 JSON */ }
  form.value = {
    term: row.term,
    pinyin: row.pinyin,
    category: row.category || '',
    aliasesText: aliases.join(', '),
    enabled: row.enabled,
  };
  showForm.value = true;
}

function toggleEnabled(row: TermDictItem, enabled: boolean) {
  const payload: TermDictSavePayload = {
    term: row.term, pinyin: row.pinyin, category: row.category || undefined,
    aliases: parseAliasesText(row.aliases || ''), enabled,
  };
  updateTermDict(row.id, payload)
    .then(() => { message.success(enabled ? '已启用' : '已停用'); void load(); })
    .catch(() => { message.error('更新失败'); });
}

function handleDelete(id: number) {
  dialog.warning({
    title: '确认删除',
    content: '删除后该术语将不再参与 ASR 纠错，确定删除吗？',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteTermDict(id);
        message.success('已删除');
        await load();
      } catch {
        message.error('删除失败');
      }
    }
  });
}

function parseAliasesText(text: string): string[] {
  return text.split(/[,，\n]/).map(s => s.trim()).filter(Boolean);
}

async function handleSubmit() {
  submitting.value = true;
  try {
    const payload: TermDictSavePayload = {
      term: form.value.term,
      pinyin: form.value.pinyin,
      category: form.value.category || undefined,
      aliases: parseAliasesText(form.value.aliasesText),
      enabled: form.value.enabled,
    };
    if (editingId.value != null) {
      await updateTermDict(editingId.value, payload);
      message.success('保存成功');
    } else {
      await createTermDict(payload);
      message.success('创建成功');
    }
    showForm.value = false;
    await load();
  } catch (e: any) {
    message.error(e?.data?.msg || (editingId.value != null ? '保存失败' : '创建失败'));
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-6xl mx-auto px-4 sm:px-6 py-8">
      <div class="flex items-center gap-4 mb-6">
        <BackButton to="/home" label="返回首页" />
      </div>

      <div class="flex items-center justify-between mb-4 gap-4 flex-wrap">
        <div>
          <h2 class="text-2xl font-bold text-slate-800 tracking-tight">ASR 纠错词库</h2>
          <p class="text-sm text-slate-500 mt-1">手动维护语音纠错术语，保存后立即生效（全局共享）</p>
        </div>
        <div class="flex items-center gap-3">
          <n-input v-model:value="keyword" placeholder="搜索术语/拼音/分类" clearable style="width: 220px" />
          <n-button type="primary" @click="openCreate">新增术语</n-button>
        </div>
      </div>

      <div v-if="loading" class="flex justify-center py-16">
        <n-spin size="large" description="加载中..." />
      </div>
      <n-data-table v-else :columns="columns" :data="filtered" :bordered="false"
        :pagination="{ pageSize: 12 }" class="bg-white rounded-2xl shadow-card" />
    </div>

    <n-modal v-model:show="showForm" preset="card"
      :title="editingId != null ? '编辑术语' : '新增术语'" class="max-w-2xl mx-4" :bordered="false">
      <form @submit.prevent="handleSubmit" class="space-y-4">
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">规范术语 <span class="text-red-500">*</span></label>
          <n-input v-model:value="form.term" placeholder="如：Raft、零拷贝、MVCC" size="large" />
        </div>
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">拼音（空格分隔读音） <span class="text-red-500">*</span></label>
          <n-input v-model:value="form.pinyin" placeholder="如：la fu te；多音字用 | 分隔，如 zhong | chong liang"
            size="large" />
          <p class="text-xs text-slate-400 mt-1">英文按中文使用者常见读音转写，缩写按字母名读音；多音字候选用 | 分隔</p>
        </div>
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1.5">分类（可选）</label>
            <n-input v-model:value="form.category" clearable
              placeholder="输入任意分类，如：middleware、database" />
            <p class="text-xs text-slate-400 mt-1">仅作术语分组展示，不参与纠错逻辑，可留空</p>
          </div>
          <div>
            <label class="block text-sm font-medium text-slate-700 mb-1.5">状态</label>
            <n-switch v-model:value="form.enabled" />
            <span class="ml-2 text-sm text-slate-500">{{ form.enabled ? '启用' : '停用' }}</span>
          </div>
        </div>
        <div>
          <label class="block text-sm font-medium text-slate-700 mb-1.5">别名 / 常见错误写法</label>
          <n-input v-model:value="form.aliasesText" placeholder="多个用逗号分隔，如：springboot, spring boot"
            size="large" />
        </div>
        <div class="flex justify-end gap-3 pt-2">
          <n-button @click="showForm = false">取消</n-button>
          <n-button type="primary" attr-type="submit" :loading="submitting"
            :disabled="!form.term || !form.pinyin">
            {{ submitting ? '提交中...' : (editingId != null ? '保存' : '创建') }}
          </n-button>
        </div>
      </form>
    </n-modal>
  </div>
</template>