<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';

const props = defineProps<{
  question: string;
}>();

/* ---------- 标题提取 ----------
 * 首行含「：」且分隔位 ≤ 20 → 冒号前为标题，冒号后内容并入正文；
 * 首行形如「1. 两数之和」（≤30 字）→ 整行为标题；
 * 首行 ≤ 16 字 → 整行为标题；
 * 否则标题「编程题」，全部文本作为正文。 */
/* 剥离标题行的 markdown 标记（## / ** 等），避免字面渲染 */
function stripMd(s: string): string {
  return s.replace(/^#{1,6}\s*/, '').replace(/^\*\*/, '').replace(/\*\*$/, '').trim();
}

function splitTitle(raw: string): { title: string; body: string } {
  const text = raw.trim();
  if (!text) return { title: '编程题', body: '' };
  const nl = text.indexOf('\n');
  const firstLine = (nl === -1 ? text : text.slice(0, nl)).trim();
  const rest = nl === -1 ? '' : text.slice(nl + 1).trim();

  if (firstLine.length <= 40) {
    const colonIdx = firstLine.indexOf('：');
    if (colonIdx > 0 && colonIdx <= 20) {
      const after = firstLine.slice(colonIdx + 1).trim();
      return { title: stripMd(firstLine.slice(0, colonIdx).trim()), body: [after, rest].filter(Boolean).join('\n') };
    }
    if (/^\d{1,3}[.、]\s*\S/.test(firstLine) && firstLine.length <= 30) {
      return { title: stripMd(firstLine.replace('、', '.')), body: rest };
    }
    if (firstLine.length <= 16) {
      return { title: stripMd(firstLine), body: rest };
    }
  }
  return { title: '编程题', body: text };
}

/* ---------- 结构化解析 ----------
 * 「示例 N」→ 示例卡片；「输入：/输出：/解释：」等 → 卡片内键值行；
 * 「提示/约束/数据范围/进阶」→ 小节；其余原样交给 marked。 */
interface IoLine { key: string; value: string }
type Block =
  | { kind: 'md'; html: string }
  | { kind: 'sample'; title: string; lines: IoLine[] }
  | { kind: 'sub'; title: string; html: string };

const SAMPLE_HEAD = /^(?:示例\s*\d*|example\s*\d*)\s*[：:]?$/i;
const IO_LINE = /^(输入|输出|解释|说明|返回|结果)\s*[：:]\s*(.+)$/;
const SUB_HEAD = /^(提示|约束|数据范围|进阶|注意|要求)\s*[：:]?$/;

function mdToHtml(lines: string[]): string {
  return marked.parse(lines.join('\n'), { breaks: true, gfm: true }) as string;
}

function parseBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let md: string[] = [];
  let sample: { title: string; lines: IoLine[] } | null = null;
  let sub: { title: string; lines: string[] } | null = null;

  const flushMd = () => {
    if (md.length) { blocks.push({ kind: 'md', html: mdToHtml(md) }); md = []; }
  };
  const flushSample = () => {
    if (sample && sample.lines.length) blocks.push({ kind: 'sample', title: sample.title, lines: sample.lines });
    sample = null;
  };
  const flushSub = () => {
    if (sub) blocks.push({ kind: 'sub', title: sub.title, html: mdToHtml(sub.lines) });
    sub = null;
  };

  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (SAMPLE_HEAD.test(line)) {
      flushMd(); flushSub(); flushSample();
      sample = { title: line.replace(/[：:]$/, ''), lines: [] };
      continue;
    }
    const io = line.match(IO_LINE);
    if (io) {
      flushMd(); flushSub();
      if (!sample) sample = { title: '', lines: [] };
      sample.lines.push({ key: io[1], value: io[2] });
      continue;
    }
    if (SUB_HEAD.test(line)) {
      flushMd(); flushSample();
      sub = { title: line.replace(/[：:]$/, ''), lines: [] };
      continue;
    }
    if (sub) {
      if (line === '') flushSub();
      else sub.lines.push(rawLine);
      continue;
    }
    if (sample) {
      if (line === '') { flushSample(); continue; }
      sample.lines.push({ key: '', value: line });
      continue;
    }
    md.push(rawLine);
  }
  flushMd(); flushSample(); flushSub();
  return blocks;
}

const parsed = computed(() => {
  const { title, body } = splitTitle(props.question);
  return { title, blocks: parseBlocks(body) };
});
</script>

<template>
  <div class="question-panel">
    <h3 class="q-title">{{ parsed.title }}</h3>
    <template v-for="(b, i) in parsed.blocks" :key="i">
      <div v-if="b.kind === 'md'" class="q-md" v-html="b.html"></div>
      <div v-else-if="b.kind === 'sample'" class="q-sample">
        <p v-if="b.title" class="q-sample-title">{{ b.title }}</p>
        <div v-for="(l, j) in b.lines" :key="j" class="q-io">
          <span v-if="l.key" class="q-io-key">{{ l.key }}：</span>
          <pre class="q-io-val">{{ l.value }}</pre>
        </div>
      </div>
      <div v-else class="q-sub">
        <h4 class="q-sub-title">{{ b.title }}</h4>
        <div class="q-md" v-html="b.html"></div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.question-panel { padding: 16px 20px 24px; font-size: 14px; color: #334155; line-height: 1.75; }
.q-title { font-size: 16px; font-weight: 600; color: #1e293b; margin-bottom: 12px; }
.q-md :deep(p) { margin-bottom: 10px; }
.q-md :deep(p:last-child) { margin-bottom: 0; }
.q-md :deep(code) { background: #f1f5f9; border-radius: 4px; padding: 1px 5px; font-size: 13px; color: #be185d; }
.q-md :deep(pre code) { background: none; padding: 0; color: inherit; font-size: inherit; border-radius: 0; }
.q-md :deep(pre) { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; overflow-x: auto; font-size: 13px; }
.q-md :deep(ul), .q-md :deep(ol) { padding-left: 20px; margin-bottom: 10px; }
.q-md :deep(strong) { color: #1e293b; }
.q-sample { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; margin: 6px 0 12px; }
.q-sample-title { font-weight: 600; color: #1e293b; margin-bottom: 4px; font-size: 13px; }
.q-io { display: flex; gap: 8px; align-items: baseline; }
.q-io-key { flex: none; color: #475569; font-weight: 600; font-size: 13px; }
.q-io-val { margin: 0; font-family: Consolas, 'Courier New', monospace; font-size: 13px; color: #0f172a; white-space: pre-wrap; word-break: break-all; }
.q-sub-title { font-weight: 600; color: #1e293b; margin: 14px 0 6px; font-size: 14px; }
</style>
