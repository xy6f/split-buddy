/* vue-router：概览 / 记账 / 结清 / 成员 + 登录注册页（含会话守卫） */
import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', redirect: '/overview' },
  {
    path: '/auth',
    name: 'auth',
    component: () => import('@/views/AuthView.vue'),
    meta: { public: true, title: '登录' },
  },
  { path: '/overview', name: 'overview', component: () => import('@/views/OverviewView.vue'), meta: { title: '概览' } },
  { path: '/expense', name: 'expense', component: () => import('@/views/ExpenseView.vue'), meta: { title: '记账' } },
  { path: '/settlement', name: 'settlement', component: () => import('@/views/SettlementView.vue'), meta: { title: '结清' } },
  { path: '/members', name: 'members', component: () => import('@/views/MembersView.vue'), meta: { title: '成员' } },
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
]

const router = createRouter({
  // hash 模式：产物可被任意静态服务器/Nginx 直接托管，无需 SPA rewrite 配置
  history: createWebHashHistory(),
  routes,
})

/* 会话守卫：未登录一律去 /auth；已登录不再看登录页 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isAuthed) return { path: '/auth' }
  if (to.path === '/auth' && auth.isAuthed) return { path: '/overview' }
  return true
})

export default router
