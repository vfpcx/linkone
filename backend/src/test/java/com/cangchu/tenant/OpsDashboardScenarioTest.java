package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.mapper.ArbitrationMapper;
import com.cangchu.notify.dto.AnnouncementCreateDto;
import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.TenantAuditDto;
import com.cangchu.tenant.dto.WholesalerApplyDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS 平台运营控制台真实接口场景测试（P5-C，21 §6）。
 *
 * <p>基建同 TenantDashboardScenarioTest：@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 +
 * mock 短信 888888。造数=HTTP 主链 + mapper seed 混合（aggregation 只消费归属与计数，无外键）。
 *
 * <p>⚠️ OPS 控制台为<b>平台级全局统计</b>（非 tenant 维度），同一 H2 内跨用例会叠加计数——
 * 因此本类全部用例采用<b>基线差分</b>断言：先 snap 当前值，执行动作后断言 delta，与执行顺序/类间残留无关。
 *
 * <p>口径速查（21 §3）：platform=全局规模（ACTIVE 仓/APPROVED 绑定/ACTIVE 黑名单）；
 * pending=OPS 待办（PENDING 仓 / OUTBOUND_COMPLAINT∧PENDING 客诉 / DRAFT 公告草稿）；
 * today=今日 0 点起（新仓 / 新客诉）。越权非 OPS → 42002。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsDashboardScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ArbitrationMapper arbitrationMapper;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseAccount;
    private String baseWholesaler;
    private String baseAdmin;
    private String baseOps;
    private String opsToken;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
        baseWholesaler = "http://localhost:" + port + "/api/v1/wholesaler";
        baseAdmin = "http://localhost:" + port + "/api/v1/admin/tenant";
        baseOps = "http://localhost:" + port + "/api/v1/ops";
        opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Object>> OBJ = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID = new ParameterizedTypeReference<>() {};

    // ======================================================================
    // 基建（照 TenantDashboardScenarioTest）
    // ======================================================================

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

    /** 注册 TA + apply 建 PENDING 租户（不审核）。 */
    private TaContext registerTaPending() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("OPS台仓库-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, token, tenantId);
    }

    /** 注册 TA + apply + OPS 审核通过（ACTIVE）。 */
    private TaContext registerTaActive() {
        TaContext pending = registerTaPending();
        auditTenant(pending.tenantId(), "APPROVED", "auto");
        return pending;
    }

    private void auditTenant(Long tenantId, String action, String remark) {
        TenantAuditDto auditDto = new TenantAuditDto();
        auditDto.setAction(action);
        auditDto.setRemark(remark);
        R<Void> audit = restTemplate.exchange(baseAdmin + "/" + tenantId + "/audit", HttpMethod.POST,
                new HttpEntity<>(auditDto, bearer(registerAndLogin(uniquePhone(P_OPS), "OPS"))), VOID).getBody();
        assertThat(audit).isNotNull();
        assertThat(audit.getCode()).as("audit tenant %s action=%s", tenantId, action).isEqualTo(0);
    }

    /** WA 申请入驻某仓 + TA 审核通过（形成 APPROVED 绑定）。 */
    private void waApplyAndApprove(String waToken, TaContext ta) {
        WholesalerApplyDto apply = new WholesalerApplyDto();
        apply.setTargetTenantId(ta.tenantId().toString());
        apply.setName("商户-" + SEQ.incrementAndGet());
        apply.setContactName("张三");
        apply.setContactPhone("138" + String.format("%08d", SEQ.incrementAndGet() % 100000000));
        R<Map<String, Object>> applied = restTemplate.exchange(baseWholesaler + "/applications", HttpMethod.POST,
                new HttpEntity<>(apply, bearer(waToken)), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).as("WA 申请入驻").isEqualTo(0);
        Long appId = Long.valueOf(applied.getData().get("applicationId").toString());

        WholesalerApplicationAuditDto audit = new WholesalerApplicationAuditDto();
        audit.setAction("APPROVED");
        audit.setRemark("ok");
        R<Map<String, Object>> decided = restTemplate.exchange(
                baseTenant + "/wholesaler-applications/" + appId + "/audit", HttpMethod.POST,
                new HttpEntity<>(audit, bearer(ta.token())), MAP).getBody();
        assertThat(decided).isNotNull();
        assertThat(decided.getCode()).as("TA 审核通过").isEqualTo(0);
    }

    // ======================================================================
    // OPS dashboard 读取（基线差分）
    // ======================================================================

    private record Snap(long activeTenants, long bindings, long blacklist, long tenantAudits,
                        long complaints, long drafts, long newTenants, long newComplaints) {}

    /** 调 /ops/dashboard 断言成功并返回全字段基线快照。 */
    private Snap snap() {
        R<Map<String, Object>> body = restTemplate.exchange(baseOps + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("ops dashboard 应成功返回").isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) body.getData().get("platform");
        @SuppressWarnings("unchecked")
        Map<String, Object> q = (Map<String, Object>) body.getData().get("pending");
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) body.getData().get("today");
        return new Snap(
                num(p.get("activeTenantCount")), num(p.get("wholesalerBindingCount")), num(p.get("activeBlacklistCount")),
                num(q.get("pendingTenantAudits")), num(q.get("pendingComplaints")), num(q.get("draftAnnouncements")),
                num(t.get("newTenantToday")), num(t.get("newComplaintsToday")));
    }

    private long num(Object v) {
        return v == null ? -1 : Long.parseLong(v.toString());
    }

    private long seqId() {
        return (System.nanoTime() & 0x3FFFFFFF) + SEQ.incrementAndGet() * 1_000_000L;
    }

    /** seed 一条客诉仲裁单（dashboard 只消费 biz_type/status/created_at，无外键，DailySnapshot seed 先例）。返回仲裁单 id。 */
    private Long seedArbitration(Long tenantId, String bizType, String status, LocalDateTime createdAt) {
        Arbitration a = new Arbitration();
        a.setId(seqId());
        a.setDocNo("KS-SEED-" + seqId());
        a.setTenantId(tenantId);
        a.setBizType(bizType);
        a.setRefDocType("OUTBOUND");
        a.setRefDocId(seqId());
        a.setRefDocNo("SEED-OUT-" + seqId());
        a.setWholesalerId(seqId());
        a.setInitiatorUserId(seqId());
        a.setInitiatorRole("WA");
        a.setReason("seed 客诉");
        a.setStatus(status);
        a.setCreatedAt(createdAt);
        a.setUpdatedAt(createdAt);
        arbitrationMapper.insert(a);
        return a.getId();
    }

    // ======================================================================
    // S1 正常：聚合口径
    // ======================================================================

    @Test
    @DisplayName("OPS-01 平台规模+租户待办：审核过 ACTIVE +1、留 PENDING +1、驳回不计，今日新仓 +3")
    void ops01_platformScaleAndTenantAudits() {
        Snap base = snap();

        registerTaActive();   // ACTIVE
        registerTaPending();  // 留 PENDING
        TaContext rejected = registerTaPending();
        auditTenant(rejected.tenantId(), "REJECTED", "资料不符");

        Snap after = snap();
        assertThat(after.activeTenants()).as("审核通过 +1").isEqualTo(base.activeTenants() + 1);
        assertThat(after.tenantAudits()).as("PENDING 待审 +1（REJECTED 不计）").isEqualTo(base.tenantAudits() + 1);
        assertThat(after.newTenants()).as("今日新仓 = 3 次 apply 全部计入").isEqualTo(base.newTenants() + 3);
    }

    @Test
    @DisplayName("OPS-02 绑定数（多仓口径）：同账号入驻两仓 → APPROVED 绑定 +2")
    void ops02_bindingsMultiWarehouse() {
        Snap base = snap();

        TaContext taA = registerTaActive();
        TaContext taB = registerTaActive();
        String waToken = registerAndLogin(uniquePhone(P_WA), "WA");
        waApplyAndApprove(waToken, taA);
        waApplyAndApprove(waToken, taB);

        assertThat(snap().bindings()).as("绑定数 +2（一账号 N 仓计 N）").isEqualTo(base.bindings() + 2);
    }

    @Test
    @DisplayName("OPS-03 客诉仲裁待办：OUTBOUND_COMPLAINT∧PENDING +1；裁决 DECIDED 后归零")
    void ops03_pendingComplaints() {
        Snap base = snap();
        Long tenantId = registerTaActive().tenantId();

        Long arbId = seedArbitration(tenantId, Arbitration.BIZ_OUTBOUND_COMPLAINT,
                Arbitration.STATUS_PENDING, LocalDateTime.now());
        assertThat(snap().complaints()).as("待裁客诉 +1").isEqualTo(base.complaints() + 1);

        // OPS 裁决后 DECIDED → 归零（仲裁流程本身由 ArbitrationScenarioTest 覆盖，此处只测计数）
        Arbitration upd = new Arbitration();
        upd.setId(arbId);
        upd.setStatus(Arbitration.STATUS_DECIDED);
        arbitrationMapper.updateById(upd);
        assertThat(snap().complaints()).as("裁决后待办归零").isEqualTo(base.complaints());
    }

    @Test
    @DisplayName("OPS-04 黑名单：OPS 加黑 ACTIVE +1；解除 REMOVED 后归零")
    void ops04_blacklistActive() {
        Snap base = snap();

        BlacklistAddDto dto = new BlacklistAddDto();
        dto.setTargetType("PHONE");
        dto.setTargetValue(uniquePhone(P_WA));
        dto.setReason("测试加黑");
        R<Map<String, Object>> added = restTemplate.exchange(baseOps + "/blacklist", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
        assertThat(added).isNotNull();
        assertThat(added.getCode()).as("加黑成功").isEqualTo(0);
        Long entryId = Long.valueOf(added.getData().get("id").toString());

        assertThat(snap().blacklist()).as("生效黑名单 +1").isEqualTo(base.blacklist() + 1);

        R<Void> removed = restTemplate.exchange(baseOps + "/blacklist/" + entryId, HttpMethod.DELETE,
                new HttpEntity<>(bearer(opsToken)), VOID).getBody();
        assertThat(removed).isNotNull();
        assertThat(removed.getCode()).as("解除黑名单").isEqualTo(0);
        assertThat(snap().blacklist()).as("解除后归零").isEqualTo(base.blacklist());
    }

    @Test
    @DisplayName("OPS-05 公告草稿：创建 DRAFT +1；发布 PUBLISHED 后归零")
    void ops05_draftAnnouncements() {
        Snap base = snap();

        AnnouncementCreateDto dto = new AnnouncementCreateDto();
        dto.setTitle("测试公告-" + SEQ.incrementAndGet());
        dto.setContent("控制台计数验证正文");
        dto.setTargetRoles(List.of("TA"));
        R<Object> created = restTemplate.exchange(baseOps + "/announcements", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), OBJ).getBody();
        assertThat(created).isNotNull();
        assertThat(created.getCode()).as("创建公告草稿").isEqualTo(0);
        Long id = Long.valueOf(created.getData().toString());

        assertThat(snap().drafts()).as("公告草稿 +1").isEqualTo(base.drafts() + 1);

        R<Void> published = restTemplate.exchange(baseOps + "/announcements/" + id + "/publish", HttpMethod.POST,
                new HttpEntity<>(bearer(opsToken)), VOID).getBody();
        assertThat(published).isNotNull();
        assertThat(published.getCode()).as("发布公告").isEqualTo(0);
        assertThat(snap().drafts()).as("发布后归零").isEqualTo(base.drafts());
    }

    @Test
    @DisplayName("OPS-06 今日动态跨日隔离：昨日客诉不计、今日客诉 +1")
    void ops06_todayCountsCrossDay() {
        Snap base = snap();
        Long tenantId = registerTaActive().tenantId();

        seedArbitration(tenantId, Arbitration.BIZ_OUTBOUND_COMPLAINT, Arbitration.STATUS_DECIDED,
                LocalDateTime.now().minusDays(1));
        assertThat(snap().newComplaints()).as("昨日客诉不计").isEqualTo(base.newComplaints());

        seedArbitration(tenantId, Arbitration.BIZ_OUTBOUND_COMPLAINT, Arbitration.STATUS_PENDING, LocalDateTime.now());
        assertThat(snap().newComplaints()).as("今日客诉 +1").isEqualTo(base.newComplaints() + 1);
    }

    // ======================================================================
    // S2 越权
    // ======================================================================

    @Test
    @DisplayName("OPS-S2-01 非 OPS 调 ops/dashboard：TA/WA → 42002")
    void opsS2_01_nonOpsRejected() {
        String taToken = registerAndLogin(uniquePhone(P_TA), "TA");
        R<Map<String, Object>> ta = restTemplate.exchange(baseOps + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(taToken)), MAP).getBody();
        assertThat(ta).isNotNull();
        assertThat(ta.getCode()).as("TA 应 42002").isEqualTo(42002);

        String waToken = registerAndLogin(uniquePhone(P_WA), "WA");
        R<Map<String, Object>> wa = restTemplate.exchange(baseOps + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(waToken)), MAP).getBody();
        assertThat(wa).isNotNull();
        assertThat(wa.getCode()).as("WA 应 42002").isEqualTo(42002);
    }
}
