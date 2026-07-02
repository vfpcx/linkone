/**
 * phase-1 卖货整链 · API 造数 helper（数据隔离）
 *
 * 直接走后端接口预置一条「可下单」的店铺：
 *   TA 注册建仓(PENDING 壳, 带 tenantName)
 *     → 生成店铺码 tenantSimpleCode（RT 进店 code）
 *     → 预注册 WA 账号（已知密码，供后续拿真实 WA token 驱动确认页）
 *     → TA 自营建商户 wholesalers（带 waPhone → 给上面 WA 账号绑 WA 角色 + wholesalerId）
 *     → 上架 SKU（ASCII 名，listed=true）
 *     → 生成 WK 员工注册码（抓 data.code，非外层 code:0）
 *     → WK 凭码注册（拿 WK token）
 *     → WK 登记入库（库存 N）——入库后 SKU 才在 RT 店铺可见（listed && qty>0）
 *
 * 契约来源（权威）：
 *   - POST /account/register                 account/controller/AccountController
 *   - POST /account/login                    同上（拿 WA 真实 token）
 *   - POST /tenant/store-qr                  tenant/controller/TenantController（返回 tenantSimpleCode）
 *   - POST /tenant/wholesalers               tenant/controller/WholesalerController（body.waPhone 开通 WA）
 *   - POST /tenant/skus?wholesalerId=        product/controller/SkuController
 *   - POST /tenant/employee-invites          tenant/controller/TenantController（EmployeeInviteVo.code）
 *   - POST /tenant/inbound                   document/controller/InboundController
 *   - GET  /rt/store?code=                   storefront/controller/RtStoreController（公开，含 stockQty）
 *
 * 安全编码规约（05-secure-coding-guardrails）自检：本 helper 仅为测试造数，全程
 *   tenantId/wholesalerId 由后端登录态推导，前端不伪造归属（G-2.1）；WA/WK 会话均走
 *   真实登录接口拿真 token，不绕鉴权（G-1.x）。断言侧覆盖 S1 整链 + S2/S6 场景。
 */

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

export const SMS_CODE = '888888'
/** 满足后端 ^(?=.*[a-zA-Z])(?=.*\d).{6,20}$ */
export const SEED_PWD = 'SellPass123'

let seq = 0
/** 唯一手机号：13 + 9 位（时间戳尾数 + 自增），避免同一毫秒内多次造号撞号 */
export function uniqPhone(): string {
  seq = (seq + 1) % 100
  const base = String(Date.now()).slice(-7)
  return '13' + base + String(seq).padStart(2, '0')
}

/** 后端统一响应包 { code, message, data } */
interface Envelope<T> {
  code: number
  message?: string
  data: T
}

async function post<T>(
  path: string,
  opts: { token?: string; body?: unknown; query?: Record<string, string | number> } = {},
): Promise<Envelope<T>> {
  const qs = opts.query
    ? '?' + new URLSearchParams(Object.entries(opts.query).map(([k, v]) => [k, String(v)])).toString()
    : ''
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (opts.token) {
    // 后端 sa-token：Authorization 放裸 token（无 Bearer 前缀）
    headers['Authorization'] = opts.token
    headers['satoken'] = opts.token
  }
  const res = await fetch(`${API}/api/v1${path}${qs}`, {
    method: 'POST',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })
  return (await res.json()) as Envelope<T>
}

async function get<T>(path: string, query?: Record<string, string | number>): Promise<Envelope<T>> {
  const qs = query
    ? '?' + new URLSearchParams(Object.entries(query).map(([k, v]) => [k, String(v)])).toString()
    : ''
  const res = await fetch(`${API}/api/v1${path}${qs}`)
  return (await res.json()) as Envelope<T>
}

/** code!==0 时 fail-fast，附带后端 message 便于定位 */
function ok<T>(env: Envelope<T>, ctx: string): T {
  if (env?.code !== 0) {
    throw new Error(`[seed] ${ctx} 失败 code=${env?.code} msg=${env?.message ?? ''}`)
  }
  return env.data
}

