/**
 * 当前仓（多仓切换）本地存储助手
 *
 * 用途：http 请求拦截器读取"当前选中仓 tenantId"注入 X-Tenant-Id 头。
 * 存储结构绑定 userId，避免"换账号后残留上一用户的仓 → 被后端判 42101"。
 * 独立无外部 import，避免 http ↔ store ↔ api 的循环依赖。
 */

const KEY = 'cangchu-current-tenant'

export interface CurrentTenantRecord {
  /** 归属用户（雪花字符串），换账号后失配即忽略 */
  userId: string
  /** 当前选中仓 tenantId（雪花字符串） */
  tenantId: string
}

export function readCurrentTenant(): CurrentTenantRecord | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const rec = JSON.parse(raw) as CurrentTenantRecord
    if (rec && typeof rec.userId === 'string' && typeof rec.tenantId === 'string') {
      return rec
    }
    return null
  } catch {
    return null
  }
}

export function writeCurrentTenant(userId: string, tenantId: string): void {
  localStorage.setItem(KEY, JSON.stringify({ userId, tenantId }))
}

export function clearCurrentTenant(): void {
  localStorage.removeItem(KEY)
}
