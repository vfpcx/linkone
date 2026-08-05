package com.cangchu.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.billing.dto.BillingRuleSaveDto;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.mapper.BillingRuleMapper;
import com.cangchu.billing.service.BillingRuleService;
import com.cangchu.billing.vo.BillingRuleVo;
import com.cangchu.billing.vo.BillingRulesVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 计费规则服务实现（P4 W1，14 §2）。
 *
 * <p>版本链不变量：任一时刻每租户至多一行 effective_to IS NULL（当前生效）；
 * uk_rule_tenant_from(tenant_id, effective_from) 保证一日一版（同日多次变更覆写）。
 * 变更日按新规则计（05 §1.5）；首版 effective_from=首次保存日、历史不补出账（D-P4-4）。
 *
 * <p>跨域访问（G-S1/G-S2）：镜像写/仓库名经 TenantService、在驻商户经 WholesalerService、
 * 鉴权经 AuthService、站内信经 NotificationService，不跨域直连 Mapper。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingRuleServiceImpl implements BillingRuleService {

    private final BillingRuleMapper billingRuleMapper;
    private final AuthService authService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    /** 单价上限（DECIMAL(14,4) 整数位护栏；超限同 50379 无效） */
    private static final BigDecimal PRICE_MAX = new BigDecimal("9999999999");

    @Override
    public BillingRulesVo getRules(Long userId) {
        Long tenantId = requireStOrTa(userId);
        BillingRule current = selectCurrent(tenantId);
        List<BillingRule> closed = billingRuleMapper.selectList(new LambdaQueryWrapper<BillingRule>()
                .eq(BillingRule::getTenantId, tenantId)
                .isNotNull(BillingRule::getEffectiveTo)
                .orderByDesc(BillingRule::getVersion));
        return BillingRulesVo.builder()
                .current(current != null ? toVo(current) : null)
                .history(closed.stream().map(this::toVo).toList())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillingRuleVo saveRule(Long userId, BillingRuleSaveDto dto) {
        // S4：写=仅 TA；tenantId 由登录态推导（不取客户端）
        Long tenantId = requireTaRole(userId);

        // 1) 校验（50379 四路：无维度 / 件·天缺价 / 托盘·天缺价 / 单价越界）
        Normalized rule = validate(dto);

        LocalDate today = LocalDate.now();
        BillingRule current = selectCurrent(tenantId);

        if (current == null) {
            // 5) 首版：effective_from=首次保存日、version=1、无需 confirmed（无变更即无 R20）；
            //    历史天数不出账（D-P4-4，出账起点截断归 W2/W3 消费）
            BillingRule first = newRow(tenantId, userId, rule, today, 1);
            insertGuardingConcurrency(first);
            afterSave(tenantId, userId, rule);
            log.info("[P4][规则] 租户 {} 首版计费规则生效 from={} qty={}/{} pallet={}/{}",
                    tenantId, today, rule.qtyEnabled(), rule.priceQty(), rule.palletEnabled(), rule.pricePallet());
            return toVo(first);
        }

        // 2) 幂等空转：与当前规则完全相同 → 不计版本、不通知、无需 confirmed
        if (sameAsCurrent(current, rule)) {
            return toVo(current);
        }

        // 3) 真实变更需 R20 二次确认凭据（batch-toggle 先例，40003）
        if (!Boolean.TRUE.equals(dto.getConfirmed())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少二次确认凭据");
        }

        BillingRule saved;
        if (today.equals(current.getEffectiveFrom())) {
            // 4a) 当日已有版本行 → 覆写该行（同日多次变更最后一次生效，uk 一日一版；version 不递增）
            current.setQtyEnabled(rule.qtyEnabled() ? 1 : 0);
            current.setPalletEnabled(rule.palletEnabled() ? 1 : 0);
            current.setPricePerQtyDay(rule.priceQty());
            current.setPricePerPalletDay(rule.pricePallet());
            billingRuleMapper.updateById(current);
            saved = current;
        } else {
            // 4b) 关旧行（CAS：仅当仍为当前行时置 to=今日−1，并发丢失即冲突 50331 语义复用）+ 插新行
            int affected = billingRuleMapper.update(null, new LambdaUpdateWrapper<BillingRule>()
                    .eq(BillingRule::getId, current.getId())
                    .isNull(BillingRule::getEffectiveTo)
                    .set(BillingRule::getEffectiveTo, today.minusDays(1)));
            if (affected != 1) {
                throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT, "计费规则已被并发修改，请刷新后重试");
            }
            saved = newRow(tenantId, userId, rule, today, current.getVersion() + 1);
            insertGuardingConcurrency(saved);
        }

        afterSave(tenantId, userId, rule);
        log.info("[P4][规则] 租户 {} 计费规则变更生效 from={} version={}（旧版留痕 to={}）",
                tenantId, today, saved.getVersion(), today.minusDays(1));
        return toVo(saved);
    }

    @Override
    public List<BillingRuleVo> listRuleChain(Long tenantId, LocalDate untilInclusive) {
        return billingRuleMapper.selectList(new LambdaQueryWrapper<BillingRule>()
                        .eq(BillingRule::getTenantId, tenantId)
                        .le(untilInclusive != null, BillingRule::getEffectiveFrom, untilInclusive))
                .stream()
                .sorted(Comparator.comparing(BillingRule::getEffectiveFrom))
                .map(this::toVo)
                .toList();
    }

    // ==================== 私有 ====================

    /** 校验后归一化的规则值（停用维单价置 null，启用维保留原精度 ≤4 位小数） */
    private record Normalized(boolean qtyEnabled, BigDecimal priceQty,
                              boolean palletEnabled, BigDecimal pricePallet) {}

    private Normalized validate(BillingRuleSaveDto dto) {
        if (dto == null) {
            throw new BizException(ErrorCode.BILLING_RULE_INVALID);
        }
        boolean qty = Boolean.TRUE.equals(dto.getBillingByQty());
        boolean pallet = Boolean.TRUE.equals(dto.getBillingByPallet());
        if (!qty && !pallet) {
            throw new BizException(ErrorCode.BILLING_RULE_INVALID, "请至少启用一种计费维度");
        }
        BigDecimal priceQty = qty ? requireValidPrice(dto.getPricePerQtyDay(), "件·天") : null;
        BigDecimal pricePallet = pallet ? requireValidPrice(dto.getPricePerPalletDay(), "托盘·天") : null;
        return new Normalized(qty, priceQty, pallet, pricePallet);
    }

    private BigDecimal requireValidPrice(BigDecimal price, String dimLabel) {
        if (price == null) {
            throw new BizException(ErrorCode.BILLING_RULE_INVALID, "请填写" + dimLabel + "单价");
        }
        if (price.signum() < 0 || price.scale() > 4 || price.compareTo(PRICE_MAX) > 0) {
            throw new BizException(ErrorCode.BILLING_RULE_INVALID,
                    dimLabel + "单价无效（须 ≥0，最多 4 位小数）");
        }
        return price;
    }

    private BillingRule selectCurrent(Long tenantId) {
        return billingRuleMapper.selectOne(new LambdaQueryWrapper<BillingRule>()
                .eq(BillingRule::getTenantId, tenantId)
                .isNull(BillingRule::getEffectiveTo)
                .last("LIMIT 1"));
    }

    private boolean sameAsCurrent(BillingRule current, Normalized rule) {
        boolean curQty = Integer.valueOf(1).equals(current.getQtyEnabled());
        boolean curPallet = Integer.valueOf(1).equals(current.getPalletEnabled());
        return curQty == rule.qtyEnabled() && curPallet == rule.palletEnabled()
                && priceEquals(current.getPricePerQtyDay(), rule.priceQty())
                && priceEquals(current.getPricePerPalletDay(), rule.pricePallet());
    }

    private boolean priceEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        return a.compareTo(b) == 0;
    }

    private BillingRule newRow(Long tenantId, Long userId, Normalized rule, LocalDate from, int version) {
        BillingRule row = new BillingRule();
        row.setId(snowflakeIdUtil.nextId());
        row.setTenantId(tenantId);
        row.setWholesalerId(null);                     // 留位恒 NULL（D-P4-3）
        row.setQtyEnabled(rule.qtyEnabled() ? 1 : 0);
        row.setPalletEnabled(rule.palletEnabled() ? 1 : 0);
        row.setPricePerQtyDay(rule.priceQty());
        row.setPricePerPalletDay(rule.pricePallet());
        row.setMinCharge(BigDecimal.ZERO);             // 留位不启用
        row.setExtraRuleJson(null);                    // 留位不启用
        row.setEffectiveFrom(from);
        row.setEffectiveTo(null);
        row.setVersion(version);
        row.setCreatedBy(userId);
        return row;
    }

    /** 插入新版本行；并发双写撞 uk_rule_tenant_from（一日一版）按并发冲突口径提示重试。 */
    private void insertGuardingConcurrency(BillingRule row) {
        try {
            billingRuleMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT, "计费规则已被并发修改，请刷新后重试");
        }
    }

    /**
     * 保存成功后的同事务收尾（14 §2.1-3/4）：
     * ① 镜像 tenant_settings.billing_dim（QTY/PALLET/BOTH，经 TenantService 出口）；
     * ② 站内信通知全部在驻（ACTIVE）批发商管理员（文案零角色码，PRD 13-p4 §9）。
     */
    private void afterSave(Long tenantId, Long userId, Normalized rule) {
        String dim = rule.qtyEnabled() && rule.palletEnabled() ? BillingRule.DIM_BOTH
                : rule.qtyEnabled() ? BillingRule.DIM_QTY : BillingRule.DIM_PALLET;
        tenantService.updateBillingDimMirror(tenantId, dim, userId);

        // 收件人：本租户全部 ACTIVE 商户的管理员账号（user_roles 推导先例，多账号全发、去重）
        Set<Long> recipients = new LinkedHashSet<>();
        for (WholesalerVo ws : wholesalerService.listByTenant(tenantId)) {
            if (ws != null && "ACTIVE".equals(ws.getStatus()) && ws.getId() != null) {
                recipients.addAll(authService.listActiveWaUserIdsOfWholesaler(ws.getId()));
            }
        }
        String tenantName = tenantService.getTenantName(tenantId);
        String storeName = tenantName != null ? tenantName : "您入驻的仓库";
        notificationService.sendToAll(tenantId, recipients, Notification.TYPE_BILLING_RULE_CHANGED,
                "仓储计费规则更新",
                storeName + "更新了仓储计费规则（今日起生效，历史账单不受影响），请知悉。",
                null, null);
    }

    private BillingRuleVo toVo(BillingRule rule) {
        return BillingRuleVo.builder()
                .id(rule.getId() != null ? rule.getId().toString() : null)
                .qtyEnabled(Integer.valueOf(1).equals(rule.getQtyEnabled()))
                .pricePerQtyDay(rule.getPricePerQtyDay())
                .palletEnabled(Integer.valueOf(1).equals(rule.getPalletEnabled()))
                .pricePerPalletDay(rule.getPricePerPalletDay())
                .effectiveFrom(rule.getEffectiveFrom())
                .effectiveTo(rule.getEffectiveTo())
                .version(rule.getVersion())
                .build();
    }

    // ==================== 鉴权（14 §2.4：写=TA；读=ST 或 TA；WE 42004、WK/WA 42001） ====================

    /** 写：仅 TA（user_roles 登录态推导，不信任客户端；TenantServiceImpl.requireTaRole 同构）。 */
    private Long requireTaRole(Long userId) {
        Long tenantId = authService.findBoundTenantId(userId, "TA");
        if (tenantId == null) {
            if (authService.hasRole(userId, "TA")) {
                throw new BizException(ErrorCode.TENANT_NOT_FOUND, "请先完成建仓后再设置计费规则");
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅仓库管理员可设置计费规则");
        }
        return tenantId;
    }

    /** 读：ST 或 TA（billing 域新 gate，requireWkOrTa 同构先例）；WE 明确 42004，其余 42001。 */
    private Long requireStOrTa(Long userId) {
        Long tenantId = authService.findBoundTenantId(userId, "TA");
        if (tenantId == null) {
            tenantId = authService.findBoundTenantId(userId, "ST");
        }
        if (tenantId == null) {
            if (authService.hasRole(userId, "WE")) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004);
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅仓库管理员或结算员可查看计费规则");
        }
        return tenantId;
    }
}
