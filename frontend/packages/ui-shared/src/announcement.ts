/**
 * 平台公告界面展示映射（P5-A W3/W4 · 18-p5-design §4.2/§5）
 *
 * 规范：所有用户可见文案（表格列、tag、下拉选项、弹窗、toast 等）禁止直接出现
 * 英文角色组 KEY（ALL/OPS/TA/WK_ST/WA_WE）与英文状态码（DRAFT/PUBLISHED/INACTIVE），
 * 一律经本文件映射为中文（CLAUDE.md 规则 10；ui-shared 是唯一映射来源）。
 * 代码内部枚举值 / API 参数不受影响，照用英文码。
 */

/** 公告目标角色组 KEY（与 @cangchu/api-types AnnouncementTargetRoleGroup 一致；ui-shared 不依赖 api-types，故独立声明） */
export type AnnouncementRoleGroup = 'ALL' | 'OPS' | 'TA' | 'WK_ST' | 'WA_WE'

/** 角色组 → 用户可见中文名（以 roleLabel 单项中文名为基础拼接） */
export const ANNOUNCEMENT_GROUP_LABELS: Record<AnnouncementRoleGroup, string> = {
  ALL: '全部角色',
  OPS: '平台运维',
  TA: '租户管理员',
  WK_ST: '库管员与结算员',
  WA_WE: '批发商管理员与员工',
}

/** 取角色组中文名；未知码原样返回（防御后端新增组时不至于显示空白） */
export function announcementGroupLabel(code: string | null | undefined): string {
  if (!code) return '—'
  return ANNOUNCEMENT_GROUP_LABELS[code as AnnouncementRoleGroup] ?? code
}

/** 公告状态 → 用户可见中文名 */
export const ANNOUNCEMENT_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  INACTIVE: '已下架',
}

/** 取公告状态中文名；未知码原样返回 */
export function announcementStatusLabel(code: string | null | undefined): string {
  if (!code) return '—'
  return ANNOUNCEMENT_STATUS_LABELS[code] ?? code
}

/** 通知分组 → 用户可见中文名（消息中心 Tab 与公告弹窗用） */
export const NOTIFICATION_GROUP_LABELS: Record<string, string> = {
  ALL: '全部',
  BIZ: '业务',
  ANNOUNCE: '公告',
  SYS: '系统',
}

/** 取通知分组中文名；未知码原样返回 */
export function notificationGroupLabel(code: string | null | undefined): string {
  if (!code) return '全部'
  return NOTIFICATION_GROUP_LABELS[code] ?? code
}

/** type → 通知分组映射（18-p5-design §4.5：仅 PLATFORM_ANNOUNCEMENT 属公告，其余业务） */
export function notificationGroupOfType(type: string | null | undefined): 'BIZ' | 'ANNOUNCE' {
  return type === 'PLATFORM_ANNOUNCEMENT' ? 'ANNOUNCE' : 'BIZ'
}
