package com.cangchu.pricing.service;

import com.cangchu.pricing.dto.BatchCustomerPriceDto;
import com.cangchu.pricing.dto.BatchPublicPriceDto;
import com.cangchu.pricing.dto.SetCustomerPriceDto;
import com.cangchu.pricing.dto.UpdateCustomerPriceDto;
import com.cangchu.pricing.vo.BatchPriceResultVo;
import com.cangchu.pricing.vo.CustomerPriceRef;
import com.cangchu.pricing.vo.CustomerPriceVo;
import com.cangchu.pricing.vo.PriceChangeLogVo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 定价服务（P2 定价 Wave 1）：客户专属价 CRUD + 价格解析（resolvePrice）。
 *
 * <p>鉴权口径沿用 A2：操作者须为该 wholesaler 的 WA 或该商户所属租户的 TA。
 * 专属价按唯一键 (wholesaler_id, rt_phone, sku_id) upsert。
 */
public interface PricingService {

    /**
     * 设置客户专属价（upsert）。已有 ACTIVE 记录则改价，否则新建。写后失效缓存。
     *
     * @param dto            {wholesalerId, skuId, rtPhone, unitPrice, expireAt?}
     * @param operatorUserId 操作人（WA 或该租户 TA）
     */
    CustomerPriceVo setCustomerPrice(SetCustomerPriceDto dto, Long operatorUserId);

    /** 部分更新专属价（unitPrice/expireAt/status，非空才改），归属校验 + 失效缓存。 */
    CustomerPriceVo updateCustomerPrice(Long id, UpdateCustomerPriceDto dto, Long operatorUserId);

    /** 作废专属价（status=DISABLED），归属校验 + 失效缓存。 */
    void revokeCustomerPrice(Long id, Long operatorUserId);

    /**
     * SKU 删除级联失效（P2 定价 Wave 3c）：把该 SKU 下所有 ACTIVE 专属价批量置 DISABLED，
     * 并逐行失效其 Redis 价格匹配缓存，使后续 resolvePrice 回退公开价（无脏缓存）。
     *
     * <p>供 product 域在**删除 SKU**（不可逆）时经本接口调用（product 域不直连
     * CustomerPriceMapper）。仅用于删除；**下架（toggleListing，可逆）不得级联**。
     * 无归属鉴权入参：删除动作本身在 product 域已完成 S4 归属校验，本方法只做数据级联。
     *
     * <p>注：截至 Wave 3c，SkuService 尚无删除/软删操作可挂接（仅 create/update/toggleListing），
     * 故本级联能力已就绪但暂无触发点（见 TODO Wave-later）。
     *
     * @param skuId 被删除的 SKU
     * @return 本次被置为 DISABLED 的专属价行数
     */
    int disableBySku(Long skuId);

    /**
     * 商户退驻级联失效（P2 入驻 Wave2 R13，PRD 05 §14b.5）：把该商户全部 ACTIVE 专属价
     * 批量置 DISABLED，并逐行失效 Redis 价格匹配缓存（复用 {@code invalidateAfterCommit}
     * 提交后失效链路，防脏缓存），后续 resolvePrice 回退公开价。
     *
     * <p>供 tenant 域在退驻审批通过的同一事务内调用（数据级联，无归属鉴权入参——
     * 审批动作在 tenant 域已完成 S4 校验）。60 天恢复不回滚：已失效的专属价不复活。
     *
     * @return 本次被置为 DISABLED 的专属价行数
     */
    int disableByWholesaler(Long wholesalerId);

    /** 列出某商户的专属价（归属校验），仅未软删记录。 */
    List<CustomerPriceVo> listCustomerPrices(Long wholesalerId, Long operatorUserId);

    /**
     * RT 价目跨域出口（C1，23-p5-c-c1 §5.1）：按 (wholesalerId, rtPhoneHmac) 返回该商户下
     * 该客户的所有<b>有效</b>专属价行（isActive：ACTIVE 且未过期），createdAt 倒序。
     *
     * <p>供 storefront 域在 RT 场景组装「我的价目」调用（storefront 不直连 CustomerPriceMapper）。
     * 无鉴权入参：调用方（storefront）已以店铺→租户解析 + wholesaler 归属完成隔离；
     * hmac 盲查不返回任何明文/尾号。无登录态 → 显式回 List.of()。
     */
    List<CustomerPriceRef> listActiveRefsByPhone(Long wholesalerId, String rtPhoneHmac);

