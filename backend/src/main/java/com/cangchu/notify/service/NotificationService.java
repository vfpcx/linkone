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

    /**
     * 群发站内信（同事务写入，P3 缺陷修复：「归属 WA」通知多账号全发）。
     *
     * <p>逐收件人复用 {@link #send}；列表为 null/空时静默跳过（warn 留痕），
     * 与单发收件人缺失的降级语义一致，不阻断业务主链。
     *
     * @param recipientUserIds 收件人集合（调用方负责去重，如 user_roles 推导已 distinct）
     */
    void sendToAll(Long tenantId, java.util.Collection<Long> recipientUserIds, String type,
                   String title, String content, String refType, Long refId);

    /**
     * 我的消息列表（recipient=当前用户，倒序；unreadOnly 只看未读）。
     *
     * @param group 分组筛选（P5-A W3，18-p5-design §4.4）：null/空/ALL=全部；
     *              BIZ=业务（非公告）；ANNOUNCE=公告；SYS=系统（本期无，返回空）
     */
    Page<NotificationVo> listMine(Long userId, int page, int size, boolean unreadOnly, String group);

    /** 兼容重载（group=null 全量，P5-A 前既有调用方/测试不破坏）。 */
    default Page<NotificationVo> listMine(Long userId, int page, int size, boolean unreadOnly) {
        return listMine(userId, page, size, unreadOnly, null);
    }

    /** 全部已读（P5-A W3，本人 scope，幂等）。 */
    void readAll(Long userId);

    /** 我的未读数（角标/轮询）。 */
    long unreadCount(Long userId);

    /** 标记已读（本人校验，非本人/不存在 → 50341；重复标记幂等）。 */
    void markRead(Long id, Long userId);
}
