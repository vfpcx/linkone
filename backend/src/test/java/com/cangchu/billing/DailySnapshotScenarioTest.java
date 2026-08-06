package com.cangchu.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.entity.DailySnapshot;
import com.cangchu.billing.mapper.BillingRuleMapper;
import com.cangchu.billing.mapper.DailySnapshotMapper;
import com.cangchu.billing.service.BillingReplayService;
import com.cangchu.billing.service.DailySnapshotService;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.billing.vo.SnapshotRunResultVo;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.entity.Inventory;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.InventoryMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
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
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 W2 每日快照/对账场景测试（14 §1.2/§1.5/§1.6 G11–G12 + 任务关卡）：
 * 快照幂等重跑（行级相等）/ 漏跑回补 / 快照≠回放以回放为准覆写 / 对账哨兵触发（人工造不一致）/
 * 跨月锚点差额 ADJUSTMENT（历史账单不重算，差额=影子重算差）/ 无规则=零账 /
 * 下钻两维端点 + recalc 端点（含 ST 放行、WE 42004、WK 42001、跨租户 50230、参数校验）。
 *
 * <p>测试基建沿 {@link BillingRuleScenarioTest}（HTTP 主链 + mapper seed 混合）；
 * 流水/规则按既定锚点口径直接 mapper 造数（回放引擎只消费锚点字段）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DailySnapshotScenarioTest {

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
    private InventoryMapper inventoryMapper;
    @Autowired
    private BillingRuleMapper billingRuleMapper;
    @Autowired
    private DailySnapshotMapper dailySnapshotMapper;
    @Autowired
    private DailySnapshotService dailySnapshotService;
    @Autowired
    private BillingReplayService billingReplayService;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", ((System.nanoTime() >> 11) & 0x7FFFFFFF) % 100000);
    private static final String P_EMP =
            "15" + String.format("%05d", ((System.nanoTime() >> 5) & 0x7FFFFFFF) % 100000);
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
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST = new ParameterizedTypeReference<>() {};

    // ==================== helpers（BillingRuleScenarioTest 同构） ====================

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
        dto.setName("快照仓-" + phone);
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

    private record WaContext(String token, Long wholesalerId) {}

    /** 完整入驻一个 WA（注册 → 自助申请 → TA 通过），供 WE 生码/WA 越权用例。 */
    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone("16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000));
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA", null);
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "快照商户-" + phone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(reg.getToken())), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();
        Map<String, Object> auditDto = Map.of("action", "APPROVED", "remark", "P4 W2 测试放行");
        R<Map<String, Object>> approved = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        return new WaContext(reg.getToken(), Long.parseLong(approved.getData().get("wholesalerId").toString()));
    }

    /** WA 生 WE 码 → 凭码注册 WE，返回其 token（BillingRuleScenarioTest 同构）。 */
    private String registerWe(WaContext wa) {
        Map<String, Object> weInvite = new LinkedHashMap<>();
        weInvite.put("maxUses", 1);
        weInvite.put("expireDays", 7);
        R<Map<String, Object>> weInviteRes = restTemplate.exchange(
                base + "/api/v1/wholesaler/employee-invites",
                HttpMethod.POST, new HttpEntity<>(weInvite, bearer(wa.token())), MAP).getBody();
        assertThat(weInviteRes).isNotNull();
        assertThat(weInviteRes.getCode()).as("WA 生 WE 码").isEqualTo(0);
        String weCode = weInviteRes.getData().get("code").toString();
        return registerAndLogin(uniquePhone(P_EMP), "WePass123", "TA" /* 入口 role 被码覆盖 */, weCode).getToken();
    }

    /** mapper 直造 ACTIVE 商户（本测试不走 WA 入驻主链，快照域只需归属行在位） */
    private Long seedWholesaler(TaContext ta) {
        TenantContext.clear();
        Wholesaler ws = new Wholesaler();
        ws.setId(snowflakeIdUtil.nextId());
        ws.setTenantId(ta.tenantId());
        ws.setName("快照商户-" + ws.getId());
        ws.setStatus("ACTIVE");
        ws.setOwnerUserId(snowflakeIdUtil.nextId());
        wholesalerMapper.insert(ws);
        return ws.getId();
    }

    /** mapper 直造流水（回放引擎消费锚点字段：type/qty/biz_time/pallet_delta/reversal_of_id） */
    private StockMovement seedMovement(TaContext ta, Long wsId, Long skuId, String type, int qty,
                                       LocalDate bizDate, int palletDelta, Long reversalOfId,
                                       LocalDate createdDate) {
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
        m.setReversalOfId(reversalOfId);
        m.setCreatedAt(createdDate.atTime(10, 0));
        stockMovementMapper.insert(m);
        return m;
    }

    /** mapper 直造与流水净值一致的库存行（对账哨兵绿基线） */
    private Inventory seedInventory(TaContext ta, Long wsId, Long skuId, int qty, int palletQty) {
        TenantContext.clear();
        Inventory inv = new Inventory();
        inv.setId(snowflakeIdUtil.nextId());
        inv.setTenantId(ta.tenantId());
        inv.setWholesalerId(wsId);
        inv.setSkuId(skuId);
        inv.setQty(qty);
        inv.setPalletQty(palletQty);
        inventoryMapper.insert(inv);
        return inv;
    }

    /** mapper 直造历史生效规则（HTTP 保存只能 from=今日，历史段须直造——mapper seed 先例） */
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

    private List<DailySnapshot> snapshotsOf(Long wsId, LocalDate date) {
        TenantContext.clear();
        return dailySnapshotMapper.selectList(new LambdaQueryWrapper<DailySnapshot>()
                .eq(DailySnapshot::getWholesalerId, wsId)
                .eq(DailySnapshot::getSnapshotDate, date)
                .orderByAsc(DailySnapshot::getSkuId));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("SNAP-01 G11 快照幂等重跑：同日重复覆写行级相等（id/值均不变、不重复落行）")
    void snap01_idempotentRebuild() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, yesterday.minusDays(5), 2, null, yesterday.minusDays(5));
        seedMovement(ta, ws, sku, StockMovement.TYPE_OUTBOUND, 3, yesterday.minusDays(2), 0, null, yesterday.minusDays(2));

        int rows1 = dailySnapshotService.rebuildSnapshotForDate(ta.tenantId(), ws, yesterday);
        List<DailySnapshot> first = snapshotsOf(ws, yesterday);
        assertThat(rows1).isEqualTo(1);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getQty()).isEqualTo(7);       // 10−3（出库日次日不计）
        assertThat(first.get(0).getPalletQty()).isEqualTo(2);

        int rows2 = dailySnapshotService.rebuildSnapshotForDate(ta.tenantId(), ws, yesterday);
        List<DailySnapshot> second = snapshotsOf(ws, yesterday);
        assertThat(rows2).isEqualTo(1);
        assertThat(second).hasSize(1);
        // 行级相等：id 不变（未删插）、值不变
        assertThat(second.get(0).getId()).isEqualTo(first.get(0).getId());
        assertThat(second.get(0).getQty()).isEqualTo(7);
        assertThat(second.get(0).getPalletQty()).isEqualTo(2);
    }

    @Test
    @DisplayName("SNAP-02 快照≠回放（人工篡改/残留行）→ 以回放为准覆写与删除")
    void snap02_replayWinsOverTamperedSnapshot() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, yesterday.minusDays(3), 1, null, yesterday.minusDays(3));
        dailySnapshotService.rebuildSnapshotForDate(ta.tenantId(), ws, yesterday);

        // 篡改快照值 + 伪造一条回放不存在的 SKU 残留行
        DailySnapshot row = snapshotsOf(ws, yesterday).get(0);
        row.setQty(999);
        dailySnapshotMapper.updateById(row);
        DailySnapshot ghost = new DailySnapshot();
        ghost.setId(snowflakeIdUtil.nextId());
        ghost.setTenantId(ta.tenantId());
        ghost.setWholesalerId(ws);
        ghost.setSkuId(snowflakeIdUtil.nextId());
        ghost.setSnapshotDate(yesterday);
        ghost.setQty(5);
        ghost.setPalletQty(0);
        dailySnapshotMapper.insert(ghost);

        dailySnapshotService.rebuildSnapshotForDate(ta.tenantId(), ws, yesterday);
        List<DailySnapshot> after = snapshotsOf(ws, yesterday);
        assertThat(after).hasSize(1);                    // 残留行被删（回放为 0 无此 SKU）
        assertThat(after.get(0).getSkuId()).isEqualTo(sku);
        assertThat(after.get(0).getQty()).isEqualTo(10); // 篡改被回放覆写
    }

    @Test
    @DisplayName("SNAP-03 G11 Job 直驱：昨日快照 + 整日缺失回补（窗口 ≤7 日）")
    void snap03_jobRunAndBackfill() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate gapDay = yesterday.minusDays(1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 6, yesterday.minusDays(4), 1, null, yesterday.minusDays(4));
        seedInventory(ta, ws, sku, 6, 1);

        // 确保窗口内 gapDay 整日无行（模拟漏跑）
        TenantContext.clear();
        dailySnapshotMapper.delete(new LambdaQueryWrapper<DailySnapshot>()
                .eq(DailySnapshot::getSnapshotDate, gapDay));

        SnapshotRunResultVo result = dailySnapshotService.runDailySnapshot();
        assertThat(result.getSnapshotDate()).isEqualTo(yesterday);
        assertThat(result.getBackfilledDates()).as("漏跑日应被回补").contains(gapDay);

        assertThat(snapshotsOf(ws, yesterday)).hasSize(1);
        List<DailySnapshot> gapRows = snapshotsOf(ws, gapDay);
        assertThat(gapRows).hasSize(1);
        assertThat(gapRows.get(0).getQty()).isEqualTo(6);
        assertThat(gapRows.get(0).getPalletQty()).isEqualTo(1);
    }

    @Test
    @DisplayName("SNAP-04 G11 对账哨兵：人工造回放≠库存 → 不一致数上升（记 ERROR 不自动改账）")
    void snap04_reconcileSentinelTriggers() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 9, yesterday.minusDays(2), 1, null, yesterday.minusDays(2));
        Inventory inv = seedInventory(ta, ws, sku, 9, 1);

        int baseline = dailySnapshotService.reconcileWithInventory();

        // 绕过流水直改库存（哨兵靶场景：流水被绕改）
        TenantContext.clear();
        inv.setQty(4);
        inventoryMapper.updateById(inv);
        int tampered = dailySnapshotService.reconcileWithInventory();
        assertThat(tampered).as("篡改后不一致数须上升").isGreaterThan(baseline);

        // 还原后回落
        inv.setQty(9);
        inventoryMapper.updateById(inv);
        assertThat(dailySnapshotService.reconcileWithInventory()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("SNAP-05 G12 跨月锚点差额：本期新落库、锚点在已出账历史月 → 差额=影子重算差")
    void snap05_crossMonthAdjustment() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        YearMonth period = YearMonth.now().minusMonths(1);      // 出账期（上月）
        YearMonth affected = period.minusMonths(1);             // 锚点历史月 M'
        int lenAffected = affected.lengthOfMonth();
        seedRule(ta, affected.atDay(1), null, "1.0000", 1);

        // 原始账本：M' 5 日入库 10 → 原 STORAGE 小计 = 10×(len−5)
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, affected.atDay(5), 0, null, affected.atDay(5));
        BigDecimal originalSubtotal = billingReplayService
                .replayMonthly(ta.tenantId(), ws, affected).getSubtotal();
        assertThat(originalSubtotal)
                .isEqualByComparingTo(BigDecimal.valueOf(10L * (lenAffected - 5)));

        // 本期（period）新落库一条纠错补录，锚点回溯 M' 5 日 +5 件
        seedMovement(ta, ws, sku, StockMovement.TYPE_CORRECTION_IN, 5, affected.atDay(5), 0, null,
                period.atDay(10));

        assertThat(billingReplayService.findBackdatedMonths(ta.tenantId(), ws, period))
                .containsExactly(affected);
        BigDecimal adjustment = billingReplayService
                .computeCrossMonthAdjustment(ta.tenantId(), ws, affected, originalSubtotal);
        // 影子重算差 = +5 件 ×(len−5) 天（历史账单本体不重算，仅出差额条目——原小计入参不变）
        assertThat(adjustment).isEqualByComparingTo(BigDecimal.valueOf(5L * (lenAffected - 5)));
    }

    @Test
    @DisplayName("SNAP-06 无规则=零账：replayMonthly 空段链 → lines=[] subtotal=0 period=null")
    void snap06_noRuleZeroBill() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 10, lastMonth.atDay(3), 0, null, lastMonth.atDay(3));

        MonthlyReplayVo vo = billingReplayService.replayMonthly(ta.tenantId(), ws, lastMonth);
        assertThat(vo.getLines()).isEmpty();
        assertThat(vo.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(vo.getPeriodStart()).isNull();
        assertThat(vo.getPeriodEnd()).isNull();
    }

    @Test
    @DisplayName("SNAP-07 下钻两维端点（ST）：按日=快照+当段现算；按 SKU=回放月汇总；recalc 幂等覆写")
    void snap07_breakdownEndpointsAndRecalc() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        Long sku = snowflakeIdUtil.nextId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        YearMonth month = YearMonth.from(yesterday);
        seedRule(ta, yesterday.minusDays(40), null, "1.5000", 1);
        seedMovement(ta, ws, sku, StockMovement.TYPE_INBOUND, 8, yesterday.minusDays(2), 0, null, yesterday.minusDays(2));
        String st = registerEmployee(ta, "ST");

        // recalc（ST 放行）：覆写 [昨日−1, 昨日]
        Map<String, Object> recalcDto = new LinkedHashMap<>();
        recalcDto.put("wholesalerId", ws.toString());
        recalcDto.put("from", yesterday.minusDays(1).toString());
        recalcDto.put("to", yesterday.toString());
        R<Map<String, Object>> recalc = restTemplate.exchange(base + "/api/v1/tenant/st/snapshots/recalc",
                HttpMethod.POST, new HttpEntity<>(recalcDto, bearer(st)), MAP).getBody();
        assertThat(recalc).isNotNull();
        assertThat(recalc.getCode()).isEqualTo(0);
        assertThat(recalc.getData().get("days")).isEqualTo(2);

        // 按日下钻：昨日行 qty=8、金额=8×1.5=12.00
        R<List<Map<String, Object>>> daily = restTemplate.exchange(
                base + "/api/v1/tenant/st/snapshots/daily?wholesalerId=" + ws + "&month=" + month,
                HttpMethod.GET, new HttpEntity<>(bearer(st)), LIST).getBody();
        assertThat(daily).isNotNull();
        assertThat(daily.getCode()).isEqualTo(0);
        Map<String, Object> yRow = daily.getData().stream()
                .filter(r -> yesterday.toString().equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(yRow.get("qty")).isEqualTo(8);
        assertThat(new BigDecimal(yRow.get("amount").toString())).isEqualByComparingTo("12.00");

        // 按 SKU 下钻：回放月汇总（与出账同源）——qtyDays = 8×(入库次日..月末∩已过天数) 由公式内生，
        // 此处断言行存在且单价/金额一致性（amount = qtyDays×1.5）
        R<Map<String, Object>> bySku = restTemplate.exchange(
                base + "/api/v1/tenant/st/snapshots/by-sku?wholesalerId=" + ws + "&month=" + month,
                HttpMethod.GET, new HttpEntity<>(bearer(st)), MAP).getBody();
        assertThat(bySku).isNotNull();
        assertThat(bySku.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) bySku.getData().get("lines");
        assertThat(lines).hasSize(1);
        long qtyDays = Long.parseLong(lines.get(0).get("qtyDays").toString());
        assertThat(new BigDecimal(lines.get(0).get("amount").toString()))
                .isEqualByComparingTo(new BigDecimal("1.5000").multiply(BigDecimal.valueOf(qtyDays))
                        .setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(new BigDecimal(bySku.getData().get("subtotal").toString()))
                .isEqualByComparingTo(new BigDecimal(lines.get(0).get("amount").toString()));
    }

    @Test
    @DisplayName("SNAP-08 越权矩阵与校验：WE 42004 / WK 42001 / 跨租户商户 50230 / 区间校验 40001·40003")
    void snap08_permissionAndValidation() {
        TaContext ta = registerTaActive();
        Long ws = seedWholesaler(ta);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        YearMonth month = YearMonth.from(yesterday);
        String st = registerEmployee(ta, "ST");
        String wk = registerEmployee(ta, "WK");
        WaContext wa = onboardWa(ta);
        String we = registerWe(wa);

        String dailyUrl = base + "/api/v1/tenant/st/snapshots/daily?wholesalerId=" + ws + "&month=" + month;
        R<List<Map<String, Object>>> weResp = restTemplate.exchange(dailyUrl,
                HttpMethod.GET, new HttpEntity<>(bearer(we)), LIST).getBody();
        assertThat(weResp).isNotNull();
        assertThat(weResp.getCode()).as("WE 对账单/快照整域拒绝").isEqualTo(42004);
        R<List<Map<String, Object>>> wkResp = restTemplate.exchange(dailyUrl,
                HttpMethod.GET, new HttpEntity<>(bearer(wk)), LIST).getBody();
        assertThat(wkResp).isNotNull();
        assertThat(wkResp.getCode()).isEqualTo(42001);
        R<List<Map<String, Object>>> waResp = restTemplate.exchange(dailyUrl,
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), LIST).getBody();
        assertThat(waResp).isNotNull();
        assertThat(waResp.getCode()).as("WA 拒绝（ST 域）").isEqualTo(42001);

        // 跨租户商户按不存在（50230）
        TaContext other = registerTaActive();
        Long otherWs = seedWholesaler(other);
        R<List<Map<String, Object>>> cross = restTemplate.exchange(
                base + "/api/v1/tenant/st/snapshots/daily?wholesalerId=" + otherWs + "&month=" + month,
                HttpMethod.GET, new HttpEntity<>(bearer(st)), LIST).getBody();
        assertThat(cross).isNotNull();
        assertThat(cross.getCode()).isEqualTo(50230);

        // recalc 校验：缺起止 40003；起>止 40001；止>昨日 40001
        R<Map<String, Object>> missing = postRecalc(st, ws, null, null);
        assertThat(missing).isNotNull();
        assertThat(missing.getCode()).isEqualTo(40003);
        R<Map<String, Object>> inverted = postRecalc(st, ws, yesterday.toString(), yesterday.minusDays(1).toString());
        assertThat(inverted).isNotNull();
        assertThat(inverted.getCode()).isEqualTo(40001);
        R<Map<String, Object>> future = postRecalc(st, ws, yesterday.toString(), LocalDate.now().toString());
        assertThat(future).isNotNull();
        assertThat(future.getCode()).isEqualTo(40001);
    }

    private R<Map<String, Object>> postRecalc(String token, Long ws, String from, String to) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("wholesalerId", ws.toString());
        if (from != null) dto.put("from", from);
        if (to != null) dto.put("to", to);
        return restTemplate.exchange(base + "/api/v1/tenant/st/snapshots/recalc",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }
}
