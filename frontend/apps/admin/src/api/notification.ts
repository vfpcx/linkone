/**
 * 站内信接口封装（P3 FE-W1 三端点 + P5-A W3 扩展 group/read-all，登录用户收件人=本人）
 *
 * 权威来源：backend/.../notify/controller/NotificationController.java
 *  - GET  /api/v1/notifications?page=&size=&unreadOnly=&group=   我的消息列表
 *  - GET  /api/v1/notifications/unread-count                       未读数（铃铛角标轮询）
 *  - POST /api/v1/notifications/read-all                           全部已读（本人 scope，幂等）
 *  - POST /api/v1/notifications/{id}/read                          标记已读（本人校验，幂等）
 * group：ALL/BIZ/ANNOUNCE（18-p5-design §4.1，type→group 映射 §4.5；SYS 后端预留）
 * 错误码：50341 消息不存在（非本人按不存在，不泄漏存在性）。
 */

import { request } from './http'
import type {
  MpPage,
  NotificationGroup,
  NotificationItem,
  UnreadCountResponse,
} from '@cangchu/api-types'

export const notificationApi = {
  /** 我的消息列表（unreadOnly=true 只看未读；group 缺省=全部） */
  list: (
    params: {
      page?: number
      size?: number
      unreadOnly?: boolean
      group?: NotificationGroup
    } = {},
  ) =>
    request<MpPage<NotificationItem>>({
      method: 'GET',
      url: '/notifications',
      params: {
        page: params.page ?? 1,
        size: params.size ?? 20,
        unreadOnly: params.unreadOnly ?? false,
        group: params.group ?? undefined,
      },
    }),

  /** 我的未读数 */
  unreadCount: () =>
    request<UnreadCountResponse>({
      method: 'GET',
      url: '/notifications/unread-count',
    }),

  /** 全部已读（本人 scope，幂等；P5-A W3） */
  readAll: () =>
    request<void>({
      method: 'POST',
      url: '/notifications/read-all',
    }),

  /** 标记已读（幂等） */
  markRead: (id: string) =>
    request<void>({
      method: 'POST',
      url: `/notifications/${id}/read`,
    }),
}
