<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue';
import { NButton, NInput, NEmpty, NSpin, useMessage, useDialog } from 'naive-ui';
import { marked } from 'marked';
import {
  createChatSession, listChatSessions, getChatMessages, deleteChatSession, askStream,
  type ChatSession, type ChatMessage, type ChatSource
} from '../api/chat';

const message = useMessage();
const dialog = useDialog();

const sessions = ref<ChatSession[]>([]);
const activeSessionId = ref<number | null>(null);
const msgs = ref<Array<ChatMessage & { streaming?: boolean; refusal?: boolean; sourcesParsed?: ChatSource[] }>>([]);
const loadingSessions = ref(false);
const loadingMessages = ref(false);
const input = ref('');
const sending = ref(false);
const expandedSources = ref<Record<number, boolean>>({});
const messagesEl = ref<HTMLElement | null>(null);

onMounted(async () => {
  await loadSessions();
});

async function loadSessions() {
  loadingSessions.value = true;
  try {
    const res = await listChatSessions();
    sessions.value = res.data.data || [];
  } catch {
    message.error('加载会话列表失败');
  } finally {
    loadingSessions.value = false;
  }
}

async function handleNewSession() {
  try {
    const res = await createChatSession();
    const s = res.data.data;
    sessions.value.unshift(s);
    await openSession(s.id);
  } catch {
    message.error('创建会话失败');
  }
}

async function openSession(id: number) {
  if (sending.value) {
    message.warning('正在回答中，请稍候');
    return;
  }
  activeSessionId.value = id;
  loadingMessages.value = true;
  msgs.value = [];
  try {
    const res = await getChatMessages(id);
    msgs.value = (res.data.data || []).map(m => ({
      ...m,
      sourcesParsed: parseSources(m.sources)
    }));
    await scrollToBottom();
  } catch {
    message.error('加载对话记录失败');
  } finally {
    loadingMessages.value = false;
  }
}

function parseSources(raw?: string | null): ChatSource[] | undefined {
  if (!raw) return undefined;
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) && arr.length ? arr : undefined;
  } catch {
    return undefined;
  }
}

