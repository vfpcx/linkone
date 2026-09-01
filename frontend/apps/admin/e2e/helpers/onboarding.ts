/**
 * P2 入驻生态 · API 造数/断言 helper（数据隔离）
 *
 * 覆盖 4 条 E2E 链路（04-onboarding-test-plan §3）所需造数：
 *   - ACTIVE 租户：TA 注册(带 tenantName → PENDING 壳) → OPS 注册 → OPS 审核 APPROVED
 *   - WA 直申：注册携 targetTenantId → 后端自动建 PENDING 申请单（AccountServiceImpl 接入点）
 *   - TA 审批（入驻 / 退驻共用 audit 契约：action=APPROVED/REJECTED，驳回 remark 必填）
 *   - 库存链：店铺码 → SKU → WK 凭码注册 → 入库（复用 sell.ts 同款契约）
 *   - 清库存：RT 公开询价下单 → WA 确认转出库（precheck stockCleared 翻绿）
 *   - OPS 黑名单三端点（/ops/blacklist GET/POST/DELETE）
 *   - WE 员工：生码（permissions）→ 凭码注册 → 禁用/恢复
 *
 * 契约权威来源（本机 dev,local 实测回归，2026-07-16）：
 *   WholesalerApplicationController / WholesalerLifecycleController /
 *   OpsBlacklistController / WholesalerEmployeeInviteController / WholesalerEmployeeController
 *
 * 安全编码自检（05-secure-coding-guardrails）：仅测试造数；tenantId/wholesalerId
 * 全部由后端登录态推导（G-2.1），各角色 token 均走真实注册/登录接口（G-1.x）。
 */

import { type Page, expect } from '@playwright/test'

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

export const SMS_CODE = '888888'
export const SEED_PWD = 'OnbPass123'

let seq = 0
/** 唯一手机号：13 + 7 位时间戳尾数 + 2 位自增，防同毫秒撞号（沿 sell.ts 惯例） */
export function uniqPhone(): string {
  seq = (seq + 1) % 100
  return '13' + String(Date.now()).slice(-7) + String(seq).padStart(2, '0')
}

/** 后端统一响应包 { code, message, data } */
export interface Envelope<T> {
  code: number
  message?: string
  data: T
}

async function call<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  opts: { token?: string; body?: unknown; query?: Record<string, string | number> } = {},
): Promise<Envelope<T>> {
  const qs = opts.query
    ? '?' + new URLSearchParams(Object.entries(opts.query).map(([k, v]) => [k, String(v)])).toString()
    : ''
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (opts.token) {
    // sa-token：Authorization 放裸 token（无 Bearer 前缀）
    headers['Authorization'] = opts.token
    headers['satoken'] = opts.token
  }
  const res = await fetch(`${API}/api/v1${path}${qs}`, {
    method,
    headers,
    // GET/DELETE 不能带 body（undici 限制）
    body: opts.body != null && method !== 'GET' ? JSON.stringify(opts.body) : undefined,
  })
  return (await res.json()) as Envelope<T>
}

export const apiGet = <T>(path: string, token?: string, query?: Record<string, string | number>) =>
  call<T>('GET', path, { token, query })
export const apiPost = <T>(path: string, body?: unknown, token?: string) =>
  call<T>('POST', path, { token, body })
export const apiDelete = <T>(path: string, token?: string) => call<T>('DELETE', path, { token })

/** code!==0 时 fail-fast，附带后端 message 便于定位 */
export function ok<T>(env: Envelope<T>, ctx: string): T {
  if (env?.code !== 0) {
    throw new Error(`[onb-seed] ${ctx} 失败 code=${env?.code} msg=${env?.message ?? ''}`)
  }
  return env.data
}

export interface LoginData {
  token: string
  userId: string
  primaryRole: string
  primaryRouter?: string
  roles: Array<{ role: string; tenantId?: string; wholesalerId?: string; priority?: number }>
}

/** 带重试的注册（规避 Redis 偶发写失败，沿 sell.ts 惯例） */
export async function registerWithRetry(
  body: Record<string, unknown>,
  ctx: string,
): Promise<LoginData> {
  let last: Envelope<LoginData> | null = null
  for (let i = 0; i < 4; i++) {
    last = await apiPost<LoginData>('/account/register', body)
    if (last?.code === 0) return last.data
    await new Promise((r) => setTimeout(r, 600))
  }
  throw new Error(`[onb-seed] ${ctx} 4 次均失败 code=${last?.code} msg=${last?.message ?? ''}`)
}

