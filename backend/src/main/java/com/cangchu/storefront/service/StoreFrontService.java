package com.cangchu.storefront.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.storefront.vo.RtPriceListVo;
import com.cangchu.storefront.vo.RtSkuSearchItemVo;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.vo.RtTenantDirectoryItemVo;

import java.math.BigDecimal;
import java.util.List;

/**
 * RT 店铺前台聚合服务（phase-1 B2 · 只读浏览）。
 *
 * <p>RT 扫码进店无登录态/无 TenantContext，TenantLine 兜底不注入 tenant 条件，
 * 故本服务**先解析出 tenantId**，再以该 tenantId 显式过滤所有下游查询（复用 A2 的
 * {@code listByTenantForRt(tenantId,...)} 等带租户入参方法），杜绝跨店数据泄漏。
 *
 * <p>聚合口径：店内 WA（仅 ACTIVE）→ 每个 WA 的在售 SKU（listed=true 且 库存 qty>0）+ 公开价 + 当前库存。
 */
public interface StoreFrontService {

    /**
     * 进店页：按 storeId 或店铺码(tenantSimpleCode)解析到 tenant，聚合返回整页。
     * 匿名口径（无 RT 身份 → 仅公开价）。供 document 域 submitByRt 等匿名调用复用。
     *
     * @param storeId 店铺 id（与 code 二选一，storeId 优先）
     * @param code    店铺码（= 租户简码 tenantSimpleCode）
     */
    StoreFrontVo getStorePage(Long storeId, String code);

    /**
     * 进店页（P2 定价 Wave 3b · 可选鉴权）：附带 RT 身份解析专属价。
     * {@code rtPhone} 非空且命中有效专属价 → 各 SKU 的 {@code matchedPrice} 置为专属价（不同于公开价才置）；
     * {@code rtPhone} 为空（匿名）→ 全部走公开价，{@code matchedPrice} 恒 null。
     *
     * @param rtPhone 已登录 RT 的手机号；匿名传 null
     */
    StoreFrontVo getStorePage(Long storeId, String code, String rtPhone);

    /** 店内批发商列表（仅 ACTIVE），不含 SKU。供 /rt/wholesalers。 */
    List<StoreWholesalerVo> listWholesalers(Long storeId, String code);

    /** 某商户在售 SKU（含公开价 + 库存）。匿名口径（仅公开价）。供 /rt/skus 匿名访问。 */
    List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId);

    /**
     * 某商户在售 SKU（P2 定价 Wave 3b · 可选鉴权）：附带 RT 身份解析专属价。
     * 语义同 {@link #getStorePage(Long, String, String)} 的 matchedPrice 口径。供 /rt/skus 登录态访问。
     *
     * @param rtPhone 已登录 RT 的手机号；匿名传 null
     */
    List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId, String rtPhone);

    /**
     * RT「我的价目」（C1 专属价复购，23-p5-c-c1 §4.1）：当前店 × 该手机号有效客户专属价
     * 清单，按店内 ACTIVE wholesaler 分组（无价目行的商户不出组）。
     *
     * <p>手机号只在服务内转 hmac 盲查（不落日志/不返回明文），响应仅回尾号 4 位归属提示。
     * 纯只读，不建单不改库存；供公开端点 POST /rt/my-pricelist。
     *
     * @param rtPhone 客户身份（RT 手机号，必填非空）
     */
    RtPriceListVo getMyPriceList(Long storeId, String code, String rtPhone);

    /**
     * RT 首页「附近仓库」公开目录（US-RT-06 · 基于位置推荐仓库）。
     *
     * <p>仅返回 ACTIVE 租户及其默认店铺；不传坐标时按创建时间倒序、距离为 null。
     * 坐标采用 GCJ-02，距离按 haversine 公式估算（米）。
     *
     * @param lat   当前纬度（可空）
     * @param lng   当前经度（可空）
     * @param limit 返回上限（可空，默认 20，最大 50）
     */
    List<RtTenantDirectoryItemVo> listNearbyStores(BigDecimal lat, BigDecimal lng, Integer limit);

    /**
     * RT「逛商品 / 搜商品找仓库」公开跨仓分页检索（先搜货再进店）。
     *
     * <p>检索全平台在售 SKU（listed + 有货 + 商户 ACTIVE + 租户 ACTIVE），返回商品公开价/库存 +
     * 所属商户/仓库归属（店铺码可直接进店）。关键词为空/空白 = 逛全部在售商品（商品广场默认视图）。
     * 与进店浏览同口径：只下发公开字段，不含 PII；联系方式走询价确认后 phone-reveal（D-RT-01）。
     *
     * @param keyword 关键词（去空格；空/空白 = 不限关键词；超长截断至 50 字符；LIKE 通配符已转义）
     * @param lat     当前纬度（可空；有坐标且仓库有坐标时按距离升序）
     * @param lng     当前经度（可空）
     * @param page    页码（≥1，默认 1）
     * @param size    每页条数（默认 20，最大 50）
     */
    Page<RtSkuSearchItemVo> searchSkus(String keyword, BigDecimal lat, BigDecimal lng, long page, long size);
}
