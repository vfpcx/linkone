package com.cangchu.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.billing.entity.BillingRule;
import com.cangchu.billing.mapper.BillingRuleMapper;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.TenantSettings;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.TenantSettingsMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 W1 计费规则场景测试（14 §2 / 13-p4-prd §1，合并闸门用例）：
 * 首存 effective_from=首次保存日（免 confirmed）/ 变更需 confirmed(40003) / 新行生效+旧行留痕(to=from−1) /
 * 同日覆写 / 幂等空转 / 50379 四路 / billing_dim 镜像三值(QTY/PALLET/BOTH) / 无规则空态契约 /
 * 幽灵字段 billingDim 忽略不落库（§2.6 缺陷收口证据）/ 越权矩阵（TA 写、ST 读、WE·WK·WA 拒）/
 * 通知仅 ACTIVE 在驻商户管理员。
 *
 * <p>测试基建沿用 {@link com.cangchu.tenant.WeEmployeeScenarioTest}（HTTP 主链 + mapper seed 混合）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingRuleScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantSettingsMapper tenantSettingsMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private BillingRuleMapper billingRuleMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_EMP =
            "15" + String.format("%05d", ((System.nanoTime() >> 7) & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", ((System.nanoTime() >> 3) & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String base;
    private String baseRules;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        baseRules = base + "/api/v1/tenant/billing-rules";
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

    private R<LoginVo> registerRaw(String phone, String password, String role, String inviteCode) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        if (inviteCode != null) dto.setInviteCode(inviteCode);
        return restTemplate.exchange(base + "/api/v1/account/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    private LoginVo registerAndLogin(String phone, String password, String role, String inviteCode) {
        R<LoginVo> body = registerRaw(phone, password, role, inviteCode);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData();
    }

    private record TaContext(String phone, String token, Long tenantId) {}
    private record WaContext(String token, Long userId, Long wholesalerId) {}

    /** 注册 TA + apply 建仓 + 置 ACTIVE。 */
    private TaContext registerTaActive() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA", null).getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("计费仓-" + phone);
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
        return new TaContext(phone, token, tenantId);
    }

    /** TA 生 WK/ST 员工注册码 → 凭码注册员工，返回其 token。 */
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

    /** 完整入驻一个 WA（注册 → 自助申请 → TA 通过），返回上下文。 */
    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA", null);
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "计费商户-" + phone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(reg.getToken())), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();
        Map<String, Object> auditDto = Map.of("action", "APPROVED", "remark", "P4 测试放行");
        R<Map<String, Object>> approved = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        long wholesalerId = Long.parseLong(approved.getData().get("wholesalerId").toString());
        return new WaContext(reg.getToken(), reg.getUserId(), wholesalerId);
    }

    private Map<String, Object> ruleDto(Boolean byQty, String priceQty, Boolean byPallet, String pricePallet,
                                        Boolean confirmed) {
        Map<String, Object> dto = new LinkedHashMap<>();
        if (byQty != null) dto.put("billingByQty", byQty);
        if (priceQty != null) dto.put("pricePerQtyDay", priceQty);
        if (byPallet != null) dto.put("billingByPallet", byPallet);
        if (pricePallet != null) dto.put("pricePerPalletDay", pricePallet);
        if (confirmed != null) dto.put("confirmed", confirmed);
        return dto;
    }

    private R<Map<String, Object>> postRule(String token, Map<String, Object> dto) {
        return restTemplate.exchange(baseRules, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    private R<Map<String, Object>> getRules(String token) {
        return restTemplate.exchange(baseRules, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
    }

    private String billingDimOf(TaContext ta) {
        TenantContext.clear();
        TenantSettings settings = tenantSettingsMapper.selectOne(
                new LambdaQueryWrapper<TenantSettings>().eq(TenantSettings::getTenantId, ta.tenantId()));
        return settings != null ? settings.getBillingDim() : null;
    }

    private List<BillingRule> rowsOf(TaContext ta) {
        TenantContext.clear();
        return billingRuleMapper.selectList(new LambdaQueryWrapper<BillingRule>()
                .eq(BillingRule::getTenantId, ta.tenantId())
                .orderByAsc(BillingRule::getVersion));
    }

    private long notifyCount(Long userId) {
        TenantContext.clear();
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, userId)
                .eq(Notification::getType, Notification.TYPE_BILLING_RULE_CHANGED));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("BR-W1-01 无规则空态契约：current=null + history=[]（TA 与 ST 读同形）")
    void brW1_01_emptyStateContract() {
        TaContext ta = registerTaActive();
        R<Map<String, Object>> body = getRules(ta.token());
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData()).as("空态仍须返回数据体").isNotNull();
        assertThat(body.getData().get("current")).as("无规则 current 必须为 null").isNull();
        assertThat(body.getData().get("history")).as("无规则 history 必须为空数组").isEqualTo(List.of());

        // ST 只读同契约
        String stToken = registerEmployee(ta, "ST");
        R<Map<String, Object>> st = getRules(stToken);
        assertThat(st).isNotNull();
        assertThat(st.getCode()).isEqualTo(0);
        assertThat(st.getData().get("current")).isNull();
        assertThat(st.getData().get("history")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("BR-W1-02 首存：免 confirmed、effective_from=首次保存日、version=1、镜像 QTY、单价真实落库")
    void brW1_02_firstSaveEffectiveFromToday() {
        TaContext ta = registerTaActive();
        R<Map<String, Object>> saved = postRule(ta.token(), ruleDto(true, "0.55", false, null, null));
        assertThat(saved).isNotNull();
        assertThat(saved.getCode()).as("首存无需二次确认").isEqualTo(0);

        List<BillingRule> rows = rowsOf(ta);
        assertThat(rows).hasSize(1);
        BillingRule row = rows.get(0);
        assertThat(row.getEffectiveFrom()).as("首存生效日=首次保存日（D-P4-4）").isEqualTo(LocalDate.now());
        assertThat(row.getEffectiveTo()).isNull();
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getQtyEnabled()).isEqualTo(1);
        assertThat(row.getPalletEnabled()).isEqualTo(0);
        assertThat(row.getPricePerQtyDay()).as("单价真实落库（§2.6 缺陷收口证据）")
                .isEqualByComparingTo(new BigDecimal("0.55"));
        assertThat(row.getPricePerPalletDay()).isNull();
        assertThat(row.getWholesalerId()).as("per-WA 留位恒 NULL").isNull();
        assertThat(row.getMinCharge()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(billingDimOf(ta)).isEqualTo("QTY");

        // GET 回显：current 有值、history 空
        R<Map<String, Object>> read = getRules(ta.token());
        assertThat(read).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) read.getData().get("current");
        assertThat(current).isNotNull();
        assertThat(current.get("version")).isEqualTo(1);
        assertThat(new BigDecimal(current.get("pricePerQtyDay").toString()))
                .isEqualByComparingTo(new BigDecimal("0.55"));
        assertThat(read.getData().get("history")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("BR-W1-03 50379 四路：无维度 / 件·天缺价 / 托盘·天缺价 / 单价非法（负数、>4 位小数）")
    void brW1_03_invalidFourWays() {
        TaContext ta = registerTaActive();
        assertThat(postRule(ta.token(), ruleDto(false, null, false, null, null)).getCode())
                .as("两维均未启用").isEqualTo(50379);
        assertThat(postRule(ta.token(), ruleDto(true, null, false, null, null)).getCode())
                .as("件·天启用缺单价").isEqualTo(50379);
        assertThat(postRule(ta.token(), ruleDto(false, null, true, null, null)).getCode())
                .as("托盘·天启用缺单价").isEqualTo(50379);
        assertThat(postRule(ta.token(), ruleDto(true, "-0.1", false, null, null)).getCode())
                .as("单价为负").isEqualTo(50379);
        assertThat(postRule(ta.token(), ruleDto(true, "0.12345", false, null, null)).getCode())
                .as("单价超 4 位小数").isEqualTo(50379);
        assertThat(rowsOf(ta)).as("非法请求不落任何行").isEmpty();
    }

    @Test
    @DisplayName("BR-W1-04 R20 凭据：真实变更缺 confirmed → 40003；相同内容幂等空转（免 confirmed、不计版本）")
    void brW1_04_changeRequiresConfirmed() {
        TaContext ta = registerTaActive();
        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode()).isEqualTo(0);

        // 真实变更缺 confirmed → 40003，链上不动
        assertThat(postRule(ta.token(), ruleDto(true, "0.80", false, null, null)).getCode())
                .as("变更缺二次确认凭据").isEqualTo(40003);
        List<BillingRule> rows = rowsOf(ta);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPricePerQtyDay()).isEqualByComparingTo(new BigDecimal("0.50"));

        // 完全相同 → 幂等空转（无需 confirmed，不新增版本）
        assertThat(postRule(ta.token(), ruleDto(true, "0.5000", false, null, null)).getCode())
                .as("等值（0.5000=0.50）幂等空转").isEqualTo(0);
        rows = rowsOf(ta);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-W1-05 变更走新行生效+旧行留痕：旧行 to=新 from−1、新行 version+1 当日生效")
    void brW1_05_changeNewRowOldRowKept() {
        TaContext ta = registerTaActive();
        LocalDate today = LocalDate.now();

        // seed 一条 10 天前生效的首版（模拟非同日变更；HTTP 首存只能落当日，走 mapper 造历史）
        BillingRule old = new BillingRule();
        old.setId(snowflakeIdUtil.nextId());
        old.setTenantId(ta.tenantId());
        old.setQtyEnabled(1);
        old.setPalletEnabled(0);
        old.setPricePerQtyDay(new BigDecimal("0.30"));
        old.setMinCharge(BigDecimal.ZERO);
        old.setEffectiveFrom(today.minusDays(10));
        old.setEffectiveTo(null);
        old.setVersion(1);
        billingRuleMapper.insert(old);
        TenantContext.clear();

        R<Map<String, Object>> changed = postRule(ta.token(),
                ruleDto(true, "0.60", true, "5.00", true));
        assertThat(changed).isNotNull();
        assertThat(changed.getCode()).isEqualTo(0);

        List<BillingRule> rows = rowsOf(ta);
        assertThat(rows).hasSize(2);
        BillingRule v1 = rows.get(0);
        BillingRule v2 = rows.get(1);
        assertThat(v1.getEffectiveTo()).as("旧行留痕：关至新版 from−1").isEqualTo(today.minusDays(1));
        assertThat(v1.getPricePerQtyDay()).as("旧行单价不被改写").isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getEffectiveFrom()).as("变更日按新规则（05 §1.5）").isEqualTo(today);
        assertThat(v2.getEffectiveTo()).isNull();
        assertThat(v2.getPricePerQtyDay()).isEqualByComparingTo(new BigDecimal("0.60"));
        assertThat(v2.getPricePerPalletDay()).isEqualByComparingTo(new BigDecimal("5.00"));

        // GET：current=v2，history=[v1]
        R<Map<String, Object>> read = getRules(ta.token());
        assertThat(read).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) read.getData().get("current");
        assertThat(current.get("version")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) read.getData().get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("version")).isEqualTo(1);
        assertThat(history.get(0).get("effectiveTo")).isNotNull();
    }

    @Test
    @DisplayName("BR-W1-06 同日多次变更覆写当日行：一日一版、version 不递增、最后一次生效")
    void brW1_06_sameDayOverwrite() {
        TaContext ta = registerTaActive();
        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode()).isEqualTo(0);
        assertThat(postRule(ta.token(), ruleDto(true, "0.90", false, null, true)).getCode()).isEqualTo(0);

        List<BillingRule> rows = rowsOf(ta);
        assertThat(rows).as("同日覆写不产生第二行（uk 一日一版）").hasSize(1);
        assertThat(rows.get(0).getVersion()).isEqualTo(1);
        assertThat(rows.get(0).getPricePerQtyDay()).isEqualByComparingTo(new BigDecimal("0.90"));
        assertThat(rows.get(0).getEffectiveTo()).isNull();
    }

    @Test
    @DisplayName("BR-W1-07 billing_dim 镜像三值联动：QTY → PALLET → BOTH（规则保存事务同步）")
    void brW1_07_mirrorThreeValues() {
        TaContext ta = registerTaActive();
        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode()).isEqualTo(0);
        assertThat(billingDimOf(ta)).isEqualTo("QTY");

        assertThat(postRule(ta.token(), ruleDto(false, null, true, "5.00", true)).getCode()).isEqualTo(0);
        assertThat(billingDimOf(ta)).isEqualTo("PALLET");

        assertThat(postRule(ta.token(), ruleDto(true, "0.50", true, "5.00", true)).getCode()).isEqualTo(0);
        assertThat(billingDimOf(ta)).as("双维并存 → BOTH 新枚举（14 §2.1-3）").isEqualTo("BOTH");
    }

    @Test
    @DisplayName("BR-W1-08 幽灵字段收口：PUT /tenant/me 携带 billingDim 被忽略，镜像不被通用设置接口污染")
    void brW1_08_ghostFieldIgnored() {
        TaContext ta = registerTaActive();
        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode()).isEqualTo(0);
        assertThat(billingDimOf(ta)).isEqualTo("QTY");

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("billingDim", "PALLET");   // 已废弃幽灵字段
        settings.put("intro", "P4 契约收口测试");
        R<Map<String, Object>> put = restTemplate.exchange(base + "/api/v1/tenant/me", HttpMethod.PUT,
                new HttpEntity<>(settings, bearer(ta.token())), MAP).getBody();
        assertThat(put).isNotNull();
        assertThat(put.getCode()).as("旧客户端兼容：不报错").isEqualTo(0);

        assertThat(billingDimOf(ta)).as("billing_dim 只由规则事务镜像写入").isEqualTo("QTY");
        List<BillingRule> rows = rowsOf(ta);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPricePerQtyDay()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    @DisplayName("BR-W1-09 越权矩阵：TA 写通过；ST 读通过写 42001；WK 读写 42001；WA 读 42001")
    void brW1_09_permissionMatrix() {
        TaContext ta = registerTaActive();
        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode())
                .as("TA 写").isEqualTo(0);

        String stToken = registerEmployee(ta, "ST");
        R<Map<String, Object>> stRead = getRules(stToken);
        assertThat(stRead).isNotNull();
        assertThat(stRead.getCode()).as("ST 只读放行").isEqualTo(0);
        assertThat(stRead.getData().get("current")).isNotNull();
        assertThat(postRule(stToken, ruleDto(true, "0.99", false, null, true)).getCode())
                .as("ST 写拒绝").isEqualTo(42001);

        String wkToken = registerEmployee(ta, "WK");
        assertThat(getRules(wkToken).getCode()).as("WK 读拒绝").isEqualTo(42001);
        assertThat(postRule(wkToken, ruleDto(true, "0.99", false, null, true)).getCode())
                .as("WK 写拒绝").isEqualTo(42001);

        WaContext wa = onboardWa(ta);
        assertThat(getRules(wa.token()).getCode()).as("WA 读拒绝").isEqualTo(42001);
        assertThat(postRule(wa.token(), ruleDto(true, "0.99", false, null, true)).getCode())
                .as("WA 写拒绝").isEqualTo(42001);

        // WE：WA 生码 → 凭码注册 → 读拒绝 42004（员工语义文案）
        Map<String, Object> weInvite = new LinkedHashMap<>();
        weInvite.put("maxUses", 1);
        weInvite.put("expireDays", 7);
        R<Map<String, Object>> weInviteRes = restTemplate.exchange(
                base + "/api/v1/wholesaler/employee-invites",
                HttpMethod.POST, new HttpEntity<>(weInvite, bearer(wa.token())), MAP).getBody();
        assertThat(weInviteRes).isNotNull();
        assertThat(weInviteRes.getCode()).as("WA 生 WE 码").isEqualTo(0);
        String weCode = weInviteRes.getData().get("code").toString();
        String weToken = registerAndLogin(uniquePhone(P_EMP), "WePass123", "TA" /* 入口 role 被码覆盖 */, weCode)
                .getToken();
        assertThat(getRules(weToken).getCode()).as("WE 读拒绝 42004").isEqualTo(42004);
        assertThat(postRule(weToken, ruleDto(true, "0.99", false, null, true)).getCode())
                .as("WE 写拒绝（无 TA 角色 42001）").isEqualTo(42001);

        // 越权尝试后规则链未被污染
        List<BillingRule> rows = rowsOf(ta);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPricePerQtyDay()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    @DisplayName("BR-W1-10 通知仅 ACTIVE 在驻商户管理员；幂等空转不通知")
    void brW1_10_notifyOnlyActiveWa() {
        TaContext ta = registerTaActive();
        WaContext waActive = onboardWa(ta);
        WaContext waOffline = onboardWa(ta);
        // 将第二个商户置 OFFLINE（mapper 直改，TenantContext 清空绕过行级过滤）
        TenantContext.clear();
        Wholesaler ws = wholesalerMapper.selectById(waOffline.wholesalerId());
        ws.setStatus("OFFLINE");
        wholesalerMapper.updateById(ws);
        TenantContext.clear();

        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode()).isEqualTo(0);
        assertThat(notifyCount(waActive.userId())).as("ACTIVE 商户管理员收到规则通知").isEqualTo(1);
        assertThat(notifyCount(waOffline.userId())).as("非 ACTIVE 商户不通知").isZero();

        // 幂等空转不通知
        assertThat(postRule(ta.token(), ruleDto(true, "0.50", false, null, null)).getCode()).isEqualTo(0);
        assertThat(notifyCount(waActive.userId())).isEqualTo(1);

        // 真实变更再通知一次
        assertThat(postRule(ta.token(), ruleDto(true, "0.70", false, null, true)).getCode()).isEqualTo(0);
        assertThat(notifyCount(waActive.userId())).isEqualTo(2);
        assertThat(notifyCount(waOffline.userId())).isZero();
    }
}
