package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.document.dto.CustomerAggRow;
import com.cangchu.document.dto.CustomerReminderDto;
import com.cangchu.document.dto.CustomerRemarkDto;
import com.cangchu.document.entity.CustomerFollowup;
import com.cangchu.document.entity.FollowupReminder;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.CustomerFollowupMapper;
import com.cangchu.document.mapper.FollowupReminderMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.service.CustomerFollowupService;
import com.cangchu.document.vo.CustomerDetailVo;
import com.cangchu.document.vo.CustomerListItemVo;
import com.cangchu.document.vo.FollowupReminderVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * wa 客户跟进实现（C3 · 24-p5-c-c3 §4，document 域）。
 *
 * <p>遵循 S4 越权（wa 侧以 AuthService scope 推导本人商户集，不信任客户端超集；越权一律 50840 假装不存在）、
 * PII-W7（仅回打码号；查全号走 PII-REVEAL 既有链路）、G-S2（跨域仅走出口：WholesalerService /
 * NotificationService / PiiCrypto）。TenantLine 对两表按 tenant_id 兜底；Job 系统态无 TenantContext 全量扫描，
 * 行内 tenant 显式带入通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerFollowupServiceImpl implements CustomerFollowupService {

    private final InquiryRequestMapper inquiryRequestMapper;
    private final CustomerFollowupMapper customerFollowupMapper;
    private final FollowupReminderMapper followupReminderMapper;
    private final AuthService authService;
    private final WholesalerService wholesalerService;
    private final NotificationService notificationService;
    private final PiiCrypto piiCrypto;

    /** 提醒 Job 单批上限（防长事务，沿 72h Job 批处理惯例）。 */
    private static final int JOB_BATCH_LIMIT = 100;

    // ==================== wa 客户列表 / 详情 ====================

    @Override
    public Page<CustomerListItemVo> listCustomers(int page, int size, Long userId, Long tenantId) {
        Page<CustomerListItemVo> empty = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100), 0);
        if (tenantId == null) {
            return empty;
        }
        List<Long> scope = scopeWholesalerIds(userId, tenantId);
        if (scope.isEmpty()) {
            return empty;
        }
        Page<CustomerAggRow> agg = inquiryRequestMapper.pageCustomerAgg(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), tenantId, scope);
        Page<CustomerListItemVo> result = new Page<>(agg.getCurrent(), agg.getSize(), agg.getTotal());
        if (agg.getRecords().isEmpty()) {
            return result;
        }
        Map<Long, String> names = wholesalerNames(scope);
        // 页内各客户档案（每商户 × 每客户至多一行）
        Map<String, CustomerFollowup> followupByKey = new HashMap<>();
        List<Long> followupIds = new ArrayList<>();
        List<CustomerAggRow> rows = agg.getRecords();
        for (CustomerAggRow row : rows) {
            CustomerFollowup fu = customerFollowupMapper.selectOne(new LambdaQueryWrapper<CustomerFollowup>()
                    .eq(CustomerFollowup::getWholesalerId, row.getWholesalerId())
                    .eq(CustomerFollowup::getRtPhoneHmac, row.getRtPhoneHmac()));
            if (fu != null) {
                followupByKey.put(customerKey(row.getRtPhoneHmac()), fu);
                followupIds.add(fu.getId());
            }
        }
        // 未触发提醒按档案分组（页级小集合单查，免 N+1）
        Map<Long, List<FollowupReminder>> pendingByFollowup = new HashMap<>();
        if (!followupIds.isEmpty()) {
            List<FollowupReminder> pending = followupReminderMapper.selectList(new LambdaQueryWrapper<FollowupReminder>()
                    .in(FollowupReminder::getCustomerFollowupId, followupIds)
                    .isNull(FollowupReminder::getRemindedAt));
            for (FollowupReminder r : pending) {
                pendingByFollowup.computeIfAbsent(r.getCustomerFollowupId(), k -> new ArrayList<>()).add(r);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<CustomerListItemVo> records = new ArrayList<>(rows.size());
        for (CustomerAggRow row : rows) {
            String key = customerKey(row.getRtPhoneHmac());
            CustomerFollowup fu = followupByKey.get(key);
            List<FollowupReminder> pending = fu == null ? List.of()
                    : pendingByFollowup.getOrDefault(fu.getId(), List.of());
            CustomerListItemVo vo = toListItem(row, names.getOrDefault(row.getWholesalerId(), ""), fu, pending, now);
            records.add(vo);
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public CustomerDetailVo detailCustomer(String customerKey, Long wholesalerId, Long userId, Long tenantId) {
        String hmac = requireScopedCustomer(customerKey, wholesalerId, userId, tenantId);
        CustomerAggRow row = aggregateOne(wholesalerId, hmac, tenantId);
        CustomerFollowup fu = customerFollowupMapper.selectOne(new LambdaQueryWrapper<CustomerFollowup>()
                .eq(CustomerFollowup::getWholesalerId, wholesalerId)
                .eq(CustomerFollowup::getRtPhoneHmac, hmac));
        List<FollowupReminder> reminders = fu == null ? List.of() : followupReminderMapper.selectList(
                new LambdaQueryWrapper<FollowupReminder>()
                        .eq(FollowupReminder::getCustomerFollowupId, fu.getId())
                        .orderByDesc(FollowupReminder::getRemindAt));
        LocalDateTime now = LocalDateTime.now();
        CustomerDetailVo vo = new CustomerDetailVo();
        CustomerListItemVo item = toListItem(row, nameOf(wholesalerId), fu,
                reminders.stream().filter(r -> r.getRemindedAt() == null).toList(), now);
        vo.setWholesalerId(item.getWholesalerId());
        vo.setWholesalerName(item.getWholesalerName());
        vo.setCustomerKey(item.getCustomerKey());
        vo.setMaskedPhone(item.getMaskedPhone());
        vo.setInquiryCount(item.getInquiryCount());
        vo.setLastInquiryAt(item.getLastInquiryAt());
        vo.setLastConfirmedAt(item.getLastConfirmedAt());
        vo.setLastInquiryId(item.getLastInquiryId());
        vo.setRemark(item.getRemark());
        vo.setRemarkUpdatedAt(item.getRemarkUpdatedAt());
        vo.setNextReminderAt(item.getNextReminderAt());
        vo.setDueReminderCount(item.getDueReminderCount());
        vo.setReminders(reminders.stream().map(this::toReminderVo).toList());
        return vo;
    }

    // ==================== 备注 / 提醒 ====================

    @Override
    @Transactional
    public void saveRemark(String customerKey, CustomerRemarkDto dto, Long userId, Long tenantId) {
        String hmac = requireScopedCustomer(customerKey, dto.getWholesalerId(), userId, tenantId);
        InquiryRequest latest = latestInquiry(dto.getWholesalerId(), hmac, tenantId);
        if (latest == null) {
            throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        CustomerFollowup fu = customerFollowupMapper.selectOne(new LambdaQueryWrapper<CustomerFollowup>()
                .eq(CustomerFollowup::getWholesalerId, dto.getWholesalerId())
                .eq(CustomerFollowup::getRtPhoneHmac, hmac));
        boolean blankRemark = !StringUtils.hasText(dto.getRemark());
        if (blankRemark) {
            // K-3：空串=清除备注；无任何提醒（含已触发）时清档不留空壳
            if (fu == null) {
                return;
            }
            Long remain = followupReminderMapper.selectCount(new LambdaQueryWrapper<FollowupReminder>()
                    .eq(FollowupReminder::getCustomerFollowupId, fu.getId()));
            if (remain == 0) {
                customerFollowupMapper.deleteById(fu.getId());
            } else {
                fu.setRemark(null);
                fu.setUpdatedBy(userId);
                customerFollowupMapper.updateById(fu);
            }
            return;
        }
        if (fu == null) {
            fu = new CustomerFollowup();
            fu.setWholesalerId(dto.getWholesalerId());
            fu.setRtPhoneHmac(hmac);
            fu.setRtPhoneCipher(latest.getRtPhoneCipher());
            fu.setRemark(dto.getRemark());
            fu.setCreatedBy(userId);
            customerFollowupMapper.insert(fu);
        } else {
            fu.setRemark(dto.getRemark());
            fu.setUpdatedBy(userId);
            if (!StringUtils.hasText(fu.getRtPhoneCipher())) {
                fu.setRtPhoneCipher(latest.getRtPhoneCipher());
            }
            customerFollowupMapper.updateById(fu);
        }
    }

    @Override
    @Transactional
    public FollowupReminderVo addReminder(String customerKey, CustomerReminderDto dto, Long userId, Long tenantId) {
        String hmac = requireScopedCustomer(customerKey, dto.getWholesalerId(), userId, tenantId);
        InquiryRequest latest = latestInquiry(dto.getWholesalerId(), hmac, tenantId);
        if (latest == null) {
            throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        LocalDateTime remindAt = dto.getRemindAt();
        if (remindAt == null || !remindAt.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.REMIND_TIME_INVALID);
        }
        CustomerFollowup fu = customerFollowupMapper.selectOne(new LambdaQueryWrapper<CustomerFollowup>()
                .eq(CustomerFollowup::getWholesalerId, dto.getWholesalerId())
                .eq(CustomerFollowup::getRtPhoneHmac, hmac));
        if (fu == null) {
            // 首次设提醒即建档（remark 可空，K-4）；冗余 cipher 供 Job 正文打尾号
            fu = new CustomerFollowup();
            fu.setWholesalerId(dto.getWholesalerId());
            fu.setRtPhoneHmac(hmac);
            fu.setRtPhoneCipher(latest.getRtPhoneCipher());
            fu.setCreatedBy(userId);
            customerFollowupMapper.insert(fu);
        } else if (!StringUtils.hasText(fu.getRtPhoneCipher())) {
            fu.setRtPhoneCipher(latest.getRtPhoneCipher());
            customerFollowupMapper.updateById(fu);
        }
        FollowupReminder r = new FollowupReminder();
        r.setWholesalerId(dto.getWholesalerId());
        r.setCustomerFollowupId(fu.getId());
        r.setContent(dto.getContent());
        r.setRemindAt(remindAt);
        r.setCreatedBy(userId);
        followupReminderMapper.insert(r);
        return toReminderVo(r);
    }

    @Override
    @Transactional
    public void deleteReminder(String customerKey, Long wholesalerId, Long reminderId, Long userId, Long tenantId) {
        String hmac = requireScopedCustomer(customerKey, wholesalerId, userId, tenantId);
        FollowupReminder r = followupReminderMapper.selectById(reminderId);
        CustomerFollowup fu = r == null ? null : customerFollowupMapper.selectById(r.getCustomerFollowupId());
        boolean owner = fu != null && wholesalerId.equals(r.getWholesalerId())
                && hmac.equals(fu.getRtPhoneHmac())
                && wholesalerId.equals(fu.getWholesalerId());
        if (!owner) {
            throw new BizException(ErrorCode.REMINDER_NOT_FOUND);
        }
        followupReminderMapper.deleteById(reminderId);
        // 清档规则：remark 为空且无其他提醒（含已触发）→ 删档案
        if (!StringUtils.hasText(fu.getRemark())) {
            Long remain = followupReminderMapper.selectCount(new LambdaQueryWrapper<FollowupReminder>()
                    .eq(FollowupReminder::getCustomerFollowupId, fu.getId()));
            if (remain == 0) {
                customerFollowupMapper.deleteById(fu.getId());
            }
        }
    }

    // ==================== Job：到点提醒 ====================

    @Override
    @Transactional
    public int fireDueReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<FollowupReminder> due = followupReminderMapper.selectList(new LambdaQueryWrapper<FollowupReminder>()
                .le(FollowupReminder::getRemindAt, now)
                .isNull(FollowupReminder::getRemindedAt)
                .orderByAsc(FollowupReminder::getRemindAt)
                .last("LIMIT " + JOB_BATCH_LIMIT));
        int fired = 0;
        for (FollowupReminder r : due) {
            // CAS 防重：并发/重跑时已触发行 0 更新 → 跳过（不重发站内信）
            int updated = followupReminderMapper.update(null, new LambdaUpdateWrapper<FollowupReminder>()
                    .eq(FollowupReminder::getId, r.getId())
                    .isNull(FollowupReminder::getRemindedAt)
                    .set(FollowupReminder::getRemindedAt, now));
            if (updated == 0) {
                continue;
            }
            CustomerFollowup cf = customerFollowupMapper.selectById(r.getCustomerFollowupId());
            if (cf == null) {
                continue;
            }
            String content = r.getContent();
            String plain = piiCrypto.decryptOrNull(cf.getRtPhoneCipher());
            if (plain != null) {
                content += "（客户 " + SmsUtil.maskPhone(plain) + "）";
            }
            notificationService.send(cf.getTenantId(), r.getCreatedBy(),
                    Notification.TYPE_CUSTOMER_FOLLOWUP, "客户跟进提醒", content, null, null);
            fired++;
        }
        return fired;
    }

    // ==================== 私有装配 ====================

    /** 客户操作键：URL-safe Base64(hmac)（K-2）。 */
    private static String customerKey(String hmac) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.getBytes(StandardCharsets.UTF_8));
    }

    /** 校验 customerKey/wholesalerId 属于当前登录人 scope，返回 hmac；不匹配 → 50840（防枚举）。 */
    private String requireScopedCustomer(String customerKey, Long wholesalerId, Long userId, Long tenantId) {
        if (customerKey == null || wholesalerId == null) {
            throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        List<Long> scope = scopeWholesalerIds(userId, tenantId);
        if (!scope.contains(wholesalerId)) {
            throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        String hmac;
        try {
            hmac = new String(Base64.getUrlDecoder().decode(customerKey), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        return hmac;
    }

    /** 当前租户下登录人归属 wholesaler（WA + WE 并集，同 listForWa 口径）；空列表上层直接返回空结果。 */
    private List<Long> scopeWholesalerIds(Long userId, Long tenantId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(authService.listActiveWholesalerIds(userId, "WA", tenantId));
        ids.addAll(authService.listActiveWeWholesalerIds(userId, tenantId));
        return new ArrayList<>(ids);
    }

    /** 客户存在性 + 单客户聚合（detail 用）。 */
    private CustomerAggRow aggregateOne(Long wholesalerId, String hmac, Long tenantId) {
        List<CustomerAggRow> one = inquiryRequestMapper.pageCustomerAgg(new Page<>(1, 1), tenantId, List.of(wholesalerId))
                .getRecords().stream().filter(r -> r.getRtPhoneHmac().equals(hmac)).toList();
        if (one.isEmpty()) {
            throw new BizException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        return one.get(0);
    }

    private InquiryRequest latestInquiry(Long wholesalerId, String hmac, Long tenantId) {
        List<InquiryRequest> list = inquiryRequestMapper.selectList(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, tenantId)
                .eq(InquiryRequest::getWholesalerId, wholesalerId)
                .eq(InquiryRequest::getRtPhoneHmac, hmac)
                .orderByDesc(InquiryRequest::getCreatedAt)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<Long, String> wholesalerNames(List<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        for (Long id : ids) {
            WholesalerVo vo = wholesalerService.getById(id);
            if (vo != null && vo.getName() != null) {
                names.put(id, vo.getName());
            }
        }
        return names;
    }

    private String nameOf(Long wholesalerId) {
        WholesalerVo vo = wholesalerService.getById(wholesalerId);
        return vo == null ? "" : vo.getName();
    }

    private CustomerListItemVo toListItem(CustomerAggRow row, String name, CustomerFollowup fu,
                                          List<FollowupReminder> pending, LocalDateTime now) {
        CustomerListItemVo vo = new CustomerListItemVo();
        vo.setWholesalerId(row.getWholesalerId());
        vo.setWholesalerName(name);
        vo.setCustomerKey(customerKey(row.getRtPhoneHmac()));
        vo.setMaskedPhone(SmsUtil.maskPhone(piiCrypto.decryptOrNull(row.getRtPhoneCipher())));
        vo.setInquiryCount(row.getInquiryCount());
        vo.setLastInquiryAt(row.getLastInquiryAt());
        vo.setLastConfirmedAt(row.getLastConfirmedAt());
        vo.setLastInquiryId(row.getLastInquiryId());
        if (fu != null) {
            vo.setRemark(fu.getRemark());
            vo.setRemarkUpdatedAt(fu.getUpdatedAt());
        }
        // 未触发提醒：next = min(remind_at)，due = 已到点条数
        if (!pending.isEmpty()) {
            LocalDateTime next = null;
            int due = 0;
            for (FollowupReminder r : pending) {
                if (next == null || r.getRemindAt().isBefore(next)) {
                    next = r.getRemindAt();
                }
                if (!r.getRemindAt().isAfter(now)) {
                    due++;
                }
            }
            vo.setNextReminderAt(next);
            vo.setDueReminderCount(due);
        }
        return vo;
    }

    private FollowupReminderVo toReminderVo(FollowupReminder r) {
        FollowupReminderVo vo = new FollowupReminderVo();
        vo.setId(r.getId());
        vo.setContent(r.getContent());
        vo.setRemindAt(r.getRemindAt());
        vo.setRemindedAt(r.getRemindedAt());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
