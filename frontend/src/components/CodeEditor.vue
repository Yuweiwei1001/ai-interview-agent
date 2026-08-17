<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';

const props = defineProps<{
  modelValue: string;
  language?: string;
  readonly?: boolean;
  bare?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const editorContainer = ref<HTMLDivElement>();
let editor: any = null;
let monacoInstance: any = null;

onMounted(async () => {
  await initMonaco();
});

onUnmounted(() => {
  editor?.getModel()?.dispose();
  editor?.dispose();
});

watch(() => props.language, (lang) => {
  if (editor && lang) {
    const model = editor.getModel();
    if (model) {
      monacoInstance.editor.setModelLanguage(model, lang);
    }
  }
});

async function initMonaco() {
  try {
    monacoInstance = await import('monaco-editor');

    if (editorContainer.value) {
      editor = monacoInstance.editor.create(editorContainer.value, {
        value: props.modelValue || '',
        language: props.language || 'java',
        theme: 'vs-dark',
        fontSize: 14,
        lineNumbers: 'on',
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        readOnly: props.readonly || false,
        automaticLayout: true,
        tabSize: 4,
        wordWrap: 'on'
      });

      editor.onDidChangeModelContent(() => {
        const value = editor.getValue();
        emit('update:modelValue', value);
      });
    }
  } catch (err) {
    console.error('Monaco Editor 初始化失败:', err);
  }
}

function getValue(): string {
  return editor?.getValue() || '';
}

function setValue(value: string) {
  if (editor) {
    editor.setValue(value);
  }
}

defineExpose({ getValue, setValue });
</script>

<template>
  <!-- bare 模式：无外框装饰，用于贴合力扣式编辑器 Tab 条的一体化布局 -->
  <div ref="editorContainer" class="h-full w-full overflow-hidden"
    :class="bare ? '' : 'border border-slate-200 rounded-xl shadow-card'"></div>
</template>