/** 单次注册（不重试）——用于「期望失败」断言（如黑名单 50205） */
export const apiRegisterOnce = (body: Record<string, unknown>) =>
  apiPost<LoginData>('/account/register', body)

export const apiLogin = (phone: string, password: string) =>
  apiPost<LoginData>('/account/login', { phone, password })

// ============================== 造数：租户 / WA / OPS ==============================

export interface TenantSeed {
  tenantId: string
  ta: { phone: string; pwd: string; login: LoginData }
  ops: { phone: string; pwd: string; login: LoginData }
}

/**
 * ACTIVE 租户三件套：TA 注册(建 PENDING 壳) → OPS 注册 → OPS 审核通过。
 * WA 入驻申请要求目标租户 ACTIVE（F5 审查修复），故审核步骤必不可少。
 */
export async function seedActiveTenant(): Promise<TenantSeed> {
  const taPhone = uniqPhone()
  const taLogin = await registerWithRetry(
    {
      phone: taPhone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'TA',
      realName: 'OnbTA',
      tenantName: 'OnbShop' + taPhone.slice(-4),
      agreedTerms: true,
    },
    'TA 注册建仓',
  )
  const tenantId = taLogin.roles.find((r) => r.role === 'TA')?.tenantId
  if (!tenantId) throw new Error('[onb-seed] TA 注册未返回 tenantId')

  const opsPhone = uniqPhone()
  const opsLogin = await registerWithRetry(
    {
      phone: opsPhone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'OPS',
      realName: 'OnbOPS',
      agreedTerms: true,
    },
    'OPS 注册',
  )
  ok(
    await apiPost(`/admin/tenant/${tenantId}/audit`, { action: 'APPROVED' }, opsLogin.token),
    'OPS 审核租户',
  )
  return {
    tenantId,
    ta: { phone: taPhone, pwd: SEED_PWD, login: taLogin },
    ops: { phone: opsPhone, pwd: SEED_PWD, login: opsLogin },
  }
}

export interface WaSeed {
  phone: string
  pwd: string
  login: LoginData
  wholesalerName: string
}

/**
 * WA 注册携 targetTenantId（注册接入点：后端自动创建 PENDING 入驻申请单）。
 * 注：注册页 UI 的「目标仓库」下拉为硬编码 mock（Register.vue fetchTenants），
 * 真实租户 ID 无法经 UI 选择（缺陷 DEF-ONB-01），故此步走 API，与后端契约等价。
 */
export async function registerWaWithTarget(tenantId: string): Promise<WaSeed> {
  const phone = uniqPhone()
  const wholesalerName = 'OnbWS' + phone.slice(-4)
  const login = await registerWithRetry(
    {
      phone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'WA',
      realName: 'OnbWA',
      wholesalerName,
      targetTenantId: tenantId,
      agreedTerms: true,
    },
    'WA 注册直申',
  )
  return { phone, pwd: SEED_PWD, login, wholesalerName }
}

/** 纯 WA 账号（不带 target，不建申请单）——黑名单链等场景用 */
export async function registerWaPlain(phone = uniqPhone()): Promise<WaSeed> {
  const login = await registerWithRetry(
    {
      phone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'WA',
      realName: 'OnbWA',
      agreedTerms: true,
    },
    'WA 纯注册',
  )
  return { phone, pwd: SEED_PWD, login, wholesalerName: 'OnbWS' + phone.slice(-4) }
}

// ============================== 申请 / 审批 ==============================

export interface ApplicationRow {
  id: string
  tenantId: string
  name: string
  status: string
  auditRemark?: string
}

/** TA 分页申请列表（records） */
export async function listTaApplications(
  taToken: string,
  status = 'PENDING',
): Promise<ApplicationRow[]> {
  const data = ok(
    await apiGet<{ records: ApplicationRow[] }>('/tenant/wholesaler-applications', taToken, {
      status,
      page: 1,
      size: 50,
    }),
    'TA 申请列表',
  )
  return data.records ?? []
}

/** WA 本人申请列表（listMine） */
export async function listMyApplications(waToken: string): Promise<ApplicationRow[]> {
  return ok(await apiGet<ApplicationRow[]>('/wholesaler/applications', waToken), 'WA listMine')
}

