package com.cangchu.pricing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.pii.PiiShadowReader;
import com.cangchu.common.util.SmsUtil;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    /** PII 阶段 0（V30）：rt_phone 盲索引双写的唯一产生点；读路径一律不用。 */
    private final PiiCrypto piiCrypto;
    /** PII 阶段 1 Step1（PII-W5）：定价链影子双查探针，返回 void，绝不参与本类任何判定。 */
    private final PiiShadowReader piiShadowReader;

    /** 自注入代理：用于在锁内调用带 @Transactional 的批量事务体（避免 this 自调用使事务失效）。 */
    @Lazy
    @Autowired
    private PricingService self;

    /** 缓存哨兵：该 (wholesaler, phone, sku) 无有效专属价，走公开价。 */
    private static final String SENTINEL_NONE = "NONE";
    /** 专属价命中/未命中结论缓存 TTL（秒）。 */
    private static final long MATCH_CACHE_TTL_SEC = 60L;

    /** 批量调价分布式锁 tryLock 等待（秒）；租约交由 Redisson 看门狗自动续租（F4）。 */
    private static final long LOCK_WAIT_SECONDS = 30L;
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
        requirePriceEditor(wholesaler, operatorUserId);
        validatePrice(dto.getUnitPrice());

        // F1：upsert 必须匹配物理唯一键 (wholesaler_id, rt_phone, sku_id)，不按 status 过滤。
        // revoke/批量 DISABLE 只置 status=DISABLED（不软删，行仍在），若按 status=ACTIVE 查会 miss
        // → insert → DuplicateKeyException（未处理 500，且在 confirmByWa 事务内会连累整单回滚）。
        // 命中任一状态的物理行则改价并重置 status=ACTIVE（重新授予被作废/过期的专属价），仅无行时才 insert。
        CustomerPrice existing = customerPriceMapper.selectOne(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, dto.getWholesalerId())
                .eq(CustomerPrice::getRtPhone, dto.getRtPhone())
                .eq(CustomerPrice::getSkuId, dto.getSkuId())
                .last("LIMIT 1"));
        // PII 阶段 1 Step1（W5）：影子重查一遍 rt_phone_hmac，只计数不改判定（15 §1.2-C1）
        piiShadowReader.checkCustomerPrice("C1-price-set", dto.getWholesalerId(), dto.getRtPhone(),
                dto.getSkuId(), null, existing);

        CustomerPrice result;
        if (existing != null) {
            LocalDateTime now = LocalDateTime.now();
            customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                    .eq(CustomerPrice::getId, existing.getId())
                    .set(CustomerPrice::getUnitPrice, dto.getUnitPrice())
                    .set(CustomerPrice::getExpireAt, dto.getExpireAt())
                    .set(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                    .set(CustomerPrice::getSource, CustomerPrice.SOURCE_MANUAL)
                    // PII 阶段 0（V30）：本分支 rt_phone 不变，但既有行可能是 legacy 期写入 /
                    // 回填尚未覆盖，hmac 仍为 NULL——顺手补齐（机会性回填，同 blacklist REMOVED 复活分支口径）
                    .set(piiCrypto.isDualWrite(), CustomerPrice::getRtPhoneHmac,
                            piiCrypto.phoneHmac(dto.getRtPhone()))
                    .set(CustomerPrice::getUpdatedAt, now));
            existing.setUnitPrice(dto.getUnitPrice());
            existing.setExpireAt(dto.getExpireAt());
            existing.setStatus(CustomerPrice.STATUS_ACTIVE);
            existing.setSource(CustomerPrice.SOURCE_MANUAL);
            existing.setUpdatedAt(now);
            result = existing;
        } else {
            CustomerPrice cp = new CustomerPrice();
            cp.setId(snowflakeIdUtil.nextId());
            cp.setTenantId(wholesaler.getTenantId());
            cp.setWholesalerId(dto.getWholesalerId());
            cp.setSkuId(dto.getSkuId());
            cp.setRtPhone(dto.getRtPhone());
            // PII 阶段 0（V30）：write-mode=dual 才写 hmac 列；读路径仍走 rt_phone 明文
            if (piiCrypto.isDualWrite()) {
                cp.setRtPhoneHmac(piiCrypto.phoneHmac(dto.getRtPhone()));
            }
            cp.setUnitPrice(dto.getUnitPrice());
            cp.setStatus(CustomerPrice.STATUS_ACTIVE);
            cp.setSource(CustomerPrice.SOURCE_MANUAL);
            cp.setExpireAt(dto.getExpireAt());
            cp.setCreatedBy(operatorUserId);
            customerPriceMapper.insert(cp);
            result = cp;
        }

        invalidateAfterCommit(dto.getWholesalerId(), dto.getRtPhone(), dto.getSkuId());
        // X硬化 H4：日志严禁明文手机号（F7 规约，统一走 SmsUtil.maskPhone）
        log.info("[P2] operator {} 设置专属价 wholesaler={} phone={} sku={} price={}",
                operatorUserId, dto.getWholesalerId(), SmsUtil.maskPhone(dto.getRtPhone()), dto.getSkuId(), dto.getUnitPrice());
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

        // F1：upsert 匹配物理唯一键 (wholesaler_id, rt_phone, sku_id)，不按 status 过滤。
        // 该方法在 confirmByWa 的 @Transactional 内调用：若因既有 DISABLED/EXPIRED 行 miss→insert
        // 触发 DuplicateKeyException，会连累整个确认事务（含扣库存）回滚。命中任一状态行则改价并
        // 重置 status=ACTIVE、source=from_inquiry（重新授予沉淀价），仅无行时才 insert。
        CustomerPrice existing = customerPriceMapper.selectOne(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getRtPhone, rtPhone)
                .eq(CustomerPrice::getSkuId, skuId)
                .last("LIMIT 1"));
        // PII 阶段 1 Step1（W5）：同 setCustomerPrice 口径，影子重查不参与 upsert 分支判定
        piiShadowReader.checkCustomerPrice("C1-price-settle", wholesalerId, rtPhone, skuId, null, existing);

        if (existing != null) {
            customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                    .eq(CustomerPrice::getId, existing.getId())
                    .set(CustomerPrice::getUnitPrice, dealPrice)
                    .set(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                    .set(CustomerPrice::getSource, CustomerPrice.SOURCE_FROM_INQUIRY)
                    .set(CustomerPrice::getSourceDocNo, sourceDocNo)
                    .set(CustomerPrice::getExpireAt, (LocalDateTime) null)
                    // PII 阶段 0（V30）：rt_phone 不变，hmac 顺手补齐（同 setCustomerPrice 口径）
                    .set(piiCrypto.isDualWrite(), CustomerPrice::getRtPhoneHmac,
                            piiCrypto.phoneHmac(rtPhone))
                    .set(CustomerPrice::getUpdatedAt, LocalDateTime.now()));
        } else {
            CustomerPrice cp = new CustomerPrice();
            cp.setId(snowflakeIdUtil.nextId());
            cp.setTenantId(wholesaler.getTenantId());
            cp.setWholesalerId(wholesalerId);
            cp.setSkuId(skuId);
            cp.setRtPhone(rtPhone);
            // PII 阶段 0（V30）：write-mode=dual 才写 hmac 列；读路径仍走 rt_phone 明文
            if (piiCrypto.isDualWrite()) {
                cp.setRtPhoneHmac(piiCrypto.phoneHmac(rtPhone));
            }
            cp.setUnitPrice(dealPrice);
            cp.setStatus(CustomerPrice.STATUS_ACTIVE);
            cp.setSource(CustomerPrice.SOURCE_FROM_INQUIRY);
            cp.setSourceDocNo(sourceDocNo);
            cp.setCreatedBy(operatorUserId);
            customerPriceMapper.insert(cp);
        }

        invalidateAfterCommit(wholesalerId, rtPhone, skuId);
        // X硬化 H4：日志严禁明文手机号（F7 规约，统一走 SmsUtil.maskPhone）
        log.info("[P2] operator {} 议价沉淀 wholesaler={} phone={} sku={} price={} src={}",
                operatorUserId, wholesalerId, SmsUtil.maskPhone(rtPhone), skuId, dealPrice, sourceDocNo);
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

        invalidateAfterCommit(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
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
        invalidateAfterCommit(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
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
            invalidateAfterCommit(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
        }
        log.info("[P2] SKU {} 删除级联：作废专属价 {} 行", skuId, rows.size());
        return rows.size();
    }

    @Override
    @Transactional
    public int disableByWholesaler(Long wholesalerId) {
        // R13 副作用链：同 disableBySku 模式——先查 ACTIVE 行（拿 rtPhone/skuId 逐行失效缓存），
        // 再批量置 DISABLED；缓存删除注册在事务提交后（F3），避免读方回填脏价。
        List<CustomerPrice> rows = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE));
        if (rows.isEmpty()) {
            return 0;
        }
        customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getStatus, CustomerPrice.STATUS_ACTIVE)
                .set(CustomerPrice::getStatus, CustomerPrice.STATUS_DISABLED)
                .set(CustomerPrice::getUpdatedAt, LocalDateTime.now()));
        for (CustomerPrice cp : rows) {
            invalidateAfterCommit(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
        }
        log.info("[P2][R13] 商户 {} 退驻级联：作废专属价 {} 行（含缓存失效）", wholesalerId, rows.size());
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
        requirePriceEditor(wholesaler, operatorUserId);
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
        requirePriceEditor(wholesaler, operatorUserId);
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
        boolean byExplicitIds = dto.getIds() != null && !dto.getIds().isEmpty();
        LambdaQueryWrapper<CustomerPrice> qw = new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, dto.getWholesalerId());
        if (byExplicitIds) {
            qw.in(CustomerPrice::getId, dto.getIds());
        } else {
            qw.eq(dto.getSkuId() != null, CustomerPrice::getSkuId, dto.getSkuId())
              .eq(dto.getRtPhone() != null && !dto.getRtPhone().isBlank(),
                      CustomerPrice::getRtPhone, dto.getRtPhone());
        }
        List<CustomerPrice> rows = customerPriceMapper.selectList(qw);
        // PII 阶段 1 Step1（W5）：仅当本次真按 rt_phone 圈选时才影子重查（15 §1.2-C3）；
        // ids 分支没读明文手机号列，压根不进这个切点的分母。
        piiShadowReader.checkCustomerPriceRows("C3-price-batch", dto.getWholesalerId(),
                byExplicitIds ? null : dto.getSkuId(), byExplicitIds ? null : dto.getRtPhone(), rows);

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
            // 失效价格匹配缓存（F3：提交后再删，避免读方回填脏价）
            invalidateAfterCommit(cp.getWholesalerId(), cp.getRtPhone(), cp.getSkuId());
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
        // PII 阶段 1 Step1（W5）：本切点在缓存 miss 分支内，分母 = 真实 DB 读次数（15 §1.2-C2）。
        // 影子查询须同带 status=ACTIVE，否则比的不是同一个问题。
        piiShadowReader.checkCustomerPrice("C2-price-resolve", wholesalerId, rtPhone, skuId,
                CustomerPrice.STATUS_ACTIVE, cp);
        BigDecimal result = (cp != null && cp.isActive()) ? cp.getUnitPrice() : null;
        bucket.set(result != null ? result.toPlainString() : SENTINEL_NONE,
                MATCH_CACHE_TTL_SEC, TimeUnit.SECONDS);
        return result;
    }

    private String matchKey(Long wholesalerId, String rtPhone, Long skuId) {
        return "price:match:" + wholesalerId + ":" + rtPhone + ":" + skuId;
    }

    /** 写后失效专属价匹配缓存（立即删除，用于无事务上下文）。 */
    private void invalidate(Long wholesalerId, String rtPhone, Long skuId) {
        redissonClient.getBucket(matchKey(wholesalerId, rtPhone, skuId)).delete();
    }

    /**
     * F3：写后失效缓存必须在事务提交后执行。
     *
     * <p>若在提交前删缓存，UPDATE-改价路径下并发的 resolvePrice（读方不加锁）可能
     * 删→miss→读到未提交的旧价→回填，导致最长 60s TTL 内返回脏价。
     * 故：有活跃事务时注册 afterCommit 回调删缓存；无事务时立即删除。
     * key 在注册前捕获，避免闭包引用可变状态。
     */
    private void invalidateAfterCommit(Long wholesalerId, String rtPhone, Long skuId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            final String key = matchKey(wholesalerId, rtPhone, skuId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redissonClient.getBucket(key).delete();
                }
            });
        } else {
            invalidate(wholesalerId, rtPhone, skuId);
        }
    }

    /**
     * S4 归属鉴权（读路径）：operator 须为该商户的 WA / 该商户的 WE（不限授权位，价格页只读可见）/
     * 该商户所属租户的 TA；皆非则越权拒绝（42101）。
     */
    private void requireWaOrTa(WholesalerVo wholesaler, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesaler.getId())
                || authService.hasWholesalerRole(userId, "WE", wholesaler.getId())) {
            return;
        }
        if (!authService.hasRole(userId, "TA", wholesaler.getTenantId())) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /**
     * S4 归属鉴权（写路径，P2 Wave3 WE 授权切点）：WA 本人/TA 不受限；
     * WE 须持 PRICE_EDIT 授权位（未授 42004，WEM-S4-01）；其余越权 42101。
     * 覆盖 WE 可走到的全部调价写路径：专属价 set/update/revoke + 公开价/专属价批量。
     */
    private void requirePriceEditor(WholesalerVo wholesaler, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesaler.getId())) {
            return;
        }
        if (authService.hasWholesalerRole(userId, "WE", wholesaler.getId())) {
            if (!authService.hasWholesalerPermission(userId, wholesaler.getId(),
                    com.cangchu.common.util.WePermissions.PRICE_EDIT)) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004, "未获得改价授权，请联系商户管理员");
            }
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
        requirePriceEditor(wholesaler, operatorUserId);
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
     * 批量调价并发/防重护栏：取 Redisson 锁 → 锁内重查/写 5 分钟冷却 → 跑事务体。
     *
     * <p>F2：冷却门的权威判定必须在临界区（锁内）。原实现「锁外 get + 解锁后 set」有并发漏洞——
     * 两个并发调用都在首个事务写冷却前读到空冷却，遂都通过门、再由锁串行执行 → 双双成功
     * （如 PCT_UP 10% 被叠加两次而复利上涨）。修正：先取锁，锁内 re-check 冷却（有则拒
     * PRICE_BATCH_TOO_FREQUENT），事务成功后在锁内 set 冷却再释放锁；后到者拿到锁必见冷却 → 拒绝。
     * 保留锁外快速失败路径，但权威门在锁内。冷却只在成功后设置，失败可立即重试。
     *
     * <p>F4：{@code tryLock} 不传显式 leaseTime，启用 Redisson 看门狗自动续租——200 SKU 批量
     * 可能超过固定租约（旧值 15s）导致租约中途到期、等待者抢锁并发执行（丢失更新）。
     * 看门狗随持有线程生命周期续租，在 finally 释放。
     */
    private BatchPriceResultVo runGuarded(Long wholesalerId, String changeType,
                                          Supplier<BatchPriceResultVo> txBody) {
        String cdKey = "price:batch:cd:" + wholesalerId + ":" + changeType;
        RBucket<String> cooldown = redissonClient.getBucket(cdKey);
        // 快速失败路径（非权威）：无需抢锁即可拒绝明显过频的调用
        if (cooldown.get() != null) {
            throw new BizException(ErrorCode.PRICE_BATCH_TOO_FREQUENT);
        }

        RLock lock = redissonClient.getLock("lock:price:" + wholesalerId);
        boolean acquired;
        try {
            // F4：不传 leaseTime → 看门狗自动续租，避免长批量中途租约到期
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.PRICE_BATCH_LOCK_FAILED);
        }
        if (!acquired) {
            throw new BizException(ErrorCode.PRICE_BATCH_LOCK_FAILED);
        }
        try {
            // F2：权威冷却门——锁内 re-check，后到的并发调用在此被拒绝
            if (cooldown.get() != null) {
                throw new BizException(ErrorCode.PRICE_BATCH_TOO_FREQUENT);
            }
            // 事务体经 self 代理调用，保证 @Transactional 生效
            BatchPriceResultVo result = txBody.get();
            // 仅在成功（未抛异常）后、释放锁前在锁内设置冷却，确保后到者必见
            cooldown.set("1", BATCH_COOLDOWN_SEC, TimeUnit.SECONDS);
            return result;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
