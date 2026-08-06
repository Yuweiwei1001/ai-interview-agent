<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';

const props = defineProps<{
  modelValue: string;
  language?: string;
  readonly?: boolean;
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
  <!-- 美化：容器统一圆角/描边/阴影体系 -->
  <div ref="editorContainer" class="h-full w-full border border-slate-200 rounded-xl overflow-hidden shadow-card"></div>
</template>