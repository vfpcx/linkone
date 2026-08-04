/**
 * P3b T4 批次/临期/清库 · API 旁路造数 helper
 *
 * 契约来源（权威）：
 *  - POST /tenant/settings/batch-toggle   inventory/controller/BatchController（24h≤2 → 50361）
 *  - POST /tenant/inbound                 document/controller/InboundController（代建=提交即登记；
 *      批次开关启用时三字段必填；过期须 expiredConfirmed=true，否则 50364）
 *  - GET  /tenant/batches                 批次登记簿（WK/TA）
 *  - GET  /tenant/batches/expiring        预警列表
 *  - POST /tenant/batches/{id}/notify-wholesaler  手动通知（24h 限 1 → 50367）
 *
 * ⚠️ 测试接缝：PENDING_CLEARANCE 由 02:30 归零标记 Job 产生（无 HTTP 触发端点，
 *  BatchExpiryMarkJob 仅 @Scheduled / JUnit 直驱）。E2E 以 mysql CLI 直改
 *  batches.status 复现 Job 效果（等价于 markExpiredBatches 的 CAS 写路径，仅测试用）。
 */

import { spawnSync } from 'node:child_process'

const API = process.env.E2E_API_URL ?? 'http://localhost:8080'

const MYSQL_BIN =
  process.env.E2E_MYSQL_BIN ?? 'C:\\Program Files\\MySQL\\MySQL Server 9.7\\bin\\mysql.exe'
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? 'root'
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? 'chenxu18458748'
const MYSQL_DB = process.env.E2E_MYSQL_DB ?? 'cangchu_dev'

export interface Envelope<T> {
  code: number
  message?: string
  data: T
}

async function req<T>(
  method: 'GET' | 'POST',
  path: string,
  token: string,
  body?: unknown,
): Promise<Envelope<T>> {
  const res = await fetch(`${API}/api/v1${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: token,
      satoken: token,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  return (await res.json()) as Envelope<T>
}

function ok<T>(env: Envelope<T>, ctx: string): T {
  if (env?.code !== 0) {
    throw new Error(`[t4-seed] ${ctx} 失败 code=${env?.code} msg=${env?.message ?? ''}`)
  }
  return env.data
}

/** yyyy-MM-dd（本地时区，today+offset 天） */
export function dateOffset(offsetDays: number): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export interface BatchToggleResult {
  batchEnabled: number
  batchEnabledAt: string | null
  defaultBatchCount: number
  closedBatchCount: number
}

/** TA 批次开关（返回完整响应包，供 50361 断言；成功场景用 ok() 解包） */
export async function apiBatchToggle(
  taToken: string,
  enable: boolean,
): Promise<Envelope<BatchToggleResult>> {
  return req('POST', '/tenant/settings/batch-toggle', taToken, { enable, confirmed: true })
}

export async function apiBatchToggleOk(taToken: string, enable: boolean): Promise<BatchToggleResult> {
  return ok(await apiBatchToggle(taToken, enable), `批次开关 enable=${enable}`)
}

/** WK 代建入库（开关启用档带批次三字段；过期批次须 expiredConfirmed）。返回完整响应包供 50364 断言 */
export async function apiProxyInbound(
  wkToken: string,
  args: {
    wholesalerId: string
    skuId: string
    qty: number
    batchNo?: string
    productionDate?: string
    expiryDate?: string
    expiredConfirmed?: boolean
  },
): Promise<Envelope<{ id: string; docNo: string }>> {
  return req('POST', '/tenant/inbound', wkToken, args)
}

export interface BatchRow {
  id: string
  wholesalerId: string
  skuId: string
  batchNo: string
  productionDate: string | null
  expiryDate: string | null
  remainingDays: number | null
  initialQty: number
  remainingQty: number
  status: string
  source: string
  manualNotifiedAt: string | null
}

/** 批次登记簿（WK/TA） */
export async function apiListBatches(token: string): Promise<BatchRow[]> {
  return ok(
    await req<{ list: BatchRow[] }>('GET', '/tenant/batches', token),
    '批次登记簿',
  ).list
}

/** 按批次号定位批次行（找不到抛错） */
export async function findBatchByNo(token: string, batchNo: string): Promise<BatchRow> {
  const rows = await apiListBatches(token)
  const hit = rows.find((b) => b.batchNo === batchNo)
  if (!hit) {
    throw new Error(
      `[t4-seed] 未找到批次 ${batchNo}（现有 ${rows.map((b) => b.batchNo).join(',')}）`,
    )
  }
  return hit
}

/** WK 手动一键通知（返回完整响应包，供 50367 断言） */
export async function apiNotifyWholesaler(
  wkToken: string,
  batchId: string,
): Promise<Envelope<null>> {
  return req('POST', `/tenant/batches/${batchId}/notify-wholesaler`, wkToken)
}

/**
 * 测试接缝：把指定批次标记为「待清理」（复现 02:30 BatchExpiryMarkJob 的写路径，仅测试用）。
 * 前置：该批次 expiry_date < 今日 且 remaining_qty > 0（与 Job 扫描条件一致）。
 */
export function sqlMarkPendingClearance(batchId: string): void {
  const sql = `UPDATE batches SET status='PENDING_CLEARANCE', updated_at=NOW() WHERE id=${BigInt(batchId)}`
  const r = spawnSync(
    MYSQL_BIN,
    [`-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, '-e', sql, MYSQL_DB],
    { encoding: 'utf8' },
  )
  if (r.status !== 0) {
    throw new Error(`[t4-seed] mysql 标记待清理失败：${r.stderr || r.stdout}`)
  }
}
