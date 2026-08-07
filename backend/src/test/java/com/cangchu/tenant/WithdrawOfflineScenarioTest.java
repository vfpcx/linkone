package com.cangchu.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.dto.SetCustomerPriceDto;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.WholesalerLifecycleService;
import com.cangchu.tenant.service.WholesalerStateMachine;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * P2 入驻生态 Wave2 场景测试：R13 退驻 + R14 强制下架。
 *
 * <p>测试基建沿用 {@link OnboardingScenarioTest}（HTTP 主链）+ {@code InquiryScenarioTest}
 * （mapper/service seed）混合风格。用例编号对齐 04-onboarding-test-plan：
 * <ul>
 *   <li>WDR-*：退驻前置失败（库存/未结单）、成功副作用链四段、防重、恢复、mine、precheck、撤回。</li>
 *   <li>WDR-S1-02（高危）：退驻踢 token 必须 WA 与全部 WE 一起踢，漏踢 WE 即红。</li>
 *   <li>BND-*（高危 BND-S3-01）：59/60/61 天边界——以 withdrawn_at（审批通过时刻）为起点、
 *       数据库时间比较（seed 与断言全部经 SQL TIMESTAMPADD，不用应用时钟）。</li>
 *   <li>CON-*：退驻审批并发 CAS 恰一方成功。</li>
 *   <li>FOF-*：强制下架新拒老放分界、不可原地恢复、不可达转移。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WithdrawOfflineScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private WholesalerLifecycleService lifecycleService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private StoreFrontService storeFrontService;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private CustomerPriceMapper customerPriceMapper;
    @Autowired
    private OutboundRequestMapper outboundRequestMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String base;
    private String baseAccount;
    private String baseWithdraw;
    private String baseTaWithdraw;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        baseAccount = base + "/api/v1/account";
        baseWithdraw = base + "/api/v1/wholesaler/withdraw";
        baseTaWithdraw = base + "/api/v1/tenant/wholesaler-withdraw-applications";
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};

    // ==================== HTTP helpers（沿用 Wave1 风格） ====================

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
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

    /** 注册 TA + apply 建仓 + 置 tenant ACTIVE + seed 店铺（storefront/询价链路需要）。 */
    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA").getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("退驻仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市滨江区");
        R<Map<String, Object>> body = restTemplate.exchange(base + "/api/v1/tenant/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());

        // 仓库审核（OPS）不在本测试范围：直接置 ACTIVE 供 RT 进店/询价链路
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        ensureStore(tenantId);
        return new TaContext(phone, token, tenantId);
    }

    private long ensureStore(long tenantId) {
        Store existing = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getTenantId, tenantId).last("LIMIT 1"));
        if (existing != null) {
            if (!"ACTIVE".equals(existing.getStatus())) {
                existing.setStatus("ACTIVE");
                storeMapper.updateById(existing);
            }
            return existing.getId();
        }
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setName("店-" + s.getId());
        s.setStatus("ACTIVE");
        storeMapper.insert(s);
        return s.getId();
    }

    private record WaContext(String phone, String password, String token, Long userId, Long wholesalerId) {}

    /** 完整入驻一个 WA（Wave1 主链：注册 → 自助申请 → TA 通过），返回 wholesalerId。 */
    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA");

        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "退驻测试商户-" + phone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(reg.getToken())), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();

        Map<String, Object> auditDto = Map.of("action", "APPROVED", "remark", "Wave2 测试放行");
        R<Map<String, Object>> approved = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        long wholesalerId = Long.parseLong(approved.getData().get("wholesalerId").toString());

        // 入驻绑定发生在注册后 → 重新登录刷新会话（拿到带 WA/wholesaler 绑定的新 token）
        String token = login(phone, "WaPass123");
        return new WaContext(phone, "WaPass123", token, reg.getUserId(), wholesalerId);
    }

    private R<Map<String, Object>> applyWithdraw(String waToken, String reason) {
        Map<String, Object> dto = reason == null ? Map.of() : Map.of("reason", reason);
        return restTemplate.exchange(baseWithdraw, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(waToken)), MAP).getBody();
    }

    private R<Map<String, Object>> auditWithdraw(String taToken, String applicationId,
                                                 String action, String remark) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("action", action);
        if (remark != null) dto.put("remark", remark);
        return restTemplate.exchange(baseTaWithdraw + "/" + applicationId + "/audit",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(taToken)), MAP).getBody();
    }

    private R<Map<String, Object>> forceOffline(String taToken, long wholesalerId, String reason) {
        Map<String, Object> dto = reason == null ? Map.of() : Map.of("reason", reason);
        return restTemplate.exchange(base + "/api/v1/tenant/wholesalers/" + wholesalerId + "/force-offline",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(taToken)), MAP).getBody();
    }

    // ==================== seed helpers ====================

    private long seedSku(long tenantId, long wholesalerId) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("退驻品-" + s.getId());
        s.setUnitPrice(new BigDecimal("20.00"));
        s.setMoqPrice(new BigDecimal("18.00"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        return s.getId();
    }

    private void seedStock(long tenantId, long wholesalerId, long skuId, int qty) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(wholesalerId).tenantId(tenantId).skuId(skuId)
                .qty(qty).refDocNo("IN-WDR-SEED").operatorUserId(1L).build());
    }

    private void clearStock(long tenantId, long wholesalerId, long skuId, int qty) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(wholesalerId).tenantId(tenantId).skuId(skuId)
                .qty(qty).refDocNo("OUT-WDR-CLEAR").operatorUserId(1L).build());
    }

    /** RT 免登录提交询价（PENDING）。 */
    private InquiryVo submitInquiry(long storeId, long wholesalerId, long skuId, int qty, String rtPhone) {
        TenantContext.clear();
        SubmitInquiryDto dto = new SubmitInquiryDto();
        dto.setStoreId(storeId);
        dto.setWholesalerId(wholesalerId);
        dto.setRtPhone(rtPhone);
        SubmitInquiryDto.InquiryItemDto item = new SubmitInquiryDto.InquiryItemDto();
        item.setSkuId(skuId);
        item.setQty(qty);
        dto.setItems(List.of(item));
        return inquiryService.submitByRt(dto);
    }

    /** seed 一个"WE 员工"会话：注册真实用户拿 token，再直插 (WE, wholesalerId, ACTIVE) 角色行。 */
    private WaContext seedWeUserWithSession(long tenantId, long wholesalerId) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WePass123", "WA");
        UserRole we = new UserRole();
        we.setId(snowflakeIdUtil.nextId());
        we.setUserId(reg.getUserId());
        we.setRole("WE");
        we.setTenantId(tenantId);
        we.setWholesalerId(wholesalerId);
        we.setStatus("ACTIVE");
        we.setPriority(4);
        userRoleMapper.insert(we);
        return new WaContext(phone, "WePass123", reg.getToken(), reg.getUserId(), wholesalerId);
    }

    /** 用数据库时间把 withdrawn_at 拨回 daysAgo 天前（BND-S3-01：不经应用时钟）。 */
    private void rewindWithdrawnAt(long wholesalerId, int daysAgo) {
        jdbcTemplate.update(
                "UPDATE wholesalers SET withdrawn_at = TIMESTAMPADD(DAY, ?, NOW()) WHERE id = ?",
                -daysAgo, wholesalerId);
    }

    /** 任意登录端点探活：token 被踢后应 41001。 */
    private int probeAuth(String token) {
        R<Map<String, Object>> body = restTemplate.exchange(baseWithdraw + "/mine",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP).getBody();
        return body == null ? -1 : body.getCode();
    }

    private Wholesaler wholesaler(long id) {
        TenantContext.clear();
        return wholesalerMapper.selectById(id);
    }

    // ======================================================================
    // WDR：R13 退驻
    // ======================================================================

    @Test
    @DisplayName("WDR-01 库存未清零退驻被拒(50312)+precheck 同口径；清零后发起成功 PENDING")
    void wdr01_stockPrecondition() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());
        seedStock(ta.tenantId(), wa.wholesalerId(), skuId, 5);

        // precheck 只读出口与提交校验同口径
        R<Map<String, Object>> pre = restTemplate.exchange(baseWithdraw + "/precheck",
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(pre).isNotNull();
        assertThat(pre.getCode()).isEqualTo(0);
        assertThat(pre.getData().get("stockCleared")).isEqualTo(false);

        R<Map<String, Object>> blocked = applyWithdraw(wa.token(), "生意不做了");
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).as("有库存不允许退驻").isEqualTo(50312);

        clearStock(ta.tenantId(), wa.wholesalerId(), skuId, 5);
        R<Map<String, Object>> ok = applyWithdraw(wa.token(), "生意不做了");
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("status")).isEqualTo("PENDING");
        // 发起留痕：withdraw_apply_at 落库
        assertThat(wholesaler(wa.wholesalerId()).getWithdrawApplyAt()).isNotNull();
    }

    @Test
    @DisplayName("WDR-02 存在未结询价单退驻被拒(50314)，precheck openDocs.count 可见")
    void wdr02_openDocsPrecondition() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        long storeId = ensureStore(ta.tenantId());
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());
        seedStock(ta.tenantId(), wa.wholesalerId(), skuId, 10);
        // PENDING 询价（未确认=未结单据）
        submitInquiry(storeId, wa.wholesalerId(), skuId, 2, "18800001111");
        // 清库存，只留未结单据这一项失败因子
        clearStock(ta.tenantId(), wa.wholesalerId(), skuId, 10);

        R<Map<String, Object>> pre = restTemplate.exchange(baseWithdraw + "/precheck",
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(pre).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> openDocs = (Map<String, Object>) pre.getData().get("openDocs");
        assertThat(openDocs.get("cleared")).isEqualTo(false);
        assertThat(Integer.parseInt(openDocs.get("count").toString())).isEqualTo(1);

        R<Map<String, Object>> blocked = applyWithdraw(wa.token(), null);
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).as("有未结询价不允许退驻").isEqualTo(50314);
    }

    @Test
    @DisplayName("WDR-03 退驻通过副作用链四段：WITHDRAWN+SKU 全下架+店铺隐藏+专属价失效(含缓存)+WA/WE 双踢(WDR-S1-02)")
    void wdr03_approveSideEffectChain() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WaContext we = seedWeUserWithSession(ta.tenantId(), wa.wholesalerId());
        long storeId = ensureStore(ta.tenantId());
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());
        String rtPhone = "18811112222";

        // 专属价 + 预热价格匹配缓存（withdrawal 后必须失效回公开价 20.00）
        SetCustomerPriceDto priceDto = new SetCustomerPriceDto();
        priceDto.setWholesalerId(wa.wholesalerId());
        priceDto.setSkuId(skuId);
        priceDto.setRtPhone(rtPhone);
        priceDto.setUnitPrice(new BigDecimal("15.50"));
        TenantContext.clear();
        pricingService.setCustomerPrice(priceDto, wa.userId());
        assertThat(pricingService.resolvePrice(wa.wholesalerId(), skuId, rtPhone, 1))
                .isEqualByComparingTo("15.50");

        // 店铺页退驻前可见
        assertThat(storeFrontService.listWholesalers(storeId, null))
                .extracting(StoreWholesalerVo::getWholesalerId).contains(wa.wholesalerId());

        String appId = applyWithdraw(wa.token(), "退驻副作用链").getData().get("applicationId").toString();

        // TA 退驻审批列表可见该 PENDING 申请（含商户名冗余）
        R<Map<String, Object>> taList = restTemplate.exchange(baseTaWithdraw + "?page=1&size=50&status=PENDING",
                HttpMethod.GET, new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(taList).isNotNull();
        assertThat(taList.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taRecs = (List<Map<String, Object>>) taList.getData().get("records");
        assertThat(taRecs).extracting(m -> m.get("id").toString()).contains(appId);

        R<Map<String, Object>> approved = auditWithdraw(ta.token(), appId, "APPROVED", "同意退驻");
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);

        // ① 主体 WITHDRAWN + withdrawn_at（60 天窗口起点）
        Wholesaler w = wholesaler(wa.wholesalerId());
        assertThat(w.getStatus()).isEqualTo("WITHDRAWN");
        assertThat(w.getWithdrawnAt()).isNotNull();

        // ② 全部 SKU 下架
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getWholesalerId, wa.wholesalerId()));
        assertThat(skus).isNotEmpty().allMatch(s -> Boolean.FALSE.equals(s.getListed()));

        // ③ 店铺页隐藏
        assertThat(storeFrontService.listWholesalers(storeId, null))
                .extracting(StoreWholesalerVo::getWholesalerId).doesNotContain(wa.wholesalerId());

        // ④ 专属价全失效：DB 置 DISABLED + Redis 缓存失效（resolvePrice 回公开价）
        List<CustomerPrice> prices = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wa.wholesalerId()));
        assertThat(prices).isNotEmpty().allMatch(p -> "DISABLED".equals(p.getStatus()));
        assertThat(pricingService.resolvePrice(wa.wholesalerId(), skuId, rtPhone, 1))
                .as("缓存必须随事务提交失效，回退公开价").isEqualByComparingTo("20.00");

        // ⑤ WDR-S1-02：WA 与 WE token 一起踢（漏踢 WE 即此断言红）
        assertThat(probeAuth(wa.token())).as("WA token 应被踢出").isEqualTo(41001);
        assertThat(probeAuth(we.token())).as("WE token 应被踢出（高危漏点 WDR-S1-02）").isEqualTo(41001);
    }

    @Test
    @DisplayName("WDR-04 PENDING 期间重复发起→50316；已退驻再发起→50202（状态机收口）")
    void wdr04_duplicateAndWithdrawnReject() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);

        String appId = applyWithdraw(wa.token(), null).getData().get("applicationId").toString();
        R<Map<String, Object>> dup = applyWithdraw(wa.token(), null);
        assertThat(dup).isNotNull();
        assertThat(dup.getCode()).as("PENDING 未决重复发起").isEqualTo(50316);

        assertThat(auditWithdraw(ta.token(), appId, "APPROVED", null).getCode()).isEqualTo(0);
        // 已退驻：token 已踢 → 重登后再发起 → 50202
        String newToken = login(wa.phone(), wa.password());
        R<Map<String, Object>> again = applyWithdraw(newToken, null);
        assertThat(again).isNotNull();
        assertThat(again.getCode()).as("已退驻不可再发起退驻").isEqualTo(50202);
    }

    @Test
    @DisplayName("WDR-05 60 天内恢复→ACTIVE；SKU 保持下架需手动上架；专属价不复活")
    void wdr05_restoreWithinWindow() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());
        SetCustomerPriceDto priceDto = new SetCustomerPriceDto();
        priceDto.setWholesalerId(wa.wholesalerId());
        priceDto.setSkuId(skuId);
        priceDto.setRtPhone("18833334444");
        priceDto.setUnitPrice(new BigDecimal("12.00"));
        TenantContext.clear();
        pricingService.setCustomerPrice(priceDto, wa.userId());

        String appId = applyWithdraw(wa.token(), null).getData().get("applicationId").toString();
        assertThat(auditWithdraw(ta.token(), appId, "APPROVED", null).getCode()).isEqualTo(0);

        // token 已踢 → 重登后恢复
        String newToken = login(wa.phone(), wa.password());
        R<Map<String, Object>> restored = restTemplate.exchange(baseWithdraw + "/restore",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(newToken)), MAP).getBody();
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).isEqualTo(0);
        assertThat(restored.getData().get("status")).isEqualTo("ACTIVE");

        Wholesaler w = wholesaler(wa.wholesalerId());
        assertThat(w.getStatus()).isEqualTo("ACTIVE");
        assertThat(w.getWithdrawnAt()).isNull();

        // SKU 保持下架（需手动重新上架）
        Sku sku = skuMapper.selectById(skuId);
        assertThat(sku.getListed()).as("恢复后 SKU 保持下架").isFalse();
        // 专属价不复活
        List<CustomerPrice> prices = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wa.wholesalerId()));
        assertThat(prices).isNotEmpty().allMatch(p -> "DISABLED".equals(p.getStatus()));
    }

    @Test
    @DisplayName("WDR-06 mine：本人可见（含驳回理由）；他人 mine 为空——不泄漏")
    void wdr06_mineVisibility() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        String appId = applyWithdraw(wa.token(), "个人原因").getData().get("applicationId").toString();
        assertThat(auditWithdraw(ta.token(), appId, "REJECTED", "先结清货款").getCode()).isEqualTo(0);

        R<Map<String, Object>> mine = restTemplate.exchange(baseWithdraw + "/mine",
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(mine).isNotNull();
        assertThat(mine.getCode()).isEqualTo(0);
        assertThat(mine.getData().get("status")).isEqualTo("REJECTED");
        assertThat(mine.getData().get("auditRemark")).isEqualTo("先结清货款");
        assertThat(mine.getData().get("reason")).isEqualTo("个人原因");

        // 另一个 WA 的 mine 为空（driven by applicant_user_id，不接受客户端过滤参数）
        WaContext other = onboardWa(ta);
        R<Map<String, Object>> otherMine = restTemplate.exchange(baseWithdraw + "/mine",
                HttpMethod.GET, new HttpEntity<>(bearer(other.token())), MAP).getBody();
        assertThat(otherMine).isNotNull();
        assertThat(otherMine.getCode()).isEqualTo(0);
        assertThat(otherMine.getData()).isNull();
    }

    @Test
    @DisplayName("WDR-07 precheck 三态结构：stockCleared/openDocs{cleared,count}/billing.cleared=null(P4 灰态)")
    void wdr07_precheckShape() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);

        R<Map<String, Object>> pre = restTemplate.exchange(baseWithdraw + "/precheck",
                HttpMethod.GET, new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(pre).isNotNull();
        assertThat(pre.getCode()).isEqualTo(0);
        Map<String, Object> data = pre.getData();
        assertThat(data.get("wholesalerId")).isEqualTo(wa.wholesalerId().toString());
        assertThat(data.get("status")).isEqualTo("ACTIVE");
        assertThat(data.get("stockCleared")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> openDocs = (Map<String, Object>) data.get("openDocs");
        assertThat(openDocs.get("cleared")).isEqualTo(true);
        assertThat(Integer.parseInt(openDocs.get("count").toString())).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> billing = (Map<String, Object>) data.get("billing");
        // P4 W3（14 §3.5-1，O-5 兑现）：灰态占位 {cleared:null} 转真值 {cleared:bool, count}
        // ——WDR-07 用例按设计适配；未结账单拦截场景见 BillPermissionLinkageScenarioTest R13-01/02
        assertThat(billing.get("cleared")).as("billing P4 W3 真值（无账单即已结清）").isEqualTo(true);
        assertThat(Long.parseLong(billing.get("count").toString())).isZero();
    }

    @Test
    @DisplayName("WDR-08 撤回：PENDING 可撤→CANCELLED 并可重新发起；已审批后撤回→50315")
    void wdr08_cancelWithdraw() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);

        applyWithdraw(wa.token(), "先试试");
        R<Map<String, Object>> cancelled = restTemplate.exchange(baseWithdraw + "/cancel",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(wa.token())), MAP).getBody();
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getCode()).isEqualTo(0);
        assertThat(cancelled.getData().get("status")).isEqualTo("CANCELLED");
        // 撤回清发起留痕
        assertThat(wholesaler(wa.wholesalerId()).getWithdrawApplyAt()).isNull();

        // 可重新发起
        R<Map<String, Object>> reapply = applyWithdraw(wa.token(), "这次真退");
        assertThat(reapply).isNotNull();
        assertThat(reapply.getCode()).isEqualTo(0);
        String appId = reapply.getData().get("applicationId").toString();

        // TA 驳回后再撤回 → 没有 PENDING 可撤 50315
        assertThat(auditWithdraw(ta.token(), appId, "REJECTED", "驳回").getCode()).isEqualTo(0);
        R<Map<String, Object>> lateCancel = restTemplate.exchange(baseWithdraw + "/cancel",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(wa.token())), MAP).getBody();
        assertThat(lateCancel).isNotNull();
        assertThat(lateCancel.getCode()).isEqualTo(50315);
    }

    // ======================================================================
    // BND：59/60/61 天边界（BND-S3-01 高危：数据库时间口径）
    // ======================================================================

    /** 建一个已退驻商户并把 withdrawn_at 拨回 daysAgo 天，返回 WA 上下文（token 为重登后有效态）。 */
    private WaContext withdrawnWaAgedDays(TaContext ta, int daysAgo) {
        WaContext wa = onboardWa(ta);
        String appId = applyWithdraw(wa.token(), null).getData().get("applicationId").toString();
        assertThat(auditWithdraw(ta.token(), appId, "APPROVED", null).getCode()).isEqualTo(0);
        rewindWithdrawnAt(wa.wholesalerId(), daysAgo);
        return new WaContext(wa.phone(), wa.password(), login(wa.phone(), wa.password()),
                wa.userId(), wa.wholesalerId());
    }

    @Test
    @DisplayName("BND-01 第 59 天：归档任务不归档，仍可恢复")
    void bnd01_day59() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = withdrawnWaAgedDays(ta, 59);

        lifecycleService.archiveExpiredWithdrawn();
        assertThat(wholesaler(wa.wholesalerId()).getStatus()).as("59 天未到窗口不归档").isEqualTo("WITHDRAWN");

        R<Map<String, Object>> restored = restTemplate.exchange(baseWithdraw + "/restore",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(wa.token())), MAP).getBody();
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).as("59 天窗口内可恢复").isEqualTo(0);
        assertThat(wholesaler(wa.wholesalerId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BND-02 第 60 天整：>=60 归档生效(ARCHIVED+archived_at)；恢复→50317")
    void bnd02_day60() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = withdrawnWaAgedDays(ta, 60);

        int archived = lifecycleService.archiveExpiredWithdrawn();
        assertThat(archived).isGreaterThanOrEqualTo(1);
        Wholesaler w = wholesaler(wa.wholesalerId());
        assertThat(w.getStatus()).as("60 天整按 >=60 归档").isEqualTo("ARCHIVED");
        assertThat(w.getArchivedAt()).isNotNull();

        R<Map<String, Object>> restored = restTemplate.exchange(baseWithdraw + "/restore",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(wa.token())), MAP).getBody();
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).as("已归档不可恢复").isEqualTo(50317);
    }

    @Test
    @DisplayName("BND-03 第 61 天：归档任务未跑恢复也被拒(50317，窗口在 SQL 内判定)；任务跑后归档")
    void bnd03_day61() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = withdrawnWaAgedDays(ta, 61);

        // 先不跑归档任务：恢复窗口条件写在 UPDATE WHERE（数据库时间）→ 同样拒绝
        R<Map<String, Object>> restored = restTemplate.exchange(baseWithdraw + "/restore",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(wa.token())), MAP).getBody();
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).as("超窗未归档也不可恢复").isEqualTo(50317);
        assertThat(wholesaler(wa.wholesalerId()).getStatus()).isEqualTo("WITHDRAWN");

        lifecycleService.archiveExpiredWithdrawn();
        assertThat(wholesaler(wa.wholesalerId()).getStatus()).isEqualTo("ARCHIVED");
    }

    // ======================================================================
    // CON：并发审批 CAS
    // ======================================================================

    @Test
    @DisplayName("CON-02 并发 approve/reject 同一退驻申请 → 恰一方成功，败者 50315（CAS）")
    void con02_concurrentWithdrawAudit() throws Exception {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        String appId = applyWithdraw(wa.token(), null).getData().get("applicationId").toString();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approve = pool.submit(() -> {
                start.await();
                R<Map<String, Object>> r = auditWithdraw(ta.token(), appId, "APPROVED", null);
                return r != null ? r.getCode() : -1;
            });
            Future<Integer> reject = pool.submit(() -> {
                start.await();
                R<Map<String, Object>> r = auditWithdraw(ta.token(), appId, "REJECTED", "并发驳回");
                return r != null ? r.getCode() : -1;
            });
            start.countDown();
            int approveCode = approve.get();
            int rejectCode = reject.get();

            assertThat((approveCode == 0) ^ (rejectCode == 0))
                    .as("并发审批必须恰一方成功: approve=%s reject=%s", approveCode, rejectCode)
                    .isTrue();
            assertThat(approveCode == 0 ? rejectCode : approveCode).isEqualTo(50315);

            // 终态与赢家一致
            String expected = approveCode == 0 ? "WITHDRAWN" : "ACTIVE";
            assertThat(wholesaler(wa.wholesalerId()).getStatus()).isEqualTo(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    // ======================================================================
    // FOF：R14 强制下架
    // ======================================================================

    @Test
    @DisplayName("FOF-01 强制下架：reason 必填；成功→OFFLINE+reason 留痕+店铺隐藏+踢 token")
    void fof01_forceOffline() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        long storeId = ensureStore(ta.tenantId());

        R<Map<String, Object>> noReason = forceOffline(ta.token(), wa.wholesalerId(), null);
        assertThat(noReason).isNotNull();
        assertThat(noReason.getCode()).as("reason 必填").isIn(40001, 40003);

        R<Map<String, Object>> ok = forceOffline(ta.token(), wa.wholesalerId(), "多次假货投诉属实");
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("status")).isEqualTo("OFFLINE");

        Wholesaler w = wholesaler(wa.wholesalerId());
        assertThat(w.getStatus()).isEqualTo("OFFLINE");
        assertThat(w.getOfflineReason()).isEqualTo("多次假货投诉属实");
        assertThat(w.getOfflineAt()).isNotNull();

        // 店铺隐藏 + WA token 被踢
        assertThat(storeFrontService.listWholesalers(storeId, null))
                .extracting(StoreWholesalerVo::getWholesalerId).doesNotContain(wa.wholesalerId());
        assertThat(probeAuth(wa.token())).as("下架后 WA token 应被踢出").isEqualTo(41001);
    }

    @Test
    @DisplayName("FOF-02 新拒老放分界：下架前确认的询价走完(出库存在)；下架后新询价 50313、旧 PENDING 不可确认 50313")
    void fof02_newRejectedOldAllowed() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        long storeId = ensureStore(ta.tenantId());
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());
        seedStock(ta.tenantId(), wa.wholesalerId(), skuId, 100);

        // 下架前：询价 A 确认走完（原子转出库 COMPLETED）；询价 B 停在 PENDING
        InquiryVo inqA = submitInquiry(storeId, wa.wholesalerId(), skuId, 3, "18855556666");
        InquiryVo inqB = submitInquiry(storeId, wa.wholesalerId(), skuId, 2, "18855557777");
        TenantContext.clear();
        InquiryVo confirmedA = inquiryService.confirmByWa(inqA.getId(), null, wa.userId());
        // P3 BE-W2（12 §8.1）：确认后停 CONFIRMED，出库单 PENDING_ACCEPT（库存已扣）
        assertThat(confirmedA.getStatus()).isEqualTo(InquiryRequest.STATUS_CONFIRMED);

        // TA 强制下架
        assertThat(forceOffline(ta.token(), wa.wholesalerId(), "违规经营").getCode()).isEqualTo(0);

        // 老业务放行（12 §8.3）：下架前确认生成的出库单原样保留（不回滚不作废），
        // PENDING_ACCEPT 的老单允许走完（print/register 不做 R14 前置）
        List<OutboundRequest> outbounds = outboundRequestMapper.selectList(
                new LambdaQueryWrapper<OutboundRequest>()
                        .eq(OutboundRequest::getInquiryId, inqA.getId()));
        assertThat(outbounds).hasSize(1);
        assertThat(outbounds.get(0).getStatus()).isEqualTo(OutboundRequest.STATUS_PENDING_ACCEPT);

        // 新业务拒绝①：下架后新询价创建被拒 50313
        TenantContext.clear();
        BizException submitEx = catchThrowableOfType(
                () -> submitInquiry(storeId, wa.wholesalerId(), skuId, 1, "18855558888"),
                BizException.class);
        assertThat(submitEx).isNotNull();
        assertThat(submitEx.getCode()).as("下架后新询价被拒").isEqualTo(50313);

        // 新业务拒绝②：下架前的 PENDING 询价不可再确认（分界=下架时刻单据状态）
        TenantContext.clear();
        assertThatThrownBy(() -> inquiryService.confirmByWa(inqB.getId(), null, wa.userId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(50313));
        // 且 B 保持 PENDING、未产生出库
        assertThat(outboundRequestMapper.selectCount(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inqB.getId()))).isZero();
    }

    @Test
    @DisplayName("FOF-03 不可达转移收口：已退驻→强制下架 50202；OFFLINE→恢复/再退驻 50318；状态机表断言")
    void fof03_unreachableTransitions() {
        TaContext ta = registerTaWithTenant();

        // 已退驻 → 强制下架 ❌ 50202
        WaContext withdrawnWa = onboardWa(ta);
        String appId = applyWithdraw(withdrawnWa.token(), null).getData().get("applicationId").toString();
        assertThat(auditWithdraw(ta.token(), appId, "APPROVED", null).getCode()).isEqualTo(0);
        R<Map<String, Object>> offlineWithdrawn = forceOffline(ta.token(), withdrawnWa.wholesalerId(), "试图下架");
        assertThat(offlineWithdrawn).isNotNull();
        assertThat(offlineWithdrawn.getCode()).as("已退驻→已下架不可达").isEqualTo(50202);

        // OFFLINE → 恢复 ❌（无原地恢复；restore 仅服务 WITHDRAWN）/ 再发起退驻 ❌
        WaContext offlineWa = onboardWa(ta);
        assertThat(forceOffline(ta.token(), offlineWa.wholesalerId(), "先下架").getCode()).isEqualTo(0);
        String newToken = login(offlineWa.phone(), offlineWa.password());
        R<Map<String, Object>> restore = restTemplate.exchange(baseWithdraw + "/restore",
                HttpMethod.POST, new HttpEntity<>(Map.of(), bearer(newToken)), MAP).getBody();
        assertThat(restore).isNotNull();
        assertThat(restore.getCode()).as("已下架→正常不可达（不可原地恢复）").isEqualTo(50318);
        R<Map<String, Object>> withdraw = applyWithdraw(newToken, null);
        assertThat(withdraw).isNotNull();
        assertThat(withdraw.getCode()).as("已下架→已退驻本期不可达（P4 仲裁）").isEqualTo(50318);
        // 主体状态未被改动
        assertThat(wholesaler(offlineWa.wholesalerId()).getStatus()).isEqualTo("OFFLINE");

        // 状态机转移表（集中收口的单点真相）
        assertThat(WholesalerStateMachine.canTransition("ACTIVE", "WITHDRAWN")).isTrue();
        assertThat(WholesalerStateMachine.canTransition("ACTIVE", "OFFLINE")).isTrue();
        assertThat(WholesalerStateMachine.canTransition("WITHDRAWN", "ACTIVE")).isTrue();
        assertThat(WholesalerStateMachine.canTransition("WITHDRAWN", "ARCHIVED")).isTrue();
        assertThat(WholesalerStateMachine.canTransition("WITHDRAWN", "OFFLINE")).isFalse();
        assertThat(WholesalerStateMachine.canTransition("OFFLINE", "ACTIVE")).isFalse();
        assertThat(WholesalerStateMachine.canTransition("OFFLINE", "WITHDRAWN")).isFalse();
        assertThat(WholesalerStateMachine.canTransition("ARCHIVED", "ACTIVE")).isFalse();
    }

    // ======================================================================
    // ONB：Wave2 契约对齐（WA 本人申请列表）
    // ======================================================================

    @Test
    @DisplayName("ONB-08 GET /wholesaler/applications：本人可见（含驳回理由）；他人列表不含")
    void onb08_listMineApplications() {
        TaContext ta = registerTaWithTenant();
        String waPhone = uniquePhone(P_WA);
        String waToken = registerAndLogin(waPhone, "WaPass123", "WA").getToken();

        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "本人列表商户-" + waPhone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(waToken)), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();
        // TA 驳回带理由
        Map<String, Object> auditDto = Map.of("action", "REJECTED", "remark", "资料不全请补充");
        assertThat(restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP)
                .getBody().getCode()).isEqualTo(0);

        R<List<Map<String, Object>>> mine = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.GET, new HttpEntity<>(bearer(waToken)), LIST).getBody();
        assertThat(mine).isNotNull();
        assertThat(mine.getCode()).isEqualTo(0);
        Map<String, Object> rec = mine.getData().stream()
                .filter(m -> appId.equals(m.get("id").toString())).findFirst().orElseThrow();
        assertThat(rec.get("status")).isEqualTo("REJECTED");
        assertThat(rec.get("auditRemark")).isEqualTo("资料不全请补充");

        // 他人（另一 WA）看不到
        String otherToken = registerAndLogin(uniquePhone(P_WA), "WaPass123", "WA").getToken();
        R<List<Map<String, Object>>> others = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.GET, new HttpEntity<>(bearer(otherToken)), LIST).getBody();
        assertThat(others).isNotNull();
        assertThat(others.getCode()).isEqualTo(0);
        assertThat(others.getData()).extracting(m -> m.get("id").toString()).doesNotContain(appId);
    }
}
