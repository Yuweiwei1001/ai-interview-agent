<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { NInput, NButton, NAlert } from 'naive-ui';
import { useAuthStore } from '../stores/auth';

const router = useRouter();
const auth = useAuthStore();
const username = ref('');
const password = ref('');
const confirmPassword = ref('');
const error = ref('');
const loading = ref(false);

async function handleRegister() {
  error.value = '';
  if (password.value !== confirmPassword.value) {
    error.value = '两次密码不一致';
    return;
  }
  loading.value = true;
  try {
    await auth.doRegister(username.value, password.value);
    router.push('/home');
  } catch (e: any) {
    error.value = e.response?.data?.msg || '注册失败';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <!-- 美化：与登录页一致的深色渐变背景 + 悬浮卡片语言 -->
  <div class="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-blue-950 flex items-center justify-center px-4 py-8">
    <div class="w-full max-w-md">
      <!-- 美化：品牌区 -->
      <div class="text-center mb-8">
        <div class="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-blue-600/20 border border-blue-400/30 text-blue-400 text-2xl font-bold mb-4">AI</div>
        <h1 class="text-3xl font-bold text-white tracking-tight">创建账号</h1>
        <p class="text-slate-400 text-sm mt-2">开启你的 AI 模拟面试之旅</p>
      </div>

      <!-- 美化：白色注册卡片 -->
      <div class="bg-white rounded-2xl shadow-pop p-8">
        <form @submit.prevent="handleRegister" class="space-y-5">
          <div>
            <label for="reg-username" class="block text-sm font-medium text-slate-700 mb-1.5">用户名</label>
            <n-input id="reg-username" v-model:value="username" size="large" placeholder="请输入用户名" />
          </div>
          <div>
            <label for="reg-password" class="block text-sm font-medium text-slate-700 mb-1.5">密码</label>
            <n-input id="reg-password" v-model:value="password" type="password" show-password-on="click"
              size="large" placeholder="请输入密码" />
          </div>
          <div>
            <label for="reg-confirm" class="block text-sm font-medium text-slate-700 mb-1.5">确认密码</label>
            <n-input id="reg-confirm" v-model:value="confirmPassword" type="password" show-password-on="click"
              size="large" placeholder="请再次输入密码" />
          </div>

          <n-alert v-if="error" type="error" :bordered="false" class="rounded-lg">{{ error }}</n-alert>

          <n-button type="primary" size="large" block attr-type="submit"
            :loading="loading" :disabled="!username || !password || !confirmPassword">
            {{ loading ? '注册中...' : '注 册' }}
          </n-button>

          <p class="text-center text-sm text-slate-500">
            已有账号？
            <router-link to="/login" class="text-blue-600 hover:text-blue-700 font-medium transition-colors duration-200">直接登录</router-link>
          </p>
        </form>
      </div>
    </div>
  </div>
</template>
