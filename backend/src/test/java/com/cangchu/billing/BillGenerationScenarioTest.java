package com.cangchu.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.BillItem;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.mapper.BillItemMapper;
import com.cangchu.billing.mapper.BillMapper;
import com.cangchu.billing.mapper.BillingRuleMapper;
import com.cangchu.billing.service.BillingReplayService;
import com.cangchu.billing.service.BillingService;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 W3 月度账单生成场景测试（14 §3.2/§3.4/§1.5，任务关卡）：
 * 生成金额=回放金额 / STORAGE 行 / BL- 单号格式 / 幂等（重跑+并发双跑恰一单）/
 * 跨月 ADJUSTMENT（历史账单不动）/ STOCKTAKE_IMPACT 恒 0 / 应收 0 直落 PAID /
 * OFFLINE 生成直落 DISPUTED / 无规则手动补跑 50380 / 通知 ST。
 *
 * <p>测试基建沿 {@link DailySnapshotScenarioTest}（HTTP 主链 + mapper seed 混合）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillGenerationScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private BillingRuleMapper billingRuleMapper;
    @Autowired
    private BillMapper billMapper;
    @Autowired
    private BillItemMapper billItemMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private BillingService billingService;
    @Autowired
    private BillingReplayService billingReplayService;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", ((System.nanoTime() >> 13) & 0x7FFFFFFF) % 100000);
    private static final String P_EMP =
            "15" + String.format("%05d", ((System.nanoTime() >> 7) & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

    // ==================== helpers（DailySnapshotScenarioTest 同构） ====================

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private LoginVo registerAndLogin(String phone, String password, String role, String inviteCode) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        if (inviteCode != null) dto.setInviteCode(inviteCode);
        R<LoginVo> body = restTemplate.exchange(base + "/api/v1/account/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData();
    }

    private record TaContext(String token, Long tenantId) {}

    private TaContext registerTaActive() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA", null).getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("账单仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省宁波市江北区");
        R<Map<String, Object>> body = restTemplate.exchange(base + "/api/v1/tenant/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        TenantContext.clear();
        return new TaContext(token, tenantId);
    }

    private String registerEmployee(TaContext ta, String role) {
        Map<String, Object> inviteDto = new LinkedHashMap<>();
        inviteDto.put("role", role);
        inviteDto.put("maxUses", 1);
        inviteDto.put("expiresInDays", 7);
        R<Map<String, Object>> invite = restTemplate.exchange(base + "/api/v1/tenant/employee-invites",
                HttpMethod.POST, new HttpEntity<>(inviteDto, bearer(ta.token())), MAP).getBody();
        assertThat(invite).isNotNull();
        assertThat(invite.getCode()).as("TA 生 %s 码", role).isEqualTo(0);
        String code = invite.getData().get("code").toString();
        return registerAndLogin(uniquePhone(P_EMP), "EmpPass123", role, code).getToken();
    }

    private Long seedWholesaler(TaContext ta, String status) {
        TenantContext.clear();
        Wholesaler ws = new Wholesaler();
        ws.setId(snowflakeIdUtil.nextId());
        ws.setTenantId(ta.tenantId());
        ws.setName("账单商户-" + ws.getId());
        ws.setStatus(status);
        ws.setOwnerUserId(snowflakeIdUtil.nextId());
        wholesalerMapper.insert(ws);
        return ws.getId();
    }

    private void seedMovement(TaContext ta, Long wsId, Long skuId, String type, int qty,
                              LocalDate bizDate, int palletDelta, LocalDate createdDate) {
        TenantContext.clear();
        StockMovement m = new StockMovement();
        m.setId(snowflakeIdUtil.nextId());
        m.setTenantId(ta.tenantId());
        m.setWholesalerId(wsId);
        m.setSkuId(skuId);
        m.setType(type);
        m.setQty(qty);
        m.setBizTime(bizDate.atTime(10, 0));
        m.setPalletDelta(palletDelta);
        m.setCreatedAt(createdDate.atTime(10, 0));
        stockMovementMapper.insert(m);
    }

    private void seedRule(TaContext ta, LocalDate from, LocalDate to, String priceQty, int version) {
        TenantContext.clear();
        BillingRule rule = new BillingRule();
        rule.setId(snowflakeIdUtil.nextId());
        rule.setTenantId(ta.tenantId());
        rule.setQtyEnabled(1);
        rule.setPalletEnabled(0);
        rule.setPricePerQtyDay(new BigDecimal(priceQty));
        rule.setMinCharge(BigDecimal.ZERO);
        rule.setEffectiveFrom(from);
        rule.setEffectiveTo(to);
        rule.setVersion(version);
        billingRuleMapper.insert(rule);
    }

    private List<BillItem> itemsOf(Long billId) {
        TenantContext.clear();
        return billItemMapper.selectList(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, billId)
                .orderByAsc(BillItem::getId));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("GEN-01 生成金额=回放金额：STORAGE 行逐项一致 + BL- 单号格式 + DRAFT 落点 + 通知 ST")
    void gen01_amountEqualsReplayAndBillNoFormat() {
        TaContext ta = registerTaActive();
        registerEmployee(ta, "ST"); // ST 收 BILL_GENERATED
        Long ws = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(1).atDay(1), null, "1.5000", 1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, month.atDay(5), 2, month.atDay(5));

        Bill bill = billingService.generateForPair(ta.tenantId(), ws, month);
        assertThat(bill).isNotNull();

        MonthlyReplayVo replay = billingReplayService.replayMonthly(ta.tenantId(), ws, month);
        // 关卡①：账单生成金额 = 回放金额（恒等式 total=subtotal+adjust）
        assertThat(bill.getSubtotalAmount()).isEqualByComparingTo(replay.getSubtotal());
        assertThat(bill.getAdjustAmount()).isEqualByComparingTo("0.00");
        assertThat(bill.getTotalAmount())
                .isEqualByComparingTo(bill.getSubtotalAmount().add(bill.getAdjustAmount()));
        long expectedQtyDays = 10L * (month.lengthOfMonth() - 5); // 入库次日起算
        assertThat(bill.getSubtotalAmount())
                .isEqualByComparingTo(new BigDecimal("1.5000")
                        .multiply(BigDecimal.valueOf(expectedQtyDays))
                        .setScale(2, java.math.RoundingMode.HALF_UP));

        // 关卡②：BL- 单号格式 BL-{简码归一}-W{wholesalerId}-{yyyyMM}（无日序列）
        String yyyyMM = month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        assertThat(bill.getBillNo()).matches("^BL-[A-Za-z0-9]{1,6}-W" + ws + "-" + yyyyMM + "$");
        assertThat(bill.getStatus()).isEqualTo(Bill.STATUS_DRAFT);
        assertThat(bill.getPeriodStart()).isEqualTo(month.atDay(1));
        assertThat(bill.getPeriodEnd()).isEqualTo(month.atEndOfMonth());

        // STORAGE 行与回放行数一致、金额一致
        List<BillItem> items = itemsOf(bill.getId());
        List<BillItem> storage = items.stream()
                .filter(i -> BillItem.TYPE_STORAGE.equals(i.getItemType())).toList();
        assertThat(storage).hasSize(replay.getLines().size());
        assertThat(storage.get(0).getAmount()).isEqualByComparingTo(replay.getLines().get(0).getAmount());
        assertThat(storage.get(0).getSkuId()).isEqualTo(sku);

        // 通知 ST（BILL_GENERATED，手动路径经 generateForTenant 才发；Job 路径此处直驱 pair 不发）——
        // 经 Job 体验证：重跑全量 Job 幂等不再新生成
        TenantContext.clear();
        long before = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, ta.tenantId())
                .eq(Notification::getType, Notification.TYPE_BILL_GENERATED));
        assertThat(before).isZero();
    }

    @Test
    @DisplayName("GEN-02 幂等：重跑返回既有（不重复）+ 并发双跑恰一单（uk_bill_idempotent 兜底）")
    void gen02_idempotentAndConcurrent() throws Exception {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(1).atDay(1), null, "1.0000", 1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 8, month.atDay(3), 0, month.atDay(3));

        Bill first = billingService.generateForPair(ta.tenantId(), ws, month);
        assertThat(first).isNotNull();
        // 重跑：先查后写返回 null（既有单不动）
        assertThat(billingService.generateForPair(ta.tenantId(), ws, month)).isNull();
        TenantContext.clear();
        assertThat(billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWholesalerId, ws))).isEqualTo(1);

        // 并发双跑恰一单（虚拟线程，另一商户）
        Long ws2 = seedWholesaler(ta, "ACTIVE");
        seedMovement(ta, ws2, sku, StockMovement.TYPE_INBOUND, 6, month.atDay(4), 0, month.atDay(4));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable task = () -> {
            ready.countDown();
            try {
                go.await();
                billingService.generateForPair(ta.tenantId(), ws2, month);
            } catch (Exception ignored) {
                // 并发败方允许异常（幂等键兜底），只断言结果恰一单
            } finally {
                TenantContext.clear();
            }
        };
        Thread t1 = Thread.ofVirtual().start(task);
        Thread t2 = Thread.ofVirtual().start(task);
        ready.await();
        go.countDown();
        t1.join();
        t2.join();
        TenantContext.clear();
        assertThat(billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWholesalerId, ws2)))
                .as("并发双跑恰一单")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GEN-03 跨月锚点差额：ADJUSTMENT 行金额=影子重算差，历史账单一字不动")
    void gen03_crossMonthAdjustment() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        YearMonth period = YearMonth.now().minusMonths(1);
        YearMonth affected = period.minusMonths(1);
        int lenAffected = affected.lengthOfMonth();
        seedRule(ta, affected.atDay(1), null, "1.0000", 1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, affected.atDay(5), 0, affected.atDay(5));

        // 先出历史月 M' 账单（以库内回读值为基准，DATETIME 截断口径一致）
        Bill generated = billingService.generateForPair(ta.tenantId(), ws, affected);
        assertThat(generated).isNotNull();
        TenantContext.clear();
        Bill history = billMapper.selectById(generated.getId());
        BigDecimal historySubtotal = history.getSubtotalAmount();
        assertThat(historySubtotal).isEqualByComparingTo(BigDecimal.valueOf(10L * (lenAffected - 5)));
        LocalDateTime historyUpdatedAt = history.getUpdatedAt();

        // 本期落库一条纠错补录，锚点回溯 M' 5 日 +5 件
        seedMovement(ta, ws, sku, StockMovement.TYPE_CORRECTION_IN, 5, affected.atDay(5), 0, period.atDay(10));

        Bill bill = billingService.generateForPair(ta.tenantId(), ws, period);
        assertThat(bill).isNotNull();
        List<BillItem> adjustments = itemsOf(bill.getId()).stream()
                .filter(i -> BillItem.TYPE_ADJUSTMENT.equals(i.getItemType())).toList();
        assertThat(adjustments).hasSize(1);
        // 关卡：差额 = 影子重算差 = +5 件 ×(len−5) 天 ×1 元
        assertThat(adjustments.get(0).getAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5L * (lenAffected - 5)));
        assertThat(adjustments.get(0).getDescription()).contains(affected.toString()).contains("回溯差额");
        assertThat(bill.getAdjustAmount()).isEqualByComparingTo(adjustments.get(0).getAmount());
        assertThat(bill.getTotalAmount())
                .isEqualByComparingTo(bill.getSubtotalAmount().add(bill.getAdjustAmount()));

        // 历史账单不重算：三金额与 updated_at 均不变（14 §8「已出账月不可变」）
        TenantContext.clear();
        Bill historyAfter = billMapper.selectById(history.getId());
        assertThat(historyAfter.getSubtotalAmount()).isEqualByComparingTo(historySubtotal);
        assertThat(historyAfter.getTotalAmount()).isEqualByComparingTo(history.getTotalAmount());
        assertThat(historyAfter.getUpdatedAt()).isEqualTo(historyUpdatedAt);
        assertThat(itemsOf(history.getId())).allMatch(i -> !BillItem.TYPE_ADJUSTMENT.equals(i.getItemType()));
    }

    @Test
    @DisplayName("GEN-04 应收 0 仍生成、直接 PAID（同日入出零件·天）+ STOCKTAKE_IMPACT 行恒 0")
    void gen04_zeroBillPaidAndStocktakeImpact() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(1).atDay(1), null, "2.0000", 1);
        // 同日入出（05 §1.2：同一天入库又出库不产生费用）+ 一条盘盈一条盘亏（同抵消）
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, month.atDay(5), 0, month.atDay(5));
        seedMovement(ta, ws, sku, StockMovement.TYPE_OUTBOUND, 10, month.atDay(5), 0, month.atDay(5));
        seedMovement(ta, ws, sku, StockMovement.TYPE_GAIN, 3, month.atDay(10), 0, month.atDay(10));
        seedMovement(ta, ws, sku, StockMovement.TYPE_LOSS, 3, month.atDay(10), 0, month.atDay(10));

        Bill bill = billingService.generateForPair(ta.tenantId(), ws, month);
        assertThat(bill).isNotNull();
        // 盘盈盘亏同日对消 → 全月 0 件·天，应收 0 直接已结清（04 §3.4）
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("0.00");
        assertThat(bill.getStatus()).isEqualTo(Bill.STATUS_PAID);

        // STOCKTAKE_IMPACT：每条 GAIN/LOSS 一行、amount 恒 0、纯展示文案（US-WK-03）
        List<BillItem> impacts = itemsOf(bill.getId()).stream()
                .filter(i -> BillItem.TYPE_STOCKTAKE_IMPACT.equals(i.getItemType())).toList();
        assertThat(impacts).hasSize(2);
        assertThat(impacts).allMatch(i -> i.getAmount().signum() == 0);
        assertThat(impacts).anyMatch(i -> i.getDescription().contains("盘盈 +3 件")
                && i.getDescription().contains("次日起算"));
        assertThat(impacts).anyMatch(i -> i.getDescription().contains("盘亏 −3 件")
                && i.getDescription().contains("当日截止"));
    }

    @Test
    @DisplayName("GEN-05 商户 OFFLINE 生成直落 DISPUTED（R14 联动位）；无出账对象不生成")
    void gen05_offlineWholesalerDisputedAndNoActivitySkipped() {
        TaContext ta = registerTaActive();
        Long offline = seedWholesaler(ta, "OFFLINE");
        Long idle = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(1).atDay(1), null, "1.0000", 1);
        seedMovement(ta, offline, sku, StockMovement.TYPE_INBOUND, 10, month.atDay(5), 0, month.atDay(5));
        // idle 商户仅有本月流水（上月无流水/无期初/无差额 → 不出上月账）
        seedMovement(ta, idle, sku, StockMovement.TYPE_INBOUND, 10, LocalDate.now(), 0, LocalDate.now());

        Bill disputed = billingService.generateForPair(ta.tenantId(), offline, month);
        assertThat(disputed).isNotNull();
        assertThat(disputed.getStatus()).isEqualTo(Bill.STATUS_DISPUTED);
        assertThat(disputed.getTotalAmount().signum()).isPositive();

        assertThat(billingService.generateForPair(ta.tenantId(), idle, month))
                .as("上月无流水∧无期初∧无差额 → 非出账对象")
                .isNull();
    }

    @Test
    @DisplayName("GEN-06 手动补跑端点：无规则 50380；非过去月 40001；正常生成并通知 ST（幂等重跑 existing）")
    void gen06_manualGenerateEndpoint() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST");
        YearMonth month = YearMonth.now().minusMonths(1);

        // 无规则 → 50380
        R<Map<String, Object>> noRule = postGenerate(st, month.toString());
        assertThat(noRule).isNotNull();
        assertThat(noRule.getCode()).isEqualTo(50380);

        seedRule(ta, month.minusMonths(1).atDay(1), null, "1.0000", 1);
        // 当前月未结束 → 40001
        R<Map<String, Object>> current = postGenerate(st, YearMonth.now().toString());
        assertThat(current).isNotNull();
        assertThat(current.getCode()).isEqualTo(40001);

        Long ws = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 5, month.atDay(2), 0, month.atDay(2));
        R<Map<String, Object>> ok = postGenerate(st, month.toString());
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("generated")).isEqualTo(1);

        // 幂等重跑：existing=1、不再新生成
        R<Map<String, Object>> rerun = postGenerate(st, month.toString());
        assertThat(rerun).isNotNull();
        assertThat(rerun.getCode()).isEqualTo(0);
        assertThat(rerun.getData().get("generated")).isEqualTo(0);
        assertThat(rerun.getData().get("existing")).isEqualTo(1);

        // 通知 ST 全员（BILL_GENERATED，「共 N 张」文案）
        TenantContext.clear();
        List<Notification> notices = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, ta.tenantId())
                .eq(Notification::getType, Notification.TYPE_BILL_GENERATED));
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getContent()).contains("共 1 张");
    }

    @Test
    @DisplayName("GEN-07 Job 体 generateMonthlyBills：无规则租户跳过；有规则租户出账+通知；重跑幂等")
    void gen07_monthlyJobBody() {
        TaContext ta = registerTaActive();
        registerEmployee(ta, "ST");
        Long ws = seedWholesaler(ta, "ACTIVE");
        Long sku = snowflakeIdUtil.nextId();
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(1).atDay(1), null, "1.0000", 1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 4, month.atDay(6), 0, month.atDay(6));

        billingService.generateMonthlyBills(month);
        TenantContext.clear();
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWholesalerId, ws));
        assertThat(bills).hasSize(1);
        long notices = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, ta.tenantId())
                .eq(Notification::getType, Notification.TYPE_BILL_GENERATED));
        assertThat(notices).isEqualTo(1);

        // Job 重跑：幂等（不再新生成、不再重复通知）
        billingService.generateMonthlyBills(month);
        TenantContext.clear();
        assertThat(billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWholesalerId, ws))).isEqualTo(1);
        assertThat(notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, ta.tenantId())
                .eq(Notification::getType, Notification.TYPE_BILL_GENERATED))).isEqualTo(1);
    }

    private R<Map<String, Object>> postGenerate(String token, String month) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("month", month);
        return restTemplate.exchange(base + "/api/v1/tenant/st/bills/generate",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }
}
