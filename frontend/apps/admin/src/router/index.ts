import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  // 公开路由
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/Register.vue'),
    meta: { public: true, title: '注册' },
  },
  {
    path: '/forgot-password',
    name: 'forgot-password',
    component: () => import('@/views/ForgotPassword.vue'),
    meta: { public: true, title: '找回密码' },
  },

  // TA 工作台
  {
    path: '/ta',
    name: 'ta-root',
    redirect: '/ta/dashboard',
    meta: { role: 'TA' },
  },
  {
    path: '/ta/dashboard',
    name: 'ta-dashboard',
    component: () => import('@/views/ta/Dashboard.vue'),
    meta: { role: 'TA', title: '店铺工作台' },
  },
  {
    path: '/ta/settings',
    name: 'ta-settings',
    component: () => import('@/views/ta/Settings.vue'),
    meta: { role: 'TA', title: '店铺设置' },
  },
  {
    path: '/ta/employees',
    name: 'ta-employees',
    component: () => import('@/views/ta/Employees.vue'),
    meta: { role: 'TA', title: '员工' },
  },
  {
    path: '/ta/wholesalers',
    name: 'ta-wholesalers',
    component: () => import('@/views/ta/Wholesalers.vue'),
    meta: { role: 'TA', title: '入驻商户' },
  },
  {
    path: '/ta/skus',
    name: 'ta-skus',
    component: () => import('@/views/ta/Skus.vue'),
    meta: { role: 'TA', title: '商品管理' },
  },
  {
    path: '/ta/inbound',
    name: 'ta-inbound',
    component: () => import('@/views/ta/Inbound.vue'),
    meta: { role: 'TA', title: '入库登记' },
  },

  // OPS / ST 工作台占位（后续 Agent 实现）
  {
    path: '/ops/dashboard',
    name: 'ops-dashboard',
    component: () => import('@/views/PlaceholderDashboard.vue'),
    meta: { role: 'OPS', title: 'OPS 控制台' },
  },
  {
    path: '/st/dashboard',
    name: 'st-dashboard',
    component: () => import('@/views/PlaceholderDashboard.vue'),
    meta: { role: 'ST', title: 'ST 结算台' },
  },

  // 根重定向
  { path: '/', redirect: '/login' },

  // 404
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFound.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

// 全局守卫：未登录跳登录页；已登录访问 /login 时按 primaryRouter 路由
router.beforeEach((to) => {
  const auth = useAuthStore()
  document.title = (to.meta?.title as string) ?? '仓储云控制台'

  if (to.meta?.public) {
    // 已登录访问登录/注册页 → 跳回主路由
    if (auth.isAuthenticated && (to.name === 'login' || to.name === 'register')) {
      return auth.primaryRouter || '/ta/dashboard'
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