    /**
     * 议价沉淀（P2 定价 Wave 3a）：确认询价时把议定成交价落为客户专属价（upsert）。
     *
     * <p>按唯一键 (wholesalerId, rtPhone, skuId) upsert：已有 ACTIVE 行则改其 unit_price；
     * 否则新建（雪花 id、tenantId 取自 wholesaler、{@code source=from_inquiry}、
     * {@code source_doc_no=sourceDocNo}、status ACTIVE）。复用 {@link #setCustomerPrice} 的
     * upsert + 写后失效缓存路径。校验 dealPrice&gt;0。
     *
     * <p>由 document 域在 confirmByWa 的同一 @Transactional 内调用（document 域不直连
     * CustomerPriceMapper），故库存不足回滚也会撤销本次沉淀。
     *
     * @param wholesalerId   商户
     * @param rtPhone        客户身份（RT 手机号）
     * @param skuId          商品
     * @param dealPrice      议定成交价（&gt;0）
     * @param sourceDocNo    来源询价单号
     * @param operatorUserId 操作人（该 wholesaler 的 WA）
     */
    void settleFromInquiry(Long wholesalerId, String rtPhone, Long skuId,
                           BigDecimal dealPrice, String sourceDocNo, Long operatorUserId);

    /**
     * 解析成交价（PRD §14b.1 优先级）：
     * rtPhone 空 → 公开价；否则命中 ACTIVE 专属价则用其 unitPrice；否则公开价。
     * 公开价 = qty >= sku.moqQty ? sku.moqPrice : sku.unitPrice。
     * 专属价命中/未命中结论带 Redis 缓存（key 不含 qty）。
     */
    BigDecimal resolvePrice(Long wholesalerId, Long skuId, String rtPhone, int qty);

    // ==================== Wave 2：批量调价 + 调价历史 ====================

    /**
     * 批量调公开价（POST /api/v1/tenant/skus/batch-price-update）。
     *
     * <p>并发/防重：先查 5 分钟防重冷却（{@code price:batch:cd:{wholesalerId}:PUBLIC_PRICE}），
     * 再取 Redisson 分布式锁 {@code lock:price:{wholesalerId}}（锁内经 self 代理跑事务体），
     * 成功后写冷却。每次写一行 price_change_logs。跨域改价经 SkuService.updateSku，不直连 SkuMapper。
     *
     * @return {batchNo, affectedCount, rejectedCount, rejectedIds}
     */
    BatchPriceResultVo batchUpdatePublicPrice(BatchPublicPriceDto dto, Long operatorUserId);

    /**
     * 批量调专属价（POST /api/v1/tenant/customer-prices/batch-update）。
     * 六种 adjustMode 全适用；PCT/SET/DELTA 改 unit_price，DISABLE 置 DISABLED，SET_EXPIRE 置 expire_at。
     * 每个受影响 (wholesalerId, rtPhone, skuId) 失效价格匹配缓存。并发/防重同公开价。
     */
    BatchPriceResultVo batchUpdateCustomerPrice(BatchCustomerPriceDto dto, Long operatorUserId);

    /**
     * 调价历史查询（GET /api/v1/tenant/price-change-logs）。
     * 归属校验后返回该商户的 price_change_logs（最新在前，上限 200），changeType 可空过滤。
     */
    List<PriceChangeLogVo> listPriceChangeLogs(String changeType, Long wholesalerId, Long operatorUserId);

    // ---- 事务体（仅供本类在持锁状态下经 @Lazy self 代理调用，保证 @Transactional 生效） ----

    /** 批量调公开价事务体（{@link #batchUpdatePublicPrice} 持锁调用）。 */
    BatchPriceResultVo doBatchPublicInTx(BatchPublicPriceDto dto, Long operatorUserId);

    /** 批量调专属价事务体（{@link #batchUpdateCustomerPrice} 持锁调用）。 */
    BatchPriceResultVo doBatchCustomerInTx(BatchCustomerPriceDto dto, Long operatorUserId);
}