/** 带重试的注册（规避 Redisson 偶发掉线，历史 Bug A） */
async function registerWithRetry(body: Record<string, unknown>, ctx: string): Promise<LoginData> {
  let last: Envelope<LoginData> | null = null
  for (let i = 0; i < 4; i++) {
    last = await post<LoginData>('/account/register', { body })
    if (last?.code === 0) return last.data
    await new Promise((r) => setTimeout(r, 600))
  }
  throw new Error(`[seed] ${ctx} 4 次均失败 code=${last?.code} msg=${last?.message ?? ''}`)
}

interface LoginData {
  token: string
  userId: string
  primaryRole: string
  roles: Array<{ role: string; tenantId?: string; wholesalerId?: string }>
  primaryRouter?: string
}

export interface SellSeed {
  /** RT 进店码（= tenantSimpleCode），驱动 /rt/store?code= */
  storeCode: string
  wholesalerId: string
  skuId: string
  skuName: string
  /** 上架的公开价三件套 */
  unitPrice: number
  /** 入库库存 N */
  stock: number
  /** WA 账号（已知密码） */
  wa: { phone: string; pwd: string }
  /** WA 登录态（注册自动登录返回的 token + roles + userId），用于注入前端 auth 直达 /wa/inquiry */
  waLogin: LoginData
  /** 便于回读校验 */
  taToken: string
}

/**
 * 造一条完整可卖的店铺，返回下单/确认所需句柄。
 *
 * @param stock 入库库存 N（默认 50，留足余量便于扣减后仍 >0 断言）
 */
export async function seedSellChain(stock = 50): Promise<SellSeed> {
  // 1) TA 注册建仓（PENDING 壳，带 tenantName）
  const taPhone = uniqPhone()
  const ta = await registerWithRetry(
    {
      phone: taPhone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'TA',
      realName: 'SellTA',
      tenantName: 'SellShop' + taPhone.slice(-4),
      agreedTerms: true,
    },
    'TA 注册',
  )
  const taToken = ta.token

  // 2) 生成店铺码（tenantSimpleCode）= RT 进店 code
  const qr = ok(
    await post<{ tenantId: string; tenantSimpleCode: string; qrUrl: string }>('/tenant/store-qr', {
      token: taToken,
    }),
    '生成店铺码',
  )
  const storeCode = qr.tenantSimpleCode

  // 3) 预注册 WA 账号（已知密码）——先建 User，后续建商户的 waPhone 复用此 User 并绑 WA 角色。
  //    注意：注册即自动登录，返回的 token 就是可用的 WA 会话（绑定 WA 角色后即拥有确认权）。
  //    ⚠️ 契约踩坑：对同一用户「再次 /account/login」会拿到一个被 sa-token 拒绝(41001)的新 token；
  //       因此 WA 会话一律复用「注册时自动登录的 token」，不再二次 password 登录（见交付说明）。
  const waPhone = uniqPhone()
  const waLogin = await registerWithRetry(
    {
      phone: waPhone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      // 用 RT 角色占位（priority 60 > WA 40，绑定 WA 后 WA 成主角色 → 前端 defaultRouterFor('WA')=/wa/inquiry）
      role: 'RT',
      realName: 'SellWA',
      agreedTerms: true,
    },
    'WA 预注册',
  )

  // 4) TA 自营建商户（带 waPhone → 给上面 WA 账号补一条 role=WA + wholesalerId 绑定）
  const wholesaler = ok(
    await post<{ id: string; name: string; waUserId?: string }>('/tenant/wholesalers', {
      token: taToken,
      body: { name: 'SellWholesaler' + waPhone.slice(-4), waPhone },
    }),
    '建商户',
  )
  const wholesalerId = wholesaler.id

  // 5) 上架 SKU（ASCII 名，unitPrice>0）
  const unitPrice = 20
  const skuName = 'SellSku' + Date.now().toString().slice(-6)
  const sku = ok(
    await post<{ id: string; name: string; listed?: boolean }>('/tenant/skus', {
      token: taToken,
      query: { wholesalerId },
      body: { name: skuName, spec: '1x1', unitPrice, moqPrice: 18, moqQty: 1 },
    }),
    '上架 SKU',
  )
  const skuId = sku.id

  // 6) 生成 WK 员工注册码（抓 data.code，非外层 code:0）
  const invite = ok(
    await post<{ id: string; code: string; role: string }>('/tenant/employee-invites', {
      token: taToken,
      body: { role: 'WK', maxUses: 1, expiresInDays: 7 },
    }),
    '生成 WK 注册码',
  )
  const wkCode = invite.code

  // 7) WK 凭码注册（拿 WK token）
  const wkPhone = uniqPhone()
  const wk = await registerWithRetry(
    {
      phone: wkPhone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'WK',
      realName: 'SellWK',
      inviteCode: wkCode,
      agreedTerms: true,
    },
    'WK 凭码注册',
  )

  // 8) WK 登记入库（库存 N）——入库后 SKU 在 RT 店铺可见（listed && qty>0）
  ok(
    await post<{ id: string; docNo: string }>('/tenant/inbound', {
      token: wk.token,
      body: { wholesalerId, skuId, qty: stock, palletQty: 0 },
    }),
    'WK 入库',
  )

  // 9) WA 会话：复用第 3 步注册自动登录返回的 token（绑定 WA 角色后即拥有确认权）。
  //    primaryRole 此时为 RT（注册时），但前端注入 auth 时会以 roles 里的 WA 条目为准（见 spec）。

  return {
    storeCode,
    wholesalerId,
    skuId,
    skuName,
    unitPrice,
    stock,
    wa: { phone: waPhone, pwd: SEED_PWD },
    waLogin,
    taToken,
  }
}

