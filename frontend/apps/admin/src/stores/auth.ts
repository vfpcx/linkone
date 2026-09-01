import { defineStore } from 'pinia'
import type { LoginResponse, LoginRoleEntry, Role } from '@cangchu/api-types'
import { readCurrentTenant, writeCurrentTenant } from '@/utils/currentTenant'

interface AuthState {
  token: string | null
  userId: string | null
  primaryRole: Role | null
  roles: LoginRoleEntry[]
  primaryRouter: string
  expireAt: string | null
  tenantInfo: LoginResponse['tenantInfo'] | null
  /** 多角色切换器弹窗显示标志 */
  switcherVisible: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: null,
    userId: null,
    primaryRole: null,
    roles: [],
    primaryRouter: '',
    expireAt: null,
    tenantInfo: null,
    switcherVisible: false,
  }),

  getters: {
    isAuthenticated: (s) => !!s.token,
    hasMultipleRoles: (s) => (s.roles?.length ?? 0) > 1,
    /** 当前店铺名（顶栏显示） */
    currentStoreName: (s) => s.tenantInfo?.tenantName || '',
  },

  actions: {
    /** 登录成功后填充 */
    setLoginPayload(payload: LoginResponse) {
      this.token = payload.token
      this.userId = payload.userId
      this.primaryRole = payload.primaryRole
      this.roles = payload.roles ?? []
      this.primaryRouter = payload.primaryRouter || this.defaultRouterFor(payload.primaryRole)
      this.expireAt = payload.expireAt
      this.tenantInfo = payload.tenantInfo ?? null
      // 多仓（2026-09-01）：当前仓记录缺失或属上一用户时，以「默认工作空间」角色补写，
      // 使 http 拦截器能注入 X-Tenant-Id（批发商端必需；TA 端 fetchWarehouses 会再按名下列表校正）
      const tenant = readCurrentTenant()
      if ((!tenant || tenant.userId !== payload.userId) && payload.userId) {
        const def = (payload.roles ?? []).find((r) => r.tenantId)
        if (def?.tenantId) writeCurrentTenant(payload.userId, def.tenantId)
      }
    },

    /** 多角色切换器：选择角色后调用 */
    switchActiveRole(role: LoginRoleEntry) {
      this.primaryRole = role.role
      this.primaryRouter = this.defaultRouterFor(role.role)
      if (role.tenantId && this.tenantInfo) {
        this.tenantInfo.tenantId = role.tenantId
        if (role.storeName) this.tenantInfo.tenantName = role.storeName
      }
      // 多仓（2026-09-01）：同步当前工作空间 → http 拦截器注入新的 X-Tenant-Id
      if (role.tenantId && this.userId) {
        writeCurrentTenant(this.userId, role.tenantId)
      }
      this.switcherVisible = false
    },

    showSwitcher() {
      this.switcherVisible = true
    },

    hideSwitcher() {
      this.switcherVisible = false
    },

    /** 清空（登出 / 41xxx 拦截） */
    clear() {
      this.token = null
      this.userId = null
      this.primaryRole = null
      this.roles = []
      this.primaryRouter = ''
      this.expireAt = null
      this.tenantInfo = null
      this.switcherVisible = false
    },

    defaultRouterFor(role: Role): string {
      const map: Record<Role, string> = {
        OPS: '/ops/dashboard',
        TA: '/ta/dashboard',
        ST: '/st/dashboard',
        WK: '/ta/dashboard', // 兼任时回 TA
        WA: '/wa/inquiry', // WA 主页 = 询价确认（phase-1 C2）
        WE: '/wa/inquiry', // 与后端 primaryRouter 一致（Wave3：WA/WE 均回 /wa/inquiry）
        RT: '/ta/dashboard',
      }
      return map[role] ?? '/ta/dashboard'
    },
  },

  persist: {
    key: 'cangchu-admin-auth',
    storage: localStorage,
    paths: ['token', 'userId', 'primaryRole', 'roles', 'primaryRouter', 'expireAt', 'tenantInfo'],
  },
})
