<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { NInput, NButton, NAlert } from 'naive-ui';
import { useAuthStore } from '../stores/auth';

const router = useRouter();
const auth = useAuthStore();
const username = ref('');
const password = ref('');
const error = ref('');
const loading = ref(false);

async function handleLogin() {
  error.value = '';
  loading.value = true;
  try {
    await auth.doLogin(username.value, password.value);
    router.push('/home');
  } catch (e: any) {
    error.value = e.response?.data?.msg || '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <!-- 美化：深色渐变背景增加层次，卡片悬浮居中，移动端留白 -->
  <div class="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-blue-950 flex items-center justify-center px-4 py-8">
    <div class="w-full max-w-md">
      <!-- 美化：品牌区，增强第一印象 -->
      <div class="text-center mb-8">
        <div class="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-blue-600/20 border border-blue-400/30 text-blue-400 text-2xl font-bold mb-4">AI</div>
        <h1 class="text-3xl font-bold text-white tracking-tight">AI 面试官</h1>
        <p class="text-slate-400 text-sm mt-2">模拟真实面试场景，助你从容应对</p>
      </div>

      <!-- 美化：白色登录卡片，大圆角 + 深色场景柔和阴影 -->
      <div class="bg-white rounded-2xl shadow-pop p-8">
        <h2 class="text-lg font-semibold text-slate-800 mb-6">欢迎回来</h2>
        <form @submit.prevent="handleLogin" class="space-y-5">
          <div>
            <label for="login-username" class="block text-sm font-medium text-slate-700 mb-1.5">用户名</label>
            <n-input id="login-username" v-model:value="username" size="large" placeholder="请输入用户名" />
          </div>
          <div>
            <label for="login-password" class="block text-sm font-medium text-slate-700 mb-1.5">密码</label>
            <n-input id="login-password" v-model:value="password" type="password" show-password-on="click"
              size="large" placeholder="请输入密码" />
          </div>

          <!-- 美化：错误提示升级为警示条，更醒目 -->
          <n-alert v-if="error" type="error" :bordered="false" class="rounded-lg">{{ error }}</n-alert>

          <n-button type="primary" size="large" block attr-type="submit"
            :loading="loading" :disabled="!username || !password">
            {{ loading ? '登录中...' : '登 录' }}
          </n-button>

          <p class="text-center text-sm text-slate-500">
            还没有账号？
            <router-link to="/register" class="text-blue-600 hover:text-blue-700 font-medium transition-colors duration-200">立即注册</router-link>
          </p>
        </form>
      </div>
    </div>
  </div>
</template>
