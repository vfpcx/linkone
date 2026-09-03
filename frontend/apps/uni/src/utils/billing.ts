/**
 * P4 计费结算 · 界面展示映射（uni 移动端；与 admin apps/admin/src/utils/billing.ts 同话术源）。
 *
 * 唯一话术源：shared/product/13-p4-prd.md §3.1 状态中文对照表 / §2.3 明细行类型表 / §3.3 收款方式。
 * 零英文枚举透出；tone 映射由页面 SCSS 语义色类（tag--*）落地。
 */

/** 账单 6 态 → 界面唯一话术 + 语义色 tone（PRD 13 §3.1） */
export const BILL_STATUS_LABELS: Record<string, string> = {
  DRAFT: '待核对',
  DISPATCHED: '已下发',
  PENDING_PAYMENT: '待回款',
  PARTIAL_PAID: '部分回款',
  PAID: '已结清',
  DISPUTED: '争议中',
}

/** tone：warn/info/ok/err/muted → 页面 tag--* 语义色 */
export function billStatusTone(s: string | null | undefined): string {
  switch (s) {
    case 'DRAFT':
      return 'warn'
    case 'DISPATCHED':
    case 'PARTIAL_PAID':
      return 'info'
    case 'PAID':
      return 'ok'
    case 'DISPUTED':
      return 'err'
    default:
      return 'muted'
  }
}

export const billStatusLabel = (s: string | null | undefined): string => (s && BILL_STATUS_LABELS[s]) ?? '—'

/** 明细行类型中文（PRD 13 §2.3） */
export const BILL_ITEM_TYPE_LABELS: Record<string, string> = {
  STORAGE: '仓储费',
  ADJUSTMENT: '调整',
  REVERSAL: '冲销',
  STOCKTAKE_IMPACT: '盘点影响',
}

export const billItemTypeLabel = (t: string | null | undefined): string => (t && BILL_ITEM_TYPE_LABELS[t]) ?? '—'

/** 收款方式五枚举（US-ST-04） */
export const PAY_METHOD_LABELS: Record<string, string> = {
  BANK_TRANSFER: '银行转账',
  CASH: '现金',
  WX: '微信',
  ALIPAY: '支付宝',
  OTHER: '其他',
}

export const payMethodLabel = (m: string | null | undefined): string => (m && PAY_METHOD_LABELS[m]) ?? '—'

/** 申诉状态话术 + tone（PRD §3.3/§3.5） */
export const DISPUTE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  RESOLVED: '成立',
  REJECTED: '不成立',
}

export function disputeStatusTone(s: string | null | undefined): string {
  switch (s) {
    case 'PENDING':
      return 'warn'
    case 'RESOLVED':
      return 'ok'
    default:
      return 'muted'
  }
}

export const disputeStatusLabel = (s: string | null | undefined): string => (s && DISPUTE_STATUS_LABELS[s]) ?? '—'

/** 争议中账单冻结横幅（PRD 13 §7.2，逐字） */
export const DISPUTED_BANNER =
  '该批发商已被强制下架，账单进入争议状态。争议期间账单冻结，仅可查看与导出；线下协商解决后的处理功能将在后续版本提供。'

/** yyyy-MM（默认当前自然月） */
export function currentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 最近 n 个月（含当月）倒序，如 n=6 → [2026-09, 2026-08, …, 2026-04] */
export function recentMonths(n: number): string[] {
  const out: string[] = []
  const d = new Date()
  for (let i = 0; i < n; i++) {
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
    d.setMonth(d.getMonth() - 1)
  }
  return out
}

/** 金额格式 ¥x,xxx.xx */
export function fmtMoney(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const sign = n < 0 ? '-' : ''
  return `${sign}¥${Math.abs(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/** 金额输入校验用：两位小数字符串 → 分单位整数（规避浮点误差） */
export function toCents(v: number | string): number {
  return Math.round(Number(v) * 100)
}

/** yyyy-MM-dd */
export function fmtDate(v: string | null | undefined): string {
  return v ? String(v).slice(0, 10) : '—'
}

/** yyyy-MM-dd HH:mm */
export function fmtDateTime(v: string | null | undefined): string {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}
