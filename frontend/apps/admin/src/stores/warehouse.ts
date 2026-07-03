import { defineStore } from 'pinia'
import type { Warehouse, CreateWarehouseRequest } from '@cangchu/api-types'
import { warehouseApi } from '@/api/warehouse'
import { useAuthStore } from '@/stores/auth'
import {
  readCurrentTenant,
  writeCurrentTenant,
  clearCurrentTenant,
} from '@/utils/currentTenant'

interface WarehouseState {
  /** 当前选中仓 tenantId（写入 localStorage，供 http 拦截器注入 X-Tenant-Id） */
  currentTenantId: string | null
  /** 名下所有仓 */
  warehouses: Warehouse[]
  loading: boolean
}

export const useWarehouseStore = defineStore('warehouse', {
  state: (): WarehouseState => ({
    currentTenantId: null,
    warehouses: [],
    loading: false,
  }),

  getters: {
    /** 当前选中仓对象 */
    current: (s): Warehouse | null =>
      s.warehouses.find((w) => w.tenantId === s.currentTenantId) ?? null,
    /** 是否名下多仓（>1 才显示切换语义） */
    hasMultiple: (s): boolean => s.warehouses.length > 1,
    /** 顶栏显示名：优先当前仓名，兜底登录态店铺名 */
    currentName(): string {
      return this.current?.name || useAuthStore().currentStoreName || ''
    },
  },

  actions: {
    /** 拉取名下所有仓，并确定默认选中仓 */
    async fetchWarehouses(): Promise<Warehouse[]> {
      this.loading = true
      try {
        const list = await warehouseApi.list()
        this.warehouses = list
        this.currentTenantId = this.resolveDefault(list)
        if (this.currentTenantId) this.persist(this.currentTenantId)
        else clearCurrentTenant()
        return list
      } finally {
        this.loading = false
      }
    },

    /**
     * 选默认仓：已存的（且仍在名下、属当前用户）→ auth.roles 首个 TA 仓 → 列表首个
     */
    resolveDefault(list: Warehouse[]): string | null {
      const auth = useAuthStore()
      const stored = readCurrentTenant()
      if (
        stored &&
        stored.userId === auth.userId &&
        list.some((w) => w.tenantId === stored.tenantId)
      ) {
        return stored.tenantId
      }
      const roleTenant =
        auth.roles.find((r) => r.role === 'TA' && r.tenantId)?.tenantId ?? null
      if (roleTenant && list.some((w) => w.tenantId === roleTenant)) {
        return roleTenant
      }
      return list[0]?.tenantId ?? null
    },

    /** 切换当前仓（写 localStorage） */
    switchWarehouse(tenantId: string): void {
      this.currentTenantId = tenantId
      this.persist(tenantId)
    },

    /** 新建仓 → 刷新仓列表 → 切到新仓 */
    async createWarehouse(dto: CreateWarehouseRequest): Promise<string> {
      const res = await warehouseApi.create(dto)
      await this.fetchWarehouses()
      this.switchWarehouse(res.tenantId)
      return res.tenantId
    },

    /** 写入本地当前仓记录（绑定当前 userId） */
    persist(tenantId: string): void {
      const auth = useAuthStore()
      if (auth.userId) writeCurrentTenant(auth.userId, tenantId)
    },

    /** 登出/换账号时清空 */
    clear(): void {
      this.currentTenantId = null
      this.warehouses = []
      clearCurrentTenant()
    },
  },
})