export const auditApplication = (
  taToken: string,
  id: string,
  action: 'APPROVED' | 'REJECTED',
  remark?: string,
) =>
  apiPost<{ applicationId: string; status: string; wholesalerId?: string }>(
    `/tenant/wholesaler-applications/${id}/audit`,
    { action, remark },
    taToken,
  )

/** WA 自助提交入驻申请（黑名单链的 API 侧断言用） */
export const selfApply = (
  waToken: string,
  body: { targetTenantId: string; name: string; contactName?: string; contactPhone?: string; license?: string },
) => apiPost<{ applicationId: string; status: string }>('/wholesaler/applications', body, waToken)

// ============================== 退驻（R13） ==============================

export const withdrawPrecheck = (waToken: string) =>
  apiGet<{ stockCleared: boolean; openDocs: { cleared: boolean; count: number } }>(
    '/wholesaler/withdraw/precheck',
    waToken,
  )

export const submitWithdraw = (waToken: string, reason?: string) =>
  apiPost<{ applicationId: string; status: string }>('/wholesaler/withdraw', { reason }, waToken)

export async function listTaWithdrawApps(taToken: string, status = 'PENDING') {
  const data = ok(
    await apiGet<{ records: Array<{ id: string; status: string; wholesalerName?: string }> }>(
      '/tenant/wholesaler-withdraw-applications',
      taToken,
      { status, page: 1, size: 50 },
    ),
    'TA 退驻列表',
  )
  return data.records ?? []
}

export const auditWithdraw = (
  taToken: string,
  id: string,
  action: 'APPROVED' | 'REJECTED',
  remark?: string,
) => apiPost(`/tenant/wholesaler-withdraw-applications/${id}/audit`, { action, remark }, taToken)

export const restoreWithdraw = (waToken: string) =>
  apiPost<{ wholesalerId: string; status: string }>('/wholesaler/withdraw/restore', {}, waToken)

// ============================== 黑名单（OPS） ==============================

export interface BlacklistRow {
  id: string
  targetType: string
  targetValue: string
  status: string
}

export const opsAddBlacklist = (
  opsToken: string,
  targetType: 'PHONE' | 'LICENSE_NO',
  targetValue: string,
  reason: string,
) => apiPost<BlacklistRow>('/ops/blacklist', { targetType, targetValue, reason }, opsToken)

/** Wave6 DEF-6：列表改分页契约 {records,total,page,size}（§31） */
export const opsListBlacklist = (
  opsToken: string,
  status = 'ACTIVE',
  extra: { page?: number; size?: number; keyword?: string } = {},
) =>
  apiGet<{ records: BlacklistRow[]; total: number; page: number; size: number }>(
    '/ops/blacklist',
    opsToken,
    { status, ...extra },
  )

export const opsRemoveBlacklist = (opsToken: string, id: string) =>
  apiDelete(`/ops/blacklist/${id}`, opsToken)

// ============================== 库存链（沿 sell.ts 契约） ==============================

export interface StockSeed {
  storeCode: string
  wholesalerId: string
  skuId: string
  stock: number
  /** P3 W5：清库存需 WK 打印+登记出库（confirm 后出库单停 PENDING_ACCEPT），故回传 WK 凭据 */
  wkToken: string
  tenantId: string
}

/**
 * 给「已通过入驻的 WA」上一条有库存的 SKU：
 * TA 店铺码 → TA 上架 SKU（挂在该 WA 的 wholesaler 下）→ TA 生 WK 码 → WK 注册 → WK 入库。
 */