function handleDeleteSession(s: ChatSession) {
  dialog.warning({
    title: '确认删除',
    content: `删除会话「${s.title}」后对话记录不可恢复，确定删除吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteChatSession(s.id);
        sessions.value = sessions.value.filter(x => x.id !== s.id);
        if (activeSessionId.value === s.id) {
          activeSessionId.value = null;
          msgs.value = [];
        }
        message.success('已删除');
      } catch {
        message.error('删除失败');
      }
    }
  });
}

async function handleSend() {
  const q = input.value.trim();
  if (!q || sending.value || !activeSessionId.value) return;
  if (!activeSessionId.value) {
    message.info('请先在左侧新建或选择一个会话');
    return;
  }
  const sid = activeSessionId.value;
  input.value = '';
  sending.value = true;
  msgs.value.push({ role: 'user', content: q });
  const assistant = ref<ChatMessage & { streaming?: boolean; refusal?: boolean; sourcesParsed?: ChatSource[] }>({
    role: 'assistant', content: '', streaming: true
  });
  msgs.value.push(assistant.value);
  await scrollToBottom();

  askStream(sid, q, {
    delta: (t) => { assistant.value.content += t; scrollToBottom(); },
    sources: (s) => { assistant.value.sourcesParsed = s; },
    refusal: (msg) => {
      assistant.value.content = msg;
      assistant.value.refusal = true;
      assistant.value.streaming = false;
      finish(sid);
    },
    done: () => {
      assistant.value.streaming = false;
      finish(sid);
    },
    error: (msg) => {
      if (!assistant.value.content) {
        assistant.value.content = msg;
        assistant.value.refusal = true;
      }
      assistant.value.streaming = false;
      finish(sid);
    }
  });
}

function finish(sid: number) {
  sending.value = false;
  // 刷新列表（标题可能在首问后更新）并保持当前选中
  listChatSessions().then(res => {
    sessions.value = res.data.data || [];
    activeSessionId.value = sid;
  }).catch(() => { /* 忽略 */ });
}

function toggleSources(idx: number) {
  expandedSources.value[idx] = !expandedSources.value[idx];
}

function renderMd(text: string): string {
  try {
    return marked.parse(text, { async: false }) as string;
  } catch {
    return text;
  }
}

async function scrollToBottom() {
  await nextTick();
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !(e as any).isComposing) {
    e.preventDefault();
    handleSend();
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <div class="max-w-6xl mx-auto px-4 sm:px-6 py-8">
      <div class="flex items-center gap-4 mb-6">
        <router-link to="/home" class="text-slate-400 hover:text-slate-600 text-sm transition-colors duration-200">← 返回首页</router-link>
      </div>

      <div class="flex items-center justify-between mb-6">
        <div>
          <h2 class="text-2xl font-bold text-slate-800 tracking-tight">知识问答</h2>
          <p class="text-sm text-slate-400 mt-1">基于你在知识笔记中的全部文档回答，超出范围的问题将拒答</p>
        </div>
      </div>

      <div class="flex gap-4 items-stretch">
        <!-- 左侧会话列表 -->
        <div class="w-60 shrink-0 bg-white rounded-2xl shadow-card p-3 flex flex-col">
          <n-button type="primary" block @click="handleNewSession">＋ 新建对话</n-button>
          <div v-if="loadingSessions" class="flex justify-center py-8">
            <n-spin size="small" />
          </div>
          <n-empty v-else-if="sessions.length === 0" description="暂无对话" size="small" class="py-8" />
          <div v-else class="mt-3 overflow-y-auto flex-1 space-y-1">
            <div v-for="s in sessions" :key="s.id"
              class="group flex items-center gap-1 rounded-xl px-3 py-2 cursor-pointer transition-colors duration-150"
              :class="activeSessionId === s.id ? 'bg-blue-50 text-blue-700' : 'hover:bg-slate-50 text-slate-600'"
              @click="openSession(s.id)">
              <div class="flex-1 min-w-0">
                <p class="text-sm font-medium truncate">{{ s.title }}</p>
                <p class="text-xs text-slate-400 mt-0.5 truncate">{{ s.updatedTime || s.updatedAt }}</p>
              </div>
              <n-button size="tiny" quaternary type="error" class="shrink-0 opacity-0 group-hover:opacity-100 transition-opacity"
                @click.stop="handleDeleteSession(s)">删</n-button>
            </div>
          </div>
        </div>

        <!-- 右侧对话区 -->
        <div class="flex-1 min-w-0 bg-white rounded-2xl shadow-card flex flex-col" style="height: calc(100vh - 240px); min-height: 480px">
          <div v-if="!activeSessionId" class="flex-1 flex items-center justify-center">
            <n-empty description="选择左侧对话，或新建一个开始提问" />
          </div>
          <template v-else>
            <div ref="messagesEl" class="flex-1 overflow-y-auto p-5 space-y-4">
              <div v-if="loadingMessages" class="flex justify-center py-8">
                <n-spin description="加载中..." />
              </div>
              <template v-else>
                <div v-for="(m, idx) in msgs" :key="idx" class="flex" :class="m.role === 'user' ? 'justify-end' : 'justify-start'">
                  <div class="max-w-[85%]">
                    <!-- 用户气泡 -->
                    <div v-if="m.role === 'user'"
                      class="bg-blue-600 text-white rounded-2xl rounded-br-md px-4 py-2.5 text-sm whitespace-pre-wrap break-words shadow-sm">
                      {{ m.content }}
                    </div>
                    <!-- 助手气泡 -->
                    <template v-else>
                      <div v-if="m.refusal"
                        class="bg-amber-50 border border-amber-200 text-amber-700 rounded-2xl rounded-bl-md px-4 py-2.5 text-sm">
                        {{ m.content }}
                      </div>
                      <div v-else-if="m.content"
                        class="bg-slate-100 text-slate-800 rounded-2xl rounded-bl-md px-4 py-2.5 text-sm prose prose-sm prose-slate max-w-none prose-pre:bg-slate-800 prose-pre:text-slate-100 prose-code:before:content-none prose-code:after:content-none"
                        v-html="renderMd(m.content)"></div>
                      <div v-if="m.streaming" class="text-xs text-slate-400 mt-1 px-1">
                        <span class="inline-block animate-pulse">▍回答生成中…</span>
                      </div>
                      <!-- 引用来源 -->
                      <div v-if="m.sourcesParsed && !m.streaming" class="mt-2">
                        <button class="text-xs text-slate-400 hover:text-blue-600 transition-colors"
                          @click="toggleSources(idx)">
                          📎 引用来源（{{ m.sourcesParsed.length }} 篇）{{ expandedSources[idx] ? '▲' : '▼' }}
                        </button>
                        <div v-if="expandedSources[idx]" class="mt-2 space-y-2">
                          <div v-for="(src, si) in m.sourcesParsed" :key="si"
                            class="bg-slate-50 border border-slate-200/80 rounded-xl px-3 py-2">
                            <p class="text-xs font-semibold text-slate-600">{{ src.title }}</p>
                            <p class="text-xs text-slate-400 mt-1 leading-relaxed">{{ src.excerpt }}</p>
                          </div>
                        </div>
                      </div>
                    </template>
                  </div>
                </div>
              </template>
            </div>

            <!-- 输入区 -->
            <div class="border-t border-slate-100 p-4">
              <div class="flex gap-3 items-end">
                <n-input v-model:value="input" type="textarea" placeholder="基于你的知识笔记提问，Enter 发送 / Shift+Enter 换行"
                  :rows="2" :disabled="sending" @keydown="onKeydown" />
                <n-button type="primary" :loading="sending" :disabled="!input.trim()" @click="handleSend">发送</n-button>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
