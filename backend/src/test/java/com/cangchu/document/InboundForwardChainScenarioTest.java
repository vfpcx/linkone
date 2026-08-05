package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundCorrectionCreateDto;
import com.cangchu.document.dto.InboundCorrectionDecideDto;
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundRejectDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.dto.InboundWithdrawDto;
import com.cangchu.document.entity.InboundCorrection;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.mapper.InboundCorrectionMapper;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.InboundCorrectionService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.vo.InboundCorrectionVo;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.dto.SkuCreateDto;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.service.SkuService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3b T1-BE 正向申请链场景测试（13 §6 T1-BE 测试关卡）。
 *
 * <p>覆盖（沿用 InboundDisputeChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>入库矩阵逐格断言（8 状态含代建 4 值交叉不可达；红线 REJECTED→ACCEPTED /
 *       CONFIRMED→WITHDRAWN / SUBMITTED→CONFIRMED）。</li>
 *   <li>提交（D-5 多行拆单+原子性）/撤回/受理/驳回全程<b>库存零变化</b>断言（10 §1.2-1
 *       时点不对称：入库=登记才加）。</li>
 *   <li>5% 差异三点边界（4.9% / 5.0% 含等于放行 / 5.1% → 50351）。</li>
 *   <li>R3 纠错封顶三态（改大补录 / 部分售出封顶+差额 / 售罄 applied=0 留痕）+
 *       biz_time=原 INBOUND、reversal_of_id 配对锚点断言（D-4）。</li>
 *   <li>R14（50313）/ WE 授权三态（INBOUND_SUBMIT 位）/ 未授权 40x / 纠错护栏 50352-50354。</li>
 *   <li>并发受理 CAS（Java21 虚拟线程）；R13 未结计数扩展（SUBMITTED/ACCEPTED/纠错 PENDING）。</li>
 *   <li>SKU 只读列表放宽 WK 回归 + 写路径不放宽回归（13 §5.1 补口）。</li>
 *   <li>库存公式不变量扩展（13 §0）：+ ΣCORRECTION_IN − ΣCORRECTION_OUT。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class InboundForwardChainScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private InboundCorrectionService inboundCorrectionService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private SkuService skuService;
    @Autowired
    private InboundRequestMapper inboundRequestMapper;
    @Autowired
    private InboundCorrectionMapper inboundCorrectionMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
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

    // ==================== seed 工具（InboundDisputeChainScenarioTest 同构） ====================

    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId, long skuId, long wkUserId) {
    }

    private Ctx seedAll() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhone("1" + String.format("%010d", tenantId % 10_000_000_000L));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        seedRole(taUserId, "TA", tenantId, null, null);

        long waUserId = snowflakeIdUtil.nextId();
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(waUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        seedRole(waUserId, "WA", tenantId, w.getId(), null);

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
        return new Ctx(tenantId, taUserId, w.getId(), waUserId, s.getId(), wkUserId);
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

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private void asTa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
    }

    private InboundSubmitDto submitDto(Ctx c, int... qtys) {
        InboundSubmitDto d = new InboundSubmitDto();
        d.setWholesalerId(c.wholesalerId());
        d.setItems(java.util.Arrays.stream(qtys).mapToObj(q -> {
            InboundSubmitDto.Item i = new InboundSubmitDto.Item();
            i.setSkuId(c.skuId());
            i.setQty(q);
            return i;
        }).toList());
        return d;
    }

    /** WA 提交（单行）→ 返回 SUBMITTED 单。 */
    private InboundRequestVo submitOne(Ctx c, int qty) {
        asWa(c);
        return inboundRequestService.submitByWa(submitDto(c, qty), c.waUserId()).get(0);
    }

    /** WA 提交 + WK 受理 → 返回 ACCEPTED 单。 */
    private InboundRequestVo submitAndAccept(Ctx c, int qty) {
        InboundRequestVo vo = submitOne(c, qty);
        asWk(c);
        return inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());
    }

    /** WA 提交 + WK 受理 + WK 登记（全程正向链）→ 返回 CONFIRMED 单。 */
    private InboundRequestVo submitAcceptRegister(Ctx c, int qty, int actual) {
        InboundRequestVo vo = submitAndAccept(c, qty);
        asWk(c);
        return inboundRequestService.registerForwardByWk(vo.getId(),
                registerDto(actual, null, qty == actual ? null : "点数差异"), c.wkUserId());
    }

    private InboundForwardRegisterDto registerDto(int actualQty, Integer palletQty, String remark) {
        InboundForwardRegisterDto d = new InboundForwardRegisterDto();
        d.setActualQty(actualQty);
        d.setPalletQty(palletQty);
        d.setRemark(remark);
        return d;
    }

    private InboundWithdrawDto withdrawDto(String reason) {
        InboundWithdrawDto d = new InboundWithdrawDto();
        d.setReason(reason);
        return d;
    }

    private InboundRejectDto rejectDto(String reason, String remark, List<String> attachments) {
        InboundRejectDto d = new InboundRejectDto();
        d.setReason(reason);
        d.setRemark(remark);
        d.setAttachments(attachments);
        return d;
    }

    private InboundCorrectionCreateDto corrDto(int newQty, String reason) {
        InboundCorrectionCreateDto d = new InboundCorrectionCreateDto();
        d.setNewQty(newQty);
        d.setReason(reason);
        return d;
    }

    private InboundCorrectionDecideDto decideDto(String conclusion, String remark) {
        InboundCorrectionDecideDto d = new InboundCorrectionDecideDto();
        d.setConclusion(conclusion);
        d.setRemark(remark);
        return d;
    }

    private void deduct(Ctx c, int qty) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).refDocNo("CK-TEST").operatorUserId(c.waUserId()).build());
    }

    private int qtyOf(Ctx c) {
        var list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() ? 0 : list.get(0).getQty();
    }

    private List<StockMovement> movements(Ctx c, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId())
                .eq(type != null, StockMovement::getType, type));
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    /** 提交/撤回/受理/驳回全程零库存零流水断言（10 §1.2-1 时点不对称）。 */
    private void assertZeroStock(Ctx c) {
        assertThat(qtyOf(c)).isZero();
        assertThat(movements(c, null)).isEmpty();
    }

    /** 13 §0 不变量（P3b 扩展）：qty = ΣIN − ΣOUT + ΣOUT_REV − ΣDIS_REV + ΣDIS_RST + ΣCORR_IN − ΣCORR_OUT。 */
    private void assertInvariant(Ctx c) {
        long expected = 0;
        for (StockMovement m : movements(c, null)) {
            switch (m.getType()) {
                case StockMovement.TYPE_INBOUND, StockMovement.TYPE_OUTBOUND_REVERSAL,
                        StockMovement.TYPE_DISPUTE_RESTORE, StockMovement.TYPE_CORRECTION_IN -> expected += m.getQty();
                case StockMovement.TYPE_OUTBOUND, StockMovement.TYPE_DISPUTE_REVERSAL,
                        StockMovement.TYPE_CORRECTION_OUT -> expected -= m.getQty();
                default -> throw new IllegalStateException("未知流水类型 " + m.getType());
            }
        }
        assertThat((long) qtyOf(c)).as("库存公式不变量（13 §0）").isEqualTo(expected);
        assertThat(expected).isGreaterThanOrEqualTo(0);
    }

    private BizException expectBiz(org.junit.jupiter.api.function.Executable e) {
        return Assertions.assertThrows(BizException.class, e);
    }

    // ======================================================================
    // 状态机矩阵逐格
    // ======================================================================

    @Test
    @DisplayName("T1-SM-01 入库矩阵逐格：8 状态×8 目标全交叉，可达集精确相等（含三条红线不可达）")
    void inboundMatrixExhaustive() {
        // 13 §1.1 冻结矩阵：正向链 4 值 + 代建链 4 值共表
        Map<String, Set<String>> expected = Map.of(
                InboundRequest.STATUS_SUBMITTED, Set.of(
                        InboundRequest.STATUS_WITHDRAWN, InboundRequest.STATUS_REJECTED,
                        InboundRequest.STATUS_ACCEPTED),
                // P3b T4-W1（T1 收尾）：ACCEPTED→REJECTED 补入矩阵（受理后现场发现问题可驳回，如 >5% 场景）
                InboundRequest.STATUS_ACCEPTED, Set.of(
                        InboundRequest.STATUS_CONFIRMED, InboundRequest.STATUS_REJECTED),
                InboundRequest.STATUS_WITHDRAWN, Set.of(),
                InboundRequest.STATUS_REJECTED, Set.of(),
                InboundRequest.STATUS_PENDING_WA_CONFIRM, Set.of(
                        InboundRequest.STATUS_CONFIRMED, InboundRequest.STATUS_DISPUTED),
                InboundRequest.STATUS_DISPUTED, Set.of(
                        InboundRequest.STATUS_CONFIRMED, InboundRequest.STATUS_REVOKED),
                InboundRequest.STATUS_CONFIRMED, Set.of(),
                InboundRequest.STATUS_REVOKED, Set.of());
        for (String from : expected.keySet()) {
            for (String to : expected.keySet()) {
                boolean can = DocStateMachine.canGo(DocStateMachine.DocKind.INBOUND, from, to);
                assertThat(can).as("%s -> %s", from, to).isEqualTo(expected.get(from).contains(to));
            }
        }
        // 红线显式重申（13 §1.1）：驳回后不能受理 / 已入库不能撤回 / 提交不能跳过受理直落入库
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.INBOUND,
                InboundRequest.STATUS_REJECTED, InboundRequest.STATUS_ACCEPTED)).isFalse();
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.INBOUND,
                InboundRequest.STATUS_CONFIRMED, InboundRequest.STATUS_WITHDRAWN)).isFalse();
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.INBOUND,
                InboundRequest.STATUS_SUBMITTED, InboundRequest.STATUS_CONFIRMED)).isFalse();
    }

    // ======================================================================
    // 提交（D-5 多行拆单）
    // ======================================================================

    @Test
    @DisplayName("T1-SUB-01 多行拆单：3 行→3 张 SUBMITTED 单共享 batch_submit_id，deadline 恒 NULL，零库存零流水，通知 WK")
    void submitMultiLineSplit() {
        Ctx c = seedAll();
        asWa(c);
        List<InboundRequestVo> created = inboundRequestService.submitByWa(submitDto(c, 10, 20, 30), c.waUserId());

        assertThat(created).hasSize(3);
        assertThat(created).extracting(InboundRequestVo::getBatchSubmitId).doesNotContainNull();
        assertThat(created.stream().map(InboundRequestVo::getBatchSubmitId).distinct()).hasSize(1);
        assertThat(created).extracting(InboundRequestVo::getQty).containsExactly(10, 20, 30);
        for (InboundRequestVo vo : created) {
            assertThat(vo.getStatus()).isEqualTo(InboundRequest.STATUS_SUBMITTED);
            assertThat(vo.getSource()).isEqualTo(InboundRequest.SOURCE_WA_SUBMIT);
            assertThat(vo.getRequestedQty()).isEqualTo(vo.getQty());
            // 正向链 deadline 恒 NULL → 72h Job 扫描天然不命中（10 §1.2-7）
            assertThat(vo.getWaConfirmDeadline()).isNull();
            assertThat(vo.getWaUserId()).isEqualTo(c.waUserId());
            assertThat(vo.getDocNo()).startsWith("WK-"); // DocType.INBOUND 前缀（A3 零改造沿用）
        }
        // 库存时点不对称（有意设计）：提交零库存零流水零计费
        assertZeroStock(c);
        // 同事务通知库管（收件人 user_roles 推导，13 §1.4；整批 1 条不轰炸）
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_INBOUND_SUBMITTED)).isEqualTo(1);
        // 72h Job 零改动回归：正向链单不产 PENDING_WA_CONFIRM → Job 扫描 0 单
        TenantContext.clear();
        assertThat(inboundRequestService.autoConfirmExpired()).isZero();
    }

    @Test
    @DisplayName("T1-SUB-02 拆单原子性：第 2 行 SKU 非法 → 整批回滚零单落库（单事务）")
    void submitAtomicRollback() {
        Ctx c = seedAll();
        Ctx other = seedAll(); // 他商户 SKU 充当非法行
        asWa(c);
        InboundSubmitDto d = submitDto(c, 10, 20);
        d.getItems().get(1).setSkuId(other.skuId());

        assertThat(expectBiz(() -> inboundRequestService.submitByWa(d, c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.SKU_NOT_FOUND);
        // 第 1 行合法单也一并回滚（D-5 原子性）
        assertThat(inboundRequestMapper.selectCount(new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getWholesalerId, c.wholesalerId()))).isZero();
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_INBOUND_SUBMITTED)).isZero();
    }

    @Test
    @DisplayName("T1-SUB-03 R14：商户非 ACTIVE → 提交 50313；存量 SUBMITTED 单受理同样 50313")
    void submitAndAcceptRequireActive() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitOne(c, 10);
        // 商户下架
        wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, c.wholesalerId()).set(Wholesaler::getStatus, "OFFLINE"));

        asWa(c);
        assertThat(expectBiz(() -> inboundRequestService.submitByWa(submitDto(c, 5), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_ACTIVE);
        // 存量 SUBMITTED 单受理同拒（货未入仓不属保护客户权益，13 §1.4）
        asWk(c);
        assertThat(expectBiz(() -> inboundRequestService.acceptByWk(vo.getId(), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_ACTIVE);
        assertZeroStock(c);
    }

    // ======================================================================
    // R1 撤回 / 受理 / R2 驳回（全程零库存）
    // ======================================================================

    @Test
    @DisplayName("T1-WD-01 R1 撤回：SUBMITTED→WITHDRAWN 落 withdraw_reason；受理后撤回 → 50350；代建链单无撤回入口 → 50330")
    void withdrawOnlySubmitted() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitOne(c, 10);
        InboundRequestVo withdrawn = inboundRequestService.withdrawByWa(vo.getId(), withdrawDto("填错件数"), c.waUserId());
        assertThat(withdrawn.getStatus()).isEqualTo(InboundRequest.STATUS_WITHDRAWN);
        assertThat(withdrawn.getWithdrawReason()).isEqualTo("填错件数");
        assertZeroStock(c);

        // 受理锁单后撤回 → 50350
        InboundRequestVo accepted = submitAndAccept(c, 20);
        asWa(c);
        assertThat(expectBiz(() -> inboundRequestService.withdrawByWa(accepted.getId(), withdrawDto("晚了"), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_NOT_WITHDRAWABLE);

        // 缺理由 → 40003
        InboundRequestVo vo3 = submitOne(c, 5);
        assertThat(expectBiz(() -> inboundRequestService.withdrawByWa(vo3.getId(), withdrawDto("  "), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);

        // 代建链单（source=WK_CREATED）无撤回入口 → 50330
        asWk(c);
        com.cangchu.document.dto.InboundRegisterDto legacy = new com.cangchu.document.dto.InboundRegisterDto();
        legacy.setWholesalerId(c.wholesalerId());
        legacy.setSkuId(c.skuId());
        legacy.setQty(7);
        InboundRequestVo wkDoc = inboundRequestService.registerByWk(legacy, c.wkUserId());
        asWa(c);
        assertThat(expectBiz(() -> inboundRequestService.withdrawByWa(wkDoc.getId(), withdrawDto("不适用"), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("L-2①：R1 撤回 → 库管站内信（多账号全发；content 含单号+理由零角色码；refId=申请单）；失败路径零通知")
    void withdrawNotifiesWk() {
        Ctx c = seedAll();
        long wk2 = seedRole(null, "WK", c.tenantId(), null, null);
        InboundRequestVo vo = submitOne(c, 10);

        // 缺理由失败路径 → 零撤回通知
        expectBiz(() -> inboundRequestService.withdrawByWa(vo.getId(), withdrawDto("  "), c.waUserId()));
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_INBOUND_WITHDRAWN)).isZero();

        inboundRequestService.withdrawByWa(vo.getId(), withdrawDto("填错件数"), c.waUserId());
        // 多账号全发（user_roles 推导先例，13 §1.4 同源）；受理锁单 50350 堵状态机，本通知补信息差
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_INBOUND_WITHDRAWN)).isEqualTo(1);
        assertThat(countNotifications(wk2, Notification.TYPE_INBOUND_WITHDRAWN)).isEqualTo(1);
        Notification n = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, c.wkUserId())
                .eq(Notification::getType, Notification.TYPE_INBOUND_WITHDRAWN));
        assertThat(n.getRefType()).isEqualTo(Notification.REF_INBOUND);
        assertThat(n.getRefId()).isEqualTo(vo.getId());
        assertThat(n.getContent()).contains(vo.getDocNo()).contains("填错件数").doesNotContain("WA");
        // 撤回零库存不变量保持
        assertZeroStock(c);
    }

    @Test
    @DisplayName("T1-ACC-01 受理：SUBMITTED→ACCEPTED 通知 WA，零库存；受理后仍可驳回（T4-W1 补，仍零库存）")
    void acceptLocksDoc() {
        Ctx c = seedAll();
        InboundRequestVo accepted = submitAndAccept(c, 15);
        assertThat(accepted.getStatus()).isEqualTo(InboundRequest.STATUS_ACCEPTED);
        assertThat(accepted.getWkUserId()).isEqualTo(c.wkUserId());
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_ACCEPTED)).isEqualTo(1);
        assertZeroStock(c);

        // P3b T4-W1（T1 收尾）：ACCEPTED→REJECTED 可达——受理后现场发现问题（如实收差异 >5%）可驳回，
        // 驳回通知沿用、全程零库存；驳回后不可再受理（红线回归见矩阵用例）
        InboundRequestVo rejected = inboundRequestService.rejectByWk(accepted.getId(),
                rejectDto("QTY", "实收差异超 5%，驳回重报", null), c.wkUserId());
        assertThat(rejected.getStatus()).isEqualTo(InboundRequest.STATUS_REJECTED);
        assertZeroStock(c);
    }

    @Test
    @DisplayName("T1-ACC-02 并发受理 CAS（虚拟线程）：双 WK 同抢一单，恰一人成功，败方 50330/50331")
    void concurrentAcceptCas() throws Exception {
        Ctx c = seedAll();
        long wk2 = seedRole(null, "WK", c.tenantId(), null, null);
        InboundRequestVo vo = submitOne(c, 10);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> f1 = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            Future<Object> f2 = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), wk2, "WK"));
                start.await();
                try {
                    return inboundRequestService.acceptByWk(vo.getId(), wk2);
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            start.countDown();
            Object r1 = f1.get(60, TimeUnit.SECONDS);
            Object r2 = f2.get(60, TimeUnit.SECONDS);

            Object winner = r1 instanceof InboundRequestVo ? r1 : r2;
            Object loser = r1 instanceof InboundRequestVo ? r2 : r1;
            assertThat(winner).isInstanceOf(InboundRequestVo.class);
            assertThat(loser).isInstanceOf(BizException.class);
            // 败方时序两态：CAS 输（50331）或重读已 ACCEPTED 撞矩阵（50330）
            assertThat(((BizException) loser).getErrorCode())
                    .isIn(ErrorCode.DOC_STATE_CAS_CONFLICT, ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        InboundRequest cur = inboundRequestMapper.selectById(vo.getId());
        assertThat(cur.getStatus()).isEqualTo(InboundRequest.STATUS_ACCEPTED);
        // 受理通知恰 1 条（败方事务回滚不重复发）
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_ACCEPTED)).isEqualTo(1);
        assertZeroStock(c);
    }

    @Test
    @DisplayName("T1-REJ-01 R2 驳回：reason 单选+remark 必填+附件≤5 落独立列，通知 WA，零库存；护栏 40001/40003")
    void rejectWithEvidence() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitOne(c, 10);
        asWk(c);
        // reason 非白名单 → 40001；缺 remark → 40003
        assertThat(expectBiz(() -> inboundRequestService.rejectByWk(vo.getId(),
                rejectDto("WHATEVER", "备注", null), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_001);
        assertThat(expectBiz(() -> inboundRequestService.rejectByWk(vo.getId(),
                rejectDto("QTY", " ", null), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);

        // N2 白名单：非本站上传地址 → 50340
        assertThat(expectBiz(() -> inboundRequestService.rejectByWk(vo.getId(),
                rejectDto("QTY", "备注", List.of("https://evil.example.com/x.jpg")), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.FILE_UPLOAD_INVALID);

        String att1 = "/files/202607/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg";
        String att2 = "/files/202607/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeef.png";
        InboundRequestVo rejected = inboundRequestService.rejectByWk(vo.getId(),
                rejectDto("QTY", "到货与申请不符", List.of(att1, att2)), c.wkUserId());
        assertThat(rejected.getStatus()).isEqualTo(InboundRequest.STATUS_REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("QTY");
        assertThat(rejected.getRejectRemark()).isEqualTo("到货与申请不符");
        assertThat(rejected.getRejectAttachments()).containsExactly(att1, att2);
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_REJECTED)).isEqualTo(1);
        assertZeroStock(c);

        // 驳回终态：再受理 → 50330（红线 REJECTED→ACCEPTED）
        assertThat(expectBiz(() -> inboundRequestService.acceptByWk(vo.getId(), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
    }

    // ======================================================================
    // 登记（5% 三点边界 + 时点）
    // ======================================================================

    @Test
    @DisplayName("T1-REG-01 5% 三点边界（申请1000）：4.9% 放行 / 5.0% 含等于放行 / 5.1% → 50351；差异非0缺备注 → 40003")
    void registerFivePercentBoundary() {
        Ctx c = seedAll();
        // 4.9%（951）放行
        InboundRequestVo a = submitAndAccept(c, 1000);
        asWk(c);
        InboundRequestVo ra = inboundRequestService.registerForwardByWk(a.getId(),
                registerDto(951, null, "少 49 件"), c.wkUserId());
        assertThat(ra.getStatus()).isEqualTo(InboundRequest.STATUS_CONFIRMED);
        assertThat(ra.getQty()).isEqualTo(951);
        assertThat(ra.getRequestedQty()).isEqualTo(1000); // 申请件数留痕不可变
        assertThat(qtyOf(c)).isEqualTo(951);

        // 5.0%（950）边界含等于放行（13 §1.2）
        InboundRequestVo b = submitAndAccept(c, 1000);
        asWk(c);
        InboundRequestVo rb = inboundRequestService.registerForwardByWk(b.getId(),
                registerDto(950, null, "少 50 件"), c.wkUserId());
        assertThat(rb.getQty()).isEqualTo(950);
        assertThat(qtyOf(c)).isEqualTo(951 + 950);

        // 5.1%（949）→ 50351，单据仍 ACCEPTED、库存不动
        InboundRequestVo d = submitAndAccept(c, 1000);
        asWk(c);
        assertThat(expectBiz(() -> inboundRequestService.registerForwardByWk(d.getId(),
                registerDto(949, null, "超界"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_QTY_DIFF_EXCEEDED);
        // 多收侧同界：1051 → 50351
        assertThat(expectBiz(() -> inboundRequestService.registerForwardByWk(d.getId(),
                registerDto(1051, null, "超界"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_QTY_DIFF_EXCEEDED);
        assertThat(inboundRequestMapper.selectById(d.getId()).getStatus())
                .isEqualTo(InboundRequest.STATUS_ACCEPTED);
        assertThat(qtyOf(c)).isEqualTo(951 + 950);

        // 差异非 0 缺备注 → 40003；差异 0 免备注放行
        assertThat(expectBiz(() -> inboundRequestService.registerForwardByWk(d.getId(),
                registerDto(999, null, null), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);
        InboundRequestVo rd = inboundRequestService.registerForwardByWk(d.getId(),
                registerDto(1000, null, null), c.wkUserId());
        assertThat(rd.getQty()).isEqualTo(1000);
        assertThat(rd.getRegisteredAt()).isNotNull();

        // INBOUND 流水 3 条与登记一一对应；通知 3 条
        assertThat(movements(c, StockMovement.TYPE_INBOUND))
                .extracting(StockMovement::getQty).containsExactlyInAnyOrder(951, 950, 1000);
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_REGISTERED)).isEqualTo(3);
        assertInvariant(c);
    }

    @Test
    @DisplayName("T1-REG-02 登记前置：SUBMITTED 直登 → 50330（必须经受理）；代建链单走本端点 → 50330；托盘覆写生效")
    void registerPreconditions() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitOne(c, 10);
        asWk(c);
        // SUBMITTED→CONFIRMED 红线：必须先受理
        assertThat(expectBiz(() -> inboundRequestService.registerForwardByWk(vo.getId(),
                registerDto(10, null, null), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 代建链单（PENDING_WA_CONFIRM）不得经正向端点绕行
        com.cangchu.document.dto.InboundRegisterDto legacy = new com.cangchu.document.dto.InboundRegisterDto();
        legacy.setWholesalerId(c.wholesalerId());
        legacy.setSkuId(c.skuId());
        legacy.setQty(7);
        InboundRequestVo wkDoc = inboundRequestService.registerByWk(legacy, c.wkUserId());
        assertThat(expectBiz(() -> inboundRequestService.registerForwardByWk(wkDoc.getId(),
                registerDto(7, null, null), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 受理后登记：WK 托盘覆写（申请未填 → 0；登记覆写 3）
        inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());
        InboundRequestVo reg = inboundRequestService.registerForwardByWk(vo.getId(),
                registerDto(10, 3, null), c.wkUserId());
        assertThat(reg.getPalletQty()).isEqualTo(3);
    }

    @Test
    @DisplayName("T1-PRT-01 打印非状态节点：登记前后均可，print_count 累加，状态不迁移")
    void printIsNotAStateNode() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitAndAccept(c, 10);
        asWk(c);
        InboundRequestVo p1 = inboundRequestService.printByWk(vo.getId(), c.wkUserId());
        assertThat(p1.getPrintedAt()).isNotNull();
        assertThat(p1.getPrintCount()).isEqualTo(1);
        assertThat(p1.getStatus()).isEqualTo(InboundRequest.STATUS_ACCEPTED); // 状态不动

        inboundRequestService.registerForwardByWk(vo.getId(), registerDto(10, null, null), c.wkUserId());
        InboundRequestVo p2 = inboundRequestService.printByWk(vo.getId(), c.wkUserId());
        assertThat(p2.getPrintCount()).isEqualTo(2); // 登记后补打
        assertThat(p2.getStatus()).isEqualTo(InboundRequest.STATUS_CONFIRMED);
    }

    // ======================================================================
    // R3 纠错封顶三态 + 护栏
    // ======================================================================

    @Test
    @DisplayName("T1-COR-01 改大补录：CORRECTION_IN qty=delta，biz_time=原 INBOUND、reversal_of_id 配对（D-4），单据 qty 覆写")
    void correctionIncrease() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitAcceptRegister(c, 100, 100);
        StockMovement original = movements(c, StockMovement.TYPE_INBOUND).get(0);

        asWk(c);
        InboundCorrectionVo corr = inboundCorrectionService.create(vo.getId(), corrDto(110, "复点多 10 件"), c.wkUserId());
        assertThat(corr.getStatus()).isEqualTo(InboundCorrection.STATUS_PENDING);
        assertThat(corr.getOldQty()).isEqualTo(100);
        assertThat(corr.getNewQty()).isEqualTo(110);
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_CORRECTION_PENDING)).isEqualTo(1);
        // 待审期间零库存影响
        assertThat(qtyOf(c)).isEqualTo(100);

        asTa(c);
        InboundCorrectionVo decided = inboundCorrectionService.decide(corr.getId(),
                decideDto(InboundCorrection.STATUS_APPROVED, null), c.taUserId());
        assertThat(decided.getStatus()).isEqualTo(InboundCorrection.STATUS_APPROVED);
        assertThat(decided.getAppliedQty()).isEqualTo(10);
        assertThat(decided.getShortfallQty()).isZero();
        assertThat(qtyOf(c)).isEqualTo(110);
        // 配对锚点（D-4，P4 按配对重算仓储费）
        List<StockMovement> ins = movements(c, StockMovement.TYPE_CORRECTION_IN);
        assertThat(ins).hasSize(1);
        assertThat(ins.get(0).getQty()).isEqualTo(10);
        assertThat(ins.get(0).getBizTime()).isEqualTo(original.getBizTime());
        assertThat(ins.get(0).getReversalOfId()).isEqualTo(original.getId());
        // 单据可见值与库存一致，原值留痕在纠错单
        assertThat(inboundRequestMapper.selectById(vo.getId()).getQty()).isEqualTo(110);
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_CORRECTION_DECIDED)).isEqualTo(1);
        assertInvariant(c);
    }

    @Test
    @DisplayName("T1-COR-02 改小部分售出：applied=min(|delta|,onhand) 封顶，CORRECTION_OUT+差额备注定责，库存不打负")
    void correctionDecreasePartialSold() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitAcceptRegister(c, 30, 30);
        deduct(c, 25); // 在库 5

        asWk(c);
        InboundCorrectionVo corr = inboundCorrectionService.create(vo.getId(), corrDto(10, "错录 30 实为 10"), c.wkUserId());
        asTa(c);
        InboundCorrectionVo decided = inboundCorrectionService.decide(corr.getId(),
                decideDto(InboundCorrection.STATUS_APPROVED, null), c.taUserId());

        // delta=-20，onhand=5 → applied=5，shortfall=15
        assertThat(decided.getAppliedQty()).isEqualTo(5);
        assertThat(decided.getShortfallQty()).isEqualTo(15);
        assertThat(decided.getRemark()).contains("15");
        assertThat(qtyOf(c)).isZero(); // 封顶不打负
        List<StockMovement> outs = movements(c, StockMovement.TYPE_CORRECTION_OUT);
        assertThat(outs).hasSize(1);
        assertThat(outs.get(0).getQty()).isEqualTo(5);
        StockMovement original = movements(c, StockMovement.TYPE_INBOUND).get(0);
        assertThat(outs.get(0).getBizTime()).isEqualTo(original.getBizTime());
        assertThat(outs.get(0).getReversalOfId()).isEqualTo(original.getId());
        assertThat(inboundRequestMapper.selectById(vo.getId()).getQty()).isEqualTo(10);
        assertInvariant(c);
    }

    @Test
    @DisplayName("T1-COR-03 售罄封顶：applied=0 不写流水不动库存，纠错单照常 APPROVED 留痕")
    void correctionDecreaseSoldOut() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitAcceptRegister(c, 30, 30);
        deduct(c, 30); // 售罄

        asWk(c);
        InboundCorrectionVo corr = inboundCorrectionService.create(vo.getId(), corrDto(10, "实收 10"), c.wkUserId());
        asTa(c);
        InboundCorrectionVo decided = inboundCorrectionService.decide(corr.getId(),
                decideDto(InboundCorrection.STATUS_APPROVED, null), c.taUserId());

        assertThat(decided.getStatus()).isEqualTo(InboundCorrection.STATUS_APPROVED);
        assertThat(decided.getAppliedQty()).isZero();
        assertThat(decided.getShortfallQty()).isEqualTo(20);
        assertThat(movements(c, StockMovement.TYPE_CORRECTION_OUT)).isEmpty();
        assertThat(qtyOf(c)).isZero();
        assertThat(inboundRequestMapper.selectById(vo.getId()).getQty()).isEqualTo(10);
        assertInvariant(c);
    }

    @Test
    @DisplayName("T1-COR-04 纠错护栏：同值 50354 / 超 24h 50352 / 在途防重 50353 / 驳回缺备注 40003 / 驳回零库存 / 代建链 50354")
    void correctionGuardrails() {
        Ctx c = seedAll();
        InboundRequestVo vo = submitAcceptRegister(c, 100, 100);
        asWk(c);
        // 与实登相同 → 50354
        assertThat(expectBiz(() -> inboundCorrectionService.create(vo.getId(), corrDto(100, "同值"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_CORRECTION_INVALID);
        // newQty<0 → 50354
        assertThat(expectBiz(() -> inboundCorrectionService.create(vo.getId(), corrDto(-1, "负数"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_CORRECTION_INVALID);

        // 在途防重：先建一张 PENDING，再建 → 50353
        InboundCorrectionVo first = inboundCorrectionService.create(vo.getId(), corrDto(90, "首张"), c.wkUserId());
        assertThat(expectBiz(() -> inboundCorrectionService.create(vo.getId(), corrDto(95, "第二张"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_CORRECTION_PENDING_EXISTS);

        // 驳回缺备注 → 40003；驳回成功零库存影响、pending 释放
        asTa(c);
        assertThat(expectBiz(() -> inboundCorrectionService.decide(first.getId(),
                decideDto(InboundCorrection.STATUS_REJECTED, " "), c.taUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);
        InboundCorrectionVo rejected = inboundCorrectionService.decide(first.getId(),
                decideDto(InboundCorrection.STATUS_REJECTED, "证据不足"), c.taUserId());
        assertThat(rejected.getStatus()).isEqualTo(InboundCorrection.STATUS_REJECTED);
        assertThat(rejected.getDecideRemark()).isEqualTo("证据不足");
        assertThat(qtyOf(c)).isEqualTo(100);
        assertThat(movements(c, StockMovement.TYPE_CORRECTION_IN)).isEmpty();
        assertThat(movements(c, StockMovement.TYPE_CORRECTION_OUT)).isEmpty();
        // 双裁 → 50331
        assertThat(expectBiz(() -> inboundCorrectionService.decide(first.getId(),
                decideDto(InboundCorrection.STATUS_APPROVED, null), c.taUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_CAS_CONFLICT);

        // 超 24h 窗口（回拨 registered_at，SQL 内比数据库时间）→ 50352
        inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, vo.getId())
                .set(InboundRequest::getRegisteredAt, LocalDateTime.now().minusHours(25)));
        asWk(c);
        assertThat(expectBiz(() -> inboundCorrectionService.create(vo.getId(), corrDto(90, "超窗"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_CORRECTION_WINDOW_CLOSED);

        // 代建链 CONFIRMED 单不入纠错（source 非 WA_SUBMIT）→ 50354
        com.cangchu.document.dto.InboundRegisterDto legacy = new com.cangchu.document.dto.InboundRegisterDto();
        legacy.setWholesalerId(c.wholesalerId());
        legacy.setSkuId(c.skuId());
        legacy.setQty(7);
        InboundRequestVo wkDoc = inboundRequestService.registerByWk(legacy, c.wkUserId());
        asWa(c);
        inboundRequestService.confirmByWa(wkDoc.getId(), c.waUserId());
        asWk(c);
        assertThat(expectBiz(() -> inboundCorrectionService.create(wkDoc.getId(), corrDto(5, "代建"), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_CORRECTION_INVALID);
    }

    // ======================================================================
    // 授权 / 越权 40x
    // ======================================================================

    @Test
    @DisplayName("T1-PERM-01 WE 授权三态：无 INBOUND_SUBMIT 位 42004 / 有位提交+撤自己单 OK / 有位撤他人单 42004")
    void wePermissionThreeStates() {
        Ctx c = seedAll();
        long weNoPerm = seedRole(null, "WE", c.tenantId(), c.wholesalerId(), "[\"PRICE_EDIT\"]");
        long weWithPerm = seedRole(null, "WE", c.tenantId(), c.wholesalerId(), "[\"INBOUND_SUBMIT\"]");

        // 无位 WE 提交 → 42004
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weNoPerm, "WE"));
        assertThat(expectBiz(() -> inboundRequestService.submitByWa(submitDto(c, 5), weNoPerm))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_004);

        // 有位 WE 提交成功，wa_user_id=提交人；可撤自己提交的单
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weWithPerm, "WE"));
        InboundRequestVo weDoc = inboundRequestService.submitByWa(submitDto(c, 5), weWithPerm).get(0);
        assertThat(weDoc.getWaUserId()).isEqualTo(weWithPerm);
        InboundRequestVo withdrawn = inboundRequestService.withdrawByWa(weDoc.getId(), withdrawDto("自撤"), weWithPerm);
        assertThat(withdrawn.getStatus()).isEqualTo(InboundRequest.STATUS_WITHDRAWN);

        // 有位 WE 撤 WA 提交的单 → 42004（D-5：仅可撤自己提交的）
        InboundRequestVo waDoc = submitOne(c, 8);
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weWithPerm, "WE"));
        assertThat(expectBiz(() -> inboundRequestService.withdrawByWa(waDoc.getId(), withdrawDto("越权"), weWithPerm))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_004);
        // WA 撤 WE 提交的单：WA 无提交人限制——先由 WE 再提一张验证
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weWithPerm, "WE"));
        InboundRequestVo weDoc2 = inboundRequestService.submitByWa(submitDto(c, 6), weWithPerm).get(0);
        asWa(c);
        assertThat(inboundRequestService.withdrawByWa(weDoc2.getId(), withdrawDto("管理员撤回"), c.waUserId())
                .getStatus()).isEqualTo(InboundRequest.STATUS_WITHDRAWN);
    }

    @Test
    @DisplayName("T1-PERM-02 越权矩阵：陌生人提交 42001 / 非 WK 受理 50273 / 非 TA 裁决 42001 / 他租户 WK 受理按不存在")
    void unauthorizedMatrix() {
        Ctx c = seedAll();
        Ctx other = seedAll();
        // 同租户但与商户无 WA/WE 绑定的用户（WK）提交 → 42001
        asWk(c);
        InboundSubmitDto d = submitDto(c, 5);
        assertThat(expectBiz(() -> inboundRequestService.submitByWa(d, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        // 他租户用户提交：TenantLine 兜底 → 商户按不存在（不泄露存在性）
        TenantContext.set(TenantContext.TenantInfo.of(other.tenantId(), other.waUserId(), "WA"));
        assertThat(expectBiz(() -> inboundRequestService.submitByWa(d, other.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_FOUND);

        InboundRequestVo vo = submitOne(c, 10);
        // WA 冒充受理 → 50273（仅本租户 WK）
        asWa(c);
        assertThat(expectBiz(() -> inboundRequestService.acceptByWk(vo.getId(), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_OPERATOR_NOT_WK);
        // 他租户 WK 受理：TenantLine 兜底 → 按不存在（不泄露存在性）
        TenantContext.set(TenantContext.TenantInfo.of(other.tenantId(), other.wkUserId(), "WK"));
        assertThat(expectBiz(() -> inboundRequestService.acceptByWk(vo.getId(), other.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_NOT_FOUND);

        // 纠错：WK 冒充 TA 裁决 → 42001
        InboundRequestVo reg = submitAcceptRegister(c, 100, 100);
        asWk(c);
        InboundCorrectionVo corr = inboundCorrectionService.create(reg.getId(), corrDto(90, "少 10"), c.wkUserId());
        assertThat(expectBiz(() -> inboundCorrectionService.decide(corr.getId(),
                decideDto(InboundCorrection.STATUS_APPROVED, null), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        // WA 冒充 WK 发起纠错 → 50273
        asWa(c);
        assertThat(expectBiz(() -> inboundCorrectionService.create(reg.getId(), corrDto(95, "越权"), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INBOUND_OPERATOR_NOT_WK);
    }

    @Test
    @DisplayName("T1-SKU-01 SKU 列表放宽回归：WK 只读列表 200；WK 写路径仍 42101；WA/TA 读不受影响（13 §5.1）")
    void skuListWkRelaxRegression() {
        Ctx c = seedAll();
        // WK 拉本租户商户 SKU 列表 → 200（上线检查单 §5-4 遗留消除）
        asWk(c);
        assertThat(skuService.listByWholesaler(c.wholesalerId(), c.wkUserId()))
                .extracting(sku -> sku.getId()).contains(c.skuId());
        // WK 写路径不放宽 → 42101
        SkuCreateDto createDto = new SkuCreateDto();
        createDto.setName("WK 越权品");
        createDto.setUnitPrice(new BigDecimal("1.00"));
        assertThat(expectBiz(() -> skuService.createSku(c.wholesalerId(), createDto, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_TENANT_001);
        // WA / TA 读回归
        asWa(c);
        assertThat(skuService.listByWholesaler(c.wholesalerId(), c.waUserId())).isNotEmpty();
        asTa(c);
        assertThat(skuService.listByWholesaler(c.wholesalerId(), c.taUserId())).isNotEmpty();
        // 他租户 WK → 42101（放宽仅限本租户）
        Ctx other = seedAll();
        TenantContext.set(TenantContext.TenantInfo.of(other.tenantId(), other.wkUserId(), "WK"));
        assertThat(expectBiz(() -> skuService.listByWholesaler(c.wholesalerId(), other.wkUserId()))
                .getErrorCode()).isIn(ErrorCode.PERMISSION_TENANT_001, ErrorCode.WHOLESALER_NOT_FOUND);
    }

    // ======================================================================
    // R13 未结计数扩展
    // ======================================================================

    @Test
    @DisplayName("T1-R13-01 未结枚举扩展：SUBMITTED/ACCEPTED 计入、WITHDRAWN/REJECTED/CONFIRMED 不计、在途纠错 PENDING 计入")
    void openCountExtension() {
        Ctx c = seedAll();
        asWa(c);
        List<InboundRequestVo> docs = inboundRequestService.submitByWa(submitDto(c, 10, 20, 30), c.waUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(3);

        // 撤 1 → 2
        inboundRequestService.withdrawByWa(docs.get(0).getId(), withdrawDto("不要了"), c.waUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(2);
        // 驳 1 → 1
        asWk(c);
        inboundRequestService.rejectByWk(docs.get(1).getId(), rejectDto("OTHER", "驳回", null), c.wkUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        // 受理仍未结 → 1
        inboundRequestService.acceptByWk(docs.get(2).getId(), c.wkUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        // 登记已结 → 0
        inboundRequestService.registerForwardByWk(docs.get(2).getId(), registerDto(30, null, null), c.wkUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
        // 在途纠错 PENDING → 1；裁决 → 0（13 §7.1 终版枚举）
        InboundCorrectionVo corr = inboundCorrectionService.create(docs.get(2).getId(), corrDto(29, "复点"), c.wkUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        asTa(c);
        inboundCorrectionService.decide(corr.getId(), decideDto(InboundCorrection.STATUS_REJECTED, "不必"), c.taUserId());
        assertThat(inboundRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
    }

    // ======================================================================
    // 队列与列表
    // ======================================================================

    @Test
    @DisplayName("T1-LIST-01 列表过滤：WA 侧 source 过滤两链分流；WK 待受理队列升序（先到先受理）；纠错列表 TA/WK 可见")
    void listFilters() {
        Ctx c = seedAll();
        // 一张代建 + 两张正向
        asWk(c);
        com.cangchu.document.dto.InboundRegisterDto legacy = new com.cangchu.document.dto.InboundRegisterDto();
        legacy.setWholesalerId(c.wholesalerId());
        legacy.setSkuId(c.skuId());
        legacy.setQty(7);
        inboundRequestService.registerByWk(legacy, c.wkUserId());
        asWa(c);
        List<InboundRequestVo> submitted = inboundRequestService.submitByWa(submitDto(c, 10, 20), c.waUserId());

        // WA 侧 source 过滤
        var mine = inboundRequestService.listForWa(c.waUserId(), null, InboundRequest.SOURCE_WA_SUBMIT, 1, 20);
        assertThat(mine.getRecords()).hasSize(2)
                .allMatch(v -> InboundRequest.SOURCE_WA_SUBMIT.equals(v.getSource()));
        var proxy = inboundRequestService.listForWa(c.waUserId(), null, InboundRequest.SOURCE_WK_CREATED, 1, 20);
        assertThat(proxy.getRecords()).hasSize(1);

        // WK 待受理队列（status=SUBMITTED）：仅正向 2 张，创建升序
        asWk(c);
        List<InboundRequestVo> queue = inboundRequestService.listByTenant(
                c.tenantId(), c.wholesalerId(), InboundRequest.STATUS_SUBMITTED);
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getId()).isEqualTo(submitted.get(0).getId());

        // 纠错列表 WK/TA 可见、WA 拒绝
        inboundRequestService.acceptByWk(submitted.get(0).getId(), c.wkUserId());
        inboundRequestService.registerForwardByWk(submitted.get(0).getId(), registerDto(10, null, null), c.wkUserId());
        inboundCorrectionService.create(submitted.get(0).getId(), corrDto(9, "复点"), c.wkUserId());
        assertThat(inboundCorrectionService.listByTenant(c.tenantId(), c.wkUserId(),
                InboundCorrection.STATUS_PENDING, 1, 20).getRecords()).hasSize(1);
        asTa(c);
        assertThat(inboundCorrectionService.listByTenant(c.tenantId(), c.taUserId(), null, 1, 20)
                .getRecords()).hasSize(1);
        asWa(c);
        assertThat(expectBiz(() -> inboundCorrectionService.listByTenant(c.tenantId(), c.waUserId(), null, 1, 20))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
    }
}
