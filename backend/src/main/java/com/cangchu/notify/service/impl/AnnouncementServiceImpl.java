package com.cangchu.notify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.notify.dto.AnnouncementCreateDto;
import com.cangchu.notify.entity.Announcement;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.AnnouncementMapper;
import com.cangchu.notify.service.AnnouncementService;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.notify.vo.AnnouncementVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 平台公告实现（P5-A W3，18-p5-design §4.2）。
 *
 * <p>发布链路：announcements 状态机 DRAFT→PUBLISHED（同事务）→ target_roles 展开
 * （AuthService 平台级反查，18 §3.2 notify→account 依赖）→ NotificationService.sendToAll
 * 批量写站内信（平台级 tenantId=null）；任一失败整体回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private static final String ROLE_OPS = "OPS";
    private static final int MAX_PAGE_SIZE = 100;

    private final AnnouncementMapper announcementMapper;
    private final NotificationService notificationService;
    private final AuthService authService;

    @Override
    @Transactional
    public Long create(Long operatorId, AnnouncementCreateDto dto) {
        requireOps(operatorId);
        validateTargetRoles(dto.getTargetRoles());
        Announcement a = new Announcement();
        a.setTitle(dto.getTitle().trim());
        a.setContent(dto.getContent().trim());
        a.setTargetRoles(String.join(",", dto.getTargetRoles()));
        a.setStatus(Announcement.STATUS_DRAFT);
        announcementMapper.insert(a);
        return a.getId();
    }

    @Override
    public Page<AnnouncementVo> page(Long operatorId, int page, int size, String status) {
        requireOps(operatorId);
        Page<Announcement> p = announcementMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)),
                new LambdaQueryWrapper<Announcement>()
                        .eq(status != null && !status.isBlank(), Announcement::getStatus, status)
                        .orderByDesc(Announcement::getCreatedAt));
        Page<AnnouncementVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(this::toVo).toList());
        return out;
    }

    @Override
    public AnnouncementVo detail(Long operatorId, Long id) {
        requireOps(operatorId);
        return toVo(getOrThrow(id));
    }

    @Override
    @Transactional
    public void publish(Long operatorId, Long id) {
        requireOps(operatorId);
        Announcement a = getOrThrow(id);
        if (!Announcement.STATUS_DRAFT.equals(a.getStatus())) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_STATE_INVALID);
        }
        List<Long> recipientIds = resolveRecipientIds(a.getTargetRoles());
        // 状态机先行（重复发布被上面的状态校验拦截，天然幂等）
        Announcement upd = new Announcement();
        upd.setId(a.getId());
        upd.setStatus(Announcement.STATUS_PUBLISHED);
        upd.setPublishedAt(LocalDateTime.now());
        upd.setPublishedBy(operatorId);
        announcementMapper.updateById(upd);
        // 同事务批量写目标角色站内信（平台级 tenantId=null；失败整体回滚）
        if (recipientIds.isEmpty()) {
            log.info("[announcement] 公告 {} 目标角色组无 ACTIVE 用户，仅落状态不发信", id);
            return;
        }
        notificationService.sendToAll(null, recipientIds, Notification.TYPE_PLATFORM_ANNOUNCEMENT,
                a.getTitle(), a.getContent(), Notification.REF_ANNOUNCEMENT, a.getId());
    }

    @Override
    @Transactional
    public void inactivate(Long operatorId, Long id) {
        requireOps(operatorId);
        Announcement a = getOrThrow(id);
        if (!Announcement.STATUS_PUBLISHED.equals(a.getStatus())) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_STATE_INVALID);
        }
        Announcement upd = new Announcement();
        upd.setId(a.getId());
        upd.setStatus(Announcement.STATUS_INACTIVE);
        announcementMapper.updateById(upd);
    }

    @Override
    public long countDrafts(Long opsUserId) {
        requireOps(opsUserId);
        // OPS 控制台「草稿待发布」待办（21 §3）：announcements status=DRAFT
        Long cnt = announcementMapper.selectCount(new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, Announcement.STATUS_DRAFT));
        return cnt != null ? cnt : 0;
    }

    // ==================== 内部 ====================

    private void requireOps(Long operatorId) {
        if (!authService.hasRole(operatorId, ROLE_OPS)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
    }

    private void validateTargetRoles(List<String> targetRoles) {
        for (String g : targetRoles) {
            if (!Announcement.VALID_GROUPS.contains(g)) {
                throw new BizException(ErrorCode.ANNOUNCEMENT_TARGET_ROLES_INVALID);
            }
        }
    }

    private Announcement getOrThrow(Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null) {
            throw new BizException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        return a;
    }

    /** target_roles（GROUP_*）→ 具体账号角色 → 全平台 ACTIVE 收件人 userId（distinct） */
    private List<Long> resolveRecipientIds(String targetRoles) {
        List<String> groups = Arrays.stream(targetRoles.split(",")).map(String::trim).toList();
        if (groups.contains(Announcement.GROUP_ALL)) {
            return authService.listAllActiveUserIds();
        }
        List<String> roles = new ArrayList<>();
        for (String g : groups) {
            switch (g) {
                case Announcement.GROUP_OPS -> roles.add("OPS");
                case Announcement.GROUP_TA -> roles.add("TA");
                case Announcement.GROUP_WK_ST -> { roles.add("WK"); roles.add("ST"); }
                case Announcement.GROUP_WA_WE -> { roles.add("WA"); roles.add("WE"); }
                default -> throw new BizException(ErrorCode.ANNOUNCEMENT_TARGET_ROLES_INVALID);
            }
        }
        return authService.listActiveUserIdsByRoles(roles);
    }

    private AnnouncementVo toVo(Announcement a) {
        return AnnouncementVo.builder()
                .id(a.getId())
                .title(a.getTitle())
                .content(a.getContent())
                .targetRoles(Arrays.stream(a.getTargetRoles().split(",")).map(String::trim).toList())
                .status(a.getStatus())
                .publishedAt(a.getPublishedAt())
                .publishedBy(a.getPublishedBy())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
