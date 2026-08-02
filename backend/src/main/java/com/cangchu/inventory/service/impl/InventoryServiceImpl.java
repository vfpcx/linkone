package com.cangchu.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.dto.DisputeReversalResult;
import com.cangchu.inventory.dto.DisputeRestoreContext;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.InboundDisputeContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.dto.OutboundReversalContext;
import com.cangchu.inventory.entity.Inventory;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.InventoryMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 库存服务实现（phase-1 B1：批次关闭，单 sku 维度）。
 *
 * <p>安全/并发规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S2（G-3.1）：qty 必须 >0，否则 STOCK_QTY_INVALID（不产生流水/库存变动）。</li>
 *   <li>S5（G-4.x）：出库前校验库存足够，不足 STOCK_NOT_ENOUGH 拒绝、不写流水。</li>
 *   <li>S7（G-6.1）：扣减全程在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内串行化，
 *       「校验→扣减→写流水」为单事务，杜绝超卖（TOCTOU 竞态）。</li>
 *   <li>租户隔离（G-2.2）：inventories/stock_movements 已纳入 TenantLine 白名单兜底。</li>
 * </ul>
 *
 * <p>事务/锁顺序（B1 修复后实况）：<b>先获取分布式锁、再调事务体</b>（经 self 代理保证
 * @Transactional 生效）。注意：当调用方自带 @Transactional（P3 业务链皆是），内层事务体
 * REQUIRED 并入外层事务，<b>锁会先于外层事务提交而释放</b>——Redisson 锁只保证粗粒度串行化
 * 与首次入库 insert 防撞，「读最新值 + 覆盖提交窗口」由 {@code lockRowForUpdate} 的
 * SELECT ... FOR UPDATE 行锁保证（见该方法注释，勿再以"锁覆盖提交窗口"为前提写代码）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final StockMovementMapper stockMovementMapper;
    private final RedissonClient redissonClient;
    private final SnowflakeIdUtil snowflakeIdUtil;

    /** 自注入代理：用于在锁内调用带 @Transactional 的事务体（避免 this 自调用使事务失效）。 */
    @Lazy
    @Autowired
    private InventoryService self;

    private static final long LOCK_WAIT_SECONDS = 30L;
    private static final long LOCK_LEASE_SECONDS = 15L;

    // ==================== 入库 ====================

    @Override
    public InventoryVo addStock(InboundContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());
        if (ctx.getPalletQty() != null && ctx.getPalletQty() < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }

        // §10 P1（F1 修复）：入库与出库对称——先获 Redisson 锁（同一 (wholesaler,sku) 锁 key）再开事务，
        // 经 self 代理保证 @Transactional 生效。防并发首入两路都走 insert 撞唯一索引（脏 DuplicateKey），
        // 以及已有行并发累加的 lost-update（读同 qty、后写覆盖前写、总量少加）。
        String lockKey = "lock:inv:" + ctx.getWholesalerId() + ":" + ctx.getSkuId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        if (!acquired) {
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        try {
            return self.doAddInTx(ctx);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 入库事务体（仅供 {@link #addStock} 在持锁状态下经代理调用）。upsert 库存行 + 写 INBOUND 流水。
     */
    @Override
    @Transactional
    public InventoryVo doAddInTx(InboundContext ctx) {
        int palletDelta = ctx.getPalletQty() != null ? ctx.getPalletQty() : 0;
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            // 首次入库：新建库存行（唯一索引 uk_inv_wholesaler_sku 双保险；并发已由外层锁互斥）
            inv = new Inventory();
            inv.setId(snowflakeIdUtil.nextId());
            inv.setTenantId(ctx.getTenantId());
            inv.setWholesalerId(ctx.getWholesalerId());
            inv.setSkuId(ctx.getSkuId());
            inv.setQty(ctx.getQty());
            inv.setPalletQty(palletDelta);
            inventoryMapper.insert(inv);
        } else {
            inv.setQty(inv.getQty() + ctx.getQty());
            inv.setPalletQty(inv.getPalletQty() + palletDelta);
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryMapper.updateById(inv);
        }

        // V20（D-8=A）：入库侧现状已 +托盘，补记 pallet_delta 列（13 §2.4-5）
        StockMovement inMv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_INBOUND, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());
        inMv.setPalletDelta(palletDelta);
        stockMovementMapper.insert(inMv);

        log.info("[B1] addStock wholesaler={} sku={} +{} -> qty={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), inv.getQty(), ctx.getRefDocNo());
        return toVo(inv);
    }

    // ==================== 出库（Redisson 锁 + 单事务） ====================

    @Override
    public InventoryVo deductStock(OutboundContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());

        String lockKey = "lock:inv:" + ctx.getWholesalerId() + ":" + ctx.getSkuId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        if (!acquired) {
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        try {
            // 事务体经 self 代理调用，保证 @Transactional 生效；提交后才在 finally 释放锁
            return self.doDeductInTx(ctx);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 出库事务体（仅供 {@link #deductStock} 在持锁状态下经代理调用）。
     * 校验库存足够（不足 STOCK_NOT_ENOUGH，不写流水）→ 扣减 → 写 OUTBOUND 流水。
     */
    @Override
    @Transactional
    public InventoryVo doDeductInTx(OutboundContext ctx) {
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        if (inv.getQty() < ctx.getQty()) {
            // S5：库存不足，拒绝且不产生流水
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        inv.setQty(inv.getQty() - ctx.getQty());
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(inv);

        writeMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_OUTBOUND, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());

        log.info("[B1] deductStock wholesaler={} sku={} -{} -> qty={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), inv.getQty(), ctx.getRefDocNo());
        return toVo(inv);
    }

    // ==================== P3 反向流水（12 §1.5 / §2.4 / §2.6，同锁同事务先例） ====================

    @Override
    public InventoryVo reverseOutbound(OutboundReversalContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doReverseOutboundInTx(ctx));
    }

    /** 出库回补事务体（仅供 {@link #reverseOutbound} 持锁经代理调用）。qty 回加 + 写 OUTBOUND_REVERSAL。 */
    @Override
    @Transactional
    public InventoryVo doReverseOutboundInTx(OutboundReversalContext ctx) {
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            // 出库过必有库存行（行不删除）；防御性拒绝，避免凭空造行
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        int palletDelta = ctx.getPalletQty() != null ? ctx.getPalletQty() : 0;
        inv.setQty(inv.getQty() + ctx.getQty());
        inv.setPalletQty(inv.getPalletQty() + palletDelta);
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(inv);

        // BE-W2（12 §3.1/§3.2）：reversalOfId 未显式传入时，锁内按 refDocNo 解析原 OUTBOUND 流水
        // （单出库单↔单 OUTBOUND 流水 1:1，confirmByWa/submit/proxy 均以 docNo 落 ref_doc_no）。
        // 调用方（document 域）无需直连 StockMovementMapper（G-S1 域边界）。
        Long reversalOfId = ctx.getReversalOfId();
        LocalDateTime bizTime = ctx.getBizTime();
        if (reversalOfId == null) {
            StockMovement original = stockMovementMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockMovement>()
                            .eq(StockMovement::getWholesalerId, ctx.getWholesalerId())
                            .eq(StockMovement::getSkuId, ctx.getSkuId())
                            .eq(StockMovement::getType, StockMovement.TYPE_OUTBOUND)
                            .eq(StockMovement::getRefDocNo, ctx.getRefDocNo())
                            .orderByAsc(StockMovement::getId)
                            .last("LIMIT 1"));
            if (original == null) {
                // 回补必须能配对原流水（不变量：OUTBOUND_REVERSAL.reversal_of_id 非空），防御性拒绝
                throw new BizException(ErrorCode.INVENTORY_NOT_FOUND, "原出库流水不存在，无法回补");
            }
            reversalOfId = original.getId();
            if (bizTime == null) {
                bizTime = original.getBizTime();
            }
        }

        StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_OUTBOUND_REVERSAL, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());
        // 计费视同从未出库：biz_time 沿用原 OUTBOUND 流水锚点；reversal_of_id 配对（P4 抵消）
        mv.setBizTime(bizTime != null ? bizTime : LocalDateTime.now());
        mv.setReversalOfId(reversalOfId);
        mv.setRemark(ctx.getRemark());
        stockMovementMapper.insert(mv);

        log.info("[P3] reverseOutbound wholesaler={} sku={} +{} -> qty={} (doc={}, reversalOf={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), inv.getQty(), ctx.getRefDocNo(), reversalOfId);
        return toVo(inv);
    }

    @Override
    public DisputeReversalResult reverseInboundForDispute(InboundDisputeContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getRegisteredQty());
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doReverseInboundForDisputeInTx(ctx));
    }

    /**
     * 异议冲销事务体（仅供 {@link #reverseInboundForDispute} 持锁经代理调用）。
     * 12 §2.4 封顶口径（无批次池化下的保守归属）：
     * <pre>
     * reversedQty    = min(Q, max(onhand, 0))   // 在库件优先视为被异议单的货，库存永不打负
     * shortfallQty   = Q − reversedQty          // 已售差额，只落仲裁单
     * palletReversed = min(ceil(pallet_qty × reversedQty / Q), 在库托盘)   // 按比例、双重封顶
     * </pre>
     * reversedQty=0（售罄）不写流水；冲销托盘数落流水 remark（仲裁恢复按此还原，流水可回溯）。
     */
    @Override
    @Transactional
    public DisputeReversalResult doReverseInboundForDisputeInTx(InboundDisputeContext ctx) {
        int q = ctx.getRegisteredQty();
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        int onhand = inv != null ? Math.max(inv.getQty(), 0) : 0;
        int reversedQty = Math.min(q, onhand);
        int shortfallQty = q - reversedQty;

        int palletReversed = 0;
        Long movementId = null;
        int remaining = inv != null ? inv.getQty() : 0;
        if (reversedQty > 0) {
            int inbPallet = ctx.getPalletQty() != null ? Math.max(ctx.getPalletQty(), 0) : 0;
            if (inbPallet > 0) {
                int proportional = (int) Math.ceil(inbPallet * (double) reversedQty / q);
                palletReversed = Math.min(proportional, Math.max(inv.getPalletQty(), 0));
            }
            inv.setQty(inv.getQty() - reversedQty);
            inv.setPalletQty(inv.getPalletQty() - palletReversed);
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryMapper.updateById(inv);
            remaining = inv.getQty();

            StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                    StockMovement.TYPE_DISPUTE_REVERSAL, reversedQty, ctx.getRefDocNo(), ctx.getOperatorUserId());
            // 计费截止异议日（D39）：biz_time=异议时刻
            mv.setBizTime(LocalDateTime.now());
            // V20 起双写：pallet_delta 正式列 + remark 快照保留（P4 兼容两代数据，13 §2.4-1）
            mv.setPalletDelta(-palletReversed);
            mv.setRemark("palletReversed=" + palletReversed);
            stockMovementMapper.insert(mv);
            movementId = mv.getId();
        }

        log.info("[P3] disputeReverse wholesaler={} sku={} Q={} onhand={} reversed={} shortfall={} pallet={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), q, onhand, reversedQty, shortfallQty, palletReversed, ctx.getRefDocNo());
        return DisputeReversalResult.builder()
                .reversedQty(reversedQty)
                .shortfallQty(shortfallQty)
                .palletReversed(palletReversed)
                .movementId(movementId)
                .remainingQty(remaining)
                .build();
    }

    @Override
    public InventoryVo restoreInboundAfterArbitration(DisputeRestoreContext ctx) {
        if (ctx.getWholesalerId() == null || ctx.getTenantId() == null || ctx.getSkuId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (ctx.getQty() == null || ctx.getQty() <= 0) {
            // 售罄 0 冲销的 APPROVED：无可恢复量，无操作（不写流水）
            return queryInventory(ctx.getWholesalerId(), ctx.getSkuId()).stream().findFirst().orElse(null);
        }
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doRestoreInboundInTx(ctx));
    }

    /**
     * 仲裁恢复事务体（仅供 {@link #restoreInboundAfterArbitration} 持锁经代理调用）。
     * qty += reversedQty；配对 DISPUTE_REVERSAL（按 ref_doc_no 定位，一单一诉天然唯一）：
     * reversal_of_id 回指 + 冲销托盘按 remark 快照还原；biz_time=原入库时间戳（G10，
     * created_at 仍为真实写入时刻，审计/计费双轨清晰）。
     */
    @Override
    @Transactional
    public InventoryVo doRestoreInboundInTx(DisputeRestoreContext ctx) {
        // 配对的冲销流水（异议冲销唯一入口=单据 CAS 成功分支，同 doc 至多一条）
        StockMovement reversal = stockMovementMapper.selectOne(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, ctx.getWholesalerId())
                .eq(StockMovement::getSkuId, ctx.getSkuId())
                .eq(StockMovement::getType, StockMovement.TYPE_DISPUTE_REVERSAL)
                .eq(StockMovement::getRefDocNo, ctx.getRefDocNo())
                .last("LIMIT 1"));
        if (reversal == null) {
            // N5（08-p3-review）：恢复必须能配对冲销流水（不变量：DISPUTE_RESTORE.reversal_of_id
            // 非空回指，P4 配对抵消依赖）——与 doReverseOutboundInTx 找不到原流水即拒对称。
            // 正常流程不可达（reversedQty>0 必有冲销流水），属防御缺口收口，防凭空造量。
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND, "原冲销流水不存在，无法恢复库存");
        }
        int palletRestore = parsePalletReversed(reversal);

        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        inv.setQty(inv.getQty() + ctx.getQty());
        inv.setPalletQty(inv.getPalletQty() + palletRestore);
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(inv);

        StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_DISPUTE_RESTORE, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());
        mv.setBizTime(ctx.getOriginalInboundAt());
        mv.setReversalOfId(reversal.getId());
        mv.setPalletDelta(palletRestore);
        mv.setRemark("palletRestored=" + palletRestore);
        stockMovementMapper.insert(mv);

        log.info("[P3] disputeRestore wholesaler={} sku={} +{} -> qty={} (doc={}, bizTime={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), inv.getQty(), ctx.getRefDocNo(), ctx.getOriginalInboundAt());
        return toVo(inv);
    }

    // ==================== P3b T1 R3 纠错联动（13 §1.3，同锁同事务先例） ====================

    @Override
    public com.cangchu.inventory.dto.InboundCorrectionResult applyInboundCorrection(
            com.cangchu.inventory.dto.InboundCorrectionContext ctx) {
        if (ctx.getWholesalerId() == null || ctx.getTenantId() == null || ctx.getSkuId() == null
                || ctx.getRefDocNo() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (ctx.getDelta() == null || ctx.getDelta() == 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doApplyInboundCorrectionInTx(ctx));
    }

    /**
     * 纠错联动事务体（仅供 {@link #applyInboundCorrection} 持锁经代理调用）。
     * 13 §1.3 口径：
     * <pre>
     * delta &gt; 0（改大）：applied = delta，qty += delta，写 CORRECTION_IN；
     * delta &lt; 0（改小）：applied = min(|delta|, max(onhand,0))（12 §2.4 封顶复用），
     *                    qty −= applied，写 CORRECTION_OUT；applied=0（售罄）不写流水；
     * 托盘 = ±ceil(原入库 pallet × applied / 原登记 qty)，释放侧对在库托盘二次封顶；
     * biz_time = 原 INBOUND 流水 biz_time、reversal_of_id = 原 INBOUND 流水 id（D-4 配对锚点，
     *            P4 按配对重算仓储费——本波仅留锚点，零金额）。
     * </pre>
     * 托盘变化量落流水 remark 快照（palletAdjusted=±N，DISPUTE_REVERSAL remark 先例；
     * pallet_delta 列随 V20/T3-W1 落地，V20 前流水按 13 §2.4-4 存量边界恒 0）。
     */
    @Override
    @Transactional
    public com.cangchu.inventory.dto.InboundCorrectionResult doApplyInboundCorrectionInTx(
            com.cangchu.inventory.dto.InboundCorrectionContext ctx) {
        // 原 INBOUND 流水（正向链登记唯一入口=register CAS 成功分支，同 doc 至多一条）
        StockMovement original = stockMovementMapper.selectOne(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, ctx.getWholesalerId())
                .eq(StockMovement::getSkuId, ctx.getSkuId())
                .eq(StockMovement::getType, StockMovement.TYPE_INBOUND)
                .eq(StockMovement::getRefDocNo, ctx.getRefDocNo())
                .orderByAsc(StockMovement::getId)
                .last("LIMIT 1"));
        if (original == null) {
            // 纠错必须能配对原 INBOUND 流水（不变量：CORRECTION_*.reversal_of_id 非空，P4 配对依赖）
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND, "原入库流水不存在，无法纠错");
        }
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            // 登记过必有库存行（行不删除）；防御性拒绝
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }

        int delta = ctx.getDelta();
        int originalQty = original.getQty();
        int originalPallet = ctx.getOriginalPalletQty() != null ? Math.max(ctx.getOriginalPalletQty(), 0) : 0;

        int applied;
        int shortfall = 0;
        int palletAdjusted = 0;
        Long movementId = null;
        if (delta > 0) {
            // 改大：补录差额，无封顶语义
            applied = delta;
            if (originalPallet > 0 && originalQty > 0) {
                palletAdjusted = (int) Math.ceil(originalPallet * (double) applied / originalQty);
            }
            inv.setQty(inv.getQty() + applied);
            inv.setPalletQty(inv.getPalletQty() + palletAdjusted);
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryMapper.updateById(inv);

            StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                    StockMovement.TYPE_CORRECTION_IN, applied, ctx.getRefDocNo(), ctx.getOperatorUserId());
            mv.setBizTime(original.getBizTime());
            mv.setReversalOfId(original.getId());
            // V20 起 remark 快照迁正式列（双写，读侧优先列；13 §2.4-1 / T1-BE 备注 5 过渡收口）
            mv.setPalletDelta(palletAdjusted);
            mv.setRemark("palletAdjusted=" + palletAdjusted);
            stockMovementMapper.insert(mv);
            movementId = mv.getId();
        } else {
            // 改小：12 §2.4 封顶——在库件优先视为被纠错单的货，库存永不打负；差额线下定责
            int onhand = Math.max(inv.getQty(), 0);
            applied = Math.min(-delta, onhand);
            shortfall = -delta - applied;
            if (applied > 0) {
                if (originalPallet > 0 && originalQty > 0) {
                    int proportional = (int) Math.ceil(originalPallet * (double) applied / originalQty);
                    palletAdjusted = -Math.min(proportional, Math.max(inv.getPalletQty(), 0));
                }
                inv.setQty(inv.getQty() - applied);
                inv.setPalletQty(inv.getPalletQty() + palletAdjusted);
                inv.setUpdatedAt(LocalDateTime.now());
                inventoryMapper.updateById(inv);

                StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                        StockMovement.TYPE_CORRECTION_OUT, applied, ctx.getRefDocNo(), ctx.getOperatorUserId());
                mv.setBizTime(original.getBizTime());
                mv.setReversalOfId(original.getId());
                mv.setPalletDelta(palletAdjusted);
                mv.setRemark("palletAdjusted=" + palletAdjusted);
                stockMovementMapper.insert(mv);
                movementId = mv.getId();
            }
            // applied=0（售罄）：不写流水、不动库存，纠错单照常 APPROVED 留痕（13 §1.3）
        }

        log.info("[P3b] inboundCorrection wholesaler={} sku={} delta={} applied={} shortfall={} pallet={} -> qty={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), delta, applied, shortfall, palletAdjusted,
                inv.getQty(), ctx.getRefDocNo());
        return com.cangchu.inventory.dto.InboundCorrectionResult.builder()
                .appliedQty(applied)
                .shortfallQty(shortfall)
                .palletAdjusted(palletAdjusted)
                .movementId(movementId)
                .remainingQty(inv.getQty())
                .build();
    }

    // ==================== P3b T3-W1：退货登记时扣 + 出库托盘释放（13 §2.1/§2.4，同锁同事务先例） ====================

    @Override
    public com.cangchu.inventory.dto.ReturnStockResult returnStock(com.cangchu.inventory.dto.ReturnStockContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());
        if (ctx.getPalletReleaseOverride() != null && ctx.getPalletReleaseOverride() < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doReturnStockInTx(ctx));
    }

    /**
     * 退货登记事务体（仅供 {@link #returnStock} 持锁经代理调用）。D-7 登记时扣：
     * 不足抛 STOCK_NOT_ENOUGH（不写流水；调用方单据事务整体回滚保持 ACCEPTED，WA 改单）。
     * 托盘释放（13 §2.4-2）：默认 ceil(池 pallet × n / 池 qty)（全出清零=全部释放），
     * WK 覆盖含 0，min(·, 在库托盘) 双重封顶，pallet_qty 恒 ≥0。
     */
    @Override
    @Transactional
    public com.cangchu.inventory.dto.ReturnStockResult doReturnStockInTx(com.cangchu.inventory.dto.ReturnStockContext ctx) {
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        int qty = ctx.getQty();
        if (inv.getQty() < qty) {
            // S5 同构（04 §3.2 拣货不足）：拒绝且不产生流水——「当前在库 N 件不足退货 M 件」由前端文案承接
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        int qtyBefore = inv.getQty();
        int palletPool = Math.max(inv.getPalletQty(), 0);
        int released = resolvePalletRelease(ctx.getPalletReleaseOverride(), palletPool, qty, qtyBefore);

        inv.setQty(inv.getQty() - qty);
        inv.setPalletQty(inv.getPalletQty() - released);
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(inv);

        StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_RETURN, qty, ctx.getRefDocNo(), ctx.getOperatorUserId());
        // 计费当日截止锚点（05 §1.2）：biz_time=登记日（零金额，P4 结算）
        mv.setBizTime(LocalDateTime.now());
        mv.setPalletDelta(-released);
        stockMovementMapper.insert(mv);

        log.info("[P3b] returnStock wholesaler={} sku={} -{} palletReleased={} -> qty={} pallet={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), qty, released, inv.getQty(), inv.getPalletQty(), ctx.getRefDocNo());
        return com.cangchu.inventory.dto.ReturnStockResult.builder()
                .palletReleased(released)
                .movementId(mv.getId())
                .remainingQty(inv.getQty())
                .remainingPalletQty(inv.getPalletQty())
                .build();
    }

    @Override
    public int releaseOutboundPallet(com.cangchu.inventory.dto.PalletReleaseContext ctx) {
        if (ctx.getWholesalerId() == null || ctx.getTenantId() == null || ctx.getSkuId() == null
                || ctx.getDocQty() == null || ctx.getDocQty() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (ctx.getPalletReleaseOverride() != null && ctx.getPalletReleaseOverride() < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doReleaseOutboundPalletInTx(ctx));
    }

    /**
     * 出库托盘释放事务体（仅供 {@link #releaseOutboundPallet} 持锁经代理调用，13 §2.4-3）。
     * 件数创建时已扣 ⇒ 比例分母取「变动前在库」= 当前池 qty + docQty；扣后在库=0 时默认释放全部
     * （05 §3.3 全出清零）。释放=0 不写流水（qty=0 且 pallet_delta=0 的空流水无对账意义）。
     */
    @Override
    @Transactional
    public int doReleaseOutboundPalletInTx(com.cangchu.inventory.dto.PalletReleaseContext ctx) {
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            // 出库过必有库存行；防御性按 0 释放（不阻断登记主链）
            return 0;
        }
        int palletPool = Math.max(inv.getPalletQty(), 0);
        int qtyBeforeChange = Math.max(inv.getQty(), 0) + ctx.getDocQty();
        int released;
        if (ctx.getPalletReleaseOverride() != null) {
            released = Math.min(ctx.getPalletReleaseOverride(), palletPool);
        } else if (palletPool == 0) {
            released = 0;
        } else if (Math.max(inv.getQty(), 0) == 0) {
            // 全出清零：变动后在库=0 → 默认释放全部占用托盘
            released = palletPool;
        } else {
            released = Math.min((int) Math.ceil(palletPool * (double) ctx.getDocQty() / qtyBeforeChange), palletPool);
        }
        if (released <= 0) {
            return 0;
        }
        inv.setPalletQty(inv.getPalletQty() - released);
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(inv);

        StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_PALLET_RELEASE, 0, ctx.getRefDocNo(), ctx.getOperatorUserId());
        mv.setBizTime(LocalDateTime.now());
        mv.setPalletDelta(-released);
        stockMovementMapper.insert(mv);

        log.info("[P3b] releaseOutboundPallet wholesaler={} sku={} -{} -> pallet={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), released, inv.getPalletQty(), ctx.getRefDocNo());
        return released;
    }

    // ==================== P3b T3-W2：盘盈/盘亏（13 §2.2，D-10 封顶；同锁同事务先例） ====================

    @Override
    public InventoryVo gainStock(com.cangchu.inventory.dto.GainStockContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());
        if (ctx.getPalletDelta() != null && ctx.getPalletDelta() < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doGainStockInTx(ctx));
    }

    /**
     * 盘盈事务体（仅供 {@link #gainStock} 持锁经代理调用）。qty += diff；无库存行 upsert 建行
     * （addStock 同构：盘出账外货 system_qty=0 也可盘盈）；写 GAIN 流水（biz_time=审批通过日——
     * 盘盈次日起算视同当日入库锚点，05 §1.2 零金额；pallet_delta=+M 可选）。
     */
    @Override
    @Transactional
    public InventoryVo doGainStockInTx(com.cangchu.inventory.dto.GainStockContext ctx) {
        int palletDelta = ctx.getPalletDelta() != null ? ctx.getPalletDelta() : 0;
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            inv = new Inventory();
            inv.setId(snowflakeIdUtil.nextId());
            inv.setTenantId(ctx.getTenantId());
            inv.setWholesalerId(ctx.getWholesalerId());
            inv.setSkuId(ctx.getSkuId());
            inv.setQty(ctx.getQty());
            inv.setPalletQty(palletDelta);
            inventoryMapper.insert(inv);
        } else {
            inv.setQty(inv.getQty() + ctx.getQty());
            inv.setPalletQty(inv.getPalletQty() + palletDelta);
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryMapper.updateById(inv);
        }

        StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_GAIN, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());
        mv.setBizTime(LocalDateTime.now());
        mv.setPalletDelta(palletDelta);
        stockMovementMapper.insert(mv);

        log.info("[P3b] gainStock wholesaler={} sku={} +{} pallet+{} -> qty={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), palletDelta, inv.getQty(), ctx.getRefDocNo());
        return toVo(inv);
    }

    @Override
    public com.cangchu.inventory.dto.LossStockResult lossStock(com.cangchu.inventory.dto.LossStockContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());
        if (ctx.getPalletReleaseOverride() != null && ctx.getPalletReleaseOverride() < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        return withLock(ctx.getWholesalerId(), ctx.getSkuId(), () -> self.doLossStockInTx(ctx));
    }

    /**
     * 盘亏事务体（仅供 {@link #lossStock} 持锁经代理调用）。D-10 封顶（12 §2.4 家族第 3 处）：
     * <pre>
     * applied   = min(qty, max(onhand, 0))   // onhand=审批时刻锁内重读（G9：等待期被出完按剩余封顶）
     * shortfall = qty − applied              // 调用方写备注+通知定责，qty 恒 ≥0 不破
     * </pre>
     * applied&gt;0 才写 LOSS 流水（qty=applied、biz_time=审批通过日计费当日截止、
     * pallet_delta=−释放——默认 resolvePalletRelease 比例/WK 覆盖，双重封顶不打负）；
     * applied=0（售罄）零冲销：不写流水、不动库存（CORRECTION_OUT/DISPUTE_REVERSAL 同构）。
     * 无库存行（从未入库却盘亏）按 onhand=0 处理，不抛不建行。
     */
    @Override
    @Transactional
    public com.cangchu.inventory.dto.LossStockResult doLossStockInTx(com.cangchu.inventory.dto.LossStockContext ctx) {
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        int onhand = inv != null ? Math.max(inv.getQty(), 0) : 0;
        int applied = Math.min(ctx.getQty(), onhand);
        int shortfall = ctx.getQty() - applied;

        int released = 0;
        Long movementId = null;
        if (applied > 0) {
            int palletPool = Math.max(inv.getPalletQty(), 0);
            released = resolvePalletRelease(ctx.getPalletReleaseOverride(), palletPool, applied, onhand);
            inv.setQty(inv.getQty() - applied);
            inv.setPalletQty(inv.getPalletQty() - released);
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryMapper.updateById(inv);

            StockMovement mv = newMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                    StockMovement.TYPE_LOSS, applied, ctx.getRefDocNo(), ctx.getOperatorUserId());
            mv.setBizTime(LocalDateTime.now());
            mv.setPalletDelta(-released);
            stockMovementMapper.insert(mv);
            movementId = mv.getId();
        }

        log.info("[P3b] lossStock wholesaler={} sku={} target={} onhand={} applied={} shortfall={} palletReleased={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), onhand, applied, shortfall, released, ctx.getRefDocNo());
        return com.cangchu.inventory.dto.LossStockResult.builder()
                .appliedQty(applied)
                .shortfallQty(shortfall)
                .palletReleased(released)
                .movementId(movementId)
                .remainingQty(inv != null ? inv.getQty() : 0)
                .remainingPalletQty(inv != null ? inv.getPalletQty() : 0)
                .build();
    }

    /**
     * 托盘释放量决议（13 §2.4-2 / 05 §3.3，退货侧：件数与托盘同事务同时扣）：
     * 覆盖值优先（含 0）；默认=ceil(池 pallet × 本次件数 / 变动前在库)，
     * 全出清零（qty 扣后=0）→ 默认释放全部；一律 min(·, 在库托盘) 封顶不打负。
     */
    private int resolvePalletRelease(Integer override, int palletPool, int qty, int qtyBefore) {
        if (override != null) {
            return Math.min(override, palletPool);
        }
        if (palletPool == 0 || qtyBefore <= 0) {
            return 0;
        }
        if (qtyBefore - qty == 0) {
            return palletPool;
        }
        return Math.min((int) Math.ceil(palletPool * (double) qty / qtyBefore), palletPool);
    }

    // ==================== 查询 ====================

    @Override
    public void assertStockEnough(Long wholesalerId, Long skuId, int qty) {
        if (qty <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        Inventory inv = findRow(wholesalerId, skuId);
        if (inv == null) {
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        if (inv.getQty() < qty) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
    }

    @Override
    public List<InventoryVo> queryInventory(Long wholesalerId, Long skuId) {
        LambdaQueryWrapper<Inventory> qw = new LambdaQueryWrapper<Inventory>()
                .eq(wholesalerId != null, Inventory::getWholesalerId, wholesalerId)
                .eq(skuId != null, Inventory::getSkuId, skuId)
                .orderByDesc(Inventory::getUpdatedAt);
        return inventoryMapper.selectList(qw).stream().map(this::toVo).toList();
    }

    @Override
    public List<InventoryVo> listInStockSkusFor(Long wholesalerId) {
        LambdaQueryWrapper<Inventory> qw = new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWholesalerId, wholesalerId)
                .gt(Inventory::getQty, 0)
                .orderByDesc(Inventory::getUpdatedAt);
        return inventoryMapper.selectList(qw).stream().map(this::toVo).toList();
    }

    // ==================== 私有 ====================

    private void validateCtx(Long wholesalerId, Long tenantId, Long skuId, Integer qty) {
        if (wholesalerId == null || tenantId == null || skuId == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (qty == null || qty <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
    }

    /** 读取库存行（受 TenantLine 兜底隔离）。 */
    private Inventory findRow(Long wholesalerId, Long skuId) {
        return inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWholesalerId, wholesalerId)
                .eq(Inventory::getSkuId, skuId));
    }

    /**
     * 锁行读（B1 修复：真正的 {@code SELECT ... FOR UPDATE}）。
     *
     * <p>为什么 Redisson 锁不够（08-p3-review B1，两并发窗口）：
     * <ol>
     *   <li><b>窗口 A（旧快照读）</b>：业务方法自带 @Transactional 时（P3 全部链路），内层事务体
     *       REQUIRED 并入外层事务——普通 SELECT 在 MySQL RR 下读的是<b>外层事务开始时</b>的旧快照，
     *       即便此刻已持有 Redisson 锁，读到的 qty 也可能是陈旧值（读-算-写把错值固化）。</li>
     *   <li><b>窗口 B（锁先于提交释放）</b>：锁在内层方法返回即释放，而外层事务还要继续建单/
     *       发通知才提交——下一个抢到锁的线程读不到未提交的库存变更。</li>
     * </ol>
     * InnoDB 锁定读（FOR UPDATE）永远读<b>最新已提交</b>版本并阻塞在未提交行锁上，一处改动同关
     * 两窗口：窗口 A 的快照读消失；窗口 B 中后进事务会等到前事务提交。Redisson 锁保留作
     * 跨进程粗粒度串行化与 insert 防撞（首次入库无行可锁，FOR UPDATE 空集不阻塞）。
     */
    private Inventory lockRowForUpdate(Long wholesalerId, Long skuId) {
        return inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWholesalerId, wholesalerId)
                .eq(Inventory::getSkuId, skuId)
                .last("FOR UPDATE"));
    }

    private void writeMovement(Long skuId, Long wholesalerId, Long tenantId,
                               String type, Integer qty, String refDocNo, Long operatorUserId) {
        StockMovement mv = newMovement(skuId, wholesalerId, tenantId, type, qty, refDocNo, operatorUserId);
        stockMovementMapper.insert(mv);
    }

    /** 构造流水行（不落库）。biz_time 默认=当下（=created_at 口径，V15 存量回填同义）；反向类型由调用方覆写。 */
    private StockMovement newMovement(Long skuId, Long wholesalerId, Long tenantId,
                                      String type, Integer qty, String refDocNo, Long operatorUserId) {
        StockMovement mv = new StockMovement();
        mv.setId(snowflakeIdUtil.nextId());
        mv.setSkuId(skuId);
        mv.setWholesalerId(wholesalerId);
        mv.setTenantId(tenantId);
        mv.setType(type);
        mv.setQty(qty);
        mv.setRefDocNo(refDocNo);
        mv.setOperatorUserId(operatorUserId);
        mv.setBizTime(LocalDateTime.now());
        mv.setPalletDelta(0);
        return mv;
    }

    /**
     * 还原 DISPUTE_REVERSAL 冲销托盘数（读侧兼容两代数据，13 §2.4-1）：
     * V20 起优先 pallet_delta 正式列（冲销为负值取绝对值）；存量流水列恒 0 → 回退 remark
     * 快照（palletReversed=N）解析；两者皆无 → 0。
     */
    private int parsePalletReversed(StockMovement reversal) {
        if (reversal == null) {
            return 0;
        }
        if (reversal.getPalletDelta() != null && reversal.getPalletDelta() != 0) {
            return Math.abs(reversal.getPalletDelta());
        }
        if (reversal.getRemark() == null) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("palletReversed=(\\d+)").matcher(reversal.getRemark());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 锁模板（P3 抽取，与 addStock/deductStock 同构）：Redisson 锁 {@code lock:inv:{w}:{s}} 内执行 body
     * （body 为 self 代理的事务体），finally 释放。锁失败 INVENTORY_LOCK_FAILED。
     */
    private <T> T withLock(Long wholesalerId, Long skuId, java.util.function.Supplier<T> body) {
        String lockKey = "lock:inv:" + wholesalerId + ":" + skuId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        if (!acquired) {
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        try {
            return body.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private InventoryVo toVo(Inventory inv) {
        return InventoryVo.builder()
                .id(inv.getId())
                .wholesalerId(inv.getWholesalerId())
                .tenantId(inv.getTenantId())
                .skuId(inv.getSkuId())
                .qty(inv.getQty())
                .palletQty(inv.getPalletQty())
                .updatedAt(inv.getUpdatedAt())
                .build();
    }
}
