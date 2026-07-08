package com.cangchu.pricing.service;

import com.cangchu.pricing.dto.BatchCustomerPriceDto;
import com.cangchu.pricing.dto.BatchPublicPriceDto;
import com.cangchu.pricing.dto.SetCustomerPriceDto;
import com.cangchu.pricing.dto.UpdateCustomerPriceDto;
import com.cangchu.pricing.vo.BatchPriceResultVo;
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

    /** 列出某商户的专属价（归属校验），仅未软删记录。 */
    List<CustomerPriceVo> listCustomerPrices(Long wholesalerId, Long operatorUserId);

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
