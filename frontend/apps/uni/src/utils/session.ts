/**
 * 商户/结算移动端会话（F3 WA/WE · F4 ST）。
 *
 * RT 匿名身份（本地手机号）与登录态并存互不干扰（见 storage.ts）；
 * 本文件只维护「登录会话 auth + 当前工作仓 work（角色条目）」。
 *
 * work = 用户从 roles 里选中的一条「带 tenantId 的工作角色」，
 * （WA/WE 一账号多仓 / ST 结算）用于组装 X-Tenant-Id，绑定 userId 防跨账号误用。
 * 各角色区（wa/st…）在 onLoad 自愈：若当前 work 非本区角色则重选本区默认。
 */
import type { LoginResponse, SnowflakeId } from '@cangchu/api-types'

export type WorkRole = 'WA' | 'WE' | 'ST'

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

/** 移动端已提供工作区的带仓角色（WA/WE=F3、ST=F4；WK 待 F5） */
const WORK_ROLES = new Set(['WA', 'WE', 'ST'])

/** 该登录会话可用的批发商角色条目（WA/WE 且已入驻某仓），按 priority 升序 */
export function workEntries(auth: Pick<LoginResponse, 'roles'>): LoginResponse['roles'] {
  return auth.roles
    .filter((r) => (r.role === 'WA' || r.role === 'WE') && r.tenantId)
    .slice()
    .sort((a, b) => a.priority - b.priority)
}

/** 该登录会话可用的结算员角色条目（ST 且带仓），按 priority 升序 */
export function stEntries(auth: Pick<LoginResponse, 'roles'>): LoginResponse['roles'] {
  return auth.roles
    .filter((r) => r.role === 'ST' && r.tenantId)
    .slice()
    .sort((a, b) => a.priority - b.priority)
}

/** 全工作角色条目（含 ST），按 priority 升序（跨区排序，账号多区角色时小的在前） */
function allWorkEntries(auth: Pick<LoginResponse, 'roles'>): LoginResponse['roles'] {
  return auth.roles
    .filter((r) => WORK_ROLES.has(r.role) && r.tenantId)
    .slice()
    .sort((a, b) => a.priority - b.priority)
}

export function pickDefaultWork(auth: Pick<LoginResponse, 'userId' | 'roles'>): WaWork | null {
  const e = allWorkEntries(auth)[0]
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

/** 是否结算员会话（已登录且带仓的 ST 条目） */
export function hasStScope(): boolean {
  const auth = readAuth()
  return !!auth && stEntries(auth).length > 0
}

/**
 * 角色区自愈：保证当前 work 属于本区（WA/WE 区 or ST 区）。
 * 供各工作台 onLoad 调用；返回自愈后的 work（null 表示无本区工作条目）。
 */
export function healWork(zone: 'wa' | 'st', auth: Pick<LoginResponse, 'userId' | 'roles'>, current: WaWork | null): WaWork | null {
  const list = zone === 'st' ? stEntries(auth) : workEntries(auth)
  if (!list.length) return null
  const inZone = current && current.userId === auth.userId && list.some((e) => e.tenantId === current!.tenantId && e.role === current!.role)
  if (inZone) return current
  const e = list[0]
  const w: WaWork = {
    userId: auth.userId,
    role: e.role as WorkRole,
    tenantId: e.tenantId as SnowflakeId,
    wholesalerId: e.wholesalerId,
    storeName: e.storeName ?? null,
  }
  writeWork(w)
  return w
}

export function roleLabel(role: string): string {
  const map: Record<string, string> = { WA: '批发商', WE: '员工', TA: '仓库主', WK: '库管', ST: '结算员', OPS: '平台', RT: '买家' }
  return map[role] ?? role
}
