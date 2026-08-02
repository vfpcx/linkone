import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
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
    path: '/ta/pricing',
    name: 'ta-pricing',
    component: () => import('@/views/ta/Pricing.vue'),
    meta: { role: 'TA', title: '价格管理' },
  },
  {
    path: '/ta/inbound',
    name: 'ta-inbound',
    component: () => import('@/views/ta/Inbound.vue'),
    meta: { role: 'TA', title: '入库登记' },
  },
  {
    path: '/ta/outbound',
    name: 'ta-outbound',
    component: () => import('@/views/ta/Outbound.vue'),
    meta: { role: 'TA', title: '出库作业' },
  },
  {
    path: '/ta/returns',
    name: 'ta-returns',
    component: () => import('@/views/ta/Returns.vue'),
    meta: { role: 'TA', title: '退货受理' },
  },
  {
    path: '/ta/stocktake',
    name: 'ta-stocktake',
    component: () => import('@/views/ta/Stocktake.vue'),
    meta: { role: 'TA', title: '盘点' },
  },
  {
    path: '/ta/wholesaler-applications',
    name: 'ta-wholesaler-applications',
    component: () => import('@/views/ta/WholesalerApplications.vue'),
    meta: { role: 'TA', title: '入驻审批' },
  },
  {
    path: '/ta/approvals',
    name: 'ta-approvals',
    component: () => import('@/views/ta/Approvals.vue'),
    meta: { role: 'TA', title: '审批中心' },
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
  {
    path: '/wa/inbound',
    name: 'wa-inbound',
    component: () => import('@/views/wa/Inbound.vue'),
    meta: { role: 'WA', title: '入库确认' },
  },
  {
    path: '/wa/outbound',
    name: 'wa-outbound',
    component: () => import('@/views/wa/Outbound.vue'),
    meta: { role: 'WA', title: '出库单' },
  },
  {
    path: '/wa/returns',
    name: 'wa-returns',
    component: () => import('@/views/wa/Returns.vue'),
    meta: { role: 'WA', title: '退货' },
  },
  {
    path: '/wa/apply',
    name: 'wa-apply',
    component: () => import('@/views/wa/Apply.vue'),
    meta: { role: 'WA', title: '入驻申请' },
  },
  {
    path: '/wa/withdraw',
    name: 'wa-withdraw',
    component: () => import('@/views/wa/Withdraw.vue'),
    meta: { role: 'WA', title: '退驻申请' },
  },
  {
    path: '/wa/staff',
    name: 'wa-staff',
    component: () => import('@/views/wa/Staff.vue'),
    meta: { role: 'WA', title: '员工管理' },
  },

  // OPS 工作台（P2 · 黑名单为 OPS 端第一个真实页面）
  {
    path: '/ops/dashboard',
    name: 'ops-dashboard',
    component: () => import('@/views/PlaceholderDashboard.vue'),
    meta: { role: 'OPS', title: '平台运维控制台' },
  },
  {
    path: '/ops/tenant-audit',
    name: 'ops-tenant-audit',
    component: () => import('@/views/ops/TenantAudit.vue'),
    meta: { role: 'OPS', title: '租户审核' },
  },
  {
    path: '/ops/blacklist',
    name: 'ops-blacklist',
    component: () => import('@/views/ops/Blacklist.vue'),
    meta: { role: 'OPS', title: '黑名单管理' },
  },
  {
    path: '/ops/arbitrations',
    name: 'ops-arbitrations',
    component: () => import('@/views/ops/Arbitrations.vue'),
    meta: { role: 'OPS', title: '客诉仲裁' },
  },

  // ST 工作台占位（后续 Agent 实现）
  {
    path: '/st/dashboard',
    name: 'st-dashboard',
    component: () => import('@/views/PlaceholderDashboard.vue'),
    meta: { role: 'ST', title: '结算员工作台' },
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

// 全局守卫：未登录跳登录页；已登录访问 /login 时按 primaryRouter 路由；
// OPS 路由按登录用户 roles 校验角色（P2 · OPS 端有真实页面后补的角色守卫）
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

  // OPS 平台页：非 OPS 账号（roles 不含 OPS）禁入，弹回其主路由。
  // 注：TA/WA 页面存在兼任互访（WK 回 TA 台等），暂不收紧，只守 OPS。
  if (to.meta?.role === 'OPS') {
    const isOps = auth.roles?.some((r) => r.role === 'OPS') || auth.primaryRole === 'OPS'
    if (!isOps) {
      ElMessage.warning('无权访问平台运营页面')
      return auth.primaryRouter || '/ta/dashboard'
    }
  }
  return true
})

export default router
