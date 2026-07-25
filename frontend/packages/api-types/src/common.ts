/**
 * 通用接口类型 · 与 shared/architecture/04-api-spec.md §3 一致
 */

/**
 * 雪花 ID
 * 后端使用 Long（≤19 位），JS Number 精度仅 15-16 位
 * 必须用 string，由 axios/uni.request 拦截器保证 JSON 反序列化后是 string
 */
export type SnowflakeId = string

/** 统一响应包装 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T | null
  details?: Record<string, unknown>
  traceId?: string
  timestamp?: string
}

/** 分页请求 */
export interface PageRequest {
  page?: number       // 1-based
  pageSize?: number   // 默认 20
  sortBy?: string
  sortOrder?: 'asc' | 'desc'
}

/** 分页响应 */
export interface PageData<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

/**
 * 分页响应 · records 命名（P2 入驻生态端点：
 * /tenant/wholesaler-applications、/tenant/wholesaler-withdraw-applications）
 * 权威来源：shared/architecture/10-onboarding-design.md §2/§12
 */
export interface PageRecords<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/**
 * 分页响应 · MyBatis-Plus Page 原样序列化（P3 BE-W1 端点：
 * /wholesaler/inbound-requests、/tenant/arbitrations、/notifications）
 * 注意页码字段名为 current（非 page）；total/size/current 经雪花防丢精度
 * 处理后可能为 string，消费侧一律 Number() 后使用。
 */
export interface MpPage<T> {
  records: T[]
  total: number | string
  size: number | string
  current: number | string
  pages?: number | string
}

/** 角色枚举（与 PRD §1 一致） */
export type Role = 'OPS' | 'TA' | 'WK' | 'ST' | 'WA' | 'WE' | 'RT'

/** 设备来源 */
export type Device = 'PC' | 'H5' | 'MP' | 'APP'