// ============ 断言/联动辅助（公开只读端点，无需登录） ============

export interface RtStoreSku {
  skuId: string
  wholesalerId: string
  name: string
  unitPrice: number
  moqPrice: number
  moqQty: number
  stockQty: number
}
export interface RtStoreFront {
  storeCode: string
  storeName: string
  wholesalers: Array<{ wholesalerId: string; name: string; skus: RtStoreSku[] }>
}

/** 读 RT 店铺聚合（公开），用于库存断言 / 可见性校验 */
export async function fetchRtStore(code: string): Promise<RtStoreFront> {
  return ok(await get<RtStoreFront>('/rt/store', { code }), '读 RT 店铺')
}

/** 从店铺聚合里取某 SKU 当前库存量；不存在返回 null（已被扣减到 0 → 从列表消失） */
export function stockOfSku(store: RtStoreFront, skuId: string): number | null {
  for (const w of store.wholesalers) {
    const hit = w.skus.find((s) => String(s.skuId) === String(skuId))
    if (hit) return hit.stockQty
  }
  return null
}

/** 直接经 API 确认询价（S6 幂等断言 / UI 联动兜底）。返回完整响应包（便于断言 code）。 */
export async function apiConfirmInquiry(
  inquiryId: string,
  waToken: string,
): Promise<Envelope<{ id: string; docNo: string; status: string }>> {
  return post<{ id: string; docNo: string; status: string }>(
    `/tenant/inquiry/${inquiryId}/confirm`,
    { token: waToken },
  )
}

export interface InquiryRow {
  id: string
  docNo: string
  status: string
  wholesalerId: string
}

/** WA 列询价单（拿 id/status），供 UI 兜底与 S6 断言 */
export async function apiListInquiries(waToken: string): Promise<InquiryRow[]> {
  const res = await fetch(`${API}/api/v1/tenant/inquiry`, {
    headers: { Authorization: waToken, satoken: waToken },
  })
  const env = (await res.json()) as Envelope<InquiryRow[]>
  return ok(env, 'WA 列询价')
}

/**
 * 按 docNo（字符串，抗雪花精度丢失）在 WA 询价列表里定位单据，返回其 server-origin id。
 * 找不到抛错。用于「UI 提交拿到 docNo → 后端确认/断言」这段跨 UI/API 的 id 传递。
 */
export async function findInquiryByDocNo(
  waToken: string,
  docNo: string,
): Promise<InquiryRow> {
  const rows = await apiListInquiries(waToken)
  const hit = rows.find((r) => r.docNo === docNo)
  if (!hit) {
    throw new Error(`[seed] 未在 WA 列表中找到询价单 docNo=${docNo}（现有 ${rows.map((r) => r.docNo).join(',')}）`)
  }
  return hit
}
