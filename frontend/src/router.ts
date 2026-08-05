import { createRouter, createWebHistory } from 'vue-router';
import LoginView from './pages/LoginView.vue';
import RegisterView from './pages/RegisterView.vue';
import HomeView from './pages/HomeView.vue';
import ResumesView from './pages/ResumesView.vue';
import JdsView from './pages/JdsView.vue';
import InterviewStartView from './pages/InterviewStartView.vue';
import InterviewRoomView from './pages/InterviewRoomView.vue';
import ReportView from './pages/ReportView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', name: 'Login', component: LoginView, meta: { requiresAuth: false } },
    { path: '/register', name: 'Register', component: RegisterView, meta: { requiresAuth: false } },
    { path: '/home', name: 'Home', component: HomeView, meta: { requiresAuth: true } },
        { path: '/resumes', name: 'Resumes', component: ResumesView, meta: { requiresAuth: true } },
        { path: '/jds', name: 'Jds', component: JdsView, meta: { requiresAuth: true } },
        { path: '/interview/start', name: 'InterviewStart', component: InterviewStartView, meta: { requiresAuth: true } },
        { path: '/interview/room', name: 'InterviewRoom', component: InterviewRoomView, meta: { requiresAuth: true } },
        { path: '/report/:id', name: 'Report', component: ReportView, meta: { requiresAuth: true } },
  ]
});

router.beforeEach((to, _from) => {
  const token = localStorage.getItem('accessToken');
  if (to.meta.requiresAuth !== false && !token) {
    return '/login';
  }
});

export default router;
