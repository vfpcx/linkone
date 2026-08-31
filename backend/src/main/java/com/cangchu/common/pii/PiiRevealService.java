package com.cangchu.common.pii;

import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.WePermissions;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.WholesalerApplication;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerApplicationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * PII 阶段 2 · 查全号（15-pii-hardening-v2 §4 阶段2-1 / 波次 PII-W7）。
 *
 * <p>VO 层默认只回打码号（138****1234），业务确需完整手机号的场景走本服务：
 * 独立端点 + 角色/归属双重校验 + 审计日志（谁、何时、看了哪类哪个对象，不含明文）。
 *
 * <p>数据来源：当前明文列仍是主数据（V29 明文收缩未做），直接读明文列；
 * W8 收缩后改为 cipher 解密，接口形态不变。
 *
 * <p>依赖模式照 {@link PiiReadRouter} 先例：PII 横切模块直连各域 mapper（G-S1/G-S2
 * 的既定例外，其余业务代码仍禁止直连他域 mapper）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PiiRevealService {

    /** 查全号 biz 类型：{@link #reveal} 的入参取值。 */
    public static final String BIZ_BLACKLIST = "BLACKLIST";
    public static final String BIZ_TENANT = "TENANT";
    public static final String BIZ_WA_APPLICATION = "WA_APPLICATION";
    public static final String BIZ_INQUIRY = "INQUIRY";

    private final AuthService authService;
    private final BlacklistMapper blacklistMapper;
    private final TenantMapper tenantMapper;
    private final WholesalerApplicationMapper wholesalerApplicationMapper;
    private final InquiryRequestMapper inquiryRequestMapper;

    /**
     * 查看完整手机号（权限校验 + 审计）。
     *
     * @param operatorUserId 登录用户（Sa-Token 推导，不取客户端）
     * @param biz            业务类型（{@code BLACKLIST}/{@code TENANT}/{@code WA_APPLICATION}/{@code INQUIRY}）
     * @param id             目标对象 id
     * @return 完整手机号明文
     */
    public String reveal(Long operatorUserId, String biz, Long id) {
        if (biz == null || biz.isBlank() || id == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "查全号缺少类型或对象 id");
        }
        String phone;
        switch (biz.trim().toUpperCase(Locale.ROOT)) {
            case BIZ_BLACKLIST -> phone = revealBlacklist(operatorUserId, id);
            case BIZ_TENANT -> phone = revealTenant(operatorUserId, id);
            case BIZ_WA_APPLICATION -> phone = revealWaApplication(operatorUserId, id);
            case BIZ_INQUIRY -> phone = revealInquiry(operatorUserId, id);
            default -> throw new BizException(ErrorCode.PII_REVEAL_TYPE_INVALID);
        }
        // 审计日志：谁在何时看了哪类哪个对象的全号（不落明文，PII 红线）
        log.info("[PII-REVEAL] operator={} biz={} id={} ts={}",
                operatorUserId, biz, id, LocalDateTime.now());
        return phone;
    }

    private String revealBlacklist(Long opsUserId, Long id) {
        if (!authService.hasRole(opsUserId, "OPS")) {
            throw forbidden();
        }
        Blacklist b = blacklistMapper.selectById(id);
        if (b == null) {
            throw notFound();
        }
        // 查全号仅对手机号行开放；执照号非手机号 PII，本接口不承载
        if (!"PHONE".equals(b.getTargetType())) {
            throw new BizException(ErrorCode.PII_REVEAL_TYPE_INVALID, "该黑名单条目不是手机号类型");
        }
        return b.getTargetValue();
    }

    private String revealTenant(Long opsUserId, Long id) {
        if (!authService.hasRole(opsUserId, "OPS")) {
            throw forbidden();
        }
        Tenant t = tenantMapper.selectById(id);
        if (t == null) {
            throw notFound();
        }
        return t.getContactPhone();
    }

    private String revealWaApplication(Long userId, Long id) {
        // 跨租户查询：TenantLine 会对 wholesaler_applications 注入当前租户条件，导致越权者拿到 50401
        // （“假装不存在”）。PII 横切场景需要区分 50401/50402，且归属校验在本方法显式完成，
        // 故查询时临时清除租户上下文（照 TenantContext.clearForGlobalQuery 语义）。
        WholesalerApplication app = selectIgnoreTenant(id);
        if (app == null) {
            throw notFound();
        }
        // OPS 平台审核，或该申请归属租户的 TA（跨租户 TA 一律拒绝，不泄漏存在性）
        boolean ops = authService.hasRole(userId, "OPS");
        boolean owningTa = app.getTenantId() != null && authService.hasRole(userId, "TA", app.getTenantId());
        if (!ops && !owningTa) {
            throw forbidden();
        }
        return app.getContactPhone();
    }

    private String revealInquiry(Long userId, Long id) {
        InquiryRequest inq = inquiryRequestMapper.selectById(id);
        if (inq == null) {
            throw notFound();
        }
        // 该询价归属 wholesaler 的 WA，或持 INQUIRY_CONFIRM 授权位的 WE（对齐 InquiryServiceImpl.requireWaRole 口径）
        boolean wa = authService.hasWholesalerRole(userId, "WA", inq.getWholesalerId());
        boolean weConfirmed = authService.hasWholesalerRole(userId, "WE", inq.getWholesalerId())
                && authService.hasWholesalerPermission(userId, inq.getWholesalerId(), WePermissions.INQUIRY_CONFIRM);
        if (!wa && !weConfirmed) {
            throw forbidden();
        }
        return inq.getRtPhone();
    }

    /** 跨租户查询申请单（绕过 TenantLine，见 revealWaApplication 注释）。 */
    private WholesalerApplication selectIgnoreTenant(Long id) {
        TenantContext.TenantInfo saved = TenantContext.get();
        TenantContext.clear();
        try {
            return wholesalerApplicationMapper.selectById(id);
        } finally {
            if (saved != null) {
                TenantContext.set(saved);
            }
        }
    }

    /** 跨租户查询询价单（绕过 TenantLine，见 revealInquiry 注释）。 */
    private InquiryRequest selectInquiryIgnoreTenant(Long id) {
        TenantContext.TenantInfo saved = TenantContext.get();
        TenantContext.clear();
        try {
            return inquiryRequestMapper.selectById(id);
        } finally {
            if (saved != null) {
                TenantContext.set(saved);
            }
        }
    }

    private static BizException forbidden() {
        return new BizException(ErrorCode.PII_REVEAL_FORBIDDEN);
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.PII_REVEAL_TARGET_NOT_FOUND);
    }
}
