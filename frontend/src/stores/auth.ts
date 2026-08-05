import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { login, register, type LoginVO } from '../api/auth';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<LoginVO | null>(null);
  const accessToken = ref(localStorage.getItem('accessToken') || '');
  const refreshTokenVal = ref(localStorage.getItem('refreshToken') || '');

  const isLoggedIn = computed(() => !!accessToken.value);

  async function doLogin(username: string, password: string) {
    const res = await login({ username, password });
    const data = res.data.data;
    setTokens(data);
    return data;
  }

  async function doRegister(username: string, password: string, email?: string) {
    const res = await register({ username, password, email });
    const data = res.data.data;
    setTokens(data);
    return data;
  }

  function setTokens(data: LoginVO) {
    user.value = data;
    accessToken.value = data.accessToken;
    refreshTokenVal.value = data.refreshToken;
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
  }

  function logout() {
    user.value = null;
    accessToken.value = '';
    refreshTokenVal.value = '';
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
  }

  return { user, accessToken, refreshToken: refreshTokenVal, isLoggedIn, doLogin, doRegister, logout };
});
