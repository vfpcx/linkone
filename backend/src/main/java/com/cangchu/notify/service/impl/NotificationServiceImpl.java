package com.cangchu.notify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.notify.vo.NotificationVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 站内信服务实现（P3 BE-W1，12 §4.3 最小版）。
 *
 * <p>send 无自建事务（MANDATORY 会拒公开调用，SUPPORTS 保持与调用方同事务语义——
 * 业务链均在 @Transactional 内调用，与业务同回滚）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void send(Long tenantId, Long recipientUserId, String type, String title, String content,
                     String refType, Long refId) {
        if (recipientUserId == null) {
            // 容忍归属人缺失（脏数据/历史单据）：通知降级跳过，不阻断业务主链
            log.warn("[NTF] 收件人为空，跳过通知 type={} ref={}:{}", type, refType, refId);
            return;
        }
        Notification n = new Notification();
        n.setId(snowflakeIdUtil.nextId());
        n.setTenantId(tenantId);
        n.setRecipientUserId(recipientUserId);
        n.setType(type);
        n.setTitle(truncate(title, 128));
        n.setContent(truncate(content, 512));
        n.setRefType(refType);
        n.setRefId(refId);
        notificationMapper.insert(n);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void sendToAll(Long tenantId, java.util.Collection<Long> recipientUserIds, String type,
                          String title, String content, String refType, Long refId) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            // 与单发 null 收件人同语义：降级跳过（脏数据/无绑定账号），不阻断业务主链
            log.warn("[NTF] 收件人列表为空，跳过群发 type={} ref={}:{}", type, refType, refId);
            return;
        }
        for (Long recipientUserId : recipientUserIds) {
            send(tenantId, recipientUserId, type, title, content, refType, refId);
        }
    }

    @Override
    public Page<NotificationVo> listMine(Long userId, int page, int size, boolean unreadOnly, String group) {
        LambdaQueryWrapper<Notification> qw = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, userId)
                .isNull(unreadOnly, Notification::getReadAt);
        // P5-A W3（18 §4.4）：group 白名单（类型安全条件，杜绝注入）；SYS 本期无 type 归属 → 恒空
        if (group != null && !group.isBlank() && !"ALL".equalsIgnoreCase(group)) {
            switch (group.toUpperCase()) {
                case "ANNOUNCE" -> qw.eq(Notification::getType, Notification.TYPE_PLATFORM_ANNOUNCEMENT);
                case "BIZ" -> qw.ne(Notification::getType, Notification.TYPE_PLATFORM_ANNOUNCEMENT);
                case "SYS" -> qw.eq(Notification::getId, Long.MIN_VALUE);
                default -> throw new BizException(ErrorCode.VALIDATION_BASIC_003);
            }
        }
        qw.orderByDesc(Notification::getCreatedAt);
        Page<Notification> p = notificationMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), qw);
        Page<NotificationVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(this::toVo).toList());
        return out;
    }

    @Override
    @Transactional
    public void readAll(Long userId) {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientUserId, userId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, userId)
                .isNull(Notification::getReadAt));
    }

    @Override
    @Transactional
    public void markRead(Long id, Long userId) {
        Notification n = notificationMapper.selectById(id);
        if (n == null || !userId.equals(n.getRecipientUserId())) {
            // 非本人一律按不存在（50341），不泄露他人消息存在性
            throw new BizException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        if (n.getReadAt() != null) {
            return; // 幂等
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private NotificationVo toVo(Notification n) {
        return NotificationVo.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .refType(n.getRefType())
                .refId(n.getRefId())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