export async function seedStockForWholesaler(
  taToken: string,
  wholesalerId: string,
  stock = 4,
): Promise<StockSeed> {
  const qr = ok(
    await apiPost<{ tenantSimpleCode: string }>('/tenant/store-qr', undefined, taToken),
    '生成店铺码',
  )
  const sku = ok(
    await call<{ id: string }>('POST', '/tenant/skus', {
      token: taToken,
      query: { wholesalerId },
      body: { name: 'OnbSku' + String(Date.now()).slice(-6), spec: '1x1', unitPrice: 20, moqPrice: 18, moqQty: 1 },
    }),
    '上架 SKU',
  )
  const invite = ok(
    await apiPost<{ code: string }>(
      '/tenant/employee-invites',
      { role: 'WK', maxUses: 1, expiresInDays: 7 },
      taToken,
    ),
    '生成 WK 码',
  )
  const wk = await registerWithRetry(
    {
      phone: uniqPhone(),
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'WK',
      realName: 'OnbWK',
      inviteCode: invite.code,
      agreedTerms: true,
    },
    'WK 凭码注册',
  )
  ok(
    await apiPost('/tenant/inbound', { wholesalerId, skuId: sku.id, qty: stock, palletQty: 0 }, wk.token),
    'WK 入库',
  )
  const tenantId = wk.roles.find((r) => r.role === 'WK')?.tenantId
  if (!tenantId) throw new Error('[onb-seed] WK 登录态缺 tenantId')
  return {
    storeCode: qr.tenantSimpleCode,
    wholesalerId,
    skuId: sku.id,
    stock,
    wkToken: wk.token,
    tenantId: String(tenantId),
  }
}

/**
 * P3 W5：WA 确认全部待确认代建入库单（BE-W1 起 WK 登记入库停 PENDING_WA_CONFIRM，
 * 属 R13 未结单据，会卡退驻前置自查；72h 内需 WA 确认收尾）。
 */
export async function confirmPendingInbound(waToken: string): Promise<void> {
  const env = await apiGet<{ records?: Array<{ id: string }> }>(
    '/wholesaler/inbound-requests',
    waToken,
    { status: 'PENDING_WA_CONFIRM', page: 1, size: 50 },
  )
  const rows = ok(env, 'WA 列待确认入库').records ?? []
  for (const r of rows) {
    ok(await apiPost(`/wholesaler/inbound-requests/${r.id}/confirm`, undefined, waToken), 'WA 确认入库')
  }
}

/**
 * 清空库存：RT 公开询价整量下单 → WA 确认（确认即扣，库存 N → 0）
 * → WK 打印 + 登记出库（P3 BE-W2 起 confirm 后出库单停 PENDING_ACCEPT，
 *   属 R13 未结单据，会卡退驻前置自查，必须走完 WK 作业闭环使询价 COMPLETED）。
 */
export async function sellOutStock(seed: StockSeed, waToken: string): Promise<void> {
  const inq = ok(
    await apiPost<{ id: string; docNo: string }>('/rt/inquiry', {
      code: seed.storeCode,
      wholesalerId: seed.wholesalerId,
      rtPhone: uniqPhone(),
      items: [{ skuId: seed.skuId, qty: seed.stock }],
    }),
    'RT 下单清库存',
  )
  const rows = ok(
    await apiGet<Array<{ id: string; docNo: string }>>('/tenant/inquiry', waToken),
    'WA 列询价',
  )
  const target = rows.find((r) => r.docNo === inq.docNo)
  if (!target) throw new Error(`[onb-seed] 未找到询价单 ${inq.docNo}`)
  ok(await apiPost(`/tenant/inquiry/${target.id}/confirm`, {}, waToken), 'WA 确认转出库')

  // P3 出库作业闭环：WK 列 PENDING_ACCEPT → 打印 → 登记出库（隔离租户内即本单）
  const wkHeaders = {
    Authorization: seed.wkToken,
    satoken: seed.wkToken,
    'X-Tenant-Id': seed.tenantId,
  }
  const listRes = await fetch(
    `${API}/api/v1/tenant/outbound-requests?status=PENDING_ACCEPT&page=1&size=10`,
    { headers: wkHeaders },
  )
  const listText = await listRes.text()
  const idm = listText.match(/"id":\s*"?(\d{10,})"?/)
  if (!idm) throw new Error(`[onb-seed] 清库存后未找到待受理出库单：${listText.slice(0, 200)}`)
  const outboundId = idm[1]
  const printRes = await fetch(`${API}/api/v1/tenant/outbound-requests/${outboundId}/print`, {
    method: 'POST',
    headers: wkHeaders,
  })
  const printEnv = (await printRes.json()) as { code: number; message?: string }
  if (printEnv.code !== 0) {
    throw new Error(`[onb-seed] WK 打印失败 code=${printEnv.code} msg=${printEnv.message ?? ''}`)
  }
  const regRes = await fetch(`${API}/api/v1/tenant/outbound-requests/${outboundId}/register`, {
    method: 'POST',
    headers: wkHeaders,
  })
  const regEnv = (await regRes.json()) as { code: number; message?: string }
  if (regEnv.code !== 0) {
    throw new Error(`[onb-seed] WK 登记出库失败 code=${regEnv.code} msg=${regEnv.message ?? ''}`)
  }
}

