package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.mapper.BatchMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.TenantAuditDto;
import com.cangchu.tenant.dto.WholesalerApplyDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.entity.CapacityPublish;
import com.cangchu.tenant.mapper.CapacityPublishMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TA 租户工作台真实接口场景测试（P5-C，19 §6）。
 *
 * <p>基建同 TenantScenarioTest：@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 +
 * mock 短信 888888。造数策略=HTTP 主链 + mapper seed 混合（DailySnapshotScenarioTest 先例）：
 * 入驻申请/权限走 HTTP 主链；容量快照/批次/今日单据直造（dashboard 聚合只消费归属与计数，无外键）。
 *
 * <p>口径速查（19 §3）：KPI=审批中心同口径（PENDING 计数）；capacity=精确值
 * （TA 本人视角，无 TIER 脱敏）；today=今日 0 点起创建的单据（不限状态）；
 * expiringBatches=expiry_date ≤ 今日+3 且 status ∉ (CLEARED/CLOSED/SOLD_OUT)，
 * 批次未启用恒 0。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantDashboardScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CapacityPublishMapper capacityPublishMapper;
    @Autowired
    private BatchMapper batchMapper;
    @Autowired
    private InboundRequestMapper inboundRequestMapper;
    @Autowired
    private OutboundRequestMapper outboundRequestMapper;
    @Autowired
    private InquiryRequestMapper inquiryRequestMapper;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseAccount;
    private String baseWholesaler;
    private String baseAdmin;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
        baseWholesaler = "http://localhost:" + port + "/api/v1/wholesaler";
        baseAdmin = "http://localhost:" + port + "/api/v1/admin/tenant";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Object>> OBJ = new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private String registerAndLogin(String phone, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("TaPass123");
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + 申请仓库 + OPS 审核通过（ACTIVE） */
    private TaContext registerTaActive() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("工作台仓库-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());

        TenantAuditDto auditDto = new TenantAuditDto();
        auditDto.setAction("APPROVED");
        auditDto.setRemark("auto");
        R<Void> audit = restTemplate.exchange(baseAdmin + "/" + tenantId + "/audit", HttpMethod.POST,
                new HttpEntity<>(auditDto, bearer(registerAndLogin(uniquePhone(P_OPS), "OPS"))), VOID).getBody();
        assertThat(audit).isNotNull();
        assertThat(audit.getCode()).as("audit tenant %s", tenantId).isEqualTo(0);
        return new TaContext(phone, token, tenantId);
    }

    private static final ParameterizedTypeReference<R<Void>> VOID = new ParameterizedTypeReference<>() {};

    /** 调 dashboard 并断言成功，返回 data map */
    private Map<String, Object> dashboard(String token) {
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("dashboard 应成功返回").isEqualTo(0);
        return body.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> kpi(Map<String, Object> data) {
        return (Map<String, Object>) data.get("kpi");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capacity(Map<String, Object> data) {
        return (Map<String, Object>) data.get("capacity");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> today(Map<String, Object> data) {
        return (Map<String, Object>) data.get("today");
    }

    private long num(Object v) {
        return v == null ? -1 : Long.parseLong(v.toString());
    }

    // ======================================================================
    // S1 正常：聚合口径
    // ======================================================================

    @Test
    @DisplayName("DB-S1-01 新仓工作台全零回退：storeName + capacity 默认 + batchEnabled=0")
    void dbS1_01_newTenantAllZero() {
        TaContext ta = registerTaActive();
        Map<String, Object> data = dashboard(ta.token);

        assertThat(data.get("storeName")).as("storeName=仓库名").isEqualTo("工作台仓库-" + ta.phone());
        assertThat(num(kpi(data).get("pendingInbound"))).isEqualTo(0);
        assertThat(num(kpi(data).get("pendingCount"))).isEqualTo(0);
        assertThat(num(kpi(data).get("pendingClearance"))).isEqualTo(0);
        assertThat(num(kpi(data).get("pendingDispute"))).isEqualTo(0);

        // 无快照 → 回退 store 默认（used=0、utilization=0、snapshotAt 非空）
        assertThat(num(capacity(data).get("usedQty"))).isEqualTo(0);
        assertThat(num(capacity(data).get("usedPallet"))).isEqualTo(0);
        assertThat(num(capacity(data).get("utilization"))).isEqualTo(0);
        assertThat(capacity(data).get("snapshotAt")).isNotNull();

        assertThat(num(today(data).get("inboundCount"))).isEqualTo(0);
        assertThat(num(today(data).get("outboundCount"))).isEqualTo(0);
        assertThat(num(today(data).get("inquiryCount"))).isEqualTo(0);
        assertThat(num(today(data).get("expiringBatches"))).isEqualTo(0);

        assertThat(num(data.get("batchEnabled"))).isEqualTo(0);
    }

    @Test
    @DisplayName("DB-S1-02 待审入驻申请计数：WA 申请→1，TA 审核通过→0")
    void dbS1_02_pendingInbound() {
        TaContext ta = registerTaActive();
        String waToken = registerAndLogin(uniquePhone("17" + String.format("%05d",
                (System.nanoTime() & 0x7FFFFFFF) % 100000)), "WA");

        // WA 申请入驻
        WholesalerApplyDto apply = new WholesalerApplyDto();
        apply.setTargetTenantId(ta.tenantId().toString());
        apply.setName("商户-" + SEQ.incrementAndGet());
        apply.setContactName("张三");
        apply.setContactPhone("13800000000");
        R<Map<String, Object>> applied = restTemplate.exchange(baseWholesaler + "/applications", HttpMethod.POST,
                new HttpEntity<>(apply, bearer(waToken)), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).as("WA 申请入驻").isEqualTo(0);
        Long appId = Long.valueOf(applied.getData().get("applicationId").toString());

        assertThat(num(kpi(dashboard(ta.token)).get("pendingInbound")))
                .as("待审申请应为 1").isEqualTo(1);

        // TA 审核通过
        WholesalerApplicationAuditDto audit = new WholesalerApplicationAuditDto();
        audit.setAction("APPROVED");
        audit.setRemark("ok");
        R<Map<String, Object>> decided = restTemplate.exchange(
                baseTenant + "/wholesaler-applications/" + appId + "/audit", HttpMethod.POST,
                new HttpEntity<>(audit, bearer(ta.token)), MAP).getBody();
        assertThat(decided).isNotNull();
        assertThat(decided.getCode()).as("TA 审核通过").isEqualTo(0);

        assertThat(num(kpi(dashboard(ta.token)).get("pendingInbound")))
                .as("审核通过后应为 0").isEqualTo(0);
    }

    @Test
    @DisplayName("DB-S1-03 容量快照精确值：快照命中返回精确 used/total 与 utilization")
    void dbS1_03_capacitySnapshot() {
        TaContext ta = registerTaActive();

        CapacityPublish snap = new CapacityPublish();
        snap.setId(seqId());
        snap.setTenantId(ta.tenantId());
        snap.setStoreId(1L);
        snap.setUsedQty(3000);
        snap.setUsedPallet(10);
        snap.setTotalQty(10000);
        snap.setTotalPallet(50);
        snap.setUtilization(new BigDecimal("0.30"));
        snap.setTier("MEDIUM");
        snap.setSnapshotAt(LocalDateTime.now());
        snap.setCreatedAt(LocalDateTime.now());
        capacityPublishMapper.insert(snap);

        Map<String, Object> cap = capacity(dashboard(ta.token));
        assertThat(num(cap.get("usedQty"))).isEqualTo(3000);
        assertThat(num(cap.get("totalQty"))).isEqualTo(10000);
        assertThat(num(cap.get("usedPallet"))).isEqualTo(10);
        assertThat(num(cap.get("totalPallet"))).isEqualTo(50);
        // utilization = used/total×100 = 30（不依赖快照表值域）
        assertThat(num(cap.get("utilization"))).isEqualTo(30);
    }

    @Test
    @DisplayName("DB-S1-04 临期窗口边界：3 天内计入、4 天外不计、CLEARED 不计、未启用恒 0")
    void dbS1_04_expiringWindow() {
        TaContext ta = registerTaActive();

        // 未启用批次 → 恒 0（即使有数据）
        seedBatch(ta.tenantId(), LocalDate.now().plusDays(2), Batch.STATUS_IN_STOCK, "SEED-A");
        assertThat(num(today(dashboard(ta.token)).get("expiringBatches")))
                .as("批次未启用应恒 0").isEqualTo(0);

        // 开启批次（HTTP toggle）
        BatchToggleDto toggle = new BatchToggleDto();
        toggle.setEnable(true);
        toggle.setConfirmed(true);
        R<Object> toggled = restTemplate.exchange(baseTenant + "/settings/batch-toggle", HttpMethod.POST,
                new HttpEntity<>(toggle, bearer(ta.token)), OBJ).getBody();
        assertThat(toggled).isNotNull();
        assertThat(toggled.getCode()).as("开启批次").isEqualTo(0);
        assertThat(num(dashboard(ta.token).get("batchEnabled"))).isEqualTo(1);

        // 窗口边界：SEED-A（toggle 前 seed，IN_STOCK）与 3 天内（含）计入、4 天外不计、终态不计
        seedBatch(ta.tenantId(), LocalDate.now().plusDays(3), Batch.STATUS_IN_STOCK, "SEED-B");  // 计入
        seedBatch(ta.tenantId(), LocalDate.now().plusDays(4), Batch.STATUS_IN_STOCK, "SEED-C");  // 不计
        seedBatch(ta.tenantId(), LocalDate.now().plusDays(2), Batch.STATUS_CLEARED, "SEED-D");   // 终态不计
        seedBatch(ta.tenantId(), null, Batch.STATUS_IN_STOCK, "SEED-E");                          // 无效期不计
        // 计入 = SEED-A(today+2) + SEED-B(today+3)，共 2 批
        assertThat(num(today(dashboard(ta.token)).get("expiringBatches")))
                .as("临期 3 天内应恰为 2 批").isEqualTo(2);
    }

    @Test
    @DisplayName("DB-S1-05 今日单据计数：今日计入、昨日不计")
    void dbS1_05_todayCounts() {
        TaContext ta = registerTaActive();

        // 今日各 1 单 + 昨日 1 单（不计）
        seedInbound(ta.tenantId(), LocalDateTime.now());
        seedInbound(ta.tenantId(), LocalDateTime.now().minusDays(1));
        seedOutbound(ta.tenantId(), LocalDateTime.now());
        seedInquiry(ta.tenantId(), LocalDateTime.now());
        seedInquiry(ta.tenantId(), LocalDateTime.now().minusDays(1));

        Map<String, Object> today = today(dashboard(ta.token));
        assertThat(num(today.get("inboundCount"))).as("今日入库单数").isEqualTo(1);
        assertThat(num(today.get("outboundCount"))).as("今日出库单数").isEqualTo(1);
        assertThat(num(today.get("inquiryCount"))).as("今日询价单数").isEqualTo(1);
    }

    // ======================================================================
    // S2 WK 兼岗（WK 回 TA 台复用工作台，router meta 注释同）
    // ======================================================================

    @Test
    @DisplayName("DB-S2-01 WK 邀请码注册 → dashboard 成功且为同仓数据")
    void dbS2_01_wkCanViewDashboard() {
        TaContext ta = registerTaActive();

        // TA 生成 WK 邀请码 → WK 注册绑定
        R<Map<String, Object>> invite = restTemplate.exchange(
                baseTenant + "/invite-code?targetRole=WK&maxUses=1", HttpMethod.POST,
                new HttpEntity<>(bearer(ta.token)), MAP).getBody();
        assertThat(invite).isNotNull();
        assertThat(invite.getCode()).as("生成 WK 邀请码").isEqualTo(0);
        String code = invite.getData().get("code").toString();

        RegisterDto wkDto = new RegisterDto();
        wkDto.setPhone(uniquePhone("18" + String.format("%05d",
                (System.nanoTime() & 0x7FFFFFFF) % 100000)));
        wkDto.setPassword("TaPass123");
        wkDto.setSmsCode("888888");
        wkDto.setRole("WK");
        wkDto.setInviteCode(code);
        wkDto.setAgreedTerms(true);
        R<LoginVo> wk = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(wkDto), LOGIN_VO).getBody();
        assertThat(wk).isNotNull();
        assertThat(wk.getCode()).as("WK 邀请码注册").isEqualTo(0);

        Map<String, Object> data = dashboard(wk.getData().getToken());
        assertThat(data.get("storeName")).as("WK 应看到同仓工作台").isEqualTo("工作台仓库-" + ta.phone());
        assertThat(num(data.get("batchEnabled"))).isEqualTo(0);
    }

    // ======================================================================
    // S4 越权
    // ======================================================================

    @Test
    @DisplayName("DB-S4-01 非 TA 调 dashboard：WE→42004、OPS→42001")
    void dbS4_01_nonTaRejected() {
        // WE（未绑定租户也是 WE 角色）→ 42004
        String weToken = registerAndLogin(uniquePhone("17" + String.format("%05d",
                (System.nanoTime() & 0x7FFFFFFF) % 100000)), "WE");
        R<Map<String, Object>> we = restTemplate.exchange(baseTenant + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(weToken)), MAP).getBody();
        assertThat(we).isNotNull();
        assertThat(we.getCode()).as("WE 应 42004").isEqualTo(42004);

        // OPS → 42001
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        R<Map<String, Object>> ops = restTemplate.exchange(baseTenant + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(ops).isNotNull();
        assertThat(ops.getCode()).as("OPS 应 42001").isEqualTo(42001);
    }

    // ======================================================================
    // mapper seed 辅助
    // ======================================================================

    private long seqId() {
        return (System.nanoTime() & 0x3FFFFFFF) + SEQ.incrementAndGet() * 1_000_000L;
    }

    private void seedBatch(Long tenantId, LocalDate expiryDate, String status, String batchNo) {
        Batch b = new Batch();
        b.setId(seqId());
        b.setTenantId(tenantId);
        b.setWholesalerId(seqId());
        b.setSkuId(seqId());
        b.setBatchNo(batchNo);
        b.setExpiryDate(expiryDate);
        b.setInitialQty(100);
        b.setRemainingQty(100);
        b.setStatus(status);
        b.setSource(Batch.SOURCE_INBOUND);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        batchMapper.insert(b);
    }

    private void seedInbound(Long tenantId, LocalDateTime createdAt) {
        InboundRequest r = new InboundRequest();
        r.setId(seqId());
        r.setDocNo("SEED-INB-" + seqId());
        r.setWholesalerId(seqId());
        r.setTenantId(tenantId);
        r.setSkuId(seqId());
        r.setQty(10);
        r.setPalletQty(1);
        r.setStatus(InboundRequest.STATUS_REGISTERED);
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        inboundRequestMapper.insert(r);
    }

    private void seedOutbound(Long tenantId, LocalDateTime createdAt) {
        OutboundRequest r = new OutboundRequest();
        r.setId(seqId());
        r.setDocNo("SEED-OUT-" + seqId());
        r.setTenantId(tenantId);
        r.setWholesalerId(seqId());
        r.setSkuId(seqId());
        r.setQty(10);
        r.setStatus(OutboundRequest.STATUS_COMPLETED);
        r.setCreatedAt(createdAt);
        outboundRequestMapper.insert(r);
    }

    private void seedInquiry(Long tenantId, LocalDateTime createdAt) {
        InquiryRequest r = new InquiryRequest();
        r.setId(seqId());
        r.setDocNo("SEED-INQ-" + seqId());
        r.setStoreId(1L);
        r.setTenantId(tenantId);
        r.setWholesalerId(seqId());
        r.setStatus(InquiryRequest.STATUS_PENDING);
        r.setCreatedAt(createdAt);
        inquiryRequestMapper.insert(r);
    }
}
