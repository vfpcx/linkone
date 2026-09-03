/**
 * WA/WE 批发商移动端会话（F3）。
 *
 * RT 匿名身份（本地手机号）与批发商登录态并存互不干扰（见 storage.ts）；
 * 本文件只维护「登录会话 auth + 当前工作仓 work（角色条目）」。
 *
 * work = 用户从 roles 里选中的一条批发商角色（一账号多仓时切换 X-Tenant-Id 用），
 * 绑定 userId 防跨账号误用。
 */
import type { LoginResponse, SnowflakeId } from '@cangchu/api-types'

export type WorkRole = 'WA' | 'WE'

export interface WaWork {
  /** 绑定用户，防跨账号残留误用 */
  userId: SnowflakeId
  role: WorkRole
  tenantId: SnowflakeId
  wholesalerId?: SnowflakeId | null
  storeName?: string | null
}

export interface WaAuth {
  token: string
  userId: SnowflakeId
  primaryRole: LoginResponse['primaryRole']
  roles: LoginResponse['roles']
  primaryRouter: string
  expireAt: string
  tenantInfo?: LoginResponse['tenantInfo']
}

const AUTH_KEY = 'cc-wa-auth'
const WORK_KEY = 'cc-wa-work'

export function readAuth(): WaAuth | null {
  try {
    const raw = uni.getStorageSync(AUTH_KEY)
    return raw ? (raw as WaAuth) : null
  } catch {
    return null
  }
}

/** 写入登录响应并自动选定默认工作仓（角色 priority 最小者） */
export function writeAuth(res: LoginResponse): void {
  const auth: WaAuth = {
    token: res.token,
    userId: res.userId,
    primaryRole: res.primaryRole,
    roles: res.roles,
    primaryRouter: res.primaryRouter,
    expireAt: res.expireAt,
    tenantInfo: res.tenantInfo,
  }
  uni.setStorageSync(AUTH_KEY, auth)
  const w = pickDefaultWork(auth)
  if (w) uni.setStorageSync(WORK_KEY, w)
  else uni.removeStorageSync(WORK_KEY)
}

export function clearAuth(): void {
  uni.removeStorageSync(AUTH_KEY)
  uni.removeStorageSync(WORK_KEY)
}

export function readWork(): WaWork | null {
  const auth = readAuth()
  if (!auth) return null
  try {
    const raw = uni.getStorageSync(WORK_KEY)
    if (!raw) return null
    const w = raw as WaWork
    return w.userId === auth.userId ? w : null
  } catch {
    return null
  }
}

export function writeWork(w: WaWork): void {
  uni.setStorageSync(WORK_KEY, w)
}

export function clearWork(): void {
  uni.removeStorageSync(WORK_KEY)
}

/** 该登录会话可用的批发商角色条目（WA/WE 且已入驻某仓），按 priority 升序 */
export function workEntries(auth: Pick<LoginResponse, 'roles'>): LoginResponse['roles'] {
  return auth.roles
    .filter((r) => (r.role === 'WA' || r.role === 'WE') && r.tenantId)
    .slice()
    .sort((a, b) => a.priority - b.priority)
}

export function pickDefaultWork(auth: Pick<LoginResponse, 'userId' | 'roles'>): WaWork | null {
  const e = workEntries(auth)[0]
  if (!e) return null
  return {
    userId: auth.userId,
    role: e.role as WorkRole,
    tenantId: e.tenantId as SnowflakeId,
    wholesalerId: e.wholesalerId,
    storeName: e.storeName ?? null,
  }
}

/** 是否批发商会话（已登录且可工作） */
export function hasWaScope(): boolean {
  const auth = readAuth()
  return !!auth && workEntries(auth).length > 0
}

export function roleLabel(role: string): string {
  const map: Record<string, string> = { WA: '批发商', WE: '员工', TA: '仓库主', WK: '库管', ST: '结算员', OPS: '平台', RT: '买家' }
  return map[role] ?? role
}
