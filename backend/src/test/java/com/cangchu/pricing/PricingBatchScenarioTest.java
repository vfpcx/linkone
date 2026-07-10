package com.cangchu.pricing;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * 批量调价 + 调价历史场景测试（P2 定价 Wave 2，HTTP 黑盒，Style A）。
 *
 * <p>沿用 {@code PricingScenarioTest} 基建（@SpringBootTest RANDOM_PORT + TestRestTemplate + H2
 * + mock 短信码 888888 + bare-token Authorization）。防重冷却按 (wholesalerId, changeType) 隔离，
 * 故各测试各用独立 wholesaler，互不干扰。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 批量调公开价 PCT_UP → 各 SKU 价更新 + 写 price_change_log + 历史可查。</li>
 *   <li>S1 批量调专属价 SET_VALUE / DISABLE。</li>
 *   <li>S2 >200 skuIds / >500 ids 校验拒绝；调后价 ≤0 → 跳过计入 rejected。</li>
 *   <li>S7 并发批量调价（虚拟线程）→ 锁串行化无丢失更新；两次快速调用 → 2nd 防重拒绝 50303。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingBatchScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String PHONE_PREFIX_TA =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseCustomerPrice;
    private String baseAccount;
    private String baseBatchPublic;
    private String baseBatchCustomer;
    private String baseLogs;

    @BeforeEach
    void setUp() {
        String root = "http://localhost:" + port;
        baseTenant = root + "/api/v1/tenant";
        baseWholesaler = root + "/api/v1/tenant/wholesalers";
        baseSku = root + "/api/v1/tenant/skus";
        baseCustomerPrice = root + "/api/v1/tenant/customer-prices";
        baseAccount = root + "/api/v1/account";
        baseBatchPublic = root + "/api/v1/tenant/skus/batch-price-update";
        baseBatchCustomer = root + "/api/v1/tenant/customer-prices/batch-update";
        baseLogs = root + "/api/v1/tenant/price-change-logs";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        long n = SEQ.incrementAndGet();
        return prefix + String.format("%04d", n % 10000);
    }

    private String rtPhone() {
        return "18" + String.format("%09d", SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private LoginVo registerAndLogin(String phone, String password, String role) {
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
        return body.getData();
    }

    private record TaContext(String phone, String token, Long tenantId, Long userId) {}

    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        LoginVo login = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("批调仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市余杭区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(login.getToken())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, login.getToken(), tenantId, login.getUserId());
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

    /** 建一个 SKU（unitPrice/moqPrice/moqQty 可指定），返回 skuId。 */
    private String createSku(TaContext ta, String wholesalerId, String name, double unitPrice) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("spec", "500ml*24");
        m.put("unitPrice", unitPrice);
        m.put("moqPrice", 8.00);
        m.put("moqQty", 5);
        R<Map<String, Object>> body = restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId,
                HttpMethod.POST, new HttpEntity<>(m, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create sku").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    private Map<String, Object> setCustomerPrice(TaContext ta, String wid, String skuId, String phone, double price) {
        Map<String, Object> m = new HashMap<>();
        m.put("wholesalerId", wid);
        m.put("skuId", skuId);
        m.put("rtPhone", phone);
        m.put("unitPrice", price);
        R<Map<String, Object>> body = restTemplate.exchange(baseCustomerPrice, HttpMethod.POST,
                new HttpEntity<>(m, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("set customer price").isEqualTo(0);
        return body.getData();
    }

    private R<Map<String, Object>> postBatchPublic(String token, Map<String, Object> dto) {
        return restTemplate.exchange(baseBatchPublic, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    private R<Map<String, Object>> postBatchCustomer(String token, Map<String, Object> dto) {
        return restTemplate.exchange(baseBatchCustomer, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    private List<Map<String, Object>> listSkus(TaContext ta, String wid) {
        R<List<Map<String, Object>>> body = restTemplate.exchange(baseSku + "?wholesalerId=" + wid,
                HttpMethod.GET, new HttpEntity<>(bearer(ta.token())), LIST).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        return body.getData();
    }

    private List<Map<String, Object>> listCustomerPrices(TaContext ta, String wid) {
        R<List<Map<String, Object>>> body = restTemplate.exchange(baseCustomerPrice + "?wholesalerId=" + wid,
                HttpMethod.GET, new HttpEntity<>(bearer(ta.token())), LIST).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        return body.getData();
    }

    private BigDecimal skuUnitPrice(TaContext ta, String wid, String skuId) {
        return listSkus(ta, wid).stream()
                .filter(m -> skuId.equals(m.get("id").toString()))
                .map(m -> new BigDecimal(m.get("unitPrice").toString()))
                .findFirst().orElseThrow();
    }

    // ======================================================================
    // S1 批量调公开价
    // ======================================================================

    @Test
    @DisplayName("BATCH-S1-01 批量调公开价 PCT_UP 10% → 各 SKU 涨价 + 写日志 + 历史可查")
    void s1_batchPublicPctUp() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "公调商户-" + ta.phone());
        List<String> skuIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            skuIds.add(createSku(ta, wid, "公调品" + i + "-" + ta.phone(), 10.00));
        }

        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("skuIds", skuIds);
        dto.put("adjustMode", "PCT_UP");
        dto.put("value", 10);
        R<Map<String, Object>> resp = postBatchPublic(ta.token(), dto);
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).as("批量调公开价成功").isEqualTo(0);
        assertThat(resp.getData().get("affectedCount")).isEqualTo(3);
        String batchNo = resp.getData().get("batchNo").toString();
        assertThat(batchNo).isNotBlank();

        // 每个 sku 单价 10.00 → 11.00
        for (String skuId : skuIds) {
            assertThat(skuUnitPrice(ta, wid, skuId)).isEqualByComparingTo("11.00");
        }

        // 历史查询
        R<List<Map<String, Object>>> logs = restTemplate.exchange(baseLogs + "?wholesalerId=" + wid,
                HttpMethod.GET, new HttpEntity<>(bearer(ta.token())), LIST).getBody();
        assertThat(logs).isNotNull();
        assertThat(logs.getCode()).isEqualTo(0);
        Map<String, Object> row = logs.getData().stream()
                .filter(m -> batchNo.equals(m.get("batchNo")))
                .findFirst().orElseThrow();
        assertThat(row.get("changeType")).isEqualTo("PUBLIC_PRICE");
        assertThat(row.get("adjustMode")).isEqualTo("PCT_UP");
        assertThat(row.get("affectedCount")).isEqualTo(3);
    }

    // ======================================================================
    // S1 批量调专属价
    // ======================================================================

    @Test
    @DisplayName("BATCH-S1-02 批量调专属价 SET_VALUE → 两行改为定值")
    void s1_batchCustomerSetValue() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "专调商户-" + ta.phone());
        String skuId = createSku(ta, wid, "专调品-" + ta.phone(), 10.00);
        setCustomerPrice(ta, wid, skuId, rtPhone(), 6.00);
        setCustomerPrice(ta, wid, skuId, rtPhone(), 6.00);

        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("skuId", skuId);
        dto.put("adjustMode", "SET_VALUE");
        dto.put("value", 5.00);
        R<Map<String, Object>> resp = postBatchCustomer(ta.token(), dto);
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData().get("affectedCount")).isEqualTo(2);

        List<Map<String, Object>> prices = listCustomerPrices(ta, wid);
        assertThat(prices).hasSize(2);
        assertThat(prices).allSatisfy(m ->
                assertThat(new BigDecimal(m.get("unitPrice").toString())).isEqualByComparingTo("5.00"));
    }

    @Test
    @DisplayName("BATCH-S1-03 批量调专属价 DISABLE → 命中行状态置 DISABLED")
    void s1_batchCustomerDisable() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "作废商户-" + ta.phone());
        String skuId = createSku(ta, wid, "作废品-" + ta.phone(), 10.00);
        Map<String, Object> p1 = setCustomerPrice(ta, wid, skuId, rtPhone(), 6.00);
        String id1 = p1.get("id").toString();

        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("ids", List.of(id1));
        dto.put("adjustMode", "DISABLE");
        R<Map<String, Object>> resp = postBatchCustomer(ta.token(), dto);
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData().get("affectedCount")).isEqualTo(1);

        Map<String, Object> row = listCustomerPrices(ta, wid).stream()
                .filter(m -> id1.equals(m.get("id").toString()))
                .findFirst().orElseThrow();
        assertThat(row.get("status")).isEqualTo("DISABLED");
    }

    // ======================================================================
    // S2 限额 / 钳制
    // ======================================================================

    @Test
    @DisplayName("BATCH-S2-01 >200 skuIds → 校验拒绝（40001）")
    void s2_publicLimitExceeded() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "超限商户-" + ta.phone());
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            tooMany.add(i);
        }
        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("skuIds", tooMany);
        dto.put("adjustMode", "PCT_UP");
        dto.put("value", 10);
        R<Map<String, Object>> resp = postBatchPublic(ta.token(), dto);
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).as(">200 skuIds 应校验失败").isEqualTo(40001);
    }

    @Test
    @DisplayName("BATCH-S2-02 >500 专属价 ids → 校验拒绝（40001）")
    void s2_customerLimitExceeded() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "超限专商户-" + ta.phone());
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            tooMany.add(i);
        }
        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("ids", tooMany);
        dto.put("adjustMode", "SET_VALUE");
        dto.put("value", 5.00);
        R<Map<String, Object>> resp = postBatchCustomer(ta.token(), dto);
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).as(">500 ids 应校验失败").isEqualTo(40001);
    }

    @Test
    @DisplayName("BATCH-S2-03 调后价 ≤0 → 该 SKU 跳过、计入 rejected、原价不变")
    void s2_clampSkip() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "钳制商户-" + ta.phone());
        String skuId = createSku(ta, wid, "钳制品-" + ta.phone(), 10.00);

        // DELTA -20：10 + (-20) = -10 ≤ 0 → 跳过
        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("skuIds", List.of(skuId));
        dto.put("adjustMode", "DELTA");
        dto.put("value", -20);
        R<Map<String, Object>> resp = postBatchPublic(ta.token(), dto);
        assertThat(resp).isNotNull();
        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData().get("affectedCount")).as("全部跳过").isEqualTo(0);
        assertThat(resp.getData().get("rejectedCount")).isEqualTo(1);

        // 原价不变
        assertThat(skuUnitPrice(ta, wid, skuId)).isEqualByComparingTo("10.00");
    }

    // ======================================================================
    // S7 并发 + 防重
    // ======================================================================

    @Test
    @DisplayName("BATCH-S7-01 并发批量调价（8 线程 PCT_UP 10%）→ 锁串行化，无丢失更新")
    void s7_concurrentNoLostUpdate() throws InterruptedException {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "并发商户-" + ta.phone());
        String skuId = createSku(ta, wid, "并发品-" + ta.phone(), 100.00);

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("skuIds", List.of(skuId));
        dto.put("adjustMode", "PCT_UP");
        dto.put("value", 10);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        R<Map<String, Object>> resp = postBatchPublic(ta.token(), dto);
                        if (resp != null && resp.getCode() == 0) {
                            ok.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    } catch (Exception e) {
                        rejected.incrementAndGet();
                    }
                });
            }
            ready.await();
            start.countDown();
        }

        int successes = ok.get();
        assertThat(successes).as("至少 1 次成功").isGreaterThanOrEqualTo(1);
        assertThat(ok.get() + rejected.get()).isEqualTo(threads);

        // 锁串行化 → 无丢失更新：最终价 == 100 依次 ×1.1（同服务端 HALF_UP 舍入）successes 次
        BigDecimal expected = new BigDecimal("100.00");
        for (int i = 0; i < successes; i++) {
            expected = expected.multiply(new BigDecimal("110"))
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        }
        assertThat(skuUnitPrice(ta, wid, skuId))
                .as("并发下最终价应等于串行叠加结果（无丢失更新）")
                .isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("BATCH-S7-02 5 分钟防重：两次快速调价，第 2 次被拒（50303）")
    void s7_cooldownRejectsSecond() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "防重商户-" + ta.phone());
        String skuId = createSku(ta, wid, "防重品-" + ta.phone(), 10.00);

        Map<String, Object> dto = new HashMap<>();
        dto.put("wholesalerId", wid);
        dto.put("skuIds", List.of(skuId));
        dto.put("adjustMode", "PCT_UP");
        dto.put("value", 10);

        R<Map<String, Object>> first = postBatchPublic(ta.token(), dto);
        assertThat(first).isNotNull();
        assertThat(first.getCode()).as("首次成功").isEqualTo(0);

        R<Map<String, Object>> second = postBatchPublic(ta.token(), dto);
        assertThat(second).isNotNull();
        assertThat(second.getCode()).as("5 分钟内第二次应被防重拒绝").isEqualTo(50303);
    }
}
