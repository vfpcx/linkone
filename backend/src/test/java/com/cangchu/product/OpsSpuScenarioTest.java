package com.cangchu.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.entity.Spu;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.mapper.SpuMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS 平台标品库（P5-D D56，22 §7）场景测试。
 *
 * <p>基建同 SkuScenarioTest/OpsDashboardScenarioTest：@SpringBootTest RANDOM_PORT + TestRestTemplate
 * + H2 + mock 短信 888888。SPU 为平台级表（无 tenant 隔离）但每用例独立注册 OPS 且标品命名唯一，
 * 无跨用例聚合断言（不做基线差分）。
 *
 * <p>覆盖：新增/自动编码/品类校验/编码重复/非 OPS 42002；合并 A→B 引用 SKU 原子重指+快照刷新 +
 * 源终态不可操作；下架禁新挂接但存量引用保留；TA 挂接 ACTIVE 成功带快照；分页过滤 + 引用数。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsSpuScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private SkuMapper skuMapper;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseAccount;
    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseOpsSpu;

    @BeforeEach
    void setUp() {
        baseAccount = "http://localhost:" + port + "/api/v1/account";
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseWholesaler = "http://localhost:" + port + "/api/v1/tenant/wholesalers";
        baseSku = "http://localhost:" + port + "/api/v1/tenant/skus";
        baseOpsSpu = "http://localhost:" + port + "/api/v1/ops/spus";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};

    // ======================================================================
    // 基建
    // ======================================================================

    private String uniquePhone(String prefix) {
        long n = SEQ.incrementAndGet();
        return prefix + String.format("%04d", n % 10000);
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

    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("标品仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, token, tenantId);
    }

    private String createWholesaler(TaContext ta) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("自营商户-" + ta.phone());
        R<Map<String, Object>> body = restTemplate.exchange(baseWholesaler, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create wholesaler").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    // ---------- OPS 标品操作 ----------

    private Map<String, Object> spuDto(String name, String l1, String l2) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("categoryL1", l1);
        m.put("categoryL2", l2);
        m.put("brand", "测试牌");
        return m;
    }

    /** OPS 新增标品，断言成功并返回 id。 */
    private Long createSpu(String opsToken, String name) {
        Map<String, Object> dto = spuDto(name, "粮油调味", "米面粮油");
        R<Map<String, Object>> body = restTemplate.exchange(baseOpsSpu, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("新增标品 %s", name).isEqualTo(0);
        return Long.valueOf(body.getData().get("id").toString());
    }

    private R<Map<String, Object>> createSpuRaw(String opsToken, Map<String, Object> dto) {
        return restTemplate.exchange(baseOpsSpu, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
    }

    // ---------- TA/WA 建 SKU ----------

    private Map<String, Object> validSku(String name, Long spuId) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("spec", "5kg/箱");
        m.put("unitPrice", 9.90);
        m.put("moqPrice", 8.50);
        m.put("moqQty", 10);
        if (spuId != null) {
            m.put("spuId", spuId.toString());
        }
        return m;
    }

    private R<Map<String, Object>> createSku(String token, String wholesalerId, Map<String, Object> dto) {
        return restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    private R<List<Map<String, Object>>> listByWholesaler(String token, String wholesalerId) {
        return restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    /** mapper seed 一条引用 sku（spuId + 快照列），返回 sku 实体供断言。 */
    private Sku seedLinkedSku(Long spuId, String name, String spuName) {
        Sku sku = new Sku();
        sku.setId((System.nanoTime() & 0x3FFFFFFF) + SEQ.incrementAndGet() * 1_000_000L);
        sku.setTenantId(1L);
        sku.setWholesalerId((System.nanoTime() & 0x3FFFFFFF) + SEQ.incrementAndGet());
        sku.setSpuId(spuId);
        sku.setSpuName(spuName);
        sku.setSpuCategoryL1("粮油调味");
        sku.setSpuCategoryL2("米面粮油");
        sku.setName(name);
        sku.setSpec("5kg/箱");
        sku.setUnitPrice(BigDecimal.TEN);
        sku.setMoqPrice(BigDecimal.valueOf(8));
        sku.setMoqQty(10);
        sku.setListed(true);
        skuMapper.insert(sku);
        return sku;
    }

    // ======================================================================
    // S1 正常
    // ======================================================================

    @Test
    @DisplayName("SPU-01 新增 ACTIVE + 自动编码；品类非法 50723；手动编码重复 50724")
    void spu01_createActiveWithAutoCodeAndValidation() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");

        // 自动编码（spuCode 留空）
        Long id = createSpu(opsToken, "金龙鱼5L花生油-" + SEQ.incrementAndGet());
        Spu spu = spuMapper.selectById(id);
        assertThat(spu).isNotNull();
        assertThat(spu.getStatus()).isEqualTo(Spu.STATUS_ACTIVE);
        assertThat(spu.getSpuCode()).startsWith("GSPU-");
        assertThat(spu.getCategoryL1()).isEqualTo("粮油调味");
        assertThat(spu.getCategoryL2()).isEqualTo("米面粮油");

        // 品类非法 → 50723
        Map<String, Object> bad = spuDto("非法品类商品-" + SEQ.incrementAndGet(), "不存在大类", "不存在小类");
        R<Map<String, Object>> badBody = createSpuRaw(opsToken, bad);
        assertThat(badBody.getCode()).as("品类非法 50723").isEqualTo(50723);

        // 手动编码重复 → 50724
        Map<String, Object> dup = spuDto("重复编码商品-" + SEQ.incrementAndGet(), "粮油调味", "食用油");
        dup.put("spuCode", spu.getSpuCode());
        R<Map<String, Object>> dupBody = createSpuRaw(opsToken, dup);
        assertThat(dupBody.getCode()).as("编码重复 50724").isEqualTo(50724);
    }

    @Test
    @DisplayName("SPU-02 合并 A→B：引用 SKU 原子重指 + 快照刷新；源终态不可操作；目标无效 50725")
    void spu02_mergeReassignsSkusAndLocksSource() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        Long srcId = createSpu(opsToken, "老标品A-" + SEQ.incrementAndGet());
        Long tgtId = createSpu(opsToken, "新主标品B-" + SEQ.incrementAndGet());
        Spu srcBefore = spuMapper.selectById(srcId);
        Spu tgtBefore = spuMapper.selectById(tgtId);

        // 两条 SKU 挂 A
        Sku sku1 = seedLinkedSku(srcId, "挂A商品1", srcBefore.getName());
        Sku sku2 = seedLinkedSku(srcId, "挂A商品2", srcBefore.getName());

        // 合并 A → B
        R<Map<String, Object>> merged = restTemplate.exchange(baseOpsSpu + "/" + srcId + "/merge?targetSpuId=" + tgtId,
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(merged).isNotNull();
        assertThat(merged.getCode()).as("合并成功").isEqualTo(0);

        // A 终态；引用 SKU 全部重指 B 且快照刷新为 B
        Spu srcAfter = spuMapper.selectById(srcId);
        assertThat(srcAfter.getStatus()).isEqualTo(Spu.STATUS_MERGED);
        assertThat(srcAfter.getMergedToSpuId()).isEqualTo(tgtId);
        for (Long skuId : List.of(sku1.getId(), sku2.getId())) {
            Sku s = skuMapper.selectById(skuId);
            assertThat(s.getSpuId()).as("SKU %s 重指 B", skuId).isEqualTo(tgtId);
            assertThat(s.getSpuName()).isEqualTo(tgtBefore.getName());
            assertThat(s.getSpuCategoryL1()).isEqualTo(tgtBefore.getCategoryL1());
        }

        // 源终态：再下架/再合并 → 50722
        R<Map<String, Object>> offlineAgain = restTemplate.exchange(baseOpsSpu + "/" + srcId + "/offline",
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(offlineAgain.getCode()).as("MERGED 源不可下架 50722").isEqualTo(50722);
        R<Map<String, Object>> mergeAgain = restTemplate.exchange(
                baseOpsSpu + "/" + srcId + "/merge?targetSpuId=" + tgtId,
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(mergeAgain.getCode()).as("MERGED 源不可再合并 50722").isEqualTo(50722);

        // 合并目标为自身 → 50725
        R<Map<String, Object>> selfMerge = restTemplate.exchange(
                baseOpsSpu + "/" + tgtId + "/merge?targetSpuId=" + tgtId,
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(selfMerge.getCode()).as("目标=自身 50725").isEqualTo(50725);
    }

    @Test
    @DisplayName("SPU-03 下架：存量 SKU 引用保留可售；新挂接 50726；重复下架 50722")
    void spu03_offlineKeepsExistingLinksButBlocksNew() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta);

        Long spuId = createSpu(opsToken, "海天生抽500ml-" + SEQ.incrementAndGet());
        Spu spu = spuMapper.selectById(spuId);

        // TA 挂 ACTIVE 成功
        R<Map<String, Object>> linked = createSku(ta.token(), wid, validSku("挂接上架商品", spuId));
        assertThat(linked.getCode()).as("挂 ACTIVE 标品成功").isEqualTo(0);
        String skuId = linked.getData().get("id").toString();

        // 下架
        R<Map<String, Object>> off = restTemplate.exchange(baseOpsSpu + "/" + spuId + "/offline",
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(off.getCode()).as("下架成功").isEqualTo(0);

        // 存量引用保留：SKU 仍返回且快照仍在
        R<List<Map<String, Object>>> list = listByWholesaler(ta.token(), wid);
        assertThat(list.getCode()).isEqualTo(0);
        assertThat(list.getData()).anySatisfy(s ->
                assertThat(s.get("id").toString()).isEqualTo(skuId));

        // 新挂接被拒 50726
        R<Map<String, Object>> blocked = createSku(ta.token(), wid, validSku("挂OFFLINE被拒", spuId));
        assertThat(blocked.getCode()).as("挂 OFFLINE 标品 50726").isEqualTo(50726);

        // 重复下架 → 50722
        R<Map<String, Object>> offAgain = restTemplate.exchange(baseOpsSpu + "/" + spuId + "/offline",
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(offAgain.getCode()).as("重复下架 50722").isEqualTo(50722);
    }

    @Test
    @DisplayName("SPU-04 挂接成功：TA 建 SKU 带 ACTIVE spuId → 返回快照字段")
    void spu04_linkSuccessWithSnapshot() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta);

        Long spuId = createSpu(opsToken, "心相印抽纸-3层-" + SEQ.incrementAndGet());
        Spu spu = spuMapper.selectById(spuId);

        R<Map<String, Object>> linked = createSku(ta.token(), wid, validSku("心相印 3层 100抽", spuId));
        assertThat(linked.getCode()).as("挂接成功").isEqualTo(0);
        Map<String, Object> data = linked.getData();
        assertThat(data.get("spuId").toString()).isEqualTo(spuId.toString());
        assertThat(data.get("spuName").toString()).isEqualTo(spu.getName());
        assertThat(data.get("spuCategoryL1").toString()).isEqualTo("粮油调味");
    }

    @Test
    @DisplayName("SPU-05 分页：关键字/品类/状态过滤 + 引用 SKU 数")
    void spu05_pageFilterAndRefCount() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        // 造一个唯一品牌名用于关键字过滤
        String tag = "ZSKU" + SEQ.incrementAndGet();
        Long aId = createSpu(opsToken, tag + "-农夫山泉550ml");
        Long bId = createSpu(opsToken, tag + "-可乐500ml");
        Spu a = spuMapper.selectById(aId);
        seedLinkedSku(aId, "挂A-sku", a.getName()); // A 有 1 个引用

        // 关键字（名称命中 2 个）
        R<Map<String, Object>> byKw = restTemplate.exchange(baseOpsSpu + "?page=1&size=10&keyword=" + tag,
                HttpMethod.GET, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(byKw.getCode()).isEqualTo(0);
        Map<String, Object> kwData = byKw.getData();
        assertThat(((Number) kwData.get("total")).longValue()).as("关键字命中 2 个").isEqualTo(2);

        // 品类过滤（全部建在粮油调味/米面粮油）
        R<Map<String, Object>> byCat = restTemplate.exchange(
                baseOpsSpu + "?page=1&size=10&categoryL1=粮油调味&categoryL2=米面粮油",
                HttpMethod.GET, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(byCat.getCode()).isEqualTo(0);
        assertThat(((Number) byCat.getData().get("total")).longValue()).as("品类命中 2 个").isGreaterThanOrEqualTo(2);

        // 状态过滤 + 引用数：page records 中找到 A → referencedSkuCount=1
        R<Map<String, Object>> byStatus = restTemplate.exchange(baseOpsSpu + "?page=1&size=20&status=ACTIVE",
                HttpMethod.GET, new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(byStatus.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) byStatus.getData().get("records");
        assertThat(records).isNotEmpty();
        Map<String, Object> aRow = records.stream()
                .filter(r -> r.get("id").toString().equals(aId.toString()))
                .findFirst().orElseThrow();
        assertThat(((Number) aRow.get("referencedSkuCount")).longValue()).as("A 引用数=1").isEqualTo(1);

        // 存在引用 count>0 的语义：同列表 B 引用 0
        Map<String, Object> bRow = records.stream()
                .filter(r -> r.get("id").toString().equals(bId.toString()))
                .findFirst().orElseThrow();
        assertThat(((Number) bRow.get("referencedSkuCount")).longValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("SPU-06 非 OPS 调标品管理端点：TA → 42002")
    void spu06_nonOpsRejected() {
        String taToken = registerAndLogin(uniquePhone(P_TA), "TA");
        R<Map<String, Object>> page = restTemplate.exchange(baseOpsSpu + "?page=1&size=10",
                HttpMethod.GET, new HttpEntity<>(bearer(taToken)), MAP).getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCode()).as("TA 分页应 42002").isEqualTo(42002);

        R<Map<String, Object>> create = createSpuRaw(taToken, spuDto("TA越权-尝试", "粮油调味", "食用油"));
        assertThat(create).isNotNull();
        assertThat(create.getCode()).as("TA 新增应 42002").isEqualTo(42002);
    }

    @Test
    @DisplayName("SPU-07 标品目录（登录态可见）：TA 可搜 ACTIVE；OFFLINE/MERGED 不可见")
    void spu07_catalogSearchActiveOnly() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        String taToken = registerAndLogin(uniquePhone(P_TA), "TA");
        String tag = "CAT" + SEQ.incrementAndGet();
        Long activeId = createSpu(opsToken, tag + "-可口可乐330ml");
        Long offlineId = createSpu(opsToken, tag + "-下架可乐600ml");
        // 下架一个
        restTemplate.exchange(baseOpsSpu + "/" + offlineId + "/offline",
                HttpMethod.POST, new HttpEntity<>(bearer(opsToken)), MAP).getBody();

        String base = "http://localhost:" + port + "/api/v1/catalog/spus";
        R<Map<String, Object>> cat = restTemplate.exchange(base + "?page=1&size=10&keyword=" + tag,
                HttpMethod.GET, new HttpEntity<>(bearer(taToken)), MAP).getBody();
        assertThat(cat.getCode()).as("TA 目录搜索成功").isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) cat.getData().get("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("id").toString()).isEqualTo(activeId.toString());
    }
}
