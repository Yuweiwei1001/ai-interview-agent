import { createRouter, createWebHistory } from 'vue-router';
import LoginView from './pages/LoginView.vue';
import RegisterView from './pages/RegisterView.vue';
import HomeView from './pages/HomeView.vue';
import ResumesView from './pages/ResumesView.vue';
import JdsView from './pages/JdsView.vue';
import KnowledgeBasesView from './pages/KnowledgeBasesView.vue';
import KnowledgeDocEditView from './pages/KnowledgeDocEditView.vue';
import InterviewStartView from './pages/InterviewStartView.vue';
import InterviewRoomView from './pages/InterviewRoomView.vue';
import SessionsView from './pages/SessionsView.vue';
import ObservabilityView from './pages/ObservabilityView.vue';
import ReportView from './pages/ReportView.vue';
import EvalView from './pages/EvalView.vue';
import CodingRoomView from './views/CodingRoomView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', name: 'Login', component: LoginView, meta: { requiresAuth: false } },
    { path: '/register', name: 'Register', component: RegisterView, meta: { requiresAuth: false } },
    { path: '/home', name: 'Home', component: HomeView, meta: { requiresAuth: true } },
        { path: '/resumes', name: 'Resumes', component: ResumesView, meta: { requiresAuth: true } },
        { path: '/jds', name: 'Jds', component: JdsView, meta: { requiresAuth: true } },
        { path: '/knowledge-bases', name: 'KnowledgeBases', component: KnowledgeBasesView, meta: { requiresAuth: true } },
        { path: '/knowledge-bases/:kbId/documents/:docId', name: 'KnowledgeDocEdit', component: KnowledgeDocEditView, meta: { requiresAuth: true } },
        { path: '/interview/start', name: 'InterviewStart', component: InterviewStartView, meta: { requiresAuth: true } },
        { path: '/interview/room', name: 'InterviewRoom', component: InterviewRoomView, meta: { requiresAuth: true } },
        { path: '/sessions', name: 'Sessions', component: SessionsView, meta: { requiresAuth: true } },
        { path: '/observability', name: 'Observability', component: ObservabilityView, meta: { requiresAuth: true } },
        { path: '/report/:id', name: 'Report', component: ReportView, meta: { requiresAuth: true } },
        { path: '/eval', name: 'Eval', component: EvalView, meta: { requiresAuth: true } },
        { path: '/coding', name: 'CodingRoom', component: CodingRoomView, meta: { requiresAuth: true } },
  ]
});

router.beforeEach((to, _from) => {
  const token = localStorage.getItem('accessToken');
  if (to.meta.requiresAuth !== false && !token) {
    return '/login';
  }
});

export default router;
