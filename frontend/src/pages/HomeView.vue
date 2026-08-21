<script setup lang="ts">
import { useRouter } from 'vue-router';
import { NButton } from 'naive-ui';
import { useAuthStore } from '../stores/auth';

const router = useRouter();
const auth = useAuthStore();

function handleLogout() {
  auth.logout();
  router.push('/login');
}
</script>

<template>
  <div class="min-h-screen bg-slate-50">
    <!-- 美化：吸顶毛玻璃导航栏 -->
    <header class="sticky top-0 z-10 bg-white/80 backdrop-blur border-b border-slate-200/70">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
        <h1 class="text-lg font-bold text-slate-800 tracking-tight">AI 面试官</h1>
        <!-- 美化：导航项增加 hover 底色药丸与过渡，移动端缩小间距 -->
        <nav class="flex items-center gap-1 sm:gap-2">
          <router-link to="/resumes"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">简历管理</router-link>
          <router-link to="/jds"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">职位管理</router-link>
          <router-link to="/knowledge-bases"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">知识笔记</router-link>
          <router-link to="/term-dict"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">纠错词库</router-link>
          <router-link to="/asr-eval"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">ASR 评测</router-link>
          <router-link to="/chat"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">知识问答</router-link>
          <router-link to="/interview/start"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">开始面试</router-link>
          <router-link to="/sessions"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">面试记录</router-link>
          <router-link to="/observability"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">LLM 观测台</router-link>
          <router-link to="/eval"
            class="px-3 py-1.5 rounded-lg text-sm text-slate-600 hover:text-blue-600 hover:bg-blue-50 transition-all duration-200">Agent 评测</router-link>
          <span class="hidden sm:inline text-sm text-slate-400 mx-1">|</span>
          <span class="hidden sm:inline text-sm text-slate-600">{{ auth.user?.username }}</span>
          <n-button size="small" type="error" secondary class="ml-2" @click="handleLogout">退出</n-button>
        </nav>
      </div>
    </header>

    <main class="max-w-7xl mx-auto px-4 sm:px-6 py-8">
      <!-- 美化：欢迎卡片 -->
      <div class="bg-white rounded-2xl shadow-card p-8 mb-8">
        <h2 class="text-2xl font-bold text-slate-800 mb-2">欢迎使用 AI 面试官</h2>
        <p class="text-slate-500">上传简历、选择职位，即可开始一场接近真实场景的模拟面试。</p>
      </div>

      <!-- 美化：快捷入口卡片，hover 上浮 + 阴影加深 -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <router-link to="/resumes"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-blue-50 text-blue-600 flex items-center justify-center text-lg font-bold mb-3">简</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors duration-200">上传简历</h3>
          <p class="text-sm text-slate-500 mt-1">支持 PDF / Word / 文本，自动解析内容</p>
        </router-link>
        <router-link to="/jds"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-emerald-50 text-emerald-600 flex items-center justify-center text-lg font-bold mb-3">职</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-emerald-600 transition-colors duration-200">管理职位</h3>
          <p class="text-sm text-slate-500 mt-1">维护目标 JD，让提问更贴合岗位</p>
        </router-link>
        <router-link to="/knowledge-bases"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-cyan-50 text-cyan-600 flex items-center justify-center text-lg font-bold mb-3">库</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-cyan-600 transition-colors duration-200">知识笔记</h3>
          <p class="text-sm text-slate-500 mt-1">沉淀专属知识文档，AI 问答自动引用</p>
        </router-link>
        <router-link to="/chat"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-teal-50 text-teal-600 flex items-center justify-center text-lg font-bold mb-3">问</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-teal-600 transition-colors duration-200">知识问答</h3>
          <p class="text-sm text-slate-500 mt-1">基于全部笔记文档的 AI 对话，超范围拒答</p>
        </router-link>
        <router-link to="/interview/start"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-violet-50 text-violet-600 flex items-center justify-center text-lg font-bold mb-3">面</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-violet-600 transition-colors duration-200">开始面试</h3>
          <p class="text-sm text-slate-500 mt-1">多 Agent 协同出题，含编程实战环节</p>
        </router-link>
        <router-link to="/sessions"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-amber-50 text-amber-600 flex items-center justify-center text-lg font-bold mb-3">记</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-amber-600 transition-colors duration-200">面试记录</h3>
          <p class="text-sm text-slate-500 mt-1">历史面试与报告，一键回顾查看</p>
        </router-link>
        <router-link to="/observability"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-rose-50 text-rose-600 flex items-center justify-center text-lg font-bold mb-3">测</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-rose-600 transition-colors duration-200">LLM 观测台</h3>
          <p class="text-sm text-slate-500 mt-1">调用追踪与 Token/成本统计</p>
        </router-link>
        <router-link to="/eval"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center text-lg font-bold mb-3">评</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-indigo-600 transition-colors duration-200">Agent 评测</h3>
          <p class="text-sm text-slate-500 mt-1">golden 数据集自动驱动面试，评估 Agent 质量</p>
        </router-link>
        <router-link to="/term-dict"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-orange-50 text-orange-600 flex items-center justify-center text-lg font-bold mb-3">词</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-orange-600 transition-colors duration-200">纠错词库</h3>
          <p class="text-sm text-slate-500 mt-1">维护语音纠错术语，识别更准</p>
        </router-link>
        <router-link to="/asr-eval"
          class="group bg-white rounded-2xl shadow-card p-6 hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          <div class="w-10 h-10 rounded-xl bg-cyan-50 text-cyan-600 flex items-center justify-center text-lg font-bold mb-3">音</div>
          <h3 class="font-semibold text-slate-800 group-hover:text-cyan-600 transition-colors duration-200">ASR 转写评测</h3>
          <p class="text-sm text-slate-500 mt-1">上传录音，端到端验证转写与术语纠错</p>
        </router-link>
      </div>
    </main>
  </div>
</template>
