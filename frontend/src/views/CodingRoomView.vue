<script setup lang="ts">
import { ref } from 'vue';
import CodeEditor from '../components/CodeEditor.vue';

const code = ref('// 请在此处编写代码\npublic class Solution {\n    public static void main(String[] args) {\n        System.out.println("Hello, Interview!");\n    }\n}');
const language = ref('java');
const output = ref('');
const testResults = ref<{ name: string; passed: boolean; detail: string }[]>([]);
const running = ref(false);
const submitting = ref(false);

const codeEditor = ref<InstanceType<typeof CodeEditor>>();

function handleRun() {
  running.value = true;
  output.value = '运行中...';
  setTimeout(() => {
    output.value = '编译成功\n程序输出: Hello, Interview!';
    testResults.value = [
      { name: '测试用例 1: 基本功能', passed: true, detail: '通过' },
      { name: '测试用例 2: 边界条件', passed: false, detail: '期望输出不匹配' }
    ];
    running.value = false;
  }, 1500);
}

function handleSubmit() {
  submitting.value = true;
  output.value = '提交中...';
  setTimeout(() => {
    output.value = '提交成功，评估中...';
    testResults.value = [
      { name: '正确性', passed: true, detail: '80%' },
      { name: '代码质量', passed: true, detail: '良好' },
      { name: '边界处理', passed: false, detail: '需要改进' }
    ];
    submitting.value = false;
  }, 2000);
}
</script>

<template>
  <div class="flex flex-col h-[calc(100vh-64px)]">
    <!-- Header -->
    <header class="bg-white shadow-sm px-6 py-3 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-4">
        <h2 class="text-lg font-bold text-slate-800">编程题</h2>
        <select v-model="language" class="px-3 py-1 border border-slate-300 rounded-lg text-sm">
          <option value="java">Java</option>
          <option value="python">Python</option>
        </select>
      </div>
      <div class="flex gap-3">
        <button @click="handleRun" :disabled="running"
          class="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors text-sm">
          {{ running ? '运行中...' : '运行' }}
        </button>
        <button @click="handleSubmit" :disabled="submitting"
          class="px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:opacity-50 transition-colors text-sm">
          {{ submitting ? '提交中...' : '提交' }}
        </button>
      </div>
    </header>

    <div class="flex-1 flex">
      <!-- 编辑器区域 -->
      <div class="flex-1 p-4">
        <div class="h-full">
          <CodeEditor ref="codeEditor" v-model="code" :language="language" />
        </div>
      </div>

      <!-- 结果面板 -->
      <div class="w-96 border-l border-slate-200 bg-white p-4 overflow-y-auto">
        <h3 class="font-bold text-slate-800 mb-3">运行结果</h3>

        <!-- 输出 -->
        <div class="mb-4">
          <h4 class="text-sm font-medium text-slate-600 mb-1">控制台输出</h4>
          <pre class="bg-slate-900 text-green-400 p-3 rounded-lg text-sm overflow-x-auto min-h-[60px]">{{ output || '点击"运行"查看输出' }}</pre>
        </div>

        <!-- 测试结果 -->
        <div v-if="testResults.length > 0">
          <h4 class="text-sm font-medium text-slate-600 mb-2">测试结果</h4>
          <div class="space-y-2">
            <div v-for="(tr, i) in testResults" :key="i"
              class="flex items-center gap-2 p-2 rounded-lg"
              :class="tr.passed ? 'bg-green-50' : 'bg-red-50'">
              <span class="text-lg" :class="tr.passed ? 'text-green-600' : 'text-red-600'">
                {{ tr.passed ? '✓' : '✗' }}
              </span>
              <div>
                <p class="text-sm font-medium" :class="tr.passed ? 'text-green-800' : 'text-red-800'">
                  {{ tr.name }}
                </p>
                <p class="text-xs text-slate-500">{{ tr.detail }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>