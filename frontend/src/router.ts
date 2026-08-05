import { createRouter, createWebHistory } from 'vue-router';
import LoginView from './pages/LoginView.vue';
import RegisterView from './pages/RegisterView.vue';
import HomeView from './pages/HomeView.vue';
import ResumesView from './pages/ResumesView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', name: 'Login', component: LoginView, meta: { requiresAuth: false } },
    { path: '/register', name: 'Register', component: RegisterView, meta: { requiresAuth: false } },
    { path: '/home', name: 'Home', component: HomeView, meta: { requiresAuth: true } },
        { path: '/resumes', name: 'Resumes', component: ResumesView, meta: { requiresAuth: true } },
  ]
});

router.beforeEach((to, _from) => {
  const token = localStorage.getItem('accessToken');
  if (to.meta.requiresAuth !== false && !token) {
    return '/login';
  }
});

export default router;
