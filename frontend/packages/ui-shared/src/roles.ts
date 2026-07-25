/**
 * 角色码 → 用户可见中文名（全仓唯一来源 · 2026-07-25 用户拍板，CLAUDE.md 第 8 条）
 *
 * 规范：所有用户可见文案（表格列、tag、下拉选项、弹窗、toast、切换器条目等）
 * 禁止直接出现 OPS/TA/WK/ST/WA/WE/RT 角色码；中文名以
 * shared/product/01-roles-and-permissions.md §3 为准。
 * 代码内部枚举值 / API 参数 / 路由 meta.role 不受影响，照用角色码。
 */

/** 七类角色码（与 @cangchu/api-types 的 Role 取值一致；ui-shared 不依赖 api-types，故独立声明） */
export type RoleCode = 'OPS' | 'TA' | 'WK' | 'ST' | 'WA' | 'WE' | 'RT'

/** 标准中文名（01-roles-and-permissions.md §3） */
export const ROLE_LABELS: Record<RoleCode, string> = {
  OPS: '平台运维',
  TA: '租户管理员',
  WK: '库管员',
  ST: '结算员',
  WA: '批发商管理员',
  WE: '批发商员工',
  RT: '终端买家',
}

/** 界面短形式（仅规范允许的：TA 可用「店长」） */
export const ROLE_LABELS_SHORT: Partial<Record<RoleCode, string>> = {
  TA: '店长',
}

/**
 * 取角色中文名；未知码原样返回（防御后端新增角色时不至于显示空白）。
 * @param short 允许时使用界面短形式（如 TA → 店长）
 */
export function roleLabel(code: string, opts?: { short?: boolean }): string {
  const c = code as RoleCode
  if (opts?.short && ROLE_LABELS_SHORT[c]) return ROLE_LABELS_SHORT[c] as string
  return ROLE_LABELS[c] ?? code
}
