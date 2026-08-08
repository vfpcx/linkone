/**
 * P4 计费结算 · E2E 造数 helper
 *
 * 契约来源（权威=backend controller 实测，main=befdf85）：
 *  - GET/POST /tenant/billing-rules            BillingRuleController（W1）
 *  - /tenant/st/bills 全端点 + /tenant/st/payments/{id}/reverse + /tenant/st/bill-disputes
 *                                              StBillController（W3，requireStOrTa）
 *  - /wholesaler/bills 4 端点                  WholesalerBillController（W3，仅 WA）
 *  - POST /tenant/st/snapshots/recalc          BillingSnapshotController（W2，≤昨日、跨度≤92 天）
 *
 * ⚠️ 测试接缝（batch.ts mysql CLI 先例）：月度账单只对「已结束月份」出账（40001），
 *  而 E2E 当刻产生的入库流水 biz_time=now、规则 effective_from=今日——均落不进上月。
 *  故以 mysql CLI 将 (a) 该商户流水 biz_time/created_at (b) 该租户规则 effective_from
 *  回拨到上月 1 日，复现「上月有在库、规则上月已生效」的真实出账前提（等价于线上
 *  自然经过一个月，仅测试用；回放公式按 biz_time 计算，与生产写路径一致）。
 */

import { spawnSync } from 'node:child_process'
import { apiGet, apiPost, ok, registerWithRetry, uniqPhone, SEED_PWD, SMS_CODE, type LoginData } from './onboarding'

const MYSQL_BIN =
  process.env.E2E_MYSQL_BIN ?? 'C:\\Program Files\\MySQL\\MySQL Server 9.7\\bin\\mysql.exe'
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? 'root'
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? 'chenxu18458748'
const MYSQL_DB = process.env.E2E_MYSQL_DB ?? 'cangchu_dev'

function mysqlExec(sql: string): void {
  const r = spawnSync(
    MYSQL_BIN,
    [`-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, MYSQL_DB, '-e', sql],
    { encoding: 'utf-8' },
  )
  if (r.status !== 0) {
    throw new Error(`[p4-seed] mysql 执行失败：${r.stderr?.slice(0, 300)}`)
  }
}

/** 上月 yyyy-MM（出账目标月） */
export function lastMonth(): string {
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/**
 * 把「已入库的当刻流水 + 今日首存规则」回拨到上月 1 日（测试接缝，见文件头注释）。
 * 调用前提：该商户已完成入库（存在流水）、该租户已保存首版计费规则。
 */
export function backdateToLastMonth(tenantId: string, wholesalerId: string): void {
  const firstDay = `${lastMonth()}-01`
  mysqlExec(
    `UPDATE stock_movements SET biz_time='${firstDay} 08:00:00', created_at='${firstDay} 08:00:00' ` +
      `WHERE wholesaler_id=${wholesalerId};` +
      `UPDATE billing_rules SET effective_from='${firstDay}' WHERE tenant_id=${tenantId};`,
  )
}

// ============ API 封装（billing 域） ============

export interface RuleVo {
  qtyEnabled: boolean
  pricePerQtyDay: number | null
  palletEnabled: boolean
  pricePerPalletDay: number | null
  version: number
  effectiveFrom: string
  effectiveTo: string | null
}

export const getRules = (token: string) =>
  apiGet<{ current?: RuleVo | null; history?: RuleVo[] }>('/tenant/billing-rules', token)

export const saveRule = (
  token: string,
  body: {
    billingByQty: boolean
    pricePerQtyDay?: number
    billingByPallet: boolean
    pricePerPalletDay?: number
    confirmed?: boolean
  },
) => apiPost<RuleVo>('/tenant/billing-rules', body, token)

export interface BillRow {
  id: string
  billNo: string
  status: string
  totalAmount: number
  paidAmount: number
  outstandingAmount: number
  wholesalerName: string
}

export const listStBills = (token: string, query: Record<string, string | number> = {}) =>
  apiGet<{ records: BillRow[]; total: number }>('/tenant/st/bills', token, query)

export const getStBill = (token: string, id: string) =>
  apiGet<{ bill: BillRow; items: Array<{ id: string; itemType: string; amount: number }>; payments: Array<{ id: string; status: string; amount: number }> }>(
    `/tenant/st/bills/${id}`,
    token,
  )

export const generateBills = (token: string, month: string) =>
  apiPost<{ generated: number; existing: number; billIds: string[] }>(
    '/tenant/st/bills/generate',
    { month },
    token,
  )

export const dispatchBill = (token: string, id: string) =>
  apiPost<BillRow>(`/tenant/st/bills/${id}/dispatch`, undefined, token)

export const confirmWaBill = (waToken: string, id: string) =>
  apiPost<BillRow>(`/wholesaler/bills/${id}/confirm`, undefined, waToken)

export const registerPaymentApi = (token: string, id: string, amount: number) =>
  apiPost(`/tenant/st/bills/${id}/payments`, {
    amount,
    payAt: new Date().toISOString().slice(0, 19),
    payMethod: 'BANK_TRANSFER',
  }, token)

export const reversePaymentApi = (token: string, paymentId: string, reason: string) =>
  apiPost(`/tenant/st/payments/${paymentId}/reverse`, { reason, confirmed: true }, token)

export const recalcSnapshots = (token: string, wholesalerId: string, from: string, to: string) =>
  apiPost('/tenant/st/snapshots/recalc', { wholesalerId, from, to }, token)

// ============ ST 员工注册（US-TA-03 链路复用 → 结算员登录动线） ============

export async function registerStEmployee(
  taToken: string,
): Promise<{ login: LoginData; phone: string }> {
  const invite = ok(
    await apiPost<{ code: string }>(
      '/tenant/employee-invites',
      { role: 'ST', maxUses: 1, expiresInDays: 7 },
      taToken,
    ),
    '生成结算员注册码',
  )
  const phone = uniqPhone()
  const login = await registerWithRetry(
    {
      phone,
      password: SEED_PWD,
      smsCode: SMS_CODE,
      role: 'ST',
      realName: 'P4结算员',
      inviteCode: invite.code,
      agreedTerms: true,
    },
    'ST 凭码注册',
  )
  return { login, phone }
}