/** 读 RT 店铺聚合（公开）——退驻后店铺隐藏断言 */
export const fetchRtStore = (code: string) =>
  apiGet<{ wholesalers: Array<{ wholesalerId: string; name: string; skus: unknown[] }> }>(
    '/rt/store',
    undefined,
    { code },
  )

/** TA 读 SKU 列表——退驻后 SKU 下架（listed=false）断言 */
export const listSkus = (taToken: string, wholesalerId: string) =>
  call<Array<{ id: string; name: string; listed: boolean }>>('GET', '/tenant/skus', {
    token: taToken,
    query: { wholesalerId },
  })

// ============================== WE 员工 ==============================

export const listWaEmployees = (waToken: string) =>
  apiGet<Array<{ id: string; phone: string; realName: string; status: string; permissions: string[] }>>(
    '/wholesaler/employees',
    waToken,
  )

export const disableWaEmployee = (waToken: string, employeeId: string) =>
  apiPost<{ employeeId: string; status: string }>(`/wholesaler/employees/${employeeId}/disable`, {}, waToken)

// ============================== UI helpers ==============================

/**
 * UI 密码登录 → 等待落地目标路由（清 storage 防上个用例登录态串扰）。
 * 泛化自 helpers/ui.ts#loginUi（那个写死 /ta/dashboard，WA/OPS 复用不了）。
 *
 * 多工作空间兼容：WA 注册直申 + 审批通过后账号存在两条 WA 角色
 * （注册占位 priority=40 + 审批绑定 wholesalerId priority=5），登录会弹
 * 「您在多个工作空间，请选择进入」→ 自动点第一个「进入」（缺陷 DEF-3 已登记：
 * 两条目文案完全相同均为"批发商管理员"，用户无从分辨）。
 */
export async function loginAs(
  page: Page,
  phone: string,
  pwd: string,
  landUrl: RegExp,
): Promise<void> {
  await page.goto('/login')
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  })
  await page.goto('/login')
  await page.waitForLoadState('networkidle')
  await page.getByPlaceholder('请输入手机号').fill(phone)
  await page.getByPlaceholder('请输入密码').fill(pwd)
  await page.locator('.submit-btn').click()

  // 竞态等待：直接落地 or 工作空间选择弹窗
  const switcher = page.locator('.el-dialog', { hasText: '请选择进入' })
  const landed = await Promise.race([
    page.waitForURL(landUrl, { timeout: 15_000 }).then(() => true).catch(() => false),
    switcher.waitFor({ state: 'visible', timeout: 15_000 }).then(() => false).catch(() => true),
  ])
  if (!landed && (await switcher.isVisible().catch(() => false))) {
    await switcher.getByRole('button', { name: '进入' }).first().click()
    await page.waitForURL(landUrl, { timeout: 15_000 })
  }
  await expect(page).toHaveURL(landUrl)
}

/**
 * 注入登录态（pinia-persist key = cangchu-admin-auth）并直达目标页。
 * 沿 sell-flow.spec.ts#enterWaInquiry 惯例；视觉验收快速进入各角色页面用。
 */
export async function injectAuthAndGoto(
  page: Page,
  login: LoginData,
  primaryRole: string,
  path: string,
): Promise<void> {
  const role =
    login.roles.find((r) => r.role === primaryRole && (r.tenantId || r.wholesalerId)) ??
    login.roles.find((r) => r.role === primaryRole) ??
    login.roles[0]
  const authState = {
    token: login.token,
    userId: login.userId,
    primaryRole,
    roles: login.roles,
    primaryRouter: path,
    expireAt: null,
    tenantInfo: role?.tenantId ? { tenantId: role.tenantId, tenantName: '测试仓' } : null,
  }
  await page.goto('/login')
  await page.evaluate((s) => {
    localStorage.clear()
    sessionStorage.clear()
    localStorage.setItem('cangchu-admin-auth', JSON.stringify(s))
  }, authState)
  await page.goto(path)
  await page.waitForLoadState('networkidle')
}
