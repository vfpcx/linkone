/**
 * 平台公告接口 TS 类型（P5-A W3 · notify 域）
 *
 * 权威来源：后端实现（单一事实源）
 *  - Controller：backend/.../notify/controller/AnnouncementController.java（/api/v1/ops/announcements）
 *  - DTO：AnnouncementCreateDto
 *  - VO：AnnouncementVo
 *
 * 接口（OPS 登录态，18-p5-design §4.2）：
 *  - POST /api/v1/ops/announcements       创建公告草稿（body: {title, content, targetRoles}，后端落 DRAFT）
 *  - GET  /api/v1/ops/announcements?page=&size=&status=  公告列表（可分页、按状态过滤）
 *  - GET  /api/v1/ops/announcements/{id}   公告详情
 *  - POST /api/v1/ops/announcements/{id}/publish     发布：DRAFT→PUBLISHED + 同事务批量写目标角色站内信
 *  - POST /api/v1/ops/announcements/{id}/inactivate  下架：PUBLISHED→INACTIVE（已发站内信保留）
 *
 * 状态机：DRAFT → PUBLISHED → INACTIVE（不可逆；非法迁移 → 50702）
 * 角色组 KEY：ALL / OPS / TA / WK_ST / WA_WE（前端展示一律经 ui-shared announcementGroupLabel 转中文）
 * ⚠️ 雪花 ID 字段均为 string（后端 ToStringSerializer）。
 *    LocalDateTime 字段无时区偏移，前端直接格式化不做时区转换（既有约定）。
 */

import type { SnowflakeId } from './common'

/** 公告状态（Announcement.status 状态机，18 §2.2） */
export type AnnouncementStatus = 'DRAFT' | 'PUBLISHED' | 'INACTIVE'

/** 公告目标角色组 KEY（发布时后端展开为具体角色） */
export type AnnouncementTargetRoleGroup = 'ALL' | 'OPS' | 'TA' | 'WK_ST' | 'WA_WE'

/** 公告视图对象（AnnouncementVo） */
export interface Announcement {
  id: SnowflakeId
  title: string
  content: string
  /** 角色组 KEY 列表（出参展开为数组） */
  targetRoles: AnnouncementTargetRoleGroup[]
  status: AnnouncementStatus | string
  /** 发布时间（DRAFT 为空） */
  publishedAt: string | null
  /** 发布人（OPS 用户 id） */
  publishedBy: SnowflakeId | null
  createdAt: string
}

/** 创建公告入参（AnnouncementCreateDto）：title ≤128 / content ≤512 / targetRoles 至少一个 */
export interface CreateAnnouncementRequest {
  title: string
  content: string
  targetRoles: AnnouncementTargetRoleGroup[]
}

/** 公告列表查询参数 */
export interface AnnouncementListQuery {
  page?: number
  size?: number
  /** 按状态过滤（缺省查全部） */
  status?: AnnouncementStatus
}
