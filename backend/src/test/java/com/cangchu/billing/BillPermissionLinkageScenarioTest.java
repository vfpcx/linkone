package com.cangchu.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.mapper.BillMapper;
import com.cangchu.billing.mapper.BillingRuleMapper;
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
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 W3 权限矩阵与 R13/R14 联动场景测试（14 §3.5/§3.6，任务关卡）：
 * ST 越权矩阵（WE 42004/WK·WA 42001）/ WA 端 WE 整域拒绝（WEM-S4-03）/ 跨租户跨商户 50370 /
 * R13 未结账单拦截 50323（发起+审批双检）+ precheck 真值（WDR-07 适配）/
 * R14 强制下架三态标 DISPUTED（FOF-S1-04 真实流转）+ DRAFT 不标 + 通知 ST。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillPermissionLinkageScenarioTest {

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
    private NotificationMapper notificationMapper;
    @Autowired
    private BillingService billingService;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", ((System.nanoTime() >> 17) & 0x7FFFFFFF) % 100000);
    private static final String P_EMP =
            "15" + String.format("%05d", ((System.nanoTime() >> 11) & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", ((System.nanoTime() >> 5) & 0x7FFFFFFF) % 100000);
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
        dto.setName("联动仓-" + phone);
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

    private record WaContext(String token, Long userId, Long wholesalerId) {}

    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA", null);
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "联动商户-" + phone);
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

    /** WA 生 WE 码 → 凭码注册 WE（BillingRuleScenarioTest 同构）。 */
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

    /** mapper 直造指定状态账单（联动用例免走完整链） */
    private Bill seedBill(Long tenantId, Long wsId, YearMonth month, String status,
                          String total, String paid) {
        TenantContext.clear();
        Bill bill = new Bill();
        bill.setId(snowflakeIdUtil.nextId());
        bill.setBillNo("BL-T" + tenantId % 100000 + "-W" + wsId + "-"
                + month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        bill.setTenantId(tenantId);
        bill.setWholesalerId(wsId);
        bill.setBillingMonth(month.toString());
        bill.setPeriodStart(month.atDay(1));
        bill.setPeriodEnd(month.atEndOfMonth());
        bill.setSubtotalAmount(new BigDecimal(total));
        bill.setAdjustAmount(BigDecimal.ZERO.setScale(2));
        bill.setTotalAmount(new BigDecimal(total));
        bill.setPaidAmount(new BigDecimal(paid));
        bill.setStatus(status);
        if (!Bill.STATUS_DRAFT.equals(status)) {
            bill.setDispatchAt(java.time.LocalDateTime.now().minusHours(3));
        }
        bill.setIdempotentKey("bill:" + tenantId + ":" + wsId + ":"
                + month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        billMapper.insert(bill);
        return bill;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("PERM-01 ST 端越权矩阵：WE 42004 / WK 42001 / WA 42001 / ST·TA 放行；跨租户账单 50370")
    void perm01_stEndpointMatrix() {
        TaContext ta = registerTaActive();
        String st = registerEmployee(ta, "ST");
        String wk = registerEmployee(ta, "WK");
        WaContext wa = onboardWa(ta);
        String we = registerWe(wa);

        String listUrl = base + "/api/v1/tenant/st/bills";
        R<Map<String, Object>> weResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(we)), MAP).getBody();
        assertThat(weResp).isNotNull();
        assertThat(weResp.getCode()).as("WE 对账单整域拒绝").isEqualTo(42004);
        R<Map<String, Object>> wkResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(wk)), MAP).getBody();
        assertThat(wkResp).isNotNull();
        assertThat(wkResp.getCode()).isEqualTo(42001);
        R<Map<String, Object>> waResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(waResp).isNotNull();
        assertThat(waResp.getCode()).isEqualTo(42001);
        R<Map<String, Object>> stResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(st)), MAP).getBody();
        assertThat(stResp).isNotNull();
        assertThat(stResp.getCode()).isEqualTo(0);
        R<Map<String, Object>> taResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(taResp).isNotNull();
        assertThat(taResp.getCode()).as("TA 兼岗并集放行").isEqualTo(0);

        // 跨租户按不存在（50370 不泄漏）：他仓账单详情/操作
        TaContext other = registerTaActive();
        Long otherWs = snowflakeIdUtil.nextId();
        TenantContext.clear();
        Wholesaler ws = new Wholesaler();
        ws.setId(otherWs);
        ws.setTenantId(other.tenantId());
        ws.setName("他仓商户");
        ws.setStatus("ACTIVE");
        ws.setOwnerUserId(snowflakeIdUtil.nextId());
        wholesalerMapper.insert(ws);
        Bill foreign = seedBill(other.tenantId(), otherWs, YearMonth.now().minusMonths(1),
                Bill.STATUS_DRAFT, "10.00", "0.00");
        R<Map<String, Object>> crossDetail = restTemplate.exchange(
                base + "/api/v1/tenant/st/bills/" + foreign.getId(),
                HttpMethod.GET, new HttpEntity<>(bearer(st)), MAP).getBody();
        assertThat(crossDetail).isNotNull();
        assertThat(crossDetail.getCode()).isEqualTo(50370);
        R<Map<String, Object>> crossOp = restTemplate.exchange(
                base + "/api/v1/tenant/st/bills/" + foreign.getId() + "/dispatch",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(st)), MAP).getBody();
        assertThat(crossOp).isNotNull();
        assertThat(crossOp.getCode()).isEqualTo(50370);
    }

    @Test
    @DisplayName("PERM-02 WA 端边界：WE 42004 整域拒；WK 42001；跨商户账单 50370；DRAFT 不可见 50370")
    void perm02_waEndpointMatrix() {
        TaContext ta = registerTaActive();
        String wk = registerEmployee(ta, "WK");
        WaContext wa = onboardWa(ta);
        WaContext waOther = onboardWa(ta);
        String we = registerWe(wa);

        String listUrl = base + "/api/v1/wholesaler/bills";
        R<Map<String, Object>> weResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(we)), MAP).getBody();
        assertThat(weResp).isNotNull();
        assertThat(weResp.getCode()).as("批发商员工账单整域不可见").isEqualTo(42004);
        R<Map<String, Object>> wkResp = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(wk)), MAP).getBody();
        assertThat(wkResp).isNotNull();
        assertThat(wkResp.getCode()).isEqualTo(42001);
        R<Map<String, Object>> waList = restTemplate.exchange(listUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(waList).isNotNull();
        assertThat(waList.getCode()).isEqualTo(0);

        // 跨商户按不存在：waOther 的已下发账单对 wa 50370
        Bill otherBill = seedBill(ta.tenantId(), waOther.wholesalerId(),
                YearMonth.now().minusMonths(1), Bill.STATUS_DISPATCHED, "10.00", "0.00");
        R<Map<String, Object>> crossWs = restTemplate.exchange(listUrl + "/" + otherBill.getId(),
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(crossWs).isNotNull();
        assertThat(crossWs.getCode()).isEqualTo(50370);

        // 本商户 DRAFT（未下发）不可见 50370；操作同样不泄漏
        Bill draft = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(2), Bill.STATUS_DRAFT, "10.00", "0.00");
        R<Map<String, Object>> draftDetail = restTemplate.exchange(listUrl + "/" + draft.getId(),
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(draftDetail).isNotNull();
        assertThat(draftDetail.getCode()).isEqualTo(50370);
        R<Map<String, Object>> draftConfirm = restTemplate.exchange(listUrl + "/" + draft.getId() + "/confirm",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(wa.token())), MAP).getBody();
        assertThat(draftConfirm).isNotNull();
        assertThat(draftConfirm.getCode()).isEqualTo(50370);
    }

    @Test
    @DisplayName("R13-01 未结账单拦截 50323：发起被拦 + precheck 真值 {cleared,count}；结清后放行")
    void r13_withdrawBlockedByUnsettledBill() {
        TaContext ta = registerTaActive();
        WaContext wa = onboardWa(ta);
        Bill unsettled = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(1), Bill.STATUS_PENDING_PAYMENT, "10.00", "0.00");

        // precheck 真值：cleared=false, count=1（灰态占位已废）
        R<Map<String, Object>> precheck = restTemplate.exchange(
                base + "/api/v1/wholesaler/withdraw/precheck",
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(precheck).isNotNull();
        assertThat(precheck.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> billing = (Map<String, Object>) precheck.getData().get("billing");
        assertThat(billing.get("cleared")).isEqualTo(false);
        assertThat(Long.parseLong(billing.get("count").toString())).isEqualTo(1);

        // 发起退驻 → 50323（含 DISPUTED 同拦，此处 PENDING_PAYMENT）
        R<Map<String, Object>> apply = restTemplate.exchange(base + "/api/v1/wholesaler/withdraw",
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "生意收缩"), bearer(wa.token())), MAP).getBody();
        assertThat(apply).isNotNull();
        assertThat(apply.getCode()).isEqualTo(50323);

        // 结清 → precheck 转真、发起放行（库存/单据本就为零）
        TenantContext.clear();
        billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, unsettled.getId())
                .set(Bill::getStatus, Bill.STATUS_PAID)
                .set(Bill::getPaidAmount, new BigDecimal("10.00")));
        R<Map<String, Object>> precheck2 = restTemplate.exchange(
                base + "/api/v1/wholesaler/withdraw/precheck",
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(precheck2).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> billing2 = (Map<String, Object>) precheck2.getData().get("billing");
        assertThat(billing2.get("cleared")).isEqualTo(true);
        R<Map<String, Object>> apply2 = restTemplate.exchange(base + "/api/v1/wholesaler/withdraw",
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "生意收缩"), bearer(wa.token())), MAP).getBody();
        assertThat(apply2).isNotNull();
        assertThat(apply2.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("R13-02 审批复检双保险：发起后新出未结账单 → TA 审批通过被 50323 拦截")
    void r13_auditRecheckBlocked() {
        TaContext ta = registerTaActive();
        WaContext wa = onboardWa(ta);

        // 无账单时发起成功
        R<Map<String, Object>> apply = restTemplate.exchange(base + "/api/v1/wholesaler/withdraw",
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "搬迁"), bearer(wa.token())), MAP).getBody();
        assertThat(apply).isNotNull();
        assertThat(apply.getCode()).isEqualTo(0);
        String appId = apply.getData().get("applicationId").toString();

        // 审批前月度出账产生未结账单（模拟月初 Job 出尾款）
        seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(1), Bill.STATUS_DISPATCHED, "8.00", "0.00");

        // TA 审批通过 → 复检 50323（双保险，50312/50314 同构）
        R<Map<String, Object>> audit = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-withdraw-applications/" + appId + "/audit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "APPROVED", "remark", "同意"), bearer(ta.token())), MAP).getBody();
        assertThat(audit).isNotNull();
        assertThat(audit.getCode()).isEqualTo(50323);
        // 商户保持 ACTIVE（审批中止，事务回滚）
        TenantContext.clear();
        assertThat(wholesalerMapper.selectById(wa.wholesalerId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("R14-01 强制下架联动：DISPATCHED/PENDING_PAYMENT/PARTIAL_PAID 三态标 DISPUTED、DRAFT/PAID 不动、通知 ST")
    void r14_forceOfflineMarksDisputed() {
        TaContext ta = registerTaActive();
        LoginVo stUser = null;
        // 先注册 ST 收通知
        Map<String, Object> inviteDto = new LinkedHashMap<>();
        inviteDto.put("role", "ST");
        inviteDto.put("maxUses", 1);
        inviteDto.put("expiresInDays", 7);
        R<Map<String, Object>> invite = restTemplate.exchange(base + "/api/v1/tenant/employee-invites",
                HttpMethod.POST, new HttpEntity<>(inviteDto, bearer(ta.token())), MAP).getBody();
        assertThat(invite).isNotNull();
        stUser = registerAndLogin(uniquePhone(P_EMP), "EmpPass123", "ST",
                invite.getData().get("code").toString());
        WaContext wa = onboardWa(ta);

        Bill dispatched = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(1), Bill.STATUS_DISPATCHED, "10.00", "0.00");
        Bill pending = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(2), Bill.STATUS_PENDING_PAYMENT, "10.00", "0.00");
        Bill partial = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(3), Bill.STATUS_PARTIAL_PAID, "10.00", "4.00");
        Bill draft = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(4), Bill.STATUS_DRAFT, "10.00", "0.00");
        Bill paid = seedBill(ta.tenantId(), wa.wholesalerId(),
                YearMonth.now().minusMonths(5), Bill.STATUS_PAID, "10.00", "10.00");

        // FOF-S1-04 真实流转：TA 强制下架 → 三态批量 CAS→DISPUTED（同事务）
        R<Map<String, Object>> offline = restTemplate.exchange(
                base + "/api/v1/tenant/wholesalers/" + wa.wholesalerId() + "/force-offline",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "多次拖欠仓储费"), bearer(ta.token())), MAP).getBody();
        assertThat(offline).isNotNull();
        assertThat(offline.getCode()).isEqualTo(0);

        TenantContext.clear();
        assertThat(billMapper.selectById(dispatched.getId()).getStatus()).isEqualTo(Bill.STATUS_DISPUTED);
        assertThat(billMapper.selectById(pending.getId()).getStatus()).isEqualTo(Bill.STATUS_DISPUTED);
        assertThat(billMapper.selectById(partial.getId()).getStatus()).isEqualTo(Bill.STATUS_DISPUTED);
        // DRAFT 未对外不标；PAID 已结清不标
        assertThat(billMapper.selectById(draft.getId()).getStatus()).isEqualTo(Bill.STATUS_DRAFT);
        assertThat(billMapper.selectById(paid.getId()).getStatus()).isEqualTo(Bill.STATUS_PAID);

        // 通知 ST（含张数）
        List<Notification> notices = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, ta.tenantId())
                .eq(Notification::getType, Notification.TYPE_BILL_DISPUTED_MARKED)
                .eq(Notification::getRecipientUserId, stUser.getUserId()));
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).getContent()).contains("3 张");

        // 下架后新生成账单直落 DISPUTED（生成侧口径，GEN-05 同证）：此处以 OFFLINE 商户直驱生成复核
        YearMonth month = YearMonth.now().minusMonths(6);
        TenantContext.clear();
        BillingRule rule = new BillingRule();
        rule.setId(snowflakeIdUtil.nextId());
        rule.setTenantId(ta.tenantId());
        rule.setQtyEnabled(1);
        rule.setPalletEnabled(0);
        rule.setPricePerQtyDay(BigDecimal.ONE);
        rule.setMinCharge(BigDecimal.ZERO);
        rule.setEffectiveFrom(month.minusMonths(1).atDay(1));
        rule.setVersion(1);
        billingRuleMapper.insert(rule);
        StockMovement m = new StockMovement();
        m.setId(snowflakeIdUtil.nextId());
        m.setTenantId(ta.tenantId());
        m.setWholesalerId(wa.wholesalerId());
        m.setSkuId(snowflakeIdUtil.nextId());
        m.setType(StockMovement.TYPE_INBOUND);
        m.setQty(2);
        m.setBizTime(month.atDay(5).atTime(10, 0));
        m.setPalletDelta(0);
        m.setCreatedAt(month.atDay(5).atTime(10, 0));
        stockMovementMapper.insert(m);
        Bill tail = billingService.generateForPair(ta.tenantId(), wa.wholesalerId(), month);
        assertThat(tail).isNotNull();
        assertThat(tail.getStatus()).isEqualTo(Bill.STATUS_DISPUTED);
    }
}
