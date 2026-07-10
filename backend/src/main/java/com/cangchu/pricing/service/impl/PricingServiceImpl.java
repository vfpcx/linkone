package com.cangchu.pricing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.pricing.dto.BatchCustomerPriceDto;
import com.cangchu.pricing.dto.BatchPublicPriceDto;
import com.cangchu.pricing.dto.SetCustomerPriceDto;
import com.cangchu.pricing.dto.UpdateCustomerPriceDto;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.entity.PriceChangeLog;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.pricing.mapper.PriceChangeLogMapper;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.pricing.vo.BatchPriceResultVo;
import com.cangchu.pricing.vo.CustomerPriceVo;
import com.cangchu.pricing.vo.PriceChangeLogVo;
import com.cangchu.product.dto.SkuUpdateDto;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 定价服务实现（P2 定价 Wave 1）。
 *
 * <p>安全/隔离规约（沿用 A2 SkuServiceImpl 口径）：
 * <ul>
 *   <li>S4 越权：写操作鉴权以 user_roles 登录态推导——操作者须为该 wholesaler 的 WA
 *       或该商户所属租户的 TA；皆非则 42101。</li>
 *   <li>租户隔离：customer_prices 已纳入 TenantLine 白名单兜底；归属判定再以 wholesaler
 *       真实 tenant_id 为准。</li>
 *   <li>跨域读价：只经 SkuService.getById（G-S1/G-S2），不直连 SkuMapper。</li>
 * </ul>
 *
 * <p>resolvePrice 用 Redis 缓存「专属价命中/未命中」结论（key 不含 qty），TTL 60s，
 * 命中缓存则跳过 DB 查询；公开价部分不缓存（SkuService 调用轻量）。写操作后失效缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingServiceImpl implements PricingService {

    private final CustomerPriceMapper customerPriceMapper;
    private final PriceChangeLogMapper priceChangeLogMapper;
    private final SkuService skuService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final RedissonClient redissonClient;
    private final SnowflakeIdUtil snowflakeIdUtil;
    private final ObjectMapper objectMapper;

    /** 自注入代理：用于在锁内调用带 @Transactional 的批量事务体（避免 this 自调用使事务失效）。 */
    @Lazy
    @Autowired
    private PricingService self;

    /** 缓存哨兵：该 (wholesaler, phone, sku) 无有效专属价，走公开价。 */
    private static final String SENTINEL_NONE = "NONE";
    /** 专属价命中/未命中结论缓存 TTL（秒）。 */
    private static final long MATCH_CACHE_TTL_SEC = 60L;

    /** 批量调价分布式锁 tryLock 等待/租约（秒）。 */
    private static final long LOCK_WAIT_SECONDS = 30L;
    private static final long LOCK_LEASE_SECONDS = 15L;
    /** 批量调价 5 分钟防重冷却（秒）。 */
    private static final long BATCH_COOLDOWN_SEC = 300L;
    /** before_after_json 最多保留的明细条数（防止超大 JSON）。 */
    private static final int BEFORE_AFTER_MAX_ENTRIES = 100;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final String FIELD_UNIT_PRICE = "unitPrice";
    private static final String FIELD_MOQ_PRICE = "moqPrice";

    @Override
    @Transactional
    public CustomerPriceVo setCustomerPrice(SetCustomerPriceDto dto, Long operatorUserId) {
        WholesalerVo wholesaler = wholesalerService.getById(dto.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        validatePrice(dto.getUnitPrice());

        // upsert on 唯一键 (wholesaler_id, rt_phone, sku_id)：已有 ACTIVE 行则改，否则新建
        CustomerPrice existing = customerPriceMapper.selectOne(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, dto.getWholesalerId())
                .eq(CustomerPrice::getRtPhone, dto.getRtPhone())
                .eq(CustomerPrice::getSkuId, dto.getSkuId())
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                .last("LIMIT 1"));

        CustomerPrice result;
        if (existing != null) {
            LocalDateTime now = LocalDateTime.now();
            customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                    .eq(CustomerPrice::getId, existing.getId())
                    .set(CustomerPrice::getUnitPrice, dto.getUnitPrice())
                    .set(CustomerPrice::getExpireAt, dto.getExpireAt())
                    .set(CustomerPrice::getUpdatedAt, now));
            existing.setUnitPrice(dto.getUnitPrice());
            existing.setExpireAt(dto.getExpireAt());
            existing.setUpdatedAt(now);
            result = existing;
        } else {
            CustomerPrice cp = new CustomerPrice();
            cp.setId(snowflakeIdUtil.nextId());
            cp.setTenantId(wholesaler.getTenantId());
            cp.setWholesalerId(dto.getWholesalerId());
            cp.setSkuId(dto.getSkuId());
            cp.setRtPhone(dto.getRtPhone());
            cp.setUnitPrice(dto.getUnitPrice());
            cp.setStatus(CustomerPrice.STATUS_ACTIVE);
            cp.setSource(CustomerPrice.SOURCE_MANUAL);
            cp.setExpireAt(dto.getExpireAt());
            cp.setCreatedBy(operatorUserId);
            customerPriceMapper.insert(cp);
            result = cp;
        }

        invalidate(dto.getWholesalerId(), dto.getRtPhone(), dto.getSkuId());
        log.info("[P2] operator {} 设置专属价 wholesaler={} phone={} sku={} price={}",
                operatorUserId, dto.getWholesalerId(), dto.getRtPhone(), dto.getSkuId(), dto.getUnitPrice());
        return toVo(result);
    }

    @Override
    @Transactional
    public void settleFromInquiry(Long wholesalerId, String rtPhone, Long skuId,
                                  BigDecimal dealPrice, String sourceDocNo, Long operatorUserId) {
        validatePrice(dealPrice);
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }

        // upsert on 唯一键 (wholesaler_id, rt_phone, sku_id)：已有 ACTIVE 行则改价，否则新建（source=from_inquiry）
        CustomerPrice existing = customerPriceMapper.selectOne(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getRtPhone, rtPhone)
                .eq(CustomerPrice::getSkuId, skuId)
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                .last("LIMIT 1"));

        if (existing != null) {
            customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                    .eq(CustomerPrice::getId, existing.getId())
                    .set(CustomerPrice::getUnitPrice, dealPrice)
                    .set(CustomerPrice::getUpdatedAt, LocalDateTime.now()));
        } else {
            CustomerPrice cp = new CustomerPrice();
            cp.setId(snowflakeIdUtil.nextId());
            cp.setTenantId(wholesaler.getTenantId());
            cp.setWholesalerId(wholesalerId);
            cp.setSkuId(skuId);
            cp.setRtPhone(rtPhone);
            cp.setUnitPrice(dealPrice);
            cp.setStatus(CustomerPrice.STATUS_ACTIVE);
            cp.setSource(CustomerPrice.SOURCE_FROM_INQUIRY);
            cp.setSourceDocNo(sourceDocNo);
            cp.setCreatedBy(operatorUserId);
            customerPriceMapper.insert(cp);
        }

        invalidate(wholesalerId, rtPhone, skuId);
        log.info("[P2] operator {} 议价沉淀 wholesaler={} phone={} sku={} price={} src={}",
                operatorUserId, wholesalerId, rtPhone, skuId, dealPrice, sourceDocNo);
    }

    @Override
    @Transactional
    public CustomerPriceVo updateCustomerPrice(Long id, UpdateCustomerPriceDto dto, Long operatorUserId) {
        CustomerPrice cp = requireOwnedCustomerPrice(id, operatorUserId);

        LambdaUpdateWrapper<CustomerPrice> uw =
                new LambdaUpdateWrapper<CustomerPrice>().eq(CustomerPrice::getId, id);

        if (dto.getUnitPrice() != null) {
            validatePrice(dto.getUnitPrice());
            cp.setUnitPrice(dto.getUnitPrice());
            uw.set(CustomerPrice::getUnitPrice, dto.getUnitPrice());
        }
        if (dto.getExpireAt() != null) {
            cp.setExpireAt(dto.getExpireAt());
            uw.set(CustomerPrice::getExpireAt, dto.getExpireAt());
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            cp.setStatus(dto.getStatus());
            uw.set(CustomerPrice::getStatus, dto.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        cp.setUpdatedAt(now);
        uw.set(CustomerPrice::getUpdatedAt, now);
        customerPriceMapper.update(null, uw);

        invalidate(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
        return toVo(cp);
    }

    @Override
    @Transactional
    public void revokeCustomerPrice(Long id, Long operatorUserId) {
        CustomerPrice cp = requireOwnedCustomerPrice(id, operatorUserId);
        customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                .eq(CustomerPrice::getId, id)
                .set(CustomerPrice::getStatus, CustomerPrice.STATUS_DISABLED)
                .set(CustomerPrice::getUpdatedAt, LocalDateTime.now()));
        invalidate(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
        log.info("[P2] operator {} 作废专属价 {}", operatorUserId, id);
    }

    @Override
    @Transactional
    public int disableBySku(Long skuId) {
        // 先查出该 SKU 所有 ACTIVE 专属价（需其 wholesaler/rtPhone 才能逐行失效缓存），
        // 再批量置 DISABLED；DB 状态更新与缓存失效同步，后续 resolvePrice 回退公开价（无脏缓存）。
        List<CustomerPrice> rows = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getSkuId, skuId)
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE));
        if (rows.isEmpty()) {
            return 0;
        }
        customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                .eq(CustomerPrice::getSkuId, skuId)
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                .set(CustomerPrice::getStatus, CustomerPrice.STATUS_DISABLED)
                .set(CustomerPrice::getUpdatedAt, LocalDateTime.now()));
        for (CustomerPrice cp : rows) {
            invalidate(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
        }
        log.info("[P2] SKU {} 删除级联：作废专属价 {} 行", skuId, rows.size());
        return rows.size();
    }

    @Override
    public List<CustomerPriceVo> listCustomerPrices(Long wholesalerId, Long operatorUserId) {
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        List<CustomerPrice> list = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .orderByDesc(CustomerPrice::getCreatedAt));
        return list.stream().map(this::toVo).toList();
    }

    @Override
    public BigDecimal resolvePrice(Long wholesalerId, Long skuId, String rtPhone, int qty) {
        SkuVo sku = skuService.getById(skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.PRICE_MATCH_FAILED);
        }
        BigDecimal publicPrice = publicPrice(sku, qty);

        // PRD §14b.1：无客户身份 → 公开价
        if (rtPhone == null || rtPhone.isBlank()) {
            return publicPrice;
        }
        BigDecimal custom = resolveCustomUnitPrice(wholesalerId, skuId, rtPhone);
        return custom != null ? custom : publicPrice;
    }

    // ==================== Wave 2：批量调价 + 调价历史 ====================

    @Override
    public BatchPriceResultVo batchUpdatePublicPrice(BatchPublicPriceDto dto, Long operatorUserId) {
        validatePublicBatch(dto);
        WholesalerVo wholesaler = wholesalerService.getById(dto.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        return runGuarded(dto.getWholesalerId(), PriceChangeLog.CHANGE_TYPE_PUBLIC_PRICE,
                () -> self.doBatchPublicInTx(dto, operatorUserId));
    }

    @Override
    public BatchPriceResultVo batchUpdateCustomerPrice(BatchCustomerPriceDto dto, Long operatorUserId) {
        validateCustomerBatch(dto);
        WholesalerVo wholesaler = wholesalerService.getById(dto.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        return runGuarded(dto.getWholesalerId(), PriceChangeLog.CHANGE_TYPE_CUSTOMER_PRICE,
                () -> self.doBatchCustomerInTx(dto, operatorUserId));
    }

    @Override
    public List<PriceChangeLogVo> listPriceChangeLogs(String changeType, Long wholesalerId, Long operatorUserId) {
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        List<PriceChangeLog> list = priceChangeLogMapper.selectList(new LambdaQueryWrapper<PriceChangeLog>()
                .eq(PriceChangeLog::getWholesalerId, wholesalerId)
                .eq(changeType != null && !changeType.isBlank(), PriceChangeLog::getChangeType, changeType)
                .orderByDesc(PriceChangeLog::getCreatedAt)
                .orderByDesc(PriceChangeLog::getId)
                .last("LIMIT 200"));
        return list.stream().map(this::toLogVo).toList();
    }

    @Override
    @Transactional
    public BatchPriceResultVo doBatchPublicInTx(BatchPublicPriceDto dto, Long operatorUserId) {
        String field = normalizeField(dto.getTargetField());
        String mode = dto.getAdjustMode();
        BigDecimal value = dto.getValue();

        List<Map<String, Object>> beforeAfter = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int affected = 0;

        for (Long skuId : dto.getSkuIds()) {
            SkuVo sku = skuService.getById(skuId);
            // 不存在（含被 TenantLine 过滤）或不属于该商户 → 跳过
            if (sku == null || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
                rejected.add(String.valueOf(skuId));
                continue;
            }
            BigDecimal oldPrice = FIELD_MOQ_PRICE.equals(field) ? sku.getMoqPrice() : sku.getUnitPrice();
            if (oldPrice == null) {
                rejected.add(String.valueOf(skuId));
                continue;
            }
            BigDecimal newPrice = computeAdjustedPrice(oldPrice, mode, value);
            // 钳制：结果价必须 > 0，否则跳过该 SKU
            if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
                rejected.add(String.valueOf(skuId));
                continue;
            }
            // 跨域改价必须经 SkuService.updateSku（partial-update），不直连 SkuMapper
            SkuUpdateDto up = new SkuUpdateDto();
            if (FIELD_MOQ_PRICE.equals(field)) {
                up.setMoqPrice(newPrice);
            } else {
                up.setUnitPrice(newPrice);
            }
            skuService.updateSku(skuId, up, operatorUserId);
            recordBeforeAfter(beforeAfter, skuId, oldPrice.toPlainString(), newPrice.toPlainString());
            affected++;
        }

        String summary = "公开价 SKU×" + affected + " " + field + " " + describeMode(mode, value);
        String batchNo = writeLog(dto.getWholesalerId(), PriceChangeLog.CHANGE_TYPE_PUBLIC_PRICE,
                mode, affected, summary, beforeAfter, operatorUserId);

        log.info("[P2] operator {} 批量调公开价 wholesaler={} mode={} field={} affected={} rejected={}",
                operatorUserId, dto.getWholesalerId(), mode, field, affected, rejected.size());
        return BatchPriceResultVo.builder()
                .batchNo(batchNo).affectedCount(affected)
                .rejectedCount(rejected.size()).rejectedIds(rejected).build();
    }

    @Override
    @Transactional
    public BatchPriceResultVo doBatchCustomerInTx(BatchCustomerPriceDto dto, Long operatorUserId) {
        String mode = dto.getAdjustMode();
        BigDecimal value = dto.getValue();

        // 选取目标行：显式 ids 优先，否则按过滤条件（skuId/rtPhone）
        LambdaQueryWrapper<CustomerPrice> qw = new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, dto.getWholesalerId());
        if (dto.getIds() != null && !dto.getIds().isEmpty()) {
            qw.in(CustomerPrice::getId, dto.getIds());
        } else {
            qw.eq(dto.getSkuId() != null, CustomerPrice::getSkuId, dto.getSkuId())
              .eq(dto.getRtPhone() != null && !dto.getRtPhone().isBlank(),
                      CustomerPrice::getRtPhone, dto.getRtPhone());
        }
        List<CustomerPrice> rows = customerPriceMapper.selectList(qw);

        List<Map<String, Object>> beforeAfter = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int affected = 0;
        LocalDateTime now = LocalDateTime.now();

        for (CustomerPrice cp : rows) {
            LambdaUpdateWrapper<CustomerPrice> uw = new LambdaUpdateWrapper<CustomerPrice>()
                    .eq(CustomerPrice::getId, cp.getId());
            String before;
            String after;

            if (PriceChangeLog.ADJUST_MODE_DISABLE.equals(mode)) {
                before = cp.getStatus();
                after = CustomerPrice.STATUS_DISABLED;
                uw.set(CustomerPrice::getStatus, CustomerPrice.STATUS_DISABLED);
            } else if (PriceChangeLog.ADJUST_MODE_SET_EXPIRE.equals(mode)) {
                before = cp.getExpireAt() == null ? "" : cp.getExpireAt().toString();
                after = dto.getExpireAt().toString();
                uw.set(CustomerPrice::getExpireAt, dto.getExpireAt());
            } else {
                // 价格类：PCT_UP/PCT_DOWN/SET_VALUE/DELTA 改 unit_price，结果须 > 0
                BigDecimal newPrice = computeAdjustedPrice(cp.getUnitPrice(), mode, value);
                if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    rejected.add(String.valueOf(cp.getId()));
                    continue;
                }
                before = cp.getUnitPrice().toPlainString();
                after = newPrice.toPlainString();
                uw.set(CustomerPrice::getUnitPrice, newPrice);
            }
            uw.set(CustomerPrice::getUpdatedAt, now);
            customerPriceMapper.update(null, uw);
            // 失效价格匹配缓存（复用 Wave 1 私有方法）
            invalidate(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
            recordBeforeAfter(beforeAfter, cp.getId(), before, after);
            affected++;
        }

        String summary = "专属价 ×" + affected + " " + describeMode(mode, value);
        String batchNo = writeLog(dto.getWholesalerId(), PriceChangeLog.CHANGE_TYPE_CUSTOMER_PRICE,
                mode, affected, summary, beforeAfter, operatorUserId);

        log.info("[P2] operator {} 批量调专属价 wholesaler={} mode={} affected={} rejected={}",
                operatorUserId, dto.getWholesalerId(), mode, affected, rejected.size());
        return BatchPriceResultVo.builder()
                .batchNo(batchNo).affectedCount(affected)
                .rejectedCount(rejected.size()).rejectedIds(rejected).build();
    }

    // ==================== 私有方法 ====================

    /** 公开价：qty>=起批量取起批价，否则单价。 */
    private BigDecimal publicPrice(SkuVo sku, int qty) {
        return (sku.getMoqQty() != null && qty >= sku.getMoqQty()) ? sku.getMoqPrice() : sku.getUnitPrice();
    }

    /**
     * 解析专属单价（带缓存）：命中有效专属价返回其 unitPrice，否则返回 null（=走公开价）。
     * 缓存 key 不含 qty；存 unitPrice 字符串或哨兵 NONE。
     */
    private BigDecimal resolveCustomUnitPrice(Long wholesalerId, Long skuId, String rtPhone) {
        String key = matchKey(wholesalerId, rtPhone, skuId);
        RBucket<String> bucket = redissonClient.getBucket(key);
        String cached = bucket.get();
        if (cached != null) {
            return SENTINEL_NONE.equals(cached) ? null : new BigDecimal(cached);
        }
        // miss：查 DB（唯一键保证 ACTIVE 且未软删 ≤1 条），再判 isActive（含 expireAt）
        CustomerPrice cp = customerPriceMapper.selectOne(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getRtPhone, rtPhone)
                .eq(CustomerPrice::getSkuId, skuId)
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                .last("LIMIT 1"));
        BigDecimal result = (cp != null && cp.isActive()) ? cp.getUnitPrice() : null;
        bucket.set(result != null ? result.toPlainString() : SENTINEL_NONE,
                MATCH_CACHE_TTL_SEC, TimeUnit.SECONDS);
        return result;
    }

    private String matchKey(Long wholesalerId, String rtPhone, Long skuId) {
        return "price:match:" + wholesalerId + ":" + rtPhone + ":" + skuId;
    }

    /** 写后失效专属价匹配缓存。 */
    private void invalidate(Long wholesalerId, String rtPhone, Long skuId) {
        redissonClient.getBucket(matchKey(wholesalerId, rtPhone, skuId)).delete();
    }

    /**
     * S4 归属鉴权：operator 须为该商户的 WA 或该商户所属租户的 TA；皆非则越权拒绝（42101）。
     */
    private void requireWaOrTa(WholesalerVo wholesaler, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesaler.getId())) {
            return;
        }
        if (!authService.hasRole(userId, "TA", wholesaler.getTenantId())) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /** 读取专属价并做归属鉴权；不存在（含被 TenantLine 过滤）→ CUSTOMER_PRICE_NOT_FOUND。 */
    private CustomerPrice requireOwnedCustomerPrice(Long id, Long operatorUserId) {
        CustomerPrice cp = customerPriceMapper.selectById(id);
        if (cp == null) {
            throw new BizException(ErrorCode.CUSTOMER_PRICE_NOT_FOUND);
        }
        WholesalerVo wholesaler = wholesalerService.getById(cp.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        return cp;
    }

    /** 专属价不变量：unit_price>0。 */
    private void validatePrice(BigDecimal unitPrice) {
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.CUSTOMER_PRICE_INVALID);
        }
    }

    // ---- Wave 2 批量调价私有工具 ----

    /**
     * 批量调价并发/防重护栏：先查 5 分钟冷却，再取 Redisson 锁跑事务体，成功后写冷却。
     *
     * <p>冷却用「get 判空 + 成功后 set」（非原子 trySet）：
     * 并发突发时各线程在首个事务提交前都读到空冷却，遂全部通过冷却门、再由锁串行化（无丢失更新）；
     * 而串行的两次快速调用，第二次读到冷却 → 拒绝（PRICE_BATCH_TOO_FREQUENT）。
     */
    private BatchPriceResultVo runGuarded(Long wholesalerId, String changeType,
                                          Supplier<BatchPriceResultVo> txBody) {
        String cdKey = "price:batch:cd:" + wholesalerId + ":" + changeType;
        RBucket<String> cooldown = redissonClient.getBucket(cdKey);
        if (cooldown.get() != null) {
            throw new BizException(ErrorCode.PRICE_BATCH_TOO_FREQUENT);
        }

        RLock lock = redissonClient.getLock("lock:price:" + wholesalerId);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.PRICE_BATCH_LOCK_FAILED);
        }
        if (!acquired) {
            throw new BizException(ErrorCode.PRICE_BATCH_LOCK_FAILED);
        }
        BatchPriceResultVo result;
        try {
            // 事务体经 self 代理调用，保证 @Transactional 生效；提交后才在 finally 释放锁
            result = txBody.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        // 仅在成功（未抛异常）后设置冷却
        cooldown.set("1", BATCH_COOLDOWN_SEC, TimeUnit.SECONDS);
        return result;
    }

    /** 公开价批量：仅 PCT_UP/PCT_DOWN/SET_VALUE/DELTA；DISABLE/SET_EXPIRE 拒绝；value 必填。 */
    private void validatePublicBatch(BatchPublicPriceDto dto) {
        if (!isPriceMode(dto.getAdjustMode())) {
            throw new BizException(ErrorCode.PRICE_BATCH_MODE_INVALID);
        }
        if (dto.getValue() == null) {
            throw new BizException(ErrorCode.PRICE_BATCH_TARGET_REQUIRED);
        }
        String field = normalizeField(dto.getTargetField());
        if (!FIELD_UNIT_PRICE.equals(field) && !FIELD_MOQ_PRICE.equals(field)) {
            throw new BizException(ErrorCode.PRICE_BATCH_TARGET_REQUIRED);
        }
    }

    /** 专属价批量：六种 mode 均可；价格类 value 必填，SET_EXPIRE expireAt 必填；须至少一个选择条件。 */
    private void validateCustomerBatch(BatchCustomerPriceDto dto) {
        String mode = dto.getAdjustMode();
        boolean known = isPriceMode(mode)
                || PriceChangeLog.ADJUST_MODE_DISABLE.equals(mode)
                || PriceChangeLog.ADJUST_MODE_SET_EXPIRE.equals(mode);
        if (!known) {
            throw new BizException(ErrorCode.PRICE_BATCH_MODE_INVALID);
        }
        if (isPriceMode(mode) && dto.getValue() == null) {
            throw new BizException(ErrorCode.PRICE_BATCH_TARGET_REQUIRED);
        }
        if (PriceChangeLog.ADJUST_MODE_SET_EXPIRE.equals(mode) && dto.getExpireAt() == null) {
            throw new BizException(ErrorCode.PRICE_BATCH_TARGET_REQUIRED);
        }
        boolean hasIds = dto.getIds() != null && !dto.getIds().isEmpty();
        boolean hasFilter = dto.getSkuId() != null
                || (dto.getRtPhone() != null && !dto.getRtPhone().isBlank());
        if (!hasIds && !hasFilter) {
            throw new BizException(ErrorCode.PRICE_BATCH_TARGET_REQUIRED);
        }
    }

    private boolean isPriceMode(String mode) {
        return PriceChangeLog.ADJUST_MODE_PCT_UP.equals(mode)
                || PriceChangeLog.ADJUST_MODE_PCT_DOWN.equals(mode)
                || PriceChangeLog.ADJUST_MODE_SET_VALUE.equals(mode)
                || PriceChangeLog.ADJUST_MODE_DELTA.equals(mode);
    }

    private String normalizeField(String targetField) {
        return (targetField == null || targetField.isBlank()) ? FIELD_UNIT_PRICE : targetField;
    }

    /** 按调整方式计算新价（结果保留 2 位小数，HALF_UP）。仅用于价格类 mode。 */
    private BigDecimal computeAdjustedPrice(BigDecimal oldPrice, String mode, BigDecimal value) {
        return switch (mode) {
            case PriceChangeLog.ADJUST_MODE_PCT_UP ->
                    oldPrice.multiply(HUNDRED.add(value)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            case PriceChangeLog.ADJUST_MODE_PCT_DOWN ->
                    oldPrice.multiply(HUNDRED.subtract(value)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            case PriceChangeLog.ADJUST_MODE_SET_VALUE -> value.setScale(2, RoundingMode.HALF_UP);
            case PriceChangeLog.ADJUST_MODE_DELTA -> oldPrice.add(value).setScale(2, RoundingMode.HALF_UP);
            default -> throw new BizException(ErrorCode.PRICE_BATCH_MODE_INVALID);
        };
    }

    private String describeMode(String mode, BigDecimal value) {
        return switch (mode) {
            case PriceChangeLog.ADJUST_MODE_PCT_UP -> "涨价" + value + "%";
            case PriceChangeLog.ADJUST_MODE_PCT_DOWN -> "降价" + value + "%";
            case PriceChangeLog.ADJUST_MODE_SET_VALUE -> "设为" + value;
            case PriceChangeLog.ADJUST_MODE_DELTA -> "增减" + value;
            case PriceChangeLog.ADJUST_MODE_DISABLE -> "作废";
            case PriceChangeLog.ADJUST_MODE_SET_EXPIRE -> "设失效";
            default -> mode;
        };
    }

    /** 追加一条 before/after 明细（超过上限则丢弃，避免 JSON 过大）。 */
    private void recordBeforeAfter(List<Map<String, Object>> list, Long id, String before, String after) {
        if (list.size() >= BEFORE_AFTER_MAX_ENTRIES) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("before", before);
        m.put("after", after);
        list.add(m);
    }

    /** 写一行 price_change_logs，返回 batchNo（雪花号字符串）。 */
    private String writeLog(Long wholesalerId, String changeType, String adjustMode,
                            int affectedCount, String targetSummary,
                            List<Map<String, Object>> beforeAfter, Long operatorUserId) {
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        String batchNo = String.valueOf(snowflakeIdUtil.nextId());
        PriceChangeLog logRow = new PriceChangeLog();
        logRow.setId(snowflakeIdUtil.nextId());
        logRow.setTenantId(wholesaler != null ? wholesaler.getTenantId() : null);
        logRow.setWholesalerId(wholesalerId);
        logRow.setBatchNo(batchNo);
        logRow.setChangeType(changeType);
        logRow.setAdjustMode(adjustMode);
        logRow.setAffectedCount(affectedCount);
        logRow.setTargetSummary(truncate(targetSummary, 512));
        logRow.setBeforeAfterJson(toJson(beforeAfter));
        logRow.setOperatorUserId(operatorUserId);
        priceChangeLogMapper.insert(logRow);
        return batchNo;
    }

    private String toJson(List<Map<String, Object>> beforeAfter) {
        try {
            return objectMapper.writeValueAsString(beforeAfter);
        } catch (JsonProcessingException e) {
            log.warn("[P2] before_after_json 序列化失败，降级为空数组", e);
            return "[]";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private PriceChangeLogVo toLogVo(PriceChangeLog logRow) {
        return PriceChangeLogVo.builder()
                .id(logRow.getId())
                .batchNo(logRow.getBatchNo())
                .changeType(logRow.getChangeType())
                .adjustMode(logRow.getAdjustMode())
                .affectedCount(logRow.getAffectedCount())
                .targetSummary(logRow.getTargetSummary())
                .createdAt(logRow.getCreatedAt())
                .operatorUserId(logRow.getOperatorUserId())
                .build();
    }

    private CustomerPriceVo toVo(CustomerPrice cp) {
        return CustomerPriceVo.builder()
                .id(cp.getId())
                .wholesalerId(cp.getWholesalerId())
                .skuId(cp.getSkuId())
                .rtPhone(cp.getRtPhone())
                .unitPrice(cp.getUnitPrice())
                .status(cp.getStatus())
                .source(cp.getSource())
                .expireAt(cp.getExpireAt())
                .createdAt(cp.getCreatedAt())
                .build();
    }
}
