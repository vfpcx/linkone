package com.cangchu.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.BillItem;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.entity.DailySnapshot;
import com.cangchu.billing.entity.PaymentRecord;
import com.cangchu.billing.mapper.BillItemMapper;
import com.cangchu.billing.mapper.BillMapper;
import com.cangchu.billing.mapper.BillingRuleMapper;
import com.cangchu.billing.mapper.DailySnapshotMapper;
import com.cangchu.billing.mapper.PaymentRecordMapper;
import com.cangchu.billing.service.BillingService;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 W3 账单生命周期操作集场景测试（14 §3.1/§3.3，任务关卡）：
 * 下发/撤回 R11 三前置 / WA 确认 / 满 1 日自动 Job（幂等+CAS）/ 回款四态（部分/结清/超收/结清后）/
 * R12 回退两分支 / 调整·R10 冲销（50371/40103/40204/50383）/ 申诉全链（窗口/条目/pending 唯一/50376）/
 * DISPUTED 位冻结全部写操作（50381）/ 虚拟线程并发 CAS（确认×撤回、回款×R12）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillLifecycleScenarioTest {

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
    private PaymentRecordMapper paymentRecordMapper;
    @Autowired
    private DailySnapshotMapper dailySnapshotMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private BillingService billingService;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", ((System.nanoTime() >> 15) & 0x7FFFFFFF) % 100000);
    private static final String P_EMP =
            "15" + String.format("%05d", ((System.nanoTime() >> 9) & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", ((System.nanoTime() >> 3) & 0x7FFFFFFF) % 100000);
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

    // ==================== helpers ====================

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
        dto.setName("生命周期仓-" + phone);
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

    private LoginVo registerEmployee(TaContext ta, String role) {
        Map<String, Object> inviteDto = new LinkedHashMap<>();
        inviteDto.put("role", role);
        inviteDto.put("maxUses", 1);
        inviteDto.put("expiresInDays", 7);
        R<Map<String, Object>> invite = restTemplate.exchange(base + "/api/v1/tenant/employee-invites",
                HttpMethod.POST, new HttpEntity<>(inviteDto, bearer(ta.token())), MAP).getBody();
        assertThat(invite).isNotNull();
        assertThat(invite.getCode()).as("TA 生 %s 码", role).isEqualTo(0);
        String code = invite.getData().get("code").toString();
        return registerAndLogin(uniquePhone(P_EMP), "EmpPass123", role, code);
    }

    private record WaContext(String token, Long userId, Long wholesalerId) {}

    /** 完整入驻一个 WA（注册 → 自助申请 → TA 通过），供确认/申诉/通知收件人用例。 */
    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA", null);
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "账单商户-" + phone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(reg.getToken())), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();
        Map<String, Object> auditDto = Map.of("action", "APPROVED", "remark", "P4 W3 测试放行");
        R<Map<String, Object>> approved = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        return new WaContext(reg.getToken(), reg.getUserId(),
                Long.parseLong(approved.getData().get("wholesalerId").toString()));
    }

    private Long seedWholesaler(TaContext ta) {
        TenantContext.clear();
        Wholesaler ws = new Wholesaler();
        ws.setId(snowflakeIdUtil.nextId());
        ws.setTenantId(ta.tenantId());
        ws.setName("生命周期商户-" + ws.getId());
        ws.setStatus("ACTIVE");
        ws.setOwnerUserId(snowflakeIdUtil.nextId());
        wholesalerMapper.insert(ws);
        return ws.getId();
    }

    private void seedRule(TaContext ta, LocalDate from) {
        TenantContext.clear();
        BillingRule rule = new BillingRule();
        rule.setId(snowflakeIdUtil.nextId());
        rule.setTenantId(ta.tenantId());
        rule.setQtyEnabled(1);
        rule.setPalletEnabled(0);
        rule.setPricePerQtyDay(new BigDecimal("1.0000"));
        rule.setMinCharge(BigDecimal.ZERO);
        rule.setEffectiveFrom(from);
        rule.setEffectiveTo(null);
        rule.setVersion(1);
        billingRuleMapper.insert(rule);
    }

    /**
     * 造一张应收 10.00 的 DRAFT 账单：上月倒数第 6 日入库 2 件（次日起算恰 5 天 ×2 件 ×1 元）。
     */
    private Bill seedDraftBill(TaContext ta, Long wsId) {
        YearMonth month = YearMonth.now().minusMonths(1);
        seedRule(ta, month.minusMonths(2).atDay(1));
        TenantContext.clear();
        StockMovement m = new StockMovement();
        m.setId(snowflakeIdUtil.nextId());
        m.setTenantId(ta.tenantId());
        m.setWholesalerId(wsId);
        m.setSkuId(snowflakeIdUtil.nextId());
        m.setType(StockMovement.TYPE_INBOUND);
        m.setQty(2);
        m.setBizTime(month.atDay(month.lengthOfMonth() - 5).atTime(10, 0));
        m.setPalletDelta(0);
        m.setCreatedAt(month.atDay(month.lengthOfMonth() - 5).atTime(10, 0));
        stockMovementMapper.insert(m);
        Bill bill = billingService.generateForPair(ta.tenantId(), wsId, month);
        assertThat(bill).isNotNull();
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("10.00");
        assertThat(bill.getStatus()).isEqualTo(Bill.STATUS_DRAFT);
        return bill;
    }

    private Bill reload(Long billId) {
        TenantContext.clear();
        return billMapper.selectById(billId);
    }

    private void backdateDispatch(Long billId, int days) {
        TenantContext.clear();
        billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, billId)
                .set(Bill::getDispatchAt, LocalDateTime.now().minusDays(days)));
    }

    private R<Map<String, Object>> post(String token, String path, Map<String, Object> body) {
        return restTemplate.exchange(base + path, HttpMethod.POST,
                new HttpEntity<>(body != null ? body : Map.of(), bearer(token)), MAP).getBody();
    }

    private R<Map<String, Object>> payment(String token, Long billId, Object amount) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("amount", amount);
        dto.put("payAt", LocalDateTime.now().minusHours(1).withNano(0).toString());
        dto.put("payMethod", "BANK_TRANSFER");
        return post(token, "/api/v1/tenant/st/bills/" + billId + "/payments", dto);
    }

    private R<List<Map<String, Object>>> getList(String token, String path) {
        return restTemplate.exchange(base + path, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    /** WA 生 WE 码 → 凭码注册 WE，返回其 token（DailySnapshotScenarioTest 同构）。 */
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

    private long noticeCount(Long tenantId, String type, Long recipient) {
        TenantContext.clear();
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getType, type)
                .eq(recipient != null, Notification::getRecipientUserId, recipient));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("LIF-01 下发→撤回→再下发：dispatch_at/通知 WA；撤回后 WA 不可见（50370）")
    void lif01_dispatchWithdrawRedispatch() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();

        R<Map<String, Object>> dispatched = post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        assertThat(dispatched).isNotNull();
        assertThat(dispatched.getCode()).isEqualTo(0);
        Bill after = reload(id);
        assertThat(after.getStatus()).isEqualTo(Bill.STATUS_DISPATCHED);
        assertThat(after.getDispatchAt()).isNotNull();
        assertThat(noticeCount(ta.tenantId(), Notification.TYPE_BILL_DISPATCHED, wa.userId())).isEqualTo(1);

        // WA 可见详情
        R<Map<String, Object>> waDetail = restTemplate.exchange(base + "/api/v1/wholesaler/bills/" + id,
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(waDetail).isNotNull();
        assertThat(waDetail.getCode()).isEqualTo(0);

        // 撤回：回 DRAFT、dispatch_at 清空、通知 WA、WA 端按不存在（50370 不泄漏）
        R<Map<String, Object>> withdrawn = post(st, "/api/v1/tenant/st/bills/" + id + "/withdraw", null);
        assertThat(withdrawn).isNotNull();
        assertThat(withdrawn.getCode()).isEqualTo(0);
        Bill back = reload(id);
        assertThat(back.getStatus()).isEqualTo(Bill.STATUS_DRAFT);
        assertThat(back.getDispatchAt()).isNull();
        assertThat(noticeCount(ta.tenantId(), Notification.TYPE_BILL_WITHDRAWN, wa.userId())).isEqualTo(1);
        R<Map<String, Object>> waAfterWithdraw = restTemplate.exchange(
                base + "/api/v1/wholesaler/bills/" + id,
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(waAfterWithdraw).isNotNull();
        assertThat(waAfterWithdraw.getCode()).isEqualTo(50370);

        // 再下发正常
        R<Map<String, Object>> again = post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        assertThat(again).isNotNull();
        assertThat(again.getCode()).isEqualTo(0);
        assertThat(reload(id).getStatus()).isEqualTo(Bill.STATUS_DISPATCHED);
    }

    @Test
    @DisplayName("LIF-02 R11 三前置：DRAFT 撤回 50330；确认后 50372；有回款后 50372；WA 确认落 confirmed_at")
    void lif02_withdrawPreconditions() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();

        // 前置①状态：DRAFT 撤回 → 50330（矩阵不可达 DRAFT→DRAFT）
        R<Map<String, Object>> onDraft = post(st, "/api/v1/tenant/st/bills/" + id + "/withdraw", null);
        assertThat(onDraft).isNotNull();
        assertThat(onDraft.getCode()).isEqualTo(50330);

        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        // 前置②确认：WA 确认 → PENDING_PAYMENT + confirmed_at；撤回 → 50372
        R<Map<String, Object>> confirmed = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/confirm", null);
        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getCode()).isEqualTo(0);
        Bill afterConfirm = reload(id);
        assertThat(afterConfirm.getStatus()).isEqualTo(Bill.STATUS_PENDING_PAYMENT);
        assertThat(afterConfirm.getConfirmedAt()).isNotNull();
        R<Map<String, Object>> afterConfirmWithdraw = post(st, "/api/v1/tenant/st/bills/" + id + "/withdraw", null);
        assertThat(afterConfirmWithdraw).isNotNull();
        assertThat(afterConfirmWithdraw.getCode()).isEqualTo(50372);

        // 前置③回款：登记部分回款 → PARTIAL_PAID；撤回 → 50372
        R<Map<String, Object>> paid = payment(st, id, 4);
        assertThat(paid).isNotNull();
        assertThat(paid.getCode()).isEqualTo(0);
        R<Map<String, Object>> afterPaidWithdraw = post(st, "/api/v1/tenant/st/bills/" + id + "/withdraw", null);
        assertThat(afterPaidWithdraw).isNotNull();
        assertThat(afterPaidWithdraw.getCode()).isEqualTo(50372);
    }

    @Test
    @DisplayName("LIF-03 满 1 日自动确认 Job：满 1 日转待回款、未满不动；重跑幂等 affected=0")
    void lif03_autoConfirmJobIdempotentAndCas() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        Long wsOld = seedWholesaler(ta);
        Bill oldBill = seedDraftBill(ta, wsOld);
        post(st, "/api/v1/tenant/st/bills/" + oldBill.getId() + "/dispatch", null);
        backdateDispatch(oldBill.getId(), 2); // 满 1 日

        Long wsFresh = seedWholesaler(ta);
        YearMonth prev2 = YearMonth.now().minusMonths(2);
        TenantContext.clear();
        StockMovement m = new StockMovement();
        m.setId(snowflakeIdUtil.nextId());
        m.setTenantId(ta.tenantId());
        m.setWholesalerId(wsFresh);
        m.setSkuId(snowflakeIdUtil.nextId());
        m.setType(StockMovement.TYPE_INBOUND);
        m.setQty(2);
        m.setBizTime(prev2.atDay(prev2.lengthOfMonth() - 5).atTime(10, 0));
        m.setPalletDelta(0);
        m.setCreatedAt(prev2.atDay(prev2.lengthOfMonth() - 5).atTime(10, 0));
        stockMovementMapper.insert(m);
        Bill freshBill = billingService.generateForPair(ta.tenantId(), wsFresh, prev2);
        assertThat(freshBill).isNotNull();
        post(st, "/api/v1/tenant/st/bills/" + freshBill.getId() + "/dispatch", null); // 刚下发未满 1 日

        int affected = billingService.autoConfirmDispatched();
        assertThat(affected).isGreaterThanOrEqualTo(1);
        Bill oldAfter = reload(oldBill.getId());
        assertThat(oldAfter.getStatus()).isEqualTo(Bill.STATUS_PENDING_PAYMENT);
        assertThat(oldAfter.getConfirmedAt()).isNotNull();
        // 未满 1 日的不动（时间条件=CAS 语义）
        assertThat(reload(freshBill.getId()).getStatus()).isEqualTo(Bill.STATUS_DISPATCHED);

        // 幂等重跑：本租户两单不再变化
        billingService.autoConfirmDispatched();
        assertThat(reload(oldBill.getId()).getStatus()).isEqualTo(Bill.STATUS_PENDING_PAYMENT);
        assertThat(reload(freshBill.getId()).getStatus()).isEqualTo(Bill.STATUS_DISPATCHED);
    }

    @Test
    @DisplayName("LIF-04 回款四态：部分→PARTIAL_PAID / 超收 50373 / 全额→PAID / 结清后 50374 / DRAFT 50330 / 参数校验")
    void lif04_paymentFourStates() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        Long ws = seedWholesaler(ta);
        Bill bill = seedDraftBill(ta, ws);
        Long id = bill.getId();

        // DRAFT 未到回款态 → 50330
        R<Map<String, Object>> onDraft = payment(st, id, 4);
        assertThat(onDraft).isNotNull();
        assertThat(onDraft.getCode()).isEqualTo(50330);

        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        backdateDispatch(id, 2);
        billingService.autoConfirmDispatched(); // → PENDING_PAYMENT

        // 参数校验：金额格式（40103）/ 收款方式（40001）/ 缺收款日期（40003）
        R<Map<String, Object>> badScale = payment(st, id, new BigDecimal("1.234"));
        assertThat(badScale).isNotNull();
        assertThat(badScale.getCode()).isEqualTo(40103);
        Map<String, Object> badMethod = new LinkedHashMap<>();
        badMethod.put("amount", 1);
        badMethod.put("payAt", LocalDateTime.now().withNano(0).toString());
        badMethod.put("payMethod", "BITCOIN");
        R<Map<String, Object>> badMethodResp = post(st, "/api/v1/tenant/st/bills/" + id + "/payments", badMethod);
        assertThat(badMethodResp).isNotNull();
        assertThat(badMethodResp.getCode()).isEqualTo(40001);
        Map<String, Object> noPayAt = new LinkedHashMap<>();
        noPayAt.put("amount", 1);
        noPayAt.put("payMethod", "CASH");
        R<Map<String, Object>> noPayAtResp = post(st, "/api/v1/tenant/st/bills/" + id + "/payments", noPayAt);
        assertThat(noPayAtResp).isNotNull();
        assertThat(noPayAtResp.getCode()).isEqualTo(40003);

        // 部分回款 4 → PARTIAL_PAID
        R<Map<String, Object>> partial = payment(st, id, 4);
        assertThat(partial).isNotNull();
        assertThat(partial.getCode()).isEqualTo(0);
        Bill afterPartial = reload(id);
        assertThat(afterPartial.getStatus()).isEqualTo(Bill.STATUS_PARTIAL_PAID);
        assertThat(afterPartial.getPaidAmount()).isEqualByComparingTo("4.00");

        // 超收（剩余 6 收 7）→ 50373
        R<Map<String, Object>> exceeds = payment(st, id, 7);
        assertThat(exceeds).isNotNull();
        assertThat(exceeds.getCode()).isEqualTo(50373);

        // 全额补足 → PAID
        R<Map<String, Object>> full = payment(st, id, 6);
        assertThat(full).isNotNull();
        assertThat(full.getCode()).isEqualTo(0);
        Bill afterFull = reload(id);
        assertThat(afterFull.getStatus()).isEqualTo(Bill.STATUS_PAID);
        assertThat(afterFull.getPaidAmount()).isEqualByComparingTo("10.00");

        // 结清后再登记 → 50374
        R<Map<String, Object>> settled = payment(st, id, 1);
        assertThat(settled).isNotNull();
        assertThat(settled.getCode()).isEqualTo(50374);
    }

    @Test
    @DisplayName("LIF-05 R12 回退两分支：PAID→PARTIAL_PAID→PENDING_PAYMENT；重复冲销 50375；凭据缺失 40003")
    void lif05_paymentReverseBranches() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        Long ws = seedWholesaler(ta);
        Bill bill = seedDraftBill(ta, ws);
        Long id = bill.getId();
        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        backdateDispatch(id, 2);
        billingService.autoConfirmDispatched();
        payment(st, id, 4);
        payment(st, id, 6); // → PAID

        TenantContext.clear();
        List<PaymentRecord> payments = paymentRecordMapper.selectList(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getBillId, id)
                        .orderByAsc(PaymentRecord::getId));
        assertThat(payments).hasSize(2);
        Long pay4 = payments.get(0).getId();
        Long pay6 = payments.get(1).getId();

        // 凭据缺失 → 40003（理由缺失同码）
        Map<String, Object> noConfirm = new LinkedHashMap<>();
        noConfirm.put("reason", "误登记");
        R<Map<String, Object>> noConfirmResp = post(st, "/api/v1/tenant/st/payments/" + pay6 + "/reverse", noConfirm);
        assertThat(noConfirmResp).isNotNull();
        assertThat(noConfirmResp.getCode()).isEqualTo(40003);

        Map<String, Object> reverseDto = new LinkedHashMap<>();
        reverseDto.put("reason", "误登记");
        reverseDto.put("confirmed", true);

        // 分支①：PAID 冲 6 → paid=4>0 → PARTIAL_PAID
        R<Map<String, Object>> r1 = post(st, "/api/v1/tenant/st/payments/" + pay6 + "/reverse", reverseDto);
        assertThat(r1).isNotNull();
        assertThat(r1.getCode()).isEqualTo(0);
        Bill afterR1 = reload(id);
        assertThat(afterR1.getStatus()).isEqualTo(Bill.STATUS_PARTIAL_PAID);
        assertThat(afterR1.getPaidAmount()).isEqualByComparingTo("4.00");

        // 分支②：PARTIAL_PAID 全额冲回 → paid=0 → PENDING_PAYMENT
        R<Map<String, Object>> r2 = post(st, "/api/v1/tenant/st/payments/" + pay4 + "/reverse", reverseDto);
        assertThat(r2).isNotNull();
        assertThat(r2.getCode()).isEqualTo(0);
        Bill afterR2 = reload(id);
        assertThat(afterR2.getStatus()).isEqualTo(Bill.STATUS_PENDING_PAYMENT);
        assertThat(afterR2.getPaidAmount()).isEqualByComparingTo("0.00");

        // 冲销记录留痕不删（REVERSED 保留可见）
        TenantContext.clear();
        PaymentRecord reversed = paymentRecordMapper.selectById(pay6);
        assertThat(reversed.getStatus()).isEqualTo(PaymentRecord.STATUS_REVERSED);
        assertThat(reversed.getReverseReason()).isEqualTo("误登记");
        assertThat(reversed.getReverseAt()).isNotNull();

        // 重复冲销 → 50375
        R<Map<String, Object>> again = post(st, "/api/v1/tenant/st/payments/" + pay6 + "/reverse", reverseDto);
        assertThat(again).isNotNull();
        assertThat(again.getCode()).isEqualTo(50375);
    }

    @Test
    @DisplayName("LIF-06 调整与 R10 冲销：仅 DRAFT（50371）；40103/40204；冲销链 50383；三金额恒等式")
    void lif06_adjustAndReverseItem() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        Long ws = seedWholesaler(ta);
        Bill bill = seedDraftBill(ta, ws);
        Long id = bill.getId();

        // 调整参数：类型无效 40001 / 金额 3 位小数 40103 / 缺原因 40003
        Map<String, Object> badType = Map.of("type", "GIFT", "amount", 1, "remark", "x");
        R<Map<String, Object>> badTypeResp = post(st, "/api/v1/tenant/st/bills/" + id + "/adjust", new LinkedHashMap<>(badType));
        assertThat(badTypeResp).isNotNull();
        assertThat(badTypeResp.getCode()).isEqualTo(40001);
        Map<String, Object> badScale = Map.of("type", "DISCOUNT", "amount", new BigDecimal("0.123"), "remark", "x");
        R<Map<String, Object>> badScaleResp = post(st, "/api/v1/tenant/st/bills/" + id + "/adjust", new LinkedHashMap<>(badScale));
        assertThat(badScaleResp).isNotNull();
        assertThat(badScaleResp.getCode()).isEqualTo(40103);
        Map<String, Object> noRemark = Map.of("type", "DISCOUNT", "amount", 1);
        R<Map<String, Object>> noRemarkResp = post(st, "/api/v1/tenant/st/bills/" + id + "/adjust", new LinkedHashMap<>(noRemark));
        assertThat(noRemarkResp).isNotNull();
        assertThat(noRemarkResp.getCode()).isEqualTo(40003);

        // 调整超小计（应收 10，折扣 11）→ 40204
        Map<String, Object> over = Map.of("type", "DISCOUNT", "amount", 11, "remark", "全免");
        R<Map<String, Object>> overResp = post(st, "/api/v1/tenant/st/bills/" + id + "/adjust", new LinkedHashMap<>(over));
        assertThat(overResp).isNotNull();
        assertThat(overResp.getCode()).isEqualTo(40204);

        // 正常调整 −3：adjust=−3、total=7、恒等式成立
        Map<String, Object> ok = Map.of("type", "DISCOUNT", "amount", 3, "remark", "老客户折扣");
        R<Map<String, Object>> okResp = post(st, "/api/v1/tenant/st/bills/" + id + "/adjust", new LinkedHashMap<>(ok));
        assertThat(okResp).isNotNull();
        assertThat(okResp.getCode()).isEqualTo(0);
        Bill afterAdjust = reload(id);
        assertThat(afterAdjust.getSubtotalAmount()).isEqualByComparingTo("10.00");
        assertThat(afterAdjust.getAdjustAmount()).isEqualByComparingTo("-3.00");
        assertThat(afterAdjust.getTotalAmount()).isEqualByComparingTo("7.00");

        // R10：冲销调整条目 → adjust 归 0；同条目二次冲销 → 50383
        TenantContext.clear();
        BillItem adjustment = billItemMapper.selectOne(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, id)
                .eq(BillItem::getItemType, BillItem.TYPE_ADJUSTMENT)
                .last("LIMIT 1"));
        Map<String, Object> reverse = Map.of("itemId", adjustment.getId().toString());
        R<Map<String, Object>> reversed = post(st, "/api/v1/tenant/st/bills/" + id + "/reverse-item", new LinkedHashMap<>(reverse));
        assertThat(reversed).isNotNull();
        assertThat(reversed.getCode()).isEqualTo(0);
        Bill afterReverse = reload(id);
        assertThat(afterReverse.getAdjustAmount()).isEqualByComparingTo("0.00");
        assertThat(afterReverse.getTotalAmount()).isEqualByComparingTo("10.00");
        // 原条目不删：REVERSAL 反向条目回指
        TenantContext.clear();
        assertThat(billItemMapper.selectCount(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, id)
                .eq(BillItem::getReverseOfItemId, adjustment.getId()))).isEqualTo(1);
        R<Map<String, Object>> reverseTwice = post(st, "/api/v1/tenant/st/bills/" + id + "/reverse-item", new LinkedHashMap<>(reverse));
        assertThat(reverseTwice).isNotNull();
        assertThat(reverseTwice.getCode()).isEqualTo(50383);

        // 下发后不可调整/冲销 → 50371（一致性约束：先撤回）
        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        R<Map<String, Object>> adjustAfterDispatch = post(st, "/api/v1/tenant/st/bills/" + id + "/adjust", new LinkedHashMap<>(ok));
        assertThat(adjustAfterDispatch).isNotNull();
        assertThat(adjustAfterDispatch.getCode()).isEqualTo(50371);
        R<Map<String, Object>> reverseAfterDispatch = post(st, "/api/v1/tenant/st/bills/" + id + "/reverse-item", new LinkedHashMap<>(reverse));
        assertThat(reverseAfterDispatch).isNotNull();
        assertThat(reverseAfterDispatch.getCode()).isEqualTo(50371);
    }

    @Test
    @DisplayName("LIF-07 申诉全链：提交/通知 ST；条目 50377；pending 唯一 50382；处理 50376；窗口 50378")
    void lif07_disputeFlow() {
        TaContext ta = registerTaActive();
        LoginVo stUser = registerEmployee(ta, "ST");
        String st = stUser.getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);

        // 条目不属本账单 → 50377
        Map<String, Object> foreign = new LinkedHashMap<>();
        foreign.put("reason", "金额不对");
        foreign.put("disputedItemIds", List.of(String.valueOf(snowflakeIdUtil.nextId())));
        R<Map<String, Object>> foreignResp = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/dispute", foreign);
        assertThat(foreignResp).isNotNull();
        assertThat(foreignResp.getCode()).isEqualTo(50377);

        // 正常提交（勾选本账单 STORAGE 条目）→ PENDING + 通知 ST
        TenantContext.clear();
        BillItem storage = billItemMapper.selectOne(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, id)
                .eq(BillItem::getItemType, BillItem.TYPE_STORAGE)
                .last("LIMIT 1"));
        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("reason", "件·天数量与我方记录不符");
        submit.put("disputedItemIds", List.of(storage.getId().toString()));
        R<Map<String, Object>> submitted = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/dispute", submit);
        assertThat(submitted).isNotNull();
        assertThat(submitted.getCode()).isEqualTo(0);
        assertThat(submitted.getData().get("status")).isEqualTo("PENDING");
        String disputeId = submitted.getData().get("id").toString();
        assertThat(noticeCount(ta.tenantId(), Notification.TYPE_BILL_DISPUTE_SUBMITTED, stUser.getUserId()))
                .isEqualTo(1);
        // 申诉不冻结账单：仍可确认
        assertThat(reload(id).getStatus()).isEqualTo(Bill.STATUS_DISPATCHED);

        // 同账单第二张待处理 → 50382
        R<Map<String, Object>> second = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/dispute", submit);
        assertThat(second).isNotNull();
        assertThat(second.getCode()).isEqualTo(50382);

        // 处理：结论无效 40001 / 缺说明 40003 / REJECTED 落终态 + 通知 WA
        R<Map<String, Object>> badConclusion = post(st, "/api/v1/tenant/st/bill-disputes/" + disputeId + "/resolve",
                new LinkedHashMap<>(Map.of("conclusion", "MAYBE", "resolution", "x")));
        assertThat(badConclusion).isNotNull();
        assertThat(badConclusion.getCode()).isEqualTo(40001);
        R<Map<String, Object>> noResolution = post(st, "/api/v1/tenant/st/bill-disputes/" + disputeId + "/resolve",
                new LinkedHashMap<>(Map.of("conclusion", "REJECTED")));
        assertThat(noResolution).isNotNull();
        assertThat(noResolution.getCode()).isEqualTo(40003);
        R<Map<String, Object>> resolved = post(st, "/api/v1/tenant/st/bill-disputes/" + disputeId + "/resolve",
                new LinkedHashMap<>(Map.of("conclusion", "REJECTED", "resolution", "核对无误，账单按快照计算")));
        assertThat(resolved).isNotNull();
        assertThat(resolved.getCode()).isEqualTo(0);
        assertThat(resolved.getData().get("status")).isEqualTo("REJECTED");
        assertThat(noticeCount(ta.tenantId(), Notification.TYPE_BILL_DISPUTE_RESOLVED, wa.userId()))
                .isEqualTo(1);

        // 已处理再处理 → 50376
        R<Map<String, Object>> resolveTwice = post(st, "/api/v1/tenant/st/bill-disputes/" + disputeId + "/resolve",
                new LinkedHashMap<>(Map.of("conclusion", "RESOLVED", "resolution", "x")));
        assertThat(resolveTwice).isNotNull();
        assertThat(resolveTwice.getCode()).isEqualTo(50376);

        // 终态后可再申诉（pending 唯一仅限在途）；超窗（下发 8 天前）→ 50378
        backdateDispatch(id, 8);
        R<Map<String, Object>> lateResp = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/dispute", submit);
        assertThat(lateResp).isNotNull();
        assertThat(lateResp.getCode()).isEqualTo(50378);
        backdateDispatch(id, 0);
        R<Map<String, Object>> reopen = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/dispute", submit);
        assertThat(reopen).isNotNull();
        assertThat(reopen.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("LIF-08 DISPUTED 位冻结：调整/冲销/下发/撤回/回款/R12/确认/申诉 全部 50381")
    void lif08_disputedFreezesAllWrites() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
        backdateDispatch(id, 2);
        billingService.autoConfirmDispatched();
        payment(st, id, 4); // 留一条 EFFECTIVE 回款供 R12 冻结断言
        TenantContext.clear();
        Long paymentId = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getBillId, id)
                .last("LIMIT 1")).getId();

        // 人工置 DISPUTED（R14 联动位）
        TenantContext.clear();
        billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, id)
                .set(Bill::getStatus, Bill.STATUS_DISPUTED));

        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/adjust",
                new LinkedHashMap<>(Map.of("type", "DISCOUNT", "amount", 1, "remark", "x"))).getCode())
                .isEqualTo(50381);
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/reverse-item",
                new LinkedHashMap<>(Map.of("itemId", "1"))).getCode()).isEqualTo(50381);
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null).getCode()).isEqualTo(50381);
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/withdraw", null).getCode()).isEqualTo(50381);
        assertThat(payment(st, id, 1).getCode()).isEqualTo(50381);
        assertThat(post(st, "/api/v1/tenant/st/payments/" + paymentId + "/reverse",
                new LinkedHashMap<>(Map.of("reason", "x", "confirmed", true))).getCode()).isEqualTo(50381);
        assertThat(post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/confirm", null).getCode())
                .isEqualTo(50381);
        assertThat(post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/dispute",
                new LinkedHashMap<>(Map.of("reason", "x"))).getCode()).isEqualTo(50381);
        // 只读仍可（保留对账知情权）：WA 详情可见
        R<Map<String, Object>> waDetail = restTemplate.exchange(base + "/api/v1/wholesaler/bills/" + id,
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(waDetail).isNotNull();
        assertThat(waDetail.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("LIF-09 虚拟线程并发 CAS：确认×撤回同单决出恰一；回款×R12 同单决出恰一（不变量恒守）")
    void lif09_concurrentCas() throws Exception {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);

        // ① 确认（WA）× 撤回（ST）同单：CAS 决出恰一方成功
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch ready1 = new CountDownLatch(2);
        CountDownLatch go1 = new CountDownLatch(1);
        Runnable confirmTask = () -> {
            ready1.countDown();
            try {
                go1.await();
                R<Map<String, Object>> r = post(wa.token(), "/api/v1/wholesaler/bills/" + id + "/confirm", null);
                if (r != null && r.getCode() == 0) successes.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        Runnable withdrawTask = () -> {
            ready1.countDown();
            try {
                go1.await();
                R<Map<String, Object>> r = post(st, "/api/v1/tenant/st/bills/" + id + "/withdraw", null);
                if (r != null && r.getCode() == 0) successes.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        Thread c1 = Thread.ofVirtual().start(confirmTask);
        Thread c2 = Thread.ofVirtual().start(withdrawTask);
        ready1.await();
        go1.countDown();
        c1.join();
        c2.join();
        assertThat(successes.get()).as("确认×撤回恰一方成功").isEqualTo(1);
        Bill decided = reload(id);
        assertThat(decided.getStatus())
                .isIn(Bill.STATUS_PENDING_PAYMENT, Bill.STATUS_DRAFT);

        // ② 回款登记 × R12 同单：先补到 PARTIAL_PAID（paid=4）
        if (Bill.STATUS_DRAFT.equals(decided.getStatus())) {
            post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null);
            backdateDispatch(id, 2);
            billingService.autoConfirmDispatched();
        }
        payment(st, id, 4);
        TenantContext.clear();
        Long pay4 = paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getBillId, id)
                .eq(PaymentRecord::getStatus, PaymentRecord.STATUS_EFFECTIVE)
                .last("LIMIT 1")).getId();

        AtomicInteger round2 = new AtomicInteger();
        CountDownLatch ready2 = new CountDownLatch(2);
        CountDownLatch go2 = new CountDownLatch(1);
        Runnable payTask = () -> {
            ready2.countDown();
            try {
                go2.await();
                R<Map<String, Object>> r = payment(st, id, 6);
                if (r != null && r.getCode() == 0) round2.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        Runnable reverseTask = () -> {
            ready2.countDown();
            try {
                go2.await();
                R<Map<String, Object>> r = post(st, "/api/v1/tenant/st/payments/" + pay4 + "/reverse",
                        new LinkedHashMap<>(Map.of("reason", "并发试冲", "confirmed", true)));
                if (r != null && r.getCode() == 0) round2.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        Thread p1 = Thread.ofVirtual().start(payTask);
        Thread p2 = Thread.ofVirtual().start(reverseTask);
        ready2.await();
        go2.countDown();
        p1.join();
        p2.join();
        assertThat(round2.get()).as("回款×R12 至少一方成功、CAS 决出").isBetween(1, 2);

        // 不变量：paid_amount == Σ EFFECTIVE 回款（无论谁赢）
        TenantContext.clear();
        BigDecimal effectiveSum = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getBillId, id)
                        .eq(PaymentRecord::getStatus, PaymentRecord.STATUS_EFFECTIVE)).stream()
                .map(PaymentRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(reload(id).getPaidAmount()).isEqualByComparingTo(effectiveSum);
    }

    @Test
    @DisplayName("LIF-10 WA 按日下钻（P4-L2）：行语义与 ST 侧逐行一致；未下发 50370；他商户 WA 50370；WE 42004")
    void lif10_waDailyBreakdown() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST").getToken();
        WaContext wa = onboardWa(ta);
        Bill bill = seedDraftBill(ta, wa.wholesalerId());
        Long id = bill.getId();
        String waPath = "/api/v1/wholesaler/bills/" + id + "/daily-breakdown";

        // 未下发（DRAFT）：按不存在 50370（不泄漏存在性）
        R<List<Map<String, Object>>> beforeDispatch = getList(wa.token(), waPath);
        assertThat(beforeDispatch).isNotNull();
        assertThat(beforeDispatch.getCode()).as("未下发按不存在").isEqualTo(50370);

        // 账期月 1..3 日快照 qty=2（其余日缺行=0；1 元/件·天规则 seedDraftBill 已在位）
        TenantContext.clear();
        YearMonth month = YearMonth.parse(bill.getBillingMonth());
        Long skuId = snowflakeIdUtil.nextId();
        for (int d = 1; d <= 3; d++) {
            DailySnapshot snap = new DailySnapshot();
            snap.setId(snowflakeIdUtil.nextId());
            snap.setTenantId(ta.tenantId());
            snap.setWholesalerId(wa.wholesalerId());
            snap.setSkuId(skuId);
            snap.setSnapshotDate(month.atDay(d));
            snap.setQty(2);
            snap.setPalletQty(0);
            dailySnapshotMapper.insert(snap);
        }

        // 下发后正常：行数=整月天数；有快照日 qty=2 金额=2.00；缺行日=0
        assertThat(post(st, "/api/v1/tenant/st/bills/" + id + "/dispatch", null).getCode()).isEqualTo(0);
        R<List<Map<String, Object>>> waRows = getList(wa.token(), waPath);
        assertThat(waRows).isNotNull();
        assertThat(waRows.getCode()).isEqualTo(0);
        assertThat(waRows.getData()).hasSize(month.lengthOfMonth());
        Map<String, Object> day1 = waRows.getData().stream()
                .filter(r -> month.atDay(1).toString().equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(day1.get("qty")).isEqualTo(2);
        assertThat(new BigDecimal(day1.get("amount").toString())).isEqualByComparingTo("2.00");
        Map<String, Object> day4 = waRows.getData().stream()
                .filter(r -> month.atDay(4).toString().equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(day4.get("qty")).isEqualTo(0);

        // 语义与 ST 侧对齐（同一快照内核）：同账单 ST 端点逐行相等
        R<List<Map<String, Object>>> stRows = getList(st, "/api/v1/tenant/st/bills/" + id + "/daily-breakdown");
        assertThat(stRows).isNotNull();
        assertThat(stRows.getCode()).isEqualTo(0);
        assertThat(waRows.getData()).as("WA 与 ST 行语义一致").isEqualTo(stRows.getData());

        // 他商户 WA 越权：按不存在 50370（不泄漏）
        WaContext otherWa = onboardWa(ta);
        R<List<Map<String, Object>>> cross = getList(otherWa.token(), waPath);
        assertThat(cross).isNotNull();
        assertThat(cross.getCode()).as("他商户 WA 按不存在").isEqualTo(50370);

        // WE 对账单整域拒绝 42004（WEM-S4-03 防回归）
        String we = registerWe(wa);
        R<List<Map<String, Object>>> weResp = getList(we, waPath);
        assertThat(weResp).isNotNull();
        assertThat(weResp.getCode()).as("WE 拒 42004").isEqualTo(42004);
    }
}
