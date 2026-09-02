/**
 * TA 工作台相关 mock（P5-C 起仅剩 UI 辅助数据；工作台真实数据走 GET /tenant/dashboard，
 * 见 19-p5c-dashboard-design.md）
 *
 * 来源：shared/product/06-page-wireframes.md §2.1
 */

/** 顶部通知 mock */
export const mockNotifications = [
  { id: 'n1', title: '新增 1 个批发商入驻申请', time: '10:30', unread: true },
  { id: 'n2', title: '盘点单 CT-XX-20260607-12 待审', time: '09:15', unread: true },
  { id: 'n3', title: '账单 BL-202605 已下发', time: '昨日', unread: false },
]

/** 模拟用户身份切换菜单 */
export const mockMyRoles = [
  { role: 'TA' as const, label: '租户管理员 · XX 海鲜库' },
  { role: 'WK' as const, label: '库管员 · XX 海鲜库' },
]
