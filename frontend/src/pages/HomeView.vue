<script setup lang="ts">
import { useRouter } from 'vue-router';
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
    <header class="bg-white shadow-sm">
      <div class="max-w-7xl mx-auto px-4 py-4 flex items-center justify-between">
        <h1 class="text-xl font-bold text-slate-800">AI 面试官</h1>
        <div class="flex items-center gap-4">
          <router-link to="/resumes" class="text-slate-600 hover:text-blue-600">简历管理</router-link>
          <span class="text-slate-600">{{ auth.user?.username }}</span>
          <button @click="handleLogout"
            class="px-4 py-1 bg-red-500 text-white rounded-lg hover:bg-red-600 transition-colors text-sm">
            退出
          </button>
        </div>
      </div>
    </header>
    <main class="max-w-7xl mx-auto px-4 py-8">
      <div class="bg-white rounded-xl shadow-sm p-8">
        <h2 class="text-2xl font-bold text-slate-800 mb-4">欢迎使用 AI 面试官</h2>
        <p class="text-slate-600">请先上传简历开始面试流程。</p>
      </div>
    </main>
  </div>
</template>
