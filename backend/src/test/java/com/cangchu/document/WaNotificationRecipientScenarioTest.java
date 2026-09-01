package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.mapper.ArbitrationMapper;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 缺陷修复回归：「归属 WA」通知收件人错位（FE-W1 契约偏差 ①）。
 *
 * <p>缺陷场景：SELF_OPERATED 商户的 {@code wholesalers.owner_user_id} 是 TA 操作人，
 * 旧实现按 owner 发「归属 WA」通知 → 绑定 WA 账号（user_roles）收不到、TA 反而收到。
 * 修复后收件人以 user_roles(role=WA, ACTIVE) 推导（listForWa 同源先例），多 WA 账号全发。
 *
 * <p>与既有用例的差异（关键）：本类 seed 刻意让 owner_user_id ≠ 任何 WA 绑定账号
 * （既有用例 owner==WA，掩盖了缺陷），并绑定两个 WA 账号验证「多账号全发」。
 */
@SpringBootTest(classes = CangchuApplication.class)
class WaNotificationRecipientScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private OutboundRequestService outboundRequestService;
    @Autowired
    private ArbitrationService arbitrationService;
    @Autowired
    private InboundRequestMapper inboundRequestMapper;
    @Autowired
    private ArbitrationMapper arbitrationMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed 工具 ====================

    /**
     * 缺陷复现上下文：SELF_OPERATED 商户 owner_user_id=TA 操作人；
     * 真实 WA 走 user_roles 绑定（wa1/wa2 两个账号验证多账号全发）。
     */
    private record Ctx(long tenantId, long taUserId, long wholesalerId,
                       long wa1UserId, long wa2UserId, long skuId, long wkUserId) {
    }

    private Ctx seedSelfOperated(boolean bindWa) {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        seedRole(taUserId, "TA", tenantId, null, null);

        // 自营商户：owner_user_id = TA 操作人（缺陷根因），WA 仅经 user_roles 绑定
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("自营商户-" + w.getId());
        w.setOwnerUserId(taUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);

        long wa1 = snowflakeIdUtil.nextId();
        long wa2 = snowflakeIdUtil.nextId();
        if (bindWa) {
            seedRole(wa1, "WA", tenantId, w.getId(), null);
            seedRole(wa2, "WA", tenantId, w.getId(), null);
        }

        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(w.getId());
        s.setName("品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);

        long wkUserId = snowflakeIdUtil.nextId();
        seedRole(wkUserId, "WK", tenantId, null, null);
        return new Ctx(tenantId, taUserId, w.getId(), wa1, wa2, s.getId(), wkUserId);
    }

    private long seedRole(Long userId, String role, Long tenantId, Long wholesalerId, String permissions) {
        long uid = userId != null ? userId : snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(uid);
        r.setRole(role);
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        r.setPermissions(permissions);
        userRoleMapper.insert(r);
        return uid;
    }

    private InboundRequestVo register(Ctx c, int qty) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        InboundRegisterDto d = new InboundRegisterDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setPalletQty(0);
        return inboundRequestService.registerByWk(d, c.wkUserId());
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    private void expireDeadline(long inboundId) {
        inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, inboundId)
                .set(InboundRequest::getWaConfirmDeadline, LocalDateTime.now().minusMinutes(5)));
    }

    // ======================================================================

    @Test
    @DisplayName("P3-NTF-01 registerByWk：待确认通知发给 user_roles 全部 WA（两账号各 1），owner=TA 不收")
    void registerNotifiesBoundWasNotOwner() {
        Ctx c = seedSelfOperated(true);
        register(c, 30);

        assertThat(countNotifications(c.wa1UserId(), Notification.TYPE_INBOUND_PENDING_CONFIRM)).isEqualTo(1);
        assertThat(countNotifications(c.wa2UserId(), Notification.TYPE_INBOUND_PENDING_CONFIRM)).isEqualTo(1);
        // owner_user_id（TA 操作人）不再错收「归属 WA」通知
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_INBOUND_PENDING_CONFIRM)).isZero();
    }

    @Test
    @DisplayName("P3-NTF-02 72h Job 自动确认：通知全部绑定 WA，owner=TA 不收；重跑幂等不重发")
    void autoConfirmNotifiesBoundWasNotOwner() {
        Ctx c = seedSelfOperated(true);
        InboundRequestVo vo = register(c, 10);
        expireDeadline(vo.getId());

        TenantContext.clear(); // Job 系统态（全平台扫描先例）
        assertThat(inboundRequestService.autoConfirmExpired()).isEqualTo(1);

        assertThat(countNotifications(c.wa1UserId(), Notification.TYPE_INBOUND_AUTO_CONFIRMED)).isEqualTo(1);
        assertThat(countNotifications(c.wa2UserId(), Notification.TYPE_INBOUND_AUTO_CONFIRMED)).isEqualTo(1);
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_INBOUND_AUTO_CONFIRMED)).isZero();

        // 幂等：重跑不重复通知
        assertThat(inboundRequestService.autoConfirmExpired()).isZero();
        assertThat(countNotifications(c.wa1UserId(), Notification.TYPE_INBOUND_AUTO_CONFIRMED)).isEqualTo(1);
    }

    @Test
    @DisplayName("P3-NTF-03 TA 仲裁裁决：裁决通知发全部绑定 WA + WK，owner=TA 不收 WA 侧通知")
    void arbitrationDecidedNotifiesBoundWas() {
        Ctx c = seedSelfOperated(true);
        InboundRequestVo vo = register(c, 20);

        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wa1UserId(), "WA"));
        InboundDisputeDto dispute = new InboundDisputeDto();
        dispute.setReason("未到货");
        inboundRequestService.disputeByWa(vo.getId(), c.wa1UserId(), dispute);
        Arbitration arb = arbitrationMapper.selectOne(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocId, vo.getId()));

        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
        ArbitrationDecideDto decide = new ArbitrationDecideDto();
        decide.setConclusion(Arbitration.CONCLUSION_APPROVED);
        decide.setRemark("查证已到货");
        arbitrationService.decideByTa(arb.getId(), c.taUserId(), decide);

        assertThat(countNotifications(c.wa1UserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);
        assertThat(countNotifications(c.wa2UserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_ARBITRATION_DECIDED)).isZero();
    }

    @Test
    @DisplayName("P3-NTF-04 WK 代建出库：代建通知发全部绑定 WA，owner=TA 不收")
    void proxyOutboundNotifiesBoundWas() {
        Ctx c = seedSelfOperated(true);
        register(c, 30); // 备货 30 在库

        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        WkOutboundCreateDto d = new WkOutboundCreateDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(10); // 10×2 ≤ 30，不触发大额复述
        d.setConfirmed(true);
        outboundRequestService.createByWk(d, c.wkUserId());

        assertThat(countNotifications(c.wa1UserId(), Notification.TYPE_OUTBOUND_PROXY_CREATED)).isEqualTo(1);
        assertThat(countNotifications(c.wa2UserId(), Notification.TYPE_OUTBOUND_PROXY_CREATED)).isEqualTo(1);
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_OUTBOUND_PROXY_CREATED)).isZero();
    }

    @Test
    @DisplayName("P3-NTF-05 无绑定 WA 的脏数据商户：通知降级跳过（不发给 owner），业务主链不阻断")
    void noBoundWaDegradesSilently() {
        Ctx c = seedSelfOperated(false);
        InboundRequestVo vo = register(c, 5); // 不抛异常=主链不阻断

        assertThat(vo.getStatus()).isEqualTo(InboundRequest.STATUS_PENDING_WA_CONFIRM);
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_INBOUND_PENDING_CONFIRM)).isZero();
        assertThat(notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, c.tenantId())
                .eq(Notification::getType, Notification.TYPE_INBOUND_PENDING_CONFIRM))).isZero();
    }
}
