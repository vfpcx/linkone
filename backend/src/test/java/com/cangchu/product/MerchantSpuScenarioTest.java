package com.cangchu.product;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商户自建聚合 SPU + 结构化规格场景测试（P6 商品规格模型，V42）。
 *
 * <p>基建沿用 {@link SkuScenarioTest}（@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 +
 * mock 短信码 888888）。WA 正路径走真实入驻链（WA 自助申请 → TA 审批通过 → 重新登录刷新绑定），
 * 与 WithdrawOfflineScenarioTest 同构。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1-01 创建自建聚合 SPU：ownerType=TENANT、TSPU- 编码、规格模板回读、列表可见。</li>
 *   <li>S1-02 缺省批量生成 = 完整笛卡尔积（2×2=4 个独立 SKU，specKey 互异）；重复生成 → 50733。</li>
 *   <li>S1-03 指定组合 + 逐组合覆盖价格（不同规格价格各自独立）。</li>
 *   <li>S1-04 下架聚合 SPU → 级联下架其 SKU（/listed 不可见、商户列表仍可见）；重复下架 → 50722。</li>
 *   <li>S1-05 partial 更新（null 字段保持原值）+ 名称变更刷新 SKU 快照。</li>
 *   <li>S2-01 规格模板缺失/空数组 → 40001 / 50730。</li>
 *   <li>S2-02 模板非法（维度名重复 / 取值重复）→ 50731。</li>
 *   <li>S2-03 模板超限（3 维 × 5³ = 125 &gt; 60）→ 50732。</li>
 *   <li>S2-04 提交组合与模板不匹配 → 50734。</li>
 *   <li>S3-01 归属：该商户 WA（本人）可建；无绑定 WA → 42101。</li>
 *   <li>S4-01 混合 SPU 隔离：跨租户 TA 读/写自建 SPU → 50735（防枚举）；跨租户建 SPU → 50230/42101。</li>
 *   <li>S4-02 对外目录隔离：自建 SPU 不出现在公开 /api/v1/catalog/spus；手动挂接 → 50737。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantSpuScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantMapper tenantMapper;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String PHONE_PREFIX_WA =
            "17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseSpu;
    private String baseCatalog;
    private String baseAccount;
    private String baseApply;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseWholesaler = "http://localhost:" + port + "/api/v1/tenant/wholesalers";
        baseSku = "http://localhost:" + port + "/api/v1/tenant/skus";
        baseSpu = "http://localhost:" + port + "/api/v1/tenant/spus";
        baseCatalog = "http://localhost:" + port + "/api/v1/catalog/spus";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
        baseApply = "http://localhost:" + port + "/api/v1/wholesaler/applications";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private String registerAndLogin(String phone, String password, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    private String login(String phone, String password) {
        LoginDto dto = new LoginDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/login", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("login %s", phone).isEqualTo(0);
        return body.getData().getToken();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    private record WaContext(String phone, String password, String token, Long wholesalerId) {}

    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("规格仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        // 仓库 OPS 审核不在本测试范围：直接置 ACTIVE（WA 自助入驻要求目标租户可用，先例 WeEmployeeScenarioTest）
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        return new TaContext(phone, token, tenantId);
    }

    private String createWholesaler(TaContext ta, String name) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        R<Map<String, Object>> body = restTemplate.exchange(baseWholesaler, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create wholesaler").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    /** WA 正路径：注册 WA → 自助入驻申请 → TA 审批通过 → 重新登录（刷新带绑定的会话）。 */
    private WaContext registerWaWithWholesaler(TaContext ta, String name) {
        String phone = uniquePhone(PHONE_PREFIX_WA);
        String password = "WaPass123";
        String token = registerAndLogin(phone, password, "WA");

        Map<String, Object> apply = new HashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", name);
        R<Map<String, Object>> applied = restTemplate.exchange(baseApply,
                HttpMethod.POST, new HttpEntity<>(apply, bearer(token)), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).as("WA 自助申请").isEqualTo(0);

        R<Map<String, Object>> approved = restTemplate.exchange(
                baseTenant + "/wholesaler-applications/" + applied.getData().get("applicationId") + "/audit",
                HttpMethod.POST, new HttpEntity<>(Map.of("action", "APPROVED", "remark", "P6 测试放行"), bearer(ta.token())),
                MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).as("TA 审批通过").isEqualTo(0);
        Long wholesalerId = Long.valueOf(approved.getData().get("wholesalerId").toString());

        // 入驻绑定发生在注册之后 → 重新登录刷新会话
        return new WaContext(phone, password, login(phone, password), wholesalerId);
    }

    // ======================================================================
    // 请求构造
    // ======================================================================

    private Map<String, Object> dim(String name, List<String> options) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("options", options);
        return m;
    }

    private Map<String, Object> spuDto(String name, List<Map<String, Object>> schema) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("categoryL1", "酒水饮料");
        m.put("categoryL2", "碳酸/果汁");
        m.put("brand", "自有品牌");
        m.put("specSchema", schema);
        return m;
    }

    private List<Map<String, Object>> twoByTwoSchema() {
        List<Map<String, Object>> schema = new ArrayList<>();
        schema.add(dim("包装", List.of("5L/桶", "10L/桶")));
        schema.add(dim("口味", List.of("原味", "柠檬")));
        return schema;
    }

    private R<Map<String, Object>> createSpu(String token, String wid, Map<String, Object> dto) {
        return restTemplate.exchange(baseSpu + "?wholesalerId=" + wid, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    private R<Map<String, Object>> detailSpu(String token, String spuId) {
        return restTemplate.exchange(baseSpu + "/" + spuId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
    }

    private R<List<Map<String, Object>>> listSpus(String token, String wid) {
        return restTemplate.exchange(baseSpu + "?wholesalerId=" + wid, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    private R<List<Map<String, Object>>> generate(String token, String spuId, Map<String, Object> dto) {
        return restTemplate.exchange(baseSpu + "/" + spuId + "/generate-skus", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), LIST).getBody();
    }

    private R<List<Map<String, Object>>> listSku(String token, String wid) {
        return restTemplate.exchange(baseSku + "?wholesalerId=" + wid, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    private R<List<Map<String, Object>>> listedSku(String token, String wid) {
        return restTemplate.exchange(baseSku + "/listed?wholesalerId=" + wid, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    // ======================================================================
    // S1 正常流程
    // ======================================================================

    @Test
    @DisplayName("P6-S1-01 TA 创建自建聚合 SPU → ownerType=TENANT / TSPU- 编码 / 规格模板回读")
    @SuppressWarnings("unchecked")
    void s1_01_createSpu() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "自建商户-" + ta.phone());
        String name = "混饮聚合-" + ta.phone();

        R<Map<String, Object>> body = createSpu(ta.token(), wid, spuDto(name, twoByTwoSchema()));
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        Map<String, Object> spu = body.getData();
        assertThat(spu.get("ownerType")).isEqualTo("TENANT");
        assertThat(spu.get("wholesalerId").toString()).isEqualTo(wid);
        assertThat(spu.get("tenantId").toString()).isEqualTo(ta.tenantId().toString());
        assertThat(spu.get("spuCode").toString()).startsWith("TSPU-");
        assertThat(spu.get("status")).isEqualTo("ACTIVE");
        assertThat(spu.get("referencedSkuCount").toString()).isEqualTo("0");
        List<Map<String, Object>> schema = (List<Map<String, Object>>) spu.get("specSchema");
        assertThat(schema).as("规格模板应可回读").hasSize(2);
        assertThat(schema.get(0).get("name")).isEqualTo("包装");
        assertThat((List<String>) schema.get(0).get("options")).containsExactly("5L/桶", "10L/桶");

        R<List<Map<String, Object>>> list = listSpus(ta.token(), wid);
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);
        assertThat(list.getData()).extracting(m -> m.get("name")).contains(name);
    }

    @Test
    @DisplayName("P6-S1-02 缺省批量生成=完整笛卡尔积（4 个独立 SKU）；重复生成 → 50733")
    void s1_02_fullCartesian() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "笛卡尔商户-" + ta.phone());
        R<Map<String, Object>> created = createSpu(ta.token(), wid, spuDto("聚合饮-" + ta.phone(), twoByTwoSchema()));
        assertThat(created.getCode()).isEqualTo(0);
        String spuId = created.getData().get("id").toString();

        Map<String, Object> gen = new HashMap<>();
        gen.put("unitPrice", 12.5);
        gen.put("moqPrice", 10.0);
        gen.put("moqQty", 6);
        R<List<Map<String, Object>>> body = generate(ta.token(), spuId, gen);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        List<Map<String, Object>> skus = body.getData();
        assertThat(skus).as("2 维 2×2 → 4 个 SKU").hasSize(4);

        // 每个组合 = 独立 SKU，specKey 互异且为结构化有序 JSON
        List<String> keys = skus.stream().map(m -> String.valueOf(m.get("specKey"))).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys).allSatisfy(k -> assertThat(k).contains("包装").contains("口味"));
        assertThat(skus).extracting(m -> m.get("spuId").toString()).containsOnly(spuId);
        assertThat(skus).extracting(m -> m.get("spec").toString())
                .contains("5L/桶 / 原味", "10L/桶 / 柠檬");
        assertThat(skus).allSatisfy(m -> {
            assertThat(m.get("listed")).isEqualTo(true);
            assertThat(m.get("unitPrice").toString()).isEqualTo("12.5");
        });
        // SKU 名 = 聚合名 + 规格摘要
        assertThat(skus).extracting(m -> m.get("name").toString())
                .anySatisfy(n -> assertThat(n).contains("聚合饮-" + ta.phone()).contains("5L/桶 / 原味"));

        // 详情引用数已刷新
        R<Map<String, Object>> detail = detailSpu(ta.token(), spuId);
        assertThat(detail.getCode()).isEqualTo(0);
        assertThat(detail.getData().get("referencedSkuCount").toString()).isEqualTo("4");

        // 重复生成同一模板（全覆盖）→ 组合重复 50733
        R<List<Map<String, Object>>> again = generate(ta.token(), spuId, gen);
        assertThat(again).isNotNull();
        assertThat(again.getCode()).as("重复生成应被组合唯一性拒绝").isEqualTo(50733);
    }

    @Test
    @DisplayName("P6-S1-03 指定组合 + 逐组合覆盖价格（不同规格价格各自独立）")
    void s1_03_explicitCombosWithPriceOverride() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "价差商户-" + ta.phone());
        R<Map<String, Object>> created = createSpu(ta.token(), wid, spuDto("分价聚合-" + ta.phone(), twoByTwoSchema()));
        String spuId = created.getData().get("id").toString();

        Map<String, Object> item1 = new HashMap<>();
        item1.put("options", Map.of("包装", "5L/桶", "口味", "原味"));
        item1.put("unitPrice", 9.9);
        Map<String, Object> item2 = new HashMap<>();
        item2.put("options", Map.of("包装", "10L/桶", "口味", "柠檬"));
        item2.put("unitPrice", 19.9);
        item2.put("moqQty", 2);

        Map<String, Object> gen = new HashMap<>();
        gen.put("unitPrice", 100.0);   // 默认价被逐条覆盖
        gen.put("items", List.of(item1, item2));
        R<List<Map<String, Object>>> body = generate(ta.token(), spuId, gen);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData()).hasSize(2);

        Map<String, Map<String, Object>> bySpec = new HashMap<>();
        body.getData().forEach(m -> bySpec.put(String.valueOf(m.get("spec")), m));
        assertThat(bySpec).containsKeys("5L/桶 / 原味", "10L/桶 / 柠檬");
        assertThat(bySpec.get("5L/桶 / 原味").get("unitPrice").toString()).isEqualTo("9.9");
        assertThat(bySpec.get("10L/桶 / 柠檬").get("unitPrice").toString()).isEqualTo("19.9");
        assertThat(bySpec.get("10L/桶 / 柠檬").get("moqQty").toString()).isEqualTo("2");
        // 未覆盖的字段落默认值
        assertThat(bySpec.get("5L/桶 / 原味").get("moqQty").toString()).isEqualTo("1");

        // 生成结果对既有商户 SKU 列表可见（库存/单据仍按 SKU 流转，无需新链路）
        R<List<Map<String, Object>>> skus = listSku(ta.token(), wid);
        assertThat(skus.getCode()).isEqualTo(0);
        assertThat(skus.getData()).extracting(m -> m.get("specKey")).doesNotContainNull();
    }

    @Test
    @DisplayName("P6-S1-04 下架聚合 SPU → 级联下架其 SKU；重复下架 → 50722")
    void s1_04_offlineCascade() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "下架商户-" + ta.phone());
        R<Map<String, Object>> created = createSpu(ta.token(), wid, spuDto("下架聚合-" + ta.phone(), twoByTwoSchema()));
        String spuId = created.getData().get("id").toString();

        R<List<Map<String, Object>>> gen = generate(ta.token(), spuId,
                Map.of("unitPrice", 5.5));
        assertThat(gen.getCode()).isEqualTo(0);
        assertThat(listedSku(ta.token(), wid).getData()).isNotEmpty();

        R<Map<String, Object>> off = restTemplate.exchange(baseSpu + "/" + spuId + "/offline",
                HttpMethod.POST, new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(off).isNotNull();
        assertThat(off.getCode()).isEqualTo(0);

        assertThat(detailSpu(ta.token(), spuId).getData().get("status")).isEqualTo("OFFLINE");
        assertThat(listedSku(ta.token(), wid).getData()).as("/listed 不再返回级联下架的 SKU").isEmpty();
        assertThat(listSku(ta.token(), wid).getData()).as("商户 SKU 列表仍可见（存量保留）").isNotEmpty();
        assertThat(listSku(ta.token(), wid).getData())
                .extracting(m -> m.get("listed")).doesNotContain(true);

        R<Map<String, Object>> again = restTemplate.exchange(baseSpu + "/" + spuId + "/offline",
                HttpMethod.POST, new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(again.getCode()).as("非 ACTIVE 再次下架应被拒").isEqualTo(50722);
    }

    @Test
    @DisplayName("P6-S1-05 partial 更新：null 字段保持原值；名称变更刷新 SKU 快照")
    void s1_05_partialUpdate() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "更新商户-" + ta.phone());
        String oldName = "旧名聚合-" + ta.phone();
        R<Map<String, Object>> created = createSpu(ta.token(), wid, spuDto(oldName, twoByTwoSchema()));
        String spuId = created.getData().get("id").toString();
        assertThat(generate(ta.token(), spuId, Map.of("unitPrice", 3.0)).getCode()).isEqualTo(0);

        // 只改备注 → 名称/模板不动
        R<Map<String, Object>> noted = restTemplate.exchange(baseSpu + "/" + spuId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("note", "仅改备注"), bearer(ta.token())), MAP).getBody();
        assertThat(noted.getCode()).isEqualTo(0);
        assertThat(noted.getData().get("note")).isEqualTo("仅改备注");
        assertThat(noted.getData().get("name")).isEqualTo(oldName);
        assertThat((List<?>) noted.getData().get("specSchema")).hasSize(2);

        // 只改名称 → SKU 快照刷新为新名
        String newName = "新名聚合-" + ta.phone();
        R<Map<String, Object>> renamed = restTemplate.exchange(baseSpu + "/" + spuId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", newName), bearer(ta.token())), MAP).getBody();
        assertThat(renamed.getCode()).isEqualTo(0);
        assertThat(renamed.getData().get("note")).as("未传字段保持").isEqualTo("仅改备注");
        assertThat(listSku(ta.token(), wid).getData())
                .extracting(m -> m.get("spuName")).containsOnly(newName);
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("P6-S2-01 规格模板缺失/空数组 → 50730；维度内字段非法 → 40001")
    void s2_01_schemaRequired() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "缺模板商户-" + ta.phone());

        Map<String, Object> noSchema = spuDto("无模板-" + ta.phone(), twoByTwoSchema());
        noSchema.remove("specSchema");
        R<Map<String, Object>> missing = createSpu(ta.token(), wid, noSchema);
        assertThat(missing.getCode()).as("模板缺失应被语义化拒绝").isEqualTo(50730);

        R<Map<String, Object>> empty = createSpu(ta.token(), wid, spuDto("空模板-" + ta.phone(), new ArrayList<>()));
        assertThat(empty.getCode()).as("空数组同义").isEqualTo(50730);

        // 维度内部（维度名/取值）非法仍由 DTO @Valid 兜底 40001
        List<Map<String, Object>> blankDim = new ArrayList<>();
        blankDim.add(dim("", List.of("5L/桶")));
        assertThat(createSpu(ta.token(), wid, spuDto("空维名-" + ta.phone(), blankDim)).getCode())
                .as("空维度名 → 40001").isEqualTo(40001);
    }

    @Test
    @DisplayName("P6-S2-02 模板非法（维度名重复 / 取值重复）→ 50731")
    void s2_02_schemaInvalid() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "非法模板商户-" + ta.phone());

        List<Map<String, Object>> dupDim = new ArrayList<>();
        dupDim.add(dim("包装", List.of("5L/桶")));
        dupDim.add(dim("包装", List.of("10L/桶")));
        assertThat(createSpu(ta.token(), wid, spuDto("维度重复-" + ta.phone(), dupDim)).getCode())
                .as("维度名重复").isEqualTo(50731);

        List<Map<String, Object>> dupOption = new ArrayList<>();
        dupOption.add(dim("包装", List.of("5L/桶", "5L/桶")));
        assertThat(createSpu(ta.token(), wid, spuDto("取值重复-" + ta.phone(), dupOption)).getCode())
                .as("同维取值重复").isEqualTo(50731);
    }

    @Test
    @DisplayName("P6-S2-03 模板超限（3 维 5×5×5=125 > 60）→ 50732")
    void s2_03_schemaLimit() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "超限商户-" + ta.phone());
        List<Map<String, Object>> schema = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            schema.add(dim("维度" + i, List.of("A", "B", "C", "D", "E")));
        }
        R<Map<String, Object>> body = createSpu(ta.token(), wid, spuDto("超限聚合-" + ta.phone(), schema));
        assertThat(body.getCode()).as("组合总数超上限").isEqualTo(50732);
    }

    @Test
    @DisplayName("P6-S2-04 提交组合与模板不匹配（缺维度 / 取值不在模板）→ 50734")
    void s2_04_comboMismatch() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "错配商户-" + ta.phone());
        R<Map<String, Object>> created = createSpu(ta.token(), wid, spuDto("错配聚合-" + ta.phone(), twoByTwoSchema()));
        String spuId = created.getData().get("id").toString();

        // 缺「口味」维度
        Map<String, Object> missingDim = new HashMap<>();
        missingDim.put("options", Map.of("包装", "5L/桶"));
        missingDim.put("unitPrice", 8.0);
        assertThat(generate(ta.token(), spuId, Map.of("unitPrice", 8.0, "items", List.of(missingDim))).getCode())
                .as("缺维度").isEqualTo(50734);

        // 取值不在模板内
        Map<String, Object> badOption = new HashMap<>();
        badOption.put("options", Map.of("包装", "20L/桶", "口味", "原味"));
        assertThat(generate(ta.token(), spuId, Map.of("unitPrice", 8.0, "items", List.of(badOption))).getCode())
                .as("取值越界").isEqualTo(50734);
    }

    // ======================================================================
    // S3 归属：商户 WA 自助建聚合 SPU
    // ======================================================================

    @Test
    @DisplayName("P6-S3-01 该商户 WA 可建自建 SPU；无绑定 WA → 42101")
    void s3_01_waOwnership() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = registerWaWithWholesaler(ta, "WA聚合商户-" + SEQ.incrementAndGet());
        String wid = wa.wholesalerId().toString();

        R<Map<String, Object>> created = createSpu(wa.token(), wid, spuDto("WA自建-" + wa.phone(), twoByTwoSchema()));
        assertThat(created).isNotNull();
        assertThat(created.getCode()).as("商户 WA 可建自建聚合 SPU").isEqualTo(0);
        assertThat(created.getData().get("wholesalerId").toString()).isEqualTo(wid);

        // 无绑定的 WA（仅注册，未入驻）→ 归属鉴权失败
        String unboundWa = registerAndLogin(uniquePhone(PHONE_PREFIX_WA), "WaPass123", "WA");
        R<Map<String, Object>> denied = createSpu(unboundWa, wid, spuDto("越权-" + SEQ.incrementAndGet(), twoByTwoSchema()));
        assertThat(denied.getCode()).as("无绑定 WA 应被拒").isEqualTo(42101);
    }

    // ======================================================================
    // S4 隔离：混合 SPU 的租户边界
    // ======================================================================

    @Test
    @DisplayName("P6-S4-01 跨租户 TA 读/写他人自建 SPU → 50735（防枚举）；建 → 50230/42101")
    void s4_01_crossTenantIsolation() {
        TaContext a = registerTaWithTenant();
        TaContext b = registerTaWithTenant();
        String widA = createWholesaler(a, "A家商户-" + a.phone());
        R<Map<String, Object>> created = createSpu(a.token(), widA, spuDto("A家聚合-" + a.phone(), twoByTwoSchema()));
        String spuId = created.getData().get("id").toString();
        assertThat(generate(a.token(), spuId, Map.of("unitPrice", 4.0)).getCode()).isEqualTo(0);

        // B 的 TA 读 A 的自建 SPU 详情 / 列表 / 生成 / 下架 → 一律按不存在处理（50735 / 商户不可见 50230）
        assertThat(detailSpu(b.token(), spuId).getCode()).as("跨租户详情").isEqualTo(50735);
        assertThat(generate(b.token(), spuId, Map.of("unitPrice", 1.0)).getCode()).as("跨租户生成").isEqualTo(50735);
        assertThat(restTemplate.exchange(baseSpu + "/" + spuId + "/offline", HttpMethod.POST,
                new HttpEntity<>(bearer(b.token())), MAP).getBody().getCode()).as("跨租户下架").isEqualTo(50735);
        assertThat(listSpus(b.token(), widA).getCode()).as("跨租户列表（商户不可见）").isEqualTo(50230);

        // B 的 TA 为 A 的商户建聚合 SPU → 商户不可见 50230 或归属越权 42101
        assertThat(createSpu(b.token(), widA, spuDto("越权聚合-" + b.phone(), twoByTwoSchema())).getCode())
                .as("跨租户建聚合 SPU").isIn(50230, 42101);

        // 反向确认：A 自己仍可正常读
        assertThat(detailSpu(a.token(), spuId).getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("P6-S4-02 自建 SPU 不入公开目录；手动挂接 SKU → 50737")
    void s4_02_catalogAndManualLinkIsolated() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "目录隔离商户-" + ta.phone());
        String name = "私域聚合-" + ta.phone();
        R<Map<String, Object>> created = createSpu(ta.token(), wid, spuDto(name, twoByTwoSchema()));
        String spuId = created.getData().get("id").toString();

        // 公开目录（/api/v1/catalog/spus 未纳入登录拦截）只出 PLATFORM 标品
        R<Map<String, Object>> catalog = restTemplate.exchange(
                baseCatalog + "?page=1&size=100&keyword=" + name, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), MAP).getBody();
        assertThat(catalog).isNotNull();
        assertThat(catalog.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) catalog.getData().get("records");
        assertThat(records).as("自建聚合 SPU 不得出现在跨租户可见的公开目录中").isEmpty();

        // 手动把 SKU 挂到自建聚合 SPU 上 → 明确拒绝（聚合 SKU 只能经批量生成）
        Map<String, Object> skuDto = new HashMap<>();
        skuDto.put("name", "手挂-" + ta.phone());
        skuDto.put("unitPrice", 9.9);
        skuDto.put("spuId", spuId);
        R<Map<String, Object>> linked = restTemplate.exchange(baseSku + "?wholesalerId=" + wid, HttpMethod.POST,
                new HttpEntity<>(skuDto, bearer(ta.token())), MAP).getBody();
        assertThat(linked).isNotNull();
        assertThat(linked.getCode()).as("手动挂接自建聚合 SPU 应被拒").isEqualTo(50737);
    }
}
