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

  // RT 扫码进店 H5（phase-1 B2/C2 · 移动优先 · 公开无需登录）
  // 进店码走 query：/rt/store?code=<租户简码>；兼容 path：/rt/:code
  {
    path: '/rt/store',
    name: 'rt-store',
    component: () => import('@/views/rt/Store.vue'),
    meta: { public: true, title: '进店浏览' },
  },
  {
    path: '/rt/:code',
    name: 'rt-store-code',
    component: () => import('@/views/rt/Store.vue'),
    meta: { public: true, title: '进店浏览' },
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

  // WA 工作台（批发商）
  {
    path: '/wa',
    name: 'wa-root',
    redirect: '/wa/inquiry',
    meta: { role: 'WA' },
  },
  {
    path: '/wa/inquiry',
    name: 'wa-inquiry',
    component: () => import('@/views/wa/Inquiry.vue'),
    meta: { role: 'WA', title: '询价确认' },
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
