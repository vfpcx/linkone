package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.document.vo.OutboundRequestVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 08-p3-review W1 审查修复回归（N1/N2/N3/N5/M3 逐项对应，B1 见 InventoryLockRegressionTest）。
 * seed 风格沿用 OutboundChainScenarioTest（mapper seed + TenantContext 模拟登录态）。
 */
@SpringBootTest(classes = CangchuApplication.class)
class ReviewFixW1ScenarioTest {

    @Autowired
    private OutboundRequestService outboundRequestService;
    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private OutboundRequestMapper outboundRequestMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private TenantMapper tenantMapper;
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

    // ==================== seed 工具（对齐 OutboundChainScenarioTest） ====================

    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId,
                       long skuId, long wkUserId) {
    }

    private Ctx seedAll() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode("T" + (tenantId % 1_000_000));
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhone("1" + String.format("%010d", tenantId % 10_000_000_000L));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        seedRole(taUserId, "TA", tenantId, null);

        long waUserId = snowflakeIdUtil.nextId();
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(waUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        seedRole(waUserId, "WA", tenantId, w.getId());

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
        seedRole(wkUserId, "WK", tenantId, null);
        return new Ctx(tenantId, taUserId, w.getId(), waUserId, s.getId(), wkUserId);
    }

    private void seedRole(Long userId, String role, Long tenantId, Long wholesalerId) {
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(userId);
        r.setRole(role);
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        userRoleMapper.insert(r);
    }

    private void seedStock(Ctx c, int qty) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).palletQty(0).refDocNo("WK-SEED-" + snowflakeIdUtil.nextId())
                .operatorUserId(c.wkUserId()).build());
    }

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private OutboundRequestVo submit(Ctx c, int qty) {
        asWa(c);
        OutboundSubmitDto d = new OutboundSubmitDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        return outboundRequestService.submitByWa(d, c.waUserId());
    }

    /** 走到「已打印 + 撤回申请在途（flag=1）」的标准前置。 */
    private OutboundRequestVo printedWithWithdrawRequested(Ctx c, int qty) {
        OutboundRequestVo vo = submit(c, qty);
        asWk(c);
        outboundRequestService.printByWk(vo.getId(), c.wkUserId());
        asWa(c);
        return outboundRequestService.withdrawByWa(vo.getId(), c.waUserId());
    }

    private InboundRequestVo registerInbound(Ctx c, int qty) {
        asWk(c);
        InboundRegisterDto d = new InboundRegisterDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setPalletQty(0);
        return inboundRequestService.registerByWk(d, c.wkUserId());
    }

    private OutboundRequest doc(long id) {
        return outboundRequestMapper.selectById(id);
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    // ======================================================================
    // N1 · confirmWithdrawByWk CAS 补 withdraw_requested=1 条件
    // ======================================================================

    @Test
    @DisplayName("N1-01 拒绝后确认（顺序）→ 50336；单据保持 PRINTED、库存未动")
    void confirmAfterRejectSequential() {
        Ctx c = seedAll();
        seedStock(c, 30);
        OutboundRequestVo vo = printedWithWithdrawRequested(c, 10);
        assertThat(vo.getWithdrawRequested()).isEqualTo(1);

        asWk(c);
        outboundRequestService.rejectWithdrawByWk(vo.getId(), c.wkUserId());
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> outboundRequestService.confirmWithdrawByWk(vo.getId(), c.wkUserId()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
        assertThat(doc(vo.getId()).getStatus()).isEqualTo(OutboundRequest.STATUS_PRINTED);
    }

    @Test
    @DisplayName("N1-02 拒绝 vs 确认并发（虚拟线程）：恰一方成功，败方 50336，终态与赢家一致（旧 CAS 可双赢互斥破坏）")
    void rejectVsConfirmRace() throws Exception {
        Ctx c = seedAll();
        seedStock(c, 30);
        OutboundRequestVo vo = printedWithWithdrawRequested(c, 10);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> rejectF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return outboundRequestService.rejectWithdrawByWk(vo.getId(), c.wkUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            Future<Object> confirmF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return outboundRequestService.confirmWithdrawByWk(vo.getId(), c.wkUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            start.countDown();
            Object rejR = rejectF.get(60, TimeUnit.SECONDS);
            Object conR = confirmF.get(60, TimeUnit.SECONDS);
            boolean rejOk = rejR instanceof OutboundRequestVo;
            boolean conOk = conR instanceof OutboundRequestVo;
            // 互斥红线：拒绝与确认不可能同时生效（旧 CAS 缺 flag 条件时确认方可在拒绝生效后仍成功）
            assertThat(rejOk ^ conOk).as("恰一方成功: rej=%s con=%s", rejR, conR).isTrue();

            OutboundRequest cur = doc(vo.getId());
            if (rejOk) {
                // 拒绝赢：单据继续履约（PRINTED、flag 清零、无回补），确认方 50336
                assertThat(cur.getStatus()).isEqualTo(OutboundRequest.STATUS_PRINTED);
                assertThat(cur.getWithdrawRequested()).isZero();
                assertThat(((BizException) conR).getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
            } else {
                // 确认赢：单据撤销回补，拒绝方 50336
                assertThat(cur.getStatus()).isEqualTo(OutboundRequest.STATUS_CANCELLED);
                assertThat(((BizException) rejR).getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
            }
        }
    }
}
