package com.cangchu.account;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.TenantCreateDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
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

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-S1/G-S2 架构债偿还回归：UserService 出口等价性场景测试（2026-07-25 补测）。
 *
 * <p>3fd2467 把 tenant 域三处 UserMapper 直连（OPS 代建 TA / TA 开通 WA /
 * pageTenantsForAdmin 批量显示名）收敛为 account 域 UserService 出口，声称
 * 「幂等语义/字段/临时密码不外泄逐一等价」。本类逐条验证：
 * <ul>
 *   <li>US-*：ensureUserByPhone 幂等两路（未注册→建；已注册→原样返回不覆写）、
 *       trim 归一、临时密码契约（不返回——record 仅 userId/isNew；只落 BCrypt 哈希）、
 *       getPhone / getDisplayNames 语义。</li>
 *   <li>TA-PROXY-*：OPS 代建租户经新出口后行为与原直连一致
 *       （registerSource=OPS_PROXY、幂等复用已注册用户且不覆写其密码/来源）。</li>
 *   <li>WA-*：TA 建商户带 waPhone 开通 WA 经新出口后行为一致
 *       （registerSource=WA_PROVISION、同号重复开通不重建用户）。</li>
 *   <li>ADMIN-DN-01：pageTenantsForAdmin 申请人显示名改走 getDisplayNames 后契约不变。</li>
 * </ul>
 *
 * <p>测试基建沿用 TenantControllerTest / WholesalerScenarioTest 风格
 * （@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信码 888888）；
 * 本类位于 account 包，直连 UserMapper 断言落库字段属于域内合法访问。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserServiceScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    /** account 域内白盒断言用（验证落库字段与原直连等价） */
    @Autowired
    private UserMapper userMapper;

    // ^1[3-9]\d{9}$：1 + 前缀位 + 5 位时间戳尾段 + 4 位自增；各流程不同前缀避免相互污染
    private static final String P_SVC = "18" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_TA  = "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS = "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA  = "17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private String base(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private User findByPhoneHash(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, DigestUtil.sha256Hex(phone)));
    }

    private long countByPhoneHash(String phone) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, DigestUtil.sha256Hex(phone)));
    }

    private R<LoginVo> register(String phone, String password, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(base("/api/v1/account/register"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s", phone).isEqualTo(0);
        return body;
    }

    /** OPS 代建租户（新出口 createByOps → ensureUserByPhone(OPS_PROXY)） */
    private R<Map<String, Object>> createTenantByOps(String opsToken, String taPhone, String name) {
        TenantCreateDto dto = new TenantCreateDto();
        dto.setName(name);
        dto.setContactPhone(taPhone);
        dto.setAddressText("上海市浦东新区");
        return restTemplate.exchange(base("/api/v1/admin/tenant/create"), HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + apply 建仓（同 WholesalerScenarioTest：无需审核即可建商户） */
    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        String token = register(phone, "TaPass123", "TA").getData().getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("等价性测试仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(base("/api/v1/tenant/apply"), HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        return new TaContext(phone, token, Long.valueOf(body.getData().get("tenantId").toString()));
    }

    /** TA 建商户并带 waPhone 开通 WA（新出口 ensureWaUser → ensureUserByPhone(WA_PROVISION)） */
    private R<Map<String, Object>> createWholesalerWithWa(TaContext ta, String name, String waPhone) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        dto.setWaPhone(waPhone);
        return restTemplate.exchange(base("/api/v1/tenant/wholesalers"), HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
    }

    // ======================================================================
    // US-* ensureUserByPhone 服务级等价性
    // ======================================================================

    @Test
    @DisplayName("US-S1-01 未注册手机号 → 新建 ACTIVE 用户，字段与原直连逐一等价")
    void us_s1_createNewUser() {
        String phone = uniquePhone(P_SVC);
        UserService.EnsuredUser ensured = userService.ensureUserByPhone(phone, "WA_PROVISION");

        assertThat(ensured.isNew()).isTrue();
        assertThat(ensured.userId()).isNotNull();

        User row = findByPhoneHash(phone);
        assertThat(row).isNotNull();
        assertThat(row.getId()).isEqualTo(ensured.userId());
        assertThat(row.getPhone()).isEqualTo(phone);
        assertThat(row.getPhoneHash()).isEqualTo(DigestUtil.sha256Hex(phone));
        assertThat(row.getNickname()).as("昵称=手机号后4位（原直连语义）")
                .isEqualTo(phone.substring(phone.length() - 4));
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getRegisterSource()).isEqualTo("WA_PROVISION");
        // 临时密码只落 BCrypt cost 12 哈希（与 AccountServiceImpl / 原直连一致），非明文
        assertThat(row.getPasswordHash()).startsWith("$2a$12$").hasSize(60);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("US-S1-02 已存在手机号 → 幂等原样返回：不重建、不覆写密码/来源")
    void us_s1_idempotentHit() {
        String phone = uniquePhone(P_SVC);
        UserService.EnsuredUser first = userService.ensureUserByPhone(phone, "WA_PROVISION");
        User before = findByPhoneHash(phone);

        // 第二次换来源调用：命中即返回，userId 一致、isNew=false、行内容不被覆写
        UserService.EnsuredUser second = userService.ensureUserByPhone(phone, "OPS_PROXY");
        assertThat(second.isNew()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(countByPhoneHash(phone)).isEqualTo(1);

        User after = findByPhoneHash(phone);
        assertThat(after.getPasswordHash()).as("幂等命中不得重新生成临时密码")
                .isEqualTo(before.getPasswordHash());
        assertThat(after.getRegisterSource()).as("幂等命中不得覆写注册来源")
                .isEqualTo("WA_PROVISION");
    }

    @Test
    @DisplayName("US-S1-03 手机号前后空白 → trim 归一到同一用户")
    void us_s1_trimNormalized() {
        String phone = uniquePhone(P_SVC);
        UserService.EnsuredUser first = userService.ensureUserByPhone(phone, "OPS_PROXY");
        UserService.EnsuredUser second = userService.ensureUserByPhone("  " + phone + " ", "OPS_PROXY");
        assertThat(second.isNew()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
    }

    @Test
    @DisplayName("US-S2-01 临时密码不出方法边界：EnsuredUser 仅 userId/isNew 两个分量")
    void us_s2_noTempPasswordExposure() {
        // 契约钉死：出参 record 结构上不可能携带临时密码/哈希/User entity
        List<String> components = Arrays.stream(UserService.EnsuredUser.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(components).containsExactly("userId", "isNew");
    }

    @Test
    @DisplayName("US-S3-01 getPhone：存在→手机号；不存在/null→null")
    void us_s3_getPhone() {
        String phone = uniquePhone(P_SVC);
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();
        assertThat(userService.getPhone(userId)).isEqualTo(phone);
        assertThat(userService.getPhone(-424242L)).isNull();
        assertThat(userService.getPhone(null)).isNull();
    }

    @Test
    @DisplayName("US-S4-01 getDisplayNames：realName 优先/nickname 回落/空集合→空 Map/未知 id 不出现")
    void us_s4_getDisplayNames() {
        assertThat(userService.getDisplayNames(List.of())).isEmpty();
        assertThat(userService.getDisplayNames(null)).isEmpty();

        String phoneA = uniquePhone(P_SVC);
        String phoneB = uniquePhone(P_SVC);
        Long idA = userService.ensureUserByPhone(phoneA, "OPS_PROXY").userId();
        Long idB = userService.ensureUserByPhone(phoneB, "OPS_PROXY").userId();

        // A 补实名 → realName 优先；B 无实名 → 回落 nickname（后4位）
        User a = userMapper.selectById(idA);
        a.setRealName("张三");
        userMapper.updateById(a);

        Map<Long, String> names = userService.getDisplayNames(List.of(idA, idB, -424242L));
        assertThat(names.get(idA)).isEqualTo("张三");
        assertThat(names.get(idB)).isEqualTo(phoneB.substring(phoneB.length() - 4));
        assertThat(names).as("不存在的 userId 不应出现在结果里").doesNotContainKey(-424242L);
    }

    // ======================================================================
    // TA-PROXY-* OPS 代建租户（新出口行为与原直连一致）
    // ======================================================================

    @Test
    @DisplayName("TA-PROXY-01 代建：手机号未注册 → 新建 OPS_PROXY 用户 + isNewUser=true")
    void taProxy_newUser() {
        String opsToken = register(uniquePhone(P_OPS), "OpsPass123", "OPS").getData().getToken();
        String taPhone = uniquePhone(P_TA);

        R<Map<String, Object>> body = createTenantByOps(opsToken, taPhone, "OPS代建-" + taPhone);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("isNewUser")).isEqualTo(true);
        assertThat(body.getData().get("status")).isEqualTo("ACTIVE");

        User row = findByPhoneHash(taPhone);
        assertThat(row).isNotNull();
        assertThat(row.getId().toString()).isEqualTo(body.getData().get("taUserId").toString());
        assertThat(row.getRegisterSource()).isEqualTo("OPS_PROXY");
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getNickname()).isEqualTo(taPhone.substring(taPhone.length() - 4));
        assertThat(row.getPasswordHash()).startsWith("$2a$12$");
    }

    @Test
    @DisplayName("TA-PROXY-02 代建：手机号已自主注册 → 复用该用户且不覆写密码/来源")
    void taProxy_reuseRegisteredUser() {
        String taPhone = uniquePhone(P_TA);
        Long registeredUserId = register(taPhone, "TaPass123", "TA").getData().getUserId();
        User before = findByPhoneHash(taPhone);

        String opsToken = register(uniquePhone(P_OPS), "OpsPass123", "OPS").getData().getToken();
        R<Map<String, Object>> body = createTenantByOps(opsToken, taPhone, "OPS代建复用-" + taPhone);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("isNewUser")).isEqualTo(false);
        assertThat(body.getData().get("taUserId").toString()).isEqualTo(registeredUserId.toString());

        User after = findByPhoneHash(taPhone);
        assertThat(countByPhoneHash(taPhone)).isEqualTo(1);
        assertThat(after.getPasswordHash()).as("已注册用户密码不得被临时密码覆写")
                .isEqualTo(before.getPasswordHash());
        assertThat(after.getRegisterSource()).as("已注册用户来源不得被覆写为 OPS_PROXY")
                .isEqualTo(before.getRegisterSource());
    }

    @Test
    @DisplayName("TA-PROXY-03 代建两次同手机号 → 第二次 isNewUser=false 且共用同一 TA 用户")
    void taProxy_doubleCreateIdempotentUser() {
        String opsToken = register(uniquePhone(P_OPS), "OpsPass123", "OPS").getData().getToken();
        String taPhone = uniquePhone(P_TA);

        R<Map<String, Object>> first = createTenantByOps(opsToken, taPhone, "代建甲-" + taPhone);
        assertThat(first.getCode()).isEqualTo(0);
        R<Map<String, Object>> second = createTenantByOps(opsToken, taPhone, "代建乙-" + taPhone);
        assertThat(second.getCode()).isEqualTo(0);

        assertThat(second.getData().get("isNewUser")).isEqualTo(false);
        assertThat(second.getData().get("taUserId")).isEqualTo(first.getData().get("taUserId"));
        assertThat(countByPhoneHash(taPhone)).isEqualTo(1);
    }

    // ======================================================================
    // WA-* TA 开通 WA（新出口行为与原直连一致）
    // ======================================================================

    @Test
    @DisplayName("WA-01 建商户带新 waPhone → 新建 WA_PROVISION 用户并返回 waUserId")
    void wa_newUser() {
        TaContext ta = registerTaWithTenant();
        String waPhone = uniquePhone(P_WA);

        R<Map<String, Object>> body = createWholesalerWithWa(ta, "WA商户-" + waPhone, waPhone);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("waUserId")).isNotNull();

        User row = findByPhoneHash(waPhone);
        assertThat(row).isNotNull();
        assertThat(row.getRegisterSource()).isEqualTo("WA_PROVISION");
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getNickname()).isEqualTo(waPhone.substring(waPhone.length() - 4));
        assertThat(row.getPasswordHash()).startsWith("$2a$12$");
        // 原直连由 MetaObjectHandler 填充时间戳，新出口显式 set——均为 now() 语义
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("WA-02 同 waPhone 再开一家商户 → 复用同一 WA 用户，不重建不改密")
    void wa_idempotentSamePhone() {
        TaContext ta = registerTaWithTenant();
        String waPhone = uniquePhone(P_WA);

        R<Map<String, Object>> first = createWholesalerWithWa(ta, "WA甲-" + waPhone, waPhone);
        assertThat(first.getCode()).isEqualTo(0);
        User before = findByPhoneHash(waPhone);

        R<Map<String, Object>> second = createWholesalerWithWa(ta, "WA乙-" + waPhone, waPhone);
        assertThat(second.getCode()).isEqualTo(0);
        // 注意：VO 的 waUserId 实为「WA 角色绑定行 id」（每个商户各一条，见 toVo(wholesaler, waRoleId)），
        // 两次必然不同；用户级幂等以 users 表行数与密码不变为准。
        assertThat(second.getData().get("waUserId")).isNotNull();

        assertThat(countByPhoneHash(waPhone)).as("同号重复开通不得重建用户").isEqualTo(1);
        assertThat(findByPhoneHash(waPhone).getPasswordHash())
                .as("重复开通不得重新生成临时密码")
                .isEqualTo(before.getPasswordHash());
    }

    // ======================================================================
    // ADMIN-DN-01 pageTenantsForAdmin 显示名收敛后契约不变
    // ======================================================================

    @Test
    @DisplayName("ADMIN-DN-01 OPS 租户列表 applicantName 经 getDisplayNames 后仍=申请人昵称")
    void adminList_applicantNameViaDisplayNames() {
        String opsToken = register(uniquePhone(P_OPS), "OpsPass123", "OPS").getData().getToken();
        String taPhone = uniquePhone(P_TA);
        R<Map<String, Object>> created = createTenantByOps(opsToken, taPhone, "显示名租户-" + taPhone);
        assertThat(created.getCode()).isEqualTo(0);
        String tenantId = created.getData().get("tenantId").toString();

        R<Map<String, Object>> list = restTemplate.exchange(
                base("/api/v1/admin/tenants?status=ACTIVE&page=1&size=100"), HttpMethod.GET,
                new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.getData().get("list");
        Map<String, Object> mine = items.stream()
                .filter(m -> tenantId.equals(String.valueOf(m.get("tenantId"))))
                .findFirst().orElse(null);
        assertThat(mine).as("createdAt 倒序前 100 条应含刚代建的租户").isNotNull();
        // 代建 TA 无实名 → 回落昵称（手机号后4位），与收敛前内联逻辑一致
        assertThat(mine.get("applicantName")).isEqualTo(taPhone.substring(taPhone.length() - 4));
    }
}
