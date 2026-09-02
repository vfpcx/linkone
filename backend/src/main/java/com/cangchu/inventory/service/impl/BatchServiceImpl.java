package com.cangchu.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.tenant.TenantScopeAuthSupport;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.dto.BatchBackfillDto;
import com.cangchu.inventory.dto.BatchLocationUpdateDto;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.InboundBatchContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.entity.BatchLocationLog;
import com.cangchu.inventory.entity.Inventory;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.BatchLocationLogMapper;
import com.cangchu.inventory.mapper.BatchMapper;
import com.cangchu.inventory.mapper.InventoryMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.BatchLocationLogVo;
import com.cangchu.inventory.vo.BatchRecalcResultVo;
import com.cangchu.inventory.vo.BatchToggleVo;
import com.cangchu.inventory.vo.BatchVo;
import com.cangchu.inventory.vo.ExpiryDashboardVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.TenantBatchConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次登记簿服务实现（P3b T4-W1，13 §3；D-11=C）。
 *
 * <p>安全/边界规约：
 * <ul>
 *   <li>S4：toggle 仅 TA（findBoundTenantId 推导租户，不取客户端）；列表 WK/TA 或 WA/WE
 *       以 user_roles 登录态推导（先例：InboundCorrectionServiceImpl.listByTenant / listForWa）。</li>
 *   <li>方案 C 零侵入：不改 InventoryService 任何交易方法；登记簿追加与 INBOUND/CORRECTION_*
 *       流水 batch_id 回填均为单据事务内后置 UPDATE（同 inventory 域直连 StockMovementMapper 合规）。</li>
 *   <li>FIFO 推算（§3.2）：纯读流水全量重算，天然幂等；不加分布式锁——remaining_qty 是
 *       展示用推算值（非记账值），与交易并发的误差 ≤1 日窗口由产品口径覆盖（10 §3.1-C）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchServiceImpl implements BatchService {

    private final BatchMapper batchMapper;
    private final InventoryMapper inventoryMapper;
    private final StockMovementMapper stockMovementMapper;
    private final BatchLocationLogMapper batchLocationLogMapper;
    private final TenantService tenantService;
    private final AuthService authService;
    // TA 一账号多仓收敛（20 §2）：toggle 当前仓解析经 TenantScopeAuthSupport
    private final TenantScopeAuthSupport tenantScopeAuthSupport;
    private final SkuService skuService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;
    private final RedissonClient redissonClient;

    /** 自注入代理：recalcAll 逐租户经代理调 recalcTenant，保证每租户独立事务生效。 */
    @Lazy
    @Autowired
    private BatchService self;

    private static final DateTimeFormatter DEFAULT_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 净出量统计（13 §3.2 第 3 步）：出向 */
    private static final Set<String> POOL_OUT_TYPES = Set.of(
            StockMovement.TYPE_OUTBOUND, StockMovement.TYPE_RETURN,
            StockMovement.TYPE_LOSS, StockMovement.TYPE_DISPUTE_REVERSAL);
    /** 净出量统计：回补/盘盈抵扣向 */
    private static final Set<String> POOL_IN_TYPES = Set.of(
            StockMovement.TYPE_OUTBOUND_REVERSAL, StockMovement.TYPE_DISPUTE_RESTORE,
            StockMovement.TYPE_GAIN);
    /** 带 batch_id 直扣（不进分摊）：出向 */
    private static final Set<String> DIRECT_OUT_TYPES = Set.of(
            StockMovement.TYPE_CORRECTION_OUT, StockMovement.TYPE_EXPIRY_CLEARANCE);

    // ==================== 开关（13 §3.5） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchToggleVo toggle(Long taUserId, BatchToggleDto dto) {
        if (dto == null || dto.getEnable() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少开关目标状态");
        }
        // S4：仅 TA；tenantId 当前仓收敛（20 §2：X-Tenant-Id 优先 + 该仓 TA 校验，回退登录态推导）
        Long tenantId = tenantScopeAuthSupport.scopedTaTenantId(taUserId);
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        boolean enable = dto.getEnable();
        TenantBatchConfigVo cfg = tenantService.getBatchConfig(tenantId);
        boolean current = cfg.getBatchEnabled() != null && cfg.getBatchEnabled() == 1;
        if (current == enable) {
            // 幂等空转：状态相同不计次、不生成默认批次、不动 batch_enabled_at
            return BatchToggleVo.builder()
                    .batchEnabled(current ? 1 : 0)
                    .batchEnabledAt(cfg.getBatchEnabledAt())
                    .defaultBatchCount(0)
                    .closedBatchCount(0)
                    .build();
        }
        if (!Boolean.TRUE.equals(dto.getConfirmed())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少二次确认凭据");
        }
        // T4-1：24h ≤2 次（Redis 计数 batch:toggle:{tenantId} TTL 24h，超限 50361）。
        // 权威判定先于副作用；计次在翻转成功后落（失败可立即重试，PricingService F2 冷却先例）
        RAtomicLong counter = redissonClient.getAtomicLong("batch:toggle:" + tenantId);
        if (counter.get() >= 2) {
            throw new BizException(ErrorCode.BATCH_TOGGLE_RATE_LIMITED);
        }

        LocalDateTime now = LocalDateTime.now();
        int defaultBatchCount = 0;
        int closedCount = 0;
        if (enable) {
            // 关→启：切割时点定格 + 同事务为全部 in_stock>0 的 (w,sku) 生成默认批次吸收存量
            tenantService.setBatchEnabled(tenantId, true, now, taUserId);
            defaultBatchCount = createDefaultBatches(tenantId, now);
        } else {
            // 启→关：登记簿冻结——全部非终态批次标 CLOSED（再启用不复活）
            tenantService.setBatchEnabled(tenantId, false, null, taUserId);
            closedCount = batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                    .eq(Batch::getTenantId, tenantId)
                    .notIn(Batch::getStatus, Batch.STATUS_CLEARED, Batch.STATUS_CLOSED)
                    .set(Batch::getStatus, Batch.STATUS_CLOSED)
                    .set(Batch::getUpdatedAt, now));
        }
        long used = counter.incrementAndGet();
        if (used == 1) {
            counter.expire(Duration.ofHours(24));
        }
        log.info("[P3b][T4] TA {} 批次开关 {} tenant={} defaultBatches={} closed={} (24h 已用 {} 次)",
                taUserId, enable ? "开启" : "关闭", tenantId, defaultBatchCount, closedCount, used);
        return BatchToggleVo.builder()
                .batchEnabled(enable ? 1 : 0)
                .batchEnabledAt(enable ? now : cfg.getBatchEnabledAt())
                .defaultBatchCount(defaultBatchCount)
                .closedBatchCount(closedCount)
                .build();
    }

    /**
     * 默认批次生成（13 §3.5 关→启）：initial_qty=当刻池 qty 快照、expiry NULL 可补录；
     * 同日再启用 uk 冲突时追加序号后缀 DEFAULT-{YYYYMMDD}-{n}（设计未载明的落地补充，回写 13）。
     */
    private int createDefaultBatches(Long tenantId, LocalDateTime now) {
        List<Inventory> inStock = inventoryMapper.selectList(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getTenantId, tenantId)
                .gt(Inventory::getQty, 0));
        String base = "DEFAULT-" + now.toLocalDate().format(DEFAULT_NO_FMT);
        int created = 0;
        for (Inventory inv : inStock) {
            Long dup = batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                    .eq(Batch::getWholesalerId, inv.getWholesalerId())
                    .eq(Batch::getSkuId, inv.getSkuId())
                    .likeRight(Batch::getBatchNo, base));
            String batchNo = (dup == null || dup == 0) ? base : base + "-" + (dup + 1);
            Batch b = new Batch();
            b.setId(snowflakeIdUtil.nextId());
            b.setTenantId(tenantId);
            b.setWholesalerId(inv.getWholesalerId());
            b.setSkuId(inv.getSkuId());
            b.setBatchNo(batchNo);
            b.setInitialQty(inv.getQty());
            b.setRemainingQty(inv.getQty());
            b.setStatus(Batch.STATUS_IN_STOCK);
            b.setSource(Batch.SOURCE_DEFAULT);
            b.setCreatedAt(now);
            b.setUpdatedAt(now);
            batchMapper.insert(b);
            created++;
        }
        return created;
    }

    // ==================== 入库登记簿钩子（13 §3.1） ====================

    @Override
    public void assertBatchNoAvailable(Long wholesalerId, Long skuId, String batchNo) {
        Long cnt = batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, wholesalerId)
                .eq(Batch::getSkuId, skuId)
                .eq(Batch::getBatchNo, batchNo));
        if (cnt != null && cnt > 0) {
            throw new BizException(ErrorCode.BATCH_NO_DUPLICATE);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Batch registerInboundBatch(InboundBatchContext ctx) {
        if (ctx.getTenantId() == null || ctx.getWholesalerId() == null || ctx.getSkuId() == null
                || ctx.getBatchNo() == null || ctx.getBatchNo().isBlank()
                || ctx.getQty() == null || ctx.getQty() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        // 初始状态：到效期−今日 ≤ 阈值 → EXPIRING（PRD §3.2：临期放行、入库后立即进入临期列表）
        TenantBatchConfigVo cfg = tenantService.getBatchConfig(ctx.getTenantId());
        int threshold = cfg.getExpiryThresholdDays() != null ? cfg.getExpiryThresholdDays() : 30;
        LocalDate today = LocalDate.now();
        String status = Batch.STATUS_IN_STOCK;
        if (ctx.getExpiryDate() != null && !ctx.getExpiryDate().isAfter(today.plusDays(threshold))) {
            status = Batch.STATUS_EXPIRING;
        }
        Batch b = new Batch();
        b.setId(snowflakeIdUtil.nextId());
        b.setTenantId(ctx.getTenantId());
        b.setWholesalerId(ctx.getWholesalerId());
        b.setSkuId(ctx.getSkuId());
        b.setBatchNo(ctx.getBatchNo().trim());
        b.setProductionDate(ctx.getProductionDate());
        b.setExpiryDate(ctx.getExpiryDate());
        b.setInitialQty(ctx.getQty());
        b.setRemainingQty(ctx.getQty());
        b.setStatus(status);
        b.setSource(Batch.SOURCE_INBOUND);
        // C2（25-p5-c-c2 §3.1/K-4）：登记带货位时同步落批次货位
        if (ctx.getLocation() != null && !ctx.getLocation().isBlank()) {
            b.setLocation(ctx.getLocation().trim());
        }
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        try {
            batchMapper.insert(b);
        } catch (DuplicateKeyException e) {
            // uk_bat_ws_sku_no 权威兜底（提交时预检后并发登记同批次号）——单据事务整体回滚
            throw new BizException(ErrorCode.BATCH_NO_DUPLICATE);
        }
        // 回填该单 INBOUND 流水 batch_id（后置 UPDATE，addStock 代码零改动；一单一 INBOUND 流水）
        stockMovementMapper.update(null, new LambdaUpdateWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, ctx.getWholesalerId())
                .eq(StockMovement::getSkuId, ctx.getSkuId())
                .eq(StockMovement::getType, StockMovement.TYPE_INBOUND)
                .eq(StockMovement::getRefDocNo, ctx.getRefDocNo())
                .isNull(StockMovement::getBatchId)
                .set(StockMovement::getBatchId, b.getId()));
        log.info("[P3b][T4] 批次登记 doc={} wholesaler={} sku={} batchNo={} qty={} status={}",
                ctx.getRefDocNo(), ctx.getWholesalerId(), ctx.getSkuId(), b.getBatchNo(), ctx.getQty(), status);
        return b;
    }

    @Override
    public void tagCorrectionMovement(Long movementId, Long wholesalerId, Long skuId, String batchNo) {
        if (movementId == null || batchNo == null || batchNo.isBlank()) {
            return;
        }
        Batch b = batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, wholesalerId)
                .eq(Batch::getSkuId, skuId)
                .eq(Batch::getBatchNo, batchNo));
        if (b == null) {
            // 防御：批次行不存在（如登记于启用前）→ 静默跳过，不阻断纠错主链
            return;
        }
        stockMovementMapper.update(null, new LambdaUpdateWrapper<StockMovement>()
                .eq(StockMovement::getId, movementId)
                .set(StockMovement::getBatchId, b.getId()));
    }

    // ==================== FIFO 离线推算（13 §3.2） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchRecalcResultVo recalcTenant(Long tenantId) {
        TenantBatchConfigVo cfg = tenantService.getBatchConfig(tenantId);
        List<Long> newlyExpiring = new ArrayList<>();
        if (cfg.getBatchEnabled() == null || cfg.getBatchEnabled() != 1) {
            // 未启用空转（启→关后批次已全 CLOSED；Job 侧按 batch_enabled=1 过滤，此处双保险）
            return emptyResult(tenantId);
        }
        int threshold = cfg.getExpiryThresholdDays() != null ? cfg.getExpiryThresholdDays() : 30;
        LocalDateTime enabledAt = cfg.getBatchEnabledAt();
        LocalDate today = LocalDate.now();

        List<Batch> active = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, tenantId)
                .notIn(Batch::getStatus, Batch.STATUS_CLEARED, Batch.STATUS_CLOSED));
        // 按 (wholesaler, sku) 分组
        Map<String, List<Batch>> byKey = new LinkedHashMap<>();
        for (Batch b : active) {
            byKey.computeIfAbsent(b.getWholesalerId() + ":" + b.getSkuId(), k -> new ArrayList<>()).add(b);
        }
        int soldOut = 0;
        int expiring = 0;
        for (List<Batch> group : byKey.values()) {
            Long wholesalerId = group.get(0).getWholesalerId();
            Long skuId = group.get(0).getSkuId();
            // 1) FIFO 序：expiry ASC NULLS LAST → 首次入库时间（登记簿 created_at）ASC → id ASC（05 §2.1 三键）
            group.sort(Comparator
                    .comparing(Batch::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Batch::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Batch::getId));

            // 2) 带 batch_id 直扣（不进分摊）：direct = Σ(CORRECTION_OUT|EXPIRY_CLEARANCE) − Σ(CORRECTION_IN)
            Map<Long, Integer> directNet = new HashMap<>();
            List<Long> ids = group.stream().map(Batch::getId).toList();
            List<StockMovement> directMvs = stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                    .eq(StockMovement::getWholesalerId, wholesalerId)
                    .eq(StockMovement::getSkuId, skuId)
                    .in(StockMovement::getBatchId, ids)
                    .in(StockMovement::getType, StockMovement.TYPE_CORRECTION_OUT,
                            StockMovement.TYPE_EXPIRY_CLEARANCE, StockMovement.TYPE_CORRECTION_IN));
            for (StockMovement mv : directMvs) {
                int signed = DIRECT_OUT_TYPES.contains(mv.getType()) ? mv.getQty() : -mv.getQty();
                directNet.merge(mv.getBatchId(), signed, Integer::sum);
            }

            // 3) 池化净出量：仅 batch_id IS NULL 且 created_at > batch_enabled_at 的流水
            //    （启用前历史已被默认批次 initial_qty 快照吸收，不重复吃）；GAIN 无批次入量先行抵扣
            List<StockMovement> poolMvs = stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                    .eq(StockMovement::getWholesalerId, wholesalerId)
                    .eq(StockMovement::getSkuId, skuId)
                    .isNull(StockMovement::getBatchId)
                    .gt(enabledAt != null, StockMovement::getCreatedAt, enabledAt)
                    .in(StockMovement::getType,
                            StockMovement.TYPE_OUTBOUND, StockMovement.TYPE_RETURN,
                            StockMovement.TYPE_LOSS, StockMovement.TYPE_DISPUTE_REVERSAL,
                            StockMovement.TYPE_OUTBOUND_REVERSAL, StockMovement.TYPE_DISPUTE_RESTORE,
                            StockMovement.TYPE_GAIN));
            long net = 0;
            for (StockMovement mv : poolMvs) {
                if (POOL_OUT_TYPES.contains(mv.getType())) {
                    net += mv.getQty();
                } else if (POOL_IN_TYPES.contains(mv.getType())) {
                    net -= mv.getQty();
                }
            }
            int poolNetOut = (int) Math.max(net, 0);

            // 4) 依 FIFO 序逐批分摊 → 5) 状态联动（不触 PENDING_CLEARANCE/终态）
            for (Batch b : group) {
                int base = b.getInitialQty() - directNet.getOrDefault(b.getId(), 0);
                int cap = Math.max(base, 0);
                int alloc = Math.min(poolNetOut, cap);
                int remaining = cap - alloc;
                poolNetOut -= alloc;

                String newStatus = b.getStatus();
                boolean statusMutable = Batch.STATUS_IN_STOCK.equals(b.getStatus())
                        || Batch.STATUS_EXPIRING.equals(b.getStatus())
                        || Batch.STATUS_SOLD_OUT.equals(b.getStatus());
                if (statusMutable) {
                    if (remaining == 0) {
                        newStatus = Batch.STATUS_SOLD_OUT;
                    } else if (b.getExpiryDate() != null
                            && !b.getExpiryDate().isAfter(today.plusDays(threshold))) {
                        newStatus = Batch.STATUS_EXPIRING;
                    } else {
                        newStatus = Batch.STATUS_IN_STOCK;
                    }
                }
                if (remaining != b.getRemainingQty() || !newStatus.equals(b.getStatus())) {
                    batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                            .eq(Batch::getId, b.getId())
                            .set(Batch::getRemainingQty, remaining)
                            .set(Batch::getStatus, newStatus)
                            .set(Batch::getUpdatedAt, LocalDateTime.now()));
                    b.setRemainingQty(remaining);
                    b.setStatus(newStatus);
                }
                if (Batch.STATUS_SOLD_OUT.equals(newStatus)) {
                    soldOut++;
                }
                if (Batch.STATUS_EXPIRING.equals(newStatus)) {
                    expiring++;
                    if (b.getExpiringNotifiedAt() == null) {
                        // D-12 首发通知集合（T4-W2 消费；本波不发不落锚点）
                        newlyExpiring.add(b.getId());
                    }
                }
            }
            // 6) Σremaining 与 inventories.qty 的差=「无批次在池量」——查询侧计算（listForTenant），推算不落库
        }
        log.info("[P3b][T4] FIFO 推算 tenant={} batches={} soldOut={} expiring={} newlyExpiring={}",
                tenantId, active.size(), soldOut, expiring, newlyExpiring.size());
        return BatchRecalcResultVo.builder()
                .tenantId(tenantId)
                .scannedBatches(active.size())
                .soldOutCount(soldOut)
                .expiringCount(expiring)
                .newlyExpiringBatchIds(newlyExpiring)
                .build();
    }

    @Override
    public List<BatchRecalcResultVo> recalcAll() {
        List<BatchRecalcResultVo> results = new ArrayList<>();
        for (Long tenantId : tenantService.listBatchEnabledTenantIds()) {
            try {
                // 经 self 代理保证每租户独立事务；单租户失败记日志不阻断（Job 吞异常先例）
                results.add(self.recalcTenant(tenantId));
            } catch (Exception e) {
                log.error("[P3b][T4] FIFO 推算失败 tenant={}（跳过继续）", tenantId, e);
            }
        }
        return results;
    }

    private BatchRecalcResultVo emptyResult(Long tenantId) {
        return BatchRecalcResultVo.builder()
                .tenantId(tenantId)
                .scannedBatches(0)
                .soldOutCount(0)
                .expiringCount(0)
                .newlyExpiringBatchIds(List.of())
                .build();
    }

    // ==================== T4-W2：临期 Job 体 + 通知（13 §3.3，D-12） ====================

    @Override
    public List<BatchRecalcResultVo> runDailyRecalcAndNotify() {
        List<BatchRecalcResultVo> results = recalcAll();
        for (BatchRecalcResultVo r : results) {
            for (Long batchId : r.getNewlyExpiringBatchIds()) {
                try {
                    notifyExpiringOnce(batchId);
                } catch (Exception e) {
                    // 单批次通知失败不阻断（次日重跑仍是「新进入」集合，锚点未落会重试）
                    log.error("[P3b][T4] 临期首发通知失败 batch={}（跳过继续）", batchId, e);
                }
            }
        }
        return results;
    }

    /**
     * D-12 首发通知（每批次一次）：条件更新 expiring_notified_at IS NULL 的赢者才发——
     * Job 重复跑/并发恰发一次；状态不变不重发（锚点已落者恒败）。收件人：库管全员 + 商户管理员全员
     * （user_roles 推导先例），站内信不发短信，文案零角色码。
     */
    private void notifyExpiringOnce(Long batchId) {
        Batch b = batchMapper.selectById(batchId);
        if (b == null || !Batch.STATUS_EXPIRING.equals(b.getStatus())) {
            // 推算后至通知前状态漂移（如清库/冻结）→ 放弃本次，锚点不落
            return;
        }
        int won = batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, batchId)
                .isNull(Batch::getExpiringNotifiedAt)
                .set(Batch::getExpiringNotifiedAt, LocalDateTime.now())
                .set(Batch::getUpdatedAt, LocalDateTime.now()));
        if (won != 1) {
            return;
        }
        String content = expiryBrief(b) + "，请及时关注（商户可降价促销或发起退货，仓库可安排清库准备）。";
        notificationService.sendToAll(b.getTenantId(),
                authService.listActiveWkUserIdsOfTenant(b.getTenantId()),
                Notification.TYPE_BATCH_EXPIRING, "批次临期提醒", content,
                Notification.REF_BATCH, b.getId());
        notificationService.sendToAll(b.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(b.getWholesalerId()),
                Notification.TYPE_BATCH_EXPIRING, "批次临期提醒", content,
                Notification.REF_BATCH, b.getId());
        log.info("[P3b][T4] 临期首发通知 batch={} batchNo={} tenant={}", b.getId(), b.getBatchNo(), b.getTenantId());
    }

    @Override
    public int markExpiredBatches() {
        int marked = 0;
        for (Long tenantId : tenantService.listBatchEnabledTenantIds()) {
            try {
                // SQL 内比数据库时间（BND-S3-01 先例）：昨日及更早到期才标，当日到期不标
                List<Batch> due = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                        .eq(Batch::getTenantId, tenantId)
                        .in(Batch::getStatus, Batch.STATUS_IN_STOCK, Batch.STATUS_EXPIRING)
                        .gt(Batch::getRemainingQty, 0)
                        .isNotNull(Batch::getExpiryDate)
                        .apply("expiry_date < CURDATE()"));
                for (Batch b : due) {
                    int won = batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                            .eq(Batch::getId, b.getId())
                            .in(Batch::getStatus, Batch.STATUS_IN_STOCK, Batch.STATUS_EXPIRING)
                            .set(Batch::getStatus, Batch.STATUS_PENDING_CLEARANCE)
                            .set(Batch::getUpdatedAt, LocalDateTime.now()));
                    if (won != 1) {
                        continue;
                    }
                    marked++;
                    notificationService.sendToAll(b.getTenantId(),
                            authService.listActiveWkUserIdsOfTenant(b.getTenantId()),
                            Notification.TYPE_BATCH_EXPIRED, "批次已过期，待清理",
                            expiryBrief(b) + "，已标记为待清理，请尽快现场核数并发起清库单。",
                            Notification.REF_BATCH, b.getId());
                }
            } catch (Exception e) {
                log.error("[P3b][T4] 归零标记失败 tenant={}（跳过继续）", tenantId, e);
            }
        }
        if (marked > 0) {
            log.info("[P3b][T4] 归零标记完成：本次标记 {} 个批次为待清理", marked);
        }
        return marked;
    }

    @Override
    public void notifyWholesalerManually(Long batchId, Long userId) {
        Batch b = batchId != null ? batchMapper.selectById(batchId) : null;
        if (b == null) {
            throw new BizException(ErrorCode.BATCH_NOT_FOUND);
        }
        // 仅库管（13 §5.3：一键通知归 WK 临期列表操作）
        if (!authService.hasRole(userId, "WK", b.getTenantId())) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员可发送临期通知");
        }
        if (!Batch.STATUS_EXPIRING.equals(b.getStatus()) && !Batch.STATUS_PENDING_CLEARANCE.equals(b.getStatus())) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID, "仅临期或待清理批次可通知商户");
        }
        // 24h 限 1（50367）：条件更新 SQL 内比数据库时间，并发双点恰一成功
        int won = batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, b.getId())
                .and(w -> w.isNull(Batch::getManualNotifiedAt)
                        .or()
                        .apply("manual_notified_at <= NOW() - INTERVAL '24' HOUR"))
                .set(Batch::getManualNotifiedAt, LocalDateTime.now())
                .set(Batch::getUpdatedAt, LocalDateTime.now()));
        if (won != 1) {
            throw new BizException(ErrorCode.EXPIRY_NOTIFY_RATE_LIMITED);
        }
        notificationService.sendToAll(b.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(b.getWholesalerId()),
                Notification.TYPE_BATCH_EXPIRING, "批次临期提醒",
                expiryBrief(b) + "，请尽快处理（可降价促销或发起退货）。",
                Notification.REF_BATCH, b.getId());
        log.info("[P3b][T4] WK {} 手动通知商户 batch={} batchNo={}", userId, b.getId(), b.getBatchNo());
    }

    @Override
    public BatchListVo listExpiring(Long tenantId, Long userId) {
        requireWkOrTa(tenantId, userId);
        List<Batch> list = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, tenantId)
                .in(Batch::getStatus, Batch.STATUS_EXPIRING, Batch.STATUS_PENDING_CLEARANCE)
                .orderByAsc(Batch::getExpiryDate)
                .orderByAsc(Batch::getCreatedAt));
        return BatchListVo.builder()
                .list(list.stream().map(this::toVo).toList())
                .build();
    }

    @Override
    public long countExpiringWithinDays(Long tenantId, int days) {
        // P5-C（19 §3）：按到期日口径，终态（CLEARED/CLOSED/SOLD_OUT）不计
        Long cnt = batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, tenantId)
                .le(Batch::getExpiryDate, LocalDate.now().plusDays(days))
                .notIn(Batch::getStatus, Batch.STATUS_CLEARED, Batch.STATUS_CLOSED, Batch.STATUS_SOLD_OUT));
        return cnt != null ? cnt : 0;
    }

    @Override
    public ExpiryDashboardVo expiryDashboard(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅租户管理员可查看临期看板");
        }
        List<Batch> attention = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, tenantId)
                .in(Batch::getStatus, Batch.STATUS_EXPIRING, Batch.STATUS_PENDING_CLEARANCE));
        long expiringCount = 0;
        long expiringQty = 0;
        long expiredCount = 0;
        long expiredQty = 0;
        Map<Long, List<Batch>> bySku = new LinkedHashMap<>();
        for (Batch b : attention) {
            int remaining = b.getRemainingQty() != null ? b.getRemainingQty() : 0;
            if (Batch.STATUS_EXPIRING.equals(b.getStatus())) {
                expiringCount++;
                expiringQty += remaining;
            } else {
                expiredCount++;
                expiredQty += remaining;
            }
            bySku.computeIfAbsent(b.getSkuId(), k -> new ArrayList<>()).add(b);
        }
        List<ExpiryDashboardVo.SkuGroup> groups = new ArrayList<>(bySku.size());
        for (Map.Entry<Long, List<Batch>> e : bySku.entrySet()) {
            List<Batch> batches = e.getValue();
            SkuVo sku = skuService.getById(e.getKey());
            groups.add(ExpiryDashboardVo.SkuGroup.builder()
                    .skuId(String.valueOf(e.getKey()))
                    .skuName(sku != null ? sku.getName() : String.valueOf(e.getKey()))
                    .wholesalerId(String.valueOf(batches.get(0).getWholesalerId()))
                    .expiringBatchCount(batches.stream()
                            .filter(b -> Batch.STATUS_EXPIRING.equals(b.getStatus())).count())
                    .expiredBatchCount(batches.stream()
                            .filter(b -> Batch.STATUS_PENDING_CLEARANCE.equals(b.getStatus())).count())
                    .remainingQtyTotal(batches.stream()
                            .mapToLong(b -> b.getRemainingQty() != null ? b.getRemainingQty() : 0).sum())
                    .nearestExpiryDate(batches.stream()
                            .map(Batch::getExpiryDate)
                            .filter(java.util.Objects::nonNull)
                            .min(Comparator.naturalOrder()).orElse(null))
                    .build());
        }
        // 组间按最近到效期升序（最紧迫在前，与列表剩余天数升序同口径）
        groups.sort(Comparator.comparing(ExpiryDashboardVo.SkuGroup::getNearestExpiryDate,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Long clearedCount = batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, tenantId)
                .eq(Batch::getStatus, Batch.STATUS_CLEARED));
        TenantBatchConfigVo cfg = tenantService.getBatchConfig(tenantId);
        return ExpiryDashboardVo.builder()
                .thresholdDays(cfg.getExpiryThresholdDays() != null ? cfg.getExpiryThresholdDays() : 30)
                .expiringBatchCount(expiringCount)
                .expiringQtyTotal(expiringQty)
                .expiredBatchCount(expiredCount)
                .expiredQtyTotal(expiredQty)
                .clearedBatchCount(clearedCount != null ? clearedCount : 0)
                .bySku(groups)
                .build();
    }

    // ==================== T4-W2：清库联动出口（document 域经此接入，G-S1） ====================

    @Override
    public BatchVo getTenantBatch(Long tenantId, Long batchId) {
        Batch b = batchId != null ? batchMapper.selectById(batchId) : null;
        if (b == null || !b.getTenantId().equals(tenantId)) {
            // 不存在/跨租户按不存在（不泄漏存在性，50363）
            throw new BizException(ErrorCode.BATCH_NOT_FOUND);
        }
        return toVo(b);
    }

    @Override
    public void markCleared(Long batchId) {
        LocalDateTime now = LocalDateTime.now();
        batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, batchId)
                .set(Batch::getRemainingQty, 0)
                .set(Batch::getStatus, Batch.STATUS_CLEARED)
                .set(Batch::getClearedAt, now)
                .set(Batch::getUpdatedAt, now));
    }

    /** 通知文案摘要（零角色码；到效期/剩余天数/推算剩余）。 */
    private String expiryBrief(Batch b) {
        SkuVo sku = skuService.getById(b.getSkuId());
        String skuName = sku != null ? sku.getName() : String.valueOf(b.getSkuId());
        StringBuilder sb = new StringBuilder()
                .append("批次 ").append(b.getBatchNo())
                .append("（").append(skuName).append("）");
        if (b.getExpiryDate() != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), b.getExpiryDate());
            sb.append(days >= 0
                    ? "将于 " + b.getExpiryDate() + " 到效（剩余 " + days + " 天"
                    : "已于 " + b.getExpiryDate() + " 过期（超期 " + (-days) + " 天");
        } else {
            sb.append("（到效期未录入");
        }
        int remaining = b.getRemainingQty() != null ? b.getRemainingQty() : 0;
        sb.append("，推算剩余 ").append(remaining).append(" 件）");
        return sb.toString();
    }

    // ==================== 列表 / 补录（13 §5.3） ====================

    @Override
    public BatchListVo listForTenant(Long tenantId, Long userId, Long wholesalerId, Long skuId, String status) {
        requireWkOrTa(tenantId, userId);
        List<Batch> list = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, tenantId)
                .eq(wholesalerId != null, Batch::getWholesalerId, wholesalerId)
                .eq(skuId != null, Batch::getSkuId, skuId)
                .eq(status != null && !status.isBlank(), Batch::getStatus, status)
                .orderByAsc(Batch::getExpiryDate)
                .orderByAsc(Batch::getCreatedAt));
        Integer unpooled = null;
        if (wholesalerId != null && skuId != null) {
            unpooled = computeUnpooledQty(wholesalerId, skuId);
        }
        return BatchListVo.builder()
                .list(list.stream().map(this::toVo).toList())
                .unpooledQty(unpooled)
                .build();
    }

    @Override
    public BatchListVo listForWholesaler(Long userId, Long skuId, String status) {
        // WA 绑定 ∪ WE 绑定（只读列表，listForWa 先例）；
        // 多仓（2026-09-01）：按当前工作空间 X-Tenant-Id 收敛，使「本仓唯一下→未入池量可算」
        Long scopedTenant = TenantContext.getTenantId();
        java.util.LinkedHashSet<Long> wholesalerIds = new java.util.LinkedHashSet<>();
        wholesalerIds.addAll(authService.listActiveWholesalerIds(userId, "WA", scopedTenant));
        wholesalerIds.addAll(authService.listActiveWeWholesalerIds(userId, scopedTenant));
        if (wholesalerIds.isEmpty()) {
            return BatchListVo.builder().list(List.of()).build();
        }
        List<Batch> list = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .in(Batch::getWholesalerId, wholesalerIds)
                .eq(skuId != null, Batch::getSkuId, skuId)
                .eq(status != null && !status.isBlank(), Batch::getStatus, status)
                .orderByAsc(Batch::getExpiryDate)
                .orderByAsc(Batch::getCreatedAt));
        Integer unpooled = null;
        if (skuId != null && wholesalerIds.size() == 1) {
            unpooled = computeUnpooledQty(wholesalerIds.iterator().next(), skuId);
        }
        return BatchListVo.builder()
                .list(list.stream().map(this::toVo).toList())
                .unpooledQty(unpooled)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchVo backfillDefaultBatch(Long batchId, BatchBackfillDto dto, Long userId) {
        if (dto == null || (dto.getProductionDate() == null && dto.getExpiryDate() == null)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少补录字段");
        }
        Batch b = batchId != null ? batchMapper.selectById(batchId) : null;
        if (b == null) {
            // 不存在/跨商户按不存在（不泄漏存在性，50363）
            throw new BizException(ErrorCode.BATCH_NOT_FOUND);
        }
        requireWkOrTa(b.getTenantId(), userId);
        if (!Batch.SOURCE_DEFAULT.equals(b.getSource())
                || Batch.STATUS_CLEARED.equals(b.getStatus()) || Batch.STATUS_CLOSED.equals(b.getStatus())) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID, "仅未终态的默认批次可补录");
        }
        LocalDate production = dto.getProductionDate() != null ? dto.getProductionDate() : b.getProductionDate();
        LocalDate expiry = dto.getExpiryDate() != null ? dto.getExpiryDate() : b.getExpiryDate();
        if (production != null && production.isAfter(LocalDate.now())) {
            throw new BizException(ErrorCode.VALIDATION_BUSINESS_005);
        }
        if (production != null && expiry != null && !expiry.isAfter(production)) {
            throw new BizException(ErrorCode.VALIDATION_BUSINESS_006);
        }
        batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, b.getId())
                .set(dto.getProductionDate() != null, Batch::getProductionDate, dto.getProductionDate())
                .set(dto.getExpiryDate() != null, Batch::getExpiryDate, dto.getExpiryDate())
                .set(Batch::getUpdatedAt, LocalDateTime.now()));
        return toVo(batchMapper.selectById(b.getId()));
    }

    // ==================== 私有 ====================

    /** 无批次在池量 = inventories.qty − Σ非终态批次推算剩余（13 §3.2-6，展示口径不报警）。 */
    private Integer computeUnpooledQty(Long wholesalerId, Long skuId) {
        Inventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWholesalerId, wholesalerId)
                .eq(Inventory::getSkuId, skuId));
        int onhand = inv != null && inv.getQty() != null ? inv.getQty() : 0;
        List<Batch> nonTerminal = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, wholesalerId)
                .eq(Batch::getSkuId, skuId)
                .notIn(Batch::getStatus, Batch.STATUS_CLEARED, Batch.STATUS_CLOSED));
        int sumRemaining = nonTerminal.stream()
                .mapToInt(b -> b.getRemainingQty() != null ? b.getRemainingQty() : 0).sum();
        return onhand - sumRemaining;
    }

    /** S4：WK 或 TA 可查看/补录（InboundCorrectionServiceImpl.listByTenant 先例）。 */
    @Override
    public TenantBatchConfigVo getConfigForMember(Long tenantId, Long userId) {
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到租户");
        }
        // P3b 收口 L-1：存在性鉴权——该租户下任一 ACTIVE 角色即可读（TA/WK/ST 直绑租户，
        // WA/WE 经商户绑定亦携 tenant_id；user_roles 登录态推导为唯一可信来源，不取客户端声明）
        boolean member = authService.listActiveRoles(userId).stream()
                .anyMatch(r -> tenantId.equals(r.getTenantId()));
        if (!member) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅该仓相关人员可查看批次配置");
        }
        return tenantService.getBatchConfig(tenantId);
    }

    private void requireWkOrTa(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId) && !authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员或租户管理员可操作批次");
        }
    }

    private BatchVo toVo(Batch b) {
        Long remainingDays = b.getExpiryDate() != null
                ? ChronoUnit.DAYS.between(LocalDate.now(), b.getExpiryDate()) : null;
        return BatchVo.builder()
                .id(b.getId())
                .wholesalerId(b.getWholesalerId())
                .skuId(b.getSkuId())
                .batchNo(b.getBatchNo())
                .productionDate(b.getProductionDate())
                .expiryDate(b.getExpiryDate())
                .remainingDays(remainingDays)
                .initialQty(b.getInitialQty())
                .remainingQty(b.getRemainingQty())
                .status(b.getStatus())
                .source(b.getSource())
                .manualNotifiedAt(b.getManualNotifiedAt())
                .clearedAt(b.getClearedAt())
                .location(b.getLocation())
                .createdAt(b.getCreatedAt())
                .build();
    }

    // ==================== P5-D C2：批次移库 + 变更日志（25-p5-c-c2 §4.4，US-WK-05 验收） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchVo updateBatchLocation(Long tenantId, Long batchId, BatchLocationUpdateDto dto, Long userId) {
        requireWkOrTa(tenantId, userId);
        Batch b = batchId != null ? batchMapper.selectById(batchId) : null;
        if (b == null || !b.getTenantId().equals(tenantId)) {
            // 不存在/跨租户按不存在（不泄漏存在性，50363）
            throw new BizException(ErrorCode.BATCH_NOT_FOUND);
        }
        String newLocation = dto != null && dto.getLocation() != null ? dto.getLocation().trim() : null;
        if (newLocation != null && newLocation.length() > 64) {
            // DTO @Size 400 之外的防御性兜底（25-p5-c-c2 §4.6）
            throw new BizException(ErrorCode.BATCH_LOCATION_TOO_LONG);
        }
        String oldLocation = b.getLocation();
        // 新旧相同（含同为 null）→ 幂等空转，不落日志（K-5）
        if (java.util.Objects.equals(newLocation, oldLocation)) {
            log.info("[C2][Location] 移库幂等空转 batch={} location={}（无差异）", batchId, newLocation);
            return toVo(b);
        }
        LocalDateTime now = LocalDateTime.now();
        batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, batchId)
                .set(Batch::getLocation, newLocation)
                .set(Batch::getUpdatedAt, now));
        BatchLocationLog logRow = new BatchLocationLog();
        logRow.setId(snowflakeIdUtil.nextId());
        logRow.setTenantId(b.getTenantId());
        logRow.setWholesalerId(b.getWholesalerId());
        logRow.setSkuId(b.getSkuId());
        logRow.setBatchId(batchId);
        logRow.setFromLocation(oldLocation);
        logRow.setToLocation(newLocation);
        logRow.setOperatorUserId(userId);
        logRow.setCreatedAt(now);
        batchLocationLogMapper.insert(logRow);
        log.info("[C2][Location] 批次移库 batch={} from={} to={} by={}", batchId, oldLocation, newLocation, userId);
        return toVo(batchMapper.selectById(batchId));
    }

    @Override
    public Page<BatchLocationLogVo> listLocationLogs(Long tenantId, Long batchId, long page, long size, Long userId) {
        requireWkOrTa(tenantId, userId);
        Batch b = batchId != null ? batchMapper.selectById(batchId) : null;
        if (b == null || !b.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.BATCH_NOT_FOUND);
        }
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, size), 50);
        Page<BatchLocationLog> logPage = batchLocationLogMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<BatchLocationLog>()
                        .eq(BatchLocationLog::getBatchId, batchId)
                        .orderByDesc(BatchLocationLog::getCreatedAt));
        Page<BatchLocationLogVo> voPage = new Page<>(logPage.getCurrent(), logPage.getSize(), logPage.getTotal());
        voPage.setRecords(logPage.getRecords().stream().map(l -> BatchLocationLogVo.builder()
                .id(l.getId())
                .batchId(l.getBatchId())
                .fromLocation(l.getFromLocation())
                .toLocation(l.getToLocation())
                .operatorUserId(l.getOperatorUserId())
                .createdAt(l.getCreatedAt())
                .build()).toList());
        return voPage;
    }
}
