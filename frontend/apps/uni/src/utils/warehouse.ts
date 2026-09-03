/**
 * WK 库管移动端文案/色板（F5-W1 · 出入库作业）。
 * 话术对齐 12-p3-design / 13-p3b-design（入库正向链 SUBMITTED→ACCEPTED→CONFIRMED；
 * 出库 PENDING_ACCEPT→PRINTED→COMPLETED；代建出库直达 COMPLETED）。
 */
import type { InboundRejectReason } from '@cangchu/api-types'

export const INBOUND_STATUS_LABELS: Record<string, string> = {
  SUBMITTED: '待受理',
  ACCEPTED: '待登记',
  CONFIRMED: '已入库',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  DISPUTED: '异议中',
  PENDING_WA_CONFIRM: '待商户确认',
  REVOKED: '已撤销',
}

export const OUTBOUND_STATUS_LABELS: Record<string, string> = {
  PENDING_ACCEPT: '待受理',
  PRINTED: '待登记',
  COMPLETED: '已完成',
  WITHDRAWN: '已撤回',
  CANCELLED: '已取消',
  COMPLAINED: '客诉中',
}

export const OUTBOUND_SOURCE_LABELS: Record<string, string> = {
  INQUIRY_AUTO: '意向单自动转',
  WA_SUBMIT: '商户申请',
  WK_CREATED: '代建出库',
}

export const INBOUND_REJECT_REASON_LABELS: Record<InboundRejectReason, string> = {
  QTY: '数量不符',
  QUALITY: '质量/破损问题',
  BATCH: '批次/效期问题',
  OTHER: '其他原因',
}

export const fmtDateTime = (s?: string | null): string => {
  if (!s) return ''
  return String(s).slice(0, 16).replace('T', ' ')
}

/** 状态→色板 tone（warn 待办 / info 进行中 / ok 完结 / err 异常 / muted 归档） */
export function statusTone(status: string | null | undefined, kind: 'in' | 'out'): string {
  const s = status ?? ''
  if (kind === 'in') {
    if (s === 'SUBMITTED' || s === 'ACCEPTED') return 'warn'
    if (s === 'CONFIRMED') return 'ok'
    if (s === 'DISPUTED') return 'err'
    if (s === 'REJECTED' || s === 'WITHDRAWN' || s === 'REVOKED' || s === 'PENDING_WA_CONFIRM') return 'muted'
    return 'info'
  }
  if (s === 'PENDING_ACCEPT' || s === 'PRINTED') return 'warn'
  if (s === 'COMPLETED') return 'ok'
  if (s === 'COMPLAINED') return 'err'
  if (s === 'WITHDRAWN' || s === 'CANCELLED') return 'muted'
  return 'info'
}
