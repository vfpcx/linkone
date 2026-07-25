package com.cangchu.notify.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.notify.vo.NotificationVo;

/**
 * 站内信服务（P3 BE-W1 最小版，12 §4.3）。
 *
 * <p>{@link #send} 与调用方业务同事务写入（Propagation.REQUIRED），业务回滚则通知一并回滚。
 */
public interface NotificationService {

    /**
     * 发送站内信（同事务写入）。
     *
     * @param tenantId        归属租户（显式传入：Job 系统态无 TenantContext 时从单据带入）
     * @param recipientUserId 收件人（null 时静默跳过，容忍归属人缺失的脏数据不阻断主链）
     * @param type            {@link com.cangchu.notify.entity.Notification} TYPE_* 常量
     * @param title           标题（≤128，超长截断）
     * @param content         正文（≤512，超长截断）
     * @param refType         跳转引用类型（REF_* 常量，可空）
     * @param refId           跳转引用 id（可空）
     */
    void send(Long tenantId, Long recipientUserId, String type, String title, String content,
              String refType, Long refId);

    /** 我的消息列表（recipient=当前用户，倒序；unreadOnly 只看未读）。 */
    Page<NotificationVo> listMine(Long userId, int page, int size, boolean unreadOnly);

    /** 我的未读数（角标/轮询）。 */
    long unreadCount(Long userId);

    /** 标记已读（本人校验，非本人/不存在 → 50341；重复标记幂等）。 */
    void markRead(Long id, Long userId);
}
