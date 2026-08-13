<script setup lang="ts">
import { watch, defineComponent, h, ref, onMounted, onUnmounted } from 'vue';
import { Crepe, CrepeFeature } from '@milkdown/crepe';
import { replaceAll } from '@milkdown/kit/utils';
import { uploadKbImage } from '../api/knowledge';
import '@milkdown/crepe/theme/common/style.css';
import '@milkdown/crepe/theme/nord.css';

/**
 * 语雀式 Markdown 编辑器（Milkdown Crepe 所见即所得封装）
 * 单栏即写即渲染：输入 # / - / ``` 等语法即时渲染为富文本，点击块可继续编辑
 * 内置工具栏（加粗/标题/列表/表格/代码块等）+ 块级拖拽编辑 + 全文占位提示
 * 存储格式为纯 Markdown，通过 v-model 双向同步
 */
withDefaults(
  defineProps<{
    modelValue: string;
    placeholder?: string;
    /** 编辑区高度，默认撑满容器 */
    height?: string;
  }>(),
  { placeholder: '输入 Markdown 内容，语法即时渲染…', height: '100%' }
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
}>();

// Crepe 需挂载到真实 DOM 节点，故拆出内部子组件承载编辑器实例
const EditorInner = defineComponent({
  name: 'MarkdownEditorInner',
  props: {
    modelValue: { type: String, default: '' },
    placeholder: { type: String, default: '' },
  },
  emits: ['update:modelValue'],
  setup(innerProps, { emit: innerEmit }) {
    const rootRef = ref<HTMLDivElement | null>(null);
    let crepe: Crepe | null = null;

    onMounted(async () => {
      if (!rootRef.value) return;
      crepe = new Crepe({
        root: rootRef.value,
        defaultValue: innerProps.modelValue,
        features: {
          // 默认全开，显式关闭暂不需要的能力（TopBar/AI 默认已关）
          [CrepeFeature.Latex]: false,
        },
        featureConfigs: {
          [CrepeFeature.Placeholder]: {
            text: innerProps.placeholder,
            mode: 'doc',
          },
          // 图片持久化：粘贴/拖拽/选择图片后上传到后端，返回可访问 URL
          [CrepeFeature.ImageBlock]: {
            onUpload: async (file: File) => {
              const res = await uploadKbImage(file);
              return res.data.data.url;
            },
          },
        },
      });
      // 编辑器内容变化 → 同步到父组件
      crepe.on((listener) => {
        listener.markdownUpdated((_ctx, markdown) => {
          innerEmit('update:modelValue', markdown);
        });
      });
      await crepe.create();
    });

    onUnmounted(() => {
      crepe?.destroy().catch(() => undefined);
    });

    // 外部内容变化（如重置/加载文档）→ 同步进编辑器；与当前内容相同时跳过，避免打断输入
    watch(
      () => innerProps.modelValue,
      (val) => {
        if (!crepe || val === undefined) return;
        const current = crepe.getMarkdown();
        if (current !== val) {
          crepe.editor.action((ctx) => {
            replaceAll(val, true)(ctx);
          });
        }
      }
    );

    return () => h('div', { ref: rootRef, 'data-crepe-root': true, style: 'height:100%' });
  }
});
</script>

<template>
  <div class="md-editor-wrapper" :style="{ height }">
    <EditorInner
      :model-value="modelValue"
      :placeholder="placeholder"
      @update:model-value="emit('update:modelValue', $event)"
    />
  </div>
</template>

<style scoped>
.md-editor-wrapper {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--n-border-color, #e5e7eb);
  border-radius: 10px;
  background: #fff;
}

/* crepe 编辑区铺满容器 */
.md-editor-wrapper :deep(.milkdown) {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 10px;
}

.md-editor-wrapper :deep(.milkdown .crepe-editor) {
  flex: 1;
  overflow-y: auto;
}

.md-editor-wrapper :deep(.milkdown .ProseMirror) {
  min-height: 100%;
  padding: 18px 24px;
  outline: none;
  font-size: 14.5px;
  line-height: 1.75;
}

/* 工具栏紧凑化，贴合页面宽度 */
.md-editor-wrapper :deep(.milkdown .crepe-toolbar) {
  padding: 4px 8px;
  border-bottom: 1px solid var(--n-border-color, #e5e7eb);
  background: #fbfcfe;
  border-radius: 10px 10px 0 0;
}

.md-editor-wrapper :deep(.milkdown .crepe-toolbar button) {
  min-width: 26px;
  height: 26px;
}

/* 焦点态 */
.md-editor-wrapper:focus-within {
  border-color: var(--primary-color, #6366f1);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12);
}
</style>
