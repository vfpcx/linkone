/**
 * EntityPickerDialog 配套类型与工具（UX 选择控件规范 · 2026-07-25 用户拍板）
 *
 * 规范：开放实体集（商品/SKU/商户/专属价等会无限增长的业务数据）一律用
 * 弹窗选择器（搜索 + 分页表格 + 确认），有限枚举（≤20 项）保持 el-select。
 * 详见 shared/design-system 「选择控件规范」小节。
 */

/** 弹窗表格列定义 */
export interface EntityPickerColumn<T = Record<string, unknown>> {
  /** 列标题 */
  label: string
  /** 直接取行字段（与 formatter 二选一） */
  prop?: string
  /** 自定义单元格文案（优先于 prop） */
  formatter?: (row: T) => string
  width?: number | string
  minWidth?: number | string
  align?: 'left' | 'center' | 'right'
}

/** 分页查询参数（keyword 为空串表示不过滤） */
export interface EntityPickerQuery {
  keyword: string
  page: number
  pageSize: number
}

/** 分页查询结果 */
export interface EntityPickerResult<T = Record<string, unknown>> {
  rows: T[]
  total: number
}

/** 数据加载函数：由页面提供（服务端分页或前端过滤均可） */
export type EntityPickerFetch<T = Record<string, unknown>> = (
  query: EntityPickerQuery,
) => Promise<EntityPickerResult<T>>

/**
 * 前端过滤 + 分页的 fetch 适配器。
 *
 * 现状：后端 GET /tenant/wholesalers、/tenant/skus、/tenant/customer-prices
 * 均不带 keyword/page 参数（已登记为后端跟进项），试点量级下先一次拉取、
 * 前端搜索分页；后端补参后仅需把页面 fetch 换成服务端实现，组件不改。
 *
 * @param getRows 返回当前完整数据集（可直接引用页面已加载的响应式数组）
 * @param match   关键字匹配（kw 已 trim + toLowerCase，保证非空）
 */
export function makeClientPickerFetch<T>(
  getRows: () => T[] | Promise<T[]>,
  match: (row: T, kw: string) => boolean,
): EntityPickerFetch<T> {
  return async ({ keyword, page, pageSize }) => {
    const all = await getRows()
    const kw = keyword.trim().toLowerCase()
    const filtered = kw ? all.filter((row) => match(row, kw)) : all
    const start = (page - 1) * pageSize
    return { rows: filtered.slice(start, start + pageSize), total: filtered.length }
  }
}
