package com.cangchu.storefront.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.account.service.AccountService;
import com.cangchu.common.response.R;
import com.cangchu.storefront.dto.MyPriceListQueryDto;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.RtPriceListVo;
import com.cangchu.storefront.vo.RtSkuSearchItemVo;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.vo.RtTenantDirectoryItemVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * RT 扫码进店浏览 Controller（phase-1 B2 · 公开只读）。
 *
 * <p>路径前缀 {@code /api/v1/rt/**}：<b>不</b>在 {@code SaTokenConfig} 的 SaInterceptor include 列表内，
 * 故默认开放、无需登录（符合 G-1.2：用真实前缀声明开放归属）。RT 无登录态/无 TenantContext，
 * 数据范围完全由 service 内 storeId/code→tenantId 的解析 + 显式租户过滤决定，
 * <b>不</b>接受 X-Tenant-Id 决定数据（防跨店泄漏）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rt")
public class RtStoreController {

    private final StoreFrontService storeFrontService;
    private final AccountService accountService;

    /**
     * 进店页：店铺信息 + 店内 ACTIVE 批发商 + 各自在售 SKU（含公开价+库存）。storeId 优先，否则用 code。
     *
     * <p>P2 定价 Wave 3b（可选鉴权）：已登录 RT 携带有效 token 时下发其专属价 matchedPrice；匿名仅公开价。
     */
    @GetMapping("/store")
    public R<StoreFrontVo> store(@RequestParam(required = false) Long storeId,
                                 @RequestParam(required = false) String code) {
        return R.ok(storeFrontService.getStorePage(storeId, code, currentRtPhone()));
    }

    /** 店内批发商列表（仅 ACTIVE，不含 SKU）。 */
    @GetMapping("/wholesalers")
    public R<List<StoreWholesalerVo>> wholesalers(@RequestParam(required = false) Long storeId,
                                                  @RequestParam(required = false) String code) {
        return R.ok(storeFrontService.listWholesalers(storeId, code));
    }

    /**
     * 某商户在售 SKU（含公开价 + 当前库存）。
     * <p>可选鉴权同 {@link #store}：已登录 RT 下发 matchedPrice，匿名仅公开价。
     */
    @GetMapping("/skus")
    public R<List<StoreSkuVo>> skus(@RequestParam(required = false) Long storeId,
                                    @RequestParam(required = false) String code,
                                    @RequestParam Long wholesalerId) {
        return R.ok(storeFrontService.listSkus(storeId, code, wholesalerId, currentRtPhone()));
    }

    /**
     * RT「我的价目」（C1 专属价复购，23-p5-c-c1 §4.1）：当前店为该手机号维护的
     * 客户专属价清单（按 wholesaler 分组）。公开端点。
     *
     * <p>手机号放 POST body（不放 GET query——防明文手机号落访问日志）；
     * 服务内 hmac 盲查，响应仅尾号 4 位归属提示，永不返回明文。
     */
    @PostMapping("/my-pricelist")
    public R<RtPriceListVo> myPriceList(@Valid @RequestBody MyPriceListQueryDto dto) {
        return R.ok(storeFrontService.getMyPriceList(dto.getStoreId(), dto.getCode(), dto.getRtPhone()));
    }

    /**
     * RT 首页「附近仓库」公开目录（US-RT-06 · 基于位置推荐仓库）。
     *
     * <p>仅返回 ACTIVE 租户及其默认店铺；不传坐标时按创建时间倒序、距离为 null。
     * 坐标采用 GCJ-02，距离按 haversine 公式估算（米）。
     */
    @GetMapping("/tenants")
    public R<List<RtTenantDirectoryItemVo>> tenants(
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return R.ok(storeFrontService.listNearbyStores(lat, lng, limit));
    }

    /**
     * RT 首页「搜商品找仓库」公开跨仓检索（先搜货再进店）。
     *
     * <p>按关键词检索全平台在售 SKU（listed + 有货 + 商户/租户均 ACTIVE），返回公开价/库存 +
     * 所属商户与仓库归属；买家用返回的 tenantSimpleCode 直接进店提交意向单，由商户主动联系
     * （联系方式不在本端点下发，仍走询价确认后 phone-reveal，D-RT-01）。
     */
    @GetMapping("/sku-search")
    public R<List<RtSkuSearchItemVo>> skuSearch(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return R.ok(storeFrontService.searchSkus(keyword, lat, lng, limit));
    }

    /**
     * 解析当前访客的 RT 定价身份（手机号）：已登录 → 其手机号；匿名 → null。
     *
     * <p>{@code /api/v1/rt/**} 不在 SaInterceptor include 列表内（公开路由，无强制登录），
     * 故 {@link StpUtil#isLogin()} 在无 token 时可能因无上下文抛异常——防御性 try/catch，
     * 任何异常一律按匿名（返回 null）处理，绝不因鉴权探测失败而中断公开浏览。
     */
    private String currentRtPhone() {
        try {
            if (!StpUtil.isLogin()) {
                return null;
            }
            return accountService.getPhoneByUserId(StpUtil.getLoginIdAsLong());
        } catch (Exception e) {
            // 公开路由上探测登录态失败 → 按匿名处理，不泄漏异常
            log.debug("[P2] RT 浏览态解析登录身份失败，按匿名处理: {}", e.getMessage());
            return null;
        }
    }
}
