package com.cangchu.pricing;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 专属价失效链路场景测试（P2 定价 Wave 3c，HTTP 黑盒 Style A + service 直调 Style B）。
 *
 * <p>沿用 {@link PricingScenarioTest} 基建（@SpringBootTest RANDOM_PORT + TestRestTemplate
 * + H2 + mock 短信码 888888 + bare-token Authorization）。
 *
 * <p>覆盖专属价三条失效路径 → resolvePrice 回退公开价，且无脏缓存：
 * <ul>
 *   <li>EXPIRY-01 过期（expireAt 过去）→ resolvePrice 回退公开价（isActive() 兜住）。</li>
 *   <li>EXPIRY-02 手动作废（revokeCustomerPrice→DISABLED）→ resolvePrice 回退公开价。</li>
 *   <li>EXPIRY-03 SKU 删除级联 disableBySku(skuId)：该 SKU 全部 ACTIVE 专属价置 DISABLED，
 *       后续 resolvePrice 回退公开价——且先 resolve 命中缓存、级联后再 resolve 仍回退，证明缓存已失效。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingExpiryScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PricingService pricingService;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

    private String base(String suffix) {
        return "http://localhost:" + port + suffix;
    }

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private String rtPhone() {
        return "17" + String.format("%09d", SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private LoginVo registerAndLogin(String phone) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("TaPass123");
        dto.setSmsCode("888888");
        dto.setRole("TA");
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(base("/api/v1/account/register"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s", phone).isEqualTo(0);
        return body.getData();
    }

    private record TaContext(String phone, String token, Long tenantId, Long userId) {}

    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        LoginVo login = registerAndLogin(phone);
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("失效链路仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(base("/api/v1/tenant/apply"), HttpMethod.POST,
                new HttpEntity<>(dto, bearer(login.getToken())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, login.getToken(), tenantId, login.getUserId());
    }

    private String createWholesaler(TaContext ta, String name) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        R<Map<String, Object>> body = restTemplate.exchange(base("/api/v1/tenant/wholesalers"), HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create wholesaler").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    /** 建一个 SKU（unitPrice=10.00, moqPrice=8.00, moqQty=5），返回 skuId。 */
    private String createSku(TaContext ta, String wholesalerId, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("spec", "500ml*24");
        m.put("unitPrice", 10.00);
        m.put("moqPrice", 8.00);
        m.put("moqQty", 5);
        R<Map<String, Object>> body = restTemplate.exchange(
                base("/api/v1/tenant/skus?wholesalerId=" + wholesalerId),
                HttpMethod.POST, new HttpEntity<>(m, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create sku").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    /** 设专属价（unitPrice + 可选 expireAt），返回创建的专属价 id。 */
    private String setPrice(TaContext ta, String wid, String skuId, String phone, Object unitPrice, String expireAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("wholesalerId", wid);
        m.put("skuId", skuId);
        m.put("rtPhone", phone);
        m.put("unitPrice", unitPrice);
        if (expireAt != null) {
            m.put("expireAt", expireAt);
        }
        R<Map<String, Object>> body = restTemplate.exchange(base("/api/v1/tenant/customer-prices"),
                HttpMethod.POST, new HttpEntity<>(m, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("set customer price").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    // ======================================================================
    // EXPIRY-01 过期 → resolvePrice 回退公开价
    // ======================================================================

    @Test
    @DisplayName("PRICE-EXPIRY-01 过期专属价 → resolvePrice 回退公开价")
    void expiry_fallsBackToPublic() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "过期商户-" + ta.phone());
        String skuId = createSku(ta, wid, "过期品-" + ta.phone());
        Long widL = Long.valueOf(wid);
        Long skuL = Long.valueOf(skuId);
        String phone = rtPhone();

        // 设一条已过期（expireAt 在过去）的专属价 3.00
        setPrice(ta, wid, skuId, phone, 3.00, "2020-01-01T00:00:00");

        // isActive() 判过期 → resolvePrice 回退公开价（qty<5 用单价 10.00）
        assertThat(pricingService.resolvePrice(widL, skuL, phone, 1))
                .as("过期专属价回退公开价").isEqualByComparingTo("10.00");
    }

    // ======================================================================
    // EXPIRY-02 手动作废（revoke→DISABLED）→ resolvePrice 回退公开价
    // ======================================================================

    @Test
    @DisplayName("PRICE-EXPIRY-02 手动作废（DISABLED）→ resolvePrice 回退公开价")
    void manualDisable_fallsBackToPublic() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "作废商户-" + ta.phone());
        String skuId = createSku(ta, wid, "作废品-" + ta.phone());
        Long widL = Long.valueOf(wid);
        Long skuL = Long.valueOf(skuId);
        String phone = rtPhone();

        String priceId = setPrice(ta, wid, skuId, phone, 6.00, null);
        // 作废前命中专属价
        assertThat(pricingService.resolvePrice(widL, skuL, phone, 1))
                .as("作废前命中专属价").isEqualByComparingTo("6.00");

        // 手动作废 → DISABLED（同时失效缓存）
        pricingService.revokeCustomerPrice(Long.valueOf(priceId), ta.userId());

        assertThat(pricingService.resolvePrice(widL, skuL, phone, 1))
                .as("作废后回退公开价").isEqualByComparingTo("10.00");
    }

    // ======================================================================
    // EXPIRY-03 SKU 删除级联 disableBySku → 全部 DISABLED + 缓存失效
    // ======================================================================

    @Test
    @DisplayName("PRICE-EXPIRY-03 disableBySku 级联作废该 SKU 全部专属价 + 缓存失效")
    void disableBySku_cascadesAndInvalidatesCache() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "级联商户-" + ta.phone());
        String skuId = createSku(ta, wid, "级联品-" + ta.phone());
        String otherSku = createSku(ta, wid, "无关品-" + ta.phone());
        Long widL = Long.valueOf(wid);
        Long skuL = Long.valueOf(skuId);
        Long otherSkuL = Long.valueOf(otherSku);

        // 该 SKU 下两个客户各一条专属价，另有一条属于无关 SKU（不应被级联）
        String p1 = rtPhone();
        String p2 = rtPhone();
        String p3 = rtPhone();
        setPrice(ta, wid, skuId, p1, 6.00, null);
        setPrice(ta, wid, skuId, p2, 7.00, null);
        setPrice(ta, wid, otherSku, p3, 5.00, null);

        // 先 resolve → 命中专属价并写入 Redis 缓存（关键：制造脏缓存前提）
        assertThat(pricingService.resolvePrice(widL, skuL, p1, 1)).isEqualByComparingTo("6.00");
        assertThat(pricingService.resolvePrice(widL, skuL, p2, 1)).isEqualByComparingTo("7.00");
        assertThat(pricingService.resolvePrice(widL, otherSkuL, p3, 1)).isEqualByComparingTo("5.00");

        // SKU 删除级联：该 SKU 全部 ACTIVE 专属价置 DISABLED
        int disabled = pricingService.disableBySku(skuL);
        assertThat(disabled).as("级联作废行数").isEqualTo(2);

        // 该 SKU 两个客户后续 resolve 回退公开价（qty<5 → 10.00）——证明缓存已随级联失效（否则读到 6/7 旧缓存）
        assertThat(pricingService.resolvePrice(widL, skuL, p1, 1))
                .as("级联后 p1 回退公开价（缓存已失效）").isEqualByComparingTo("10.00");
        assertThat(pricingService.resolvePrice(widL, skuL, p2, 1))
                .as("级联后 p2 回退公开价（缓存已失效）").isEqualByComparingTo("10.00");

        // 无关 SKU 的专属价不受影响
        assertThat(pricingService.resolvePrice(widL, otherSkuL, p3, 1))
                .as("无关 SKU 专属价不被级联").isEqualByComparingTo("5.00");

        // 再次级联幂等：已无 ACTIVE → 返回 0
        assertThat(pricingService.disableBySku(skuL)).as("重复级联幂等").isZero();
    }
}
