/**
 * TA 工作台 mock 数据
 * 后端真实接口（GET /api/v1/tenant/dashboard 或聚合）跑通前用
 *
 * 来源：shared/product/06-page-wireframes.md §2.1
 */

import type { TenantDashboardResponse } from '@cangchu/api-types'

export const mockTenantDashboard: TenantDashboardResponse = {
  storeName: 'XX 海鲜库',
  kpi: {
    pendingInbound: 3,
    pendingCount: 2,
    pendingClearance: 1,
    pendingDispute: 0,
  },
  capacity: {
    usedQty: 14_300,
    totalQty: 20_000,
    usedPallet: 86,
    totalPallet: 120,
    utilization: 72,
    visibility: 'WA_ONLY',
    snapshotAt: '2026-06-07T10:30:00+08:00',
  },
  today: {
    inboundCount: 12,
    outboundCount: 8,
    inquiryCount: 25,
    expiringBatches: 5,
  },
  batchEnabled: true,
}

/** 顶部通知 mock */
export const mockNotifications = [
  { id: 'n1', title: '新增 1 个 WA 入驻申请', time: '10:30', unread: true },
  { id: 'n2', title: '盘点单 CT-XX-20260607-12 待审', time: '09:15', unread: true },
  { id: 'n3', title: '账单 BL-202605 已下发', time: '昨日', unread: false },
]

/** 模拟用户身份切换菜单 */
export const mockMyRoles = [
  { role: 'TA' as const, label: '租户管理员 · XX 海鲜库' },
  { role: 'WK' as const, label: '库管员 · XX 海鲜库' },
]
