/**
 * P4 计费结算 · 界面展示映射（零角色码/零英文枚举透出铁律）
 *
 * 唯一话术源：shared/product/13-p4-prd.md §3.1 状态中文对照表 / §2.3 明细行类型表。
 * StatusBadge 语义色对齐 MASTER §4.6 六语义。
 */

export type BadgeVariant = 'default' | 'progress' | 'success' | 'warning' | 'danger' | 'archived'

/** 账单 6 态 → 界面唯一话术 + 徽章语义（PRD 13 §3.1） */
export const BILL_STATUS_META: Record<string, { label: string; variant: BadgeVariant }> = {
  DRAFT: { label: '待核对', variant: 'warning' },
  DISPATCHED: { label: '已下发', variant: 'progress' },
  PENDING_PAYMENT: { label: '待回款', variant: 'warning' },
  PARTIAL_PAID: { label: '部分回款', variant: 'progress' },
  PAID: { label: '已结清', variant: 'success' },
  DISPUTED: { label: '争议中', variant: 'danger' },
}

export const billStatusMeta = (s: string | null | undefined) =>
  (s && BILL_STATUS_META[s]) || { label: s ?? '—', variant: 'default' as BadgeVariant }

/** 明细行类型中文（PRD 13 §2.3） */
export const BILL_ITEM_TYPE_LABELS: Record<string, string> = {
  STORAGE: '仓储费',
  ADJUSTMENT: '调整',
  REVERSAL: '冲销',
  STOCKTAKE_IMPACT: '盘点影响',
}

export const billItemTypeLabel = (t: string | null | undefined): string =>
  (t && BILL_ITEM_TYPE_LABELS[t]) || (t ?? '—')

/** 调整类型（US-ST-02） */
export const ADJUST_TYPE_LABELS: Record<string, string> = {
  DISCOUNT: '折扣',
  RELIEF: '减免',
}

/** 收款方式五枚举（US-ST-04） */
export const PAY_METHOD_LABELS: Record<string, string> = {
  BANK_TRANSFER: '银行转账',
  CASH: '现金',
  WX: '微信',
  ALIPAY: '支付宝',
  OTHER: '其他',
}

export const payMethodLabel = (m: string | null | undefined): string =>
  (m && PAY_METHOD_LABELS[m]) || (m ?? '—')

/** 申诉状态（成立/不成立话术照 PRD §3.3/§3.5） */
export const DISPUTE_STATUS_META: Record<string, { label: string; variant: BadgeVariant }> = {
  PENDING: { label: '待处理', variant: 'warning' },
  RESOLVED: { label: '成立', variant: 'success' },
  REJECTED: { label: '不成立', variant: 'default' },
}

export const disputeStatusMeta = (s: string | null | undefined) =>
  (s && DISPUTE_STATUS_META[s]) || { label: s ?? '—', variant: 'default' as BadgeVariant }

/**
 * 计费维度只读镜像展示三值（W1 起新增 BOTH=并存；14-p4 §8 前端展示映射）。
 * 店铺设置只读摘要 / 对外展示随规则联动。
 */
export const BILLING_DIM_LABELS: Record<string, string> = {
  QTY: '件·天',
  PALLET: '托盘·天',
  BOTH: '件·天+托盘·天并存',
}

export const billingDimLabel = (d: string | null | undefined): string =>
  (d && BILLING_DIM_LABELS[d]) || '未设置'

/** yyyy-MM（默认当前自然月，北京时间口径由浏览器本地近似） */
export const currentMonth = (): string => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 金额格式 ¥x,xxx.xx（简单场景用；表格金额优先 MoneyDisplay 组件） */
export const fmtMoney = (v: number | string | null | undefined): string => {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const sign = n < 0 ? '-' : ''
  return `${sign}¥${Math.abs(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/** 日期时间截断展示 yyyy-MM-dd HH:mm */
export const fmtDateTime = (v: string | null | undefined): string =>
  v ? String(v).replace('T', ' ').slice(0, 16) : '—'

/** 每日计费口径帮助文案（PRD 13 §2.1，逐字） */
export const DAILY_BILLING_HELP =
  '每日计费件数按前一日 24:00 的在库量计算：入库、盘盈自次日起计费；出库、退货、盘亏、清库当日仍计费、次日起不再计费；同一天入库又出库不产生费用。'

/** 争议中账单冻结横幅（PRD 13 §7.2，逐字） */
export const DISPUTED_BANNER =
  '该批发商已被强制下架，账单进入争议状态。争议期间账单冻结，仅可查看与导出；线下协商解决后的处理功能将在后续版本提供。'
