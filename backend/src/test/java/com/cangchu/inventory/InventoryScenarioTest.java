package com.cangchu.inventory;

import com.cangchu.CangchuApplication;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存模块场景测试（phase-1 B1：入/出库，批次关闭单 sku 维度）。
 *
 * <p>直接面向 service 层验证（B1 不暴露公开加/扣库存 HTTP，入/出库由 C document 单据调用）。
 * 每个用例用独立 (wholesalerId, skuId) 雪花随机值隔离测试数据。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 addStock 增加库存 + 写 INBOUND 流水。</li>
 *   <li>S1 deductStock 扣减库存 + 写 OUTBOUND 流水。</li>
 *   <li>S3 边界：扣到恰好 0。</li>
 *   <li>S5/异常：库存不足拒绝（STOCK_NOT_ENOUGH），且不产生流水。</li>
 *   <li>S7 并发：50 库存 + 100 虚拟线程各扣 1 → 恰好 50 成功、库存=0、不超卖。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class InventoryScenarioTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private StockMovementMapper stockMovementMapper;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() & 0x7FFFFFFFL);

    private long nextWholesaler() { return 900_000_000_000L + SEQ.incrementAndGet(); }
    private long nextSku() { return 800_000_000_000L + SEQ.incrementAndGet(); }
    private static final long TENANT_ID = 700_000_000_001L;

    private long countMovements(long wholesalerId, long skuId, String type) {
        return stockMovementMapper.selectCount(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, wholesalerId)
                .eq(StockMovement::getSkuId, skuId)
                .eq(StockMovement::getType, type));
    }

    // ======================================================================
    // S1 正常流程
    // ======================================================================

    @Test
    @DisplayName("INV-S1-01 addStock 增加库存 + 写 INBOUND 流水")
    void s1_addStock() {
        long w = nextWholesaler();
        long sku = nextSku();

        InventoryVo vo = inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                .qty(30).palletQty(2).refDocNo("IN-001").operatorUserId(11L).build());

        assertThat(vo.getQty()).isEqualTo(30);
        assertThat(vo.getPalletQty()).isEqualTo(2);
        assertThat(countMovements(w, sku, StockMovement.TYPE_INBOUND)).isEqualTo(1);

        // 二次入库累加
        InventoryVo vo2 = inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                .qty(20).refDocNo("IN-002").operatorUserId(11L).build());
        assertThat(vo2.getQty()).isEqualTo(50);
        assertThat(countMovements(w, sku, StockMovement.TYPE_INBOUND)).isEqualTo(2);
    }

    @Test
    @DisplayName("INV-S1-02 deductStock 扣减库存 + 写 OUTBOUND 流水")
    void s1_deductStock() {
        long w = nextWholesaler();
        long sku = nextSku();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(50).operatorUserId(11L).build());

        InventoryVo vo = inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                .qty(20).refDocNo("OUT-001").operatorUserId(22L).build());

        assertThat(vo.getQty()).isEqualTo(30);
        assertThat(countMovements(w, sku, StockMovement.TYPE_OUTBOUND)).isEqualTo(1);
    }

    // ======================================================================
    // S3 边界：扣到恰好 0
    // ======================================================================

    @Test
    @DisplayName("INV-S3-01 扣到恰好 0")
    void s3_deductToZero() {
        long w = nextWholesaler();
        long sku = nextSku();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(10).operatorUserId(11L).build());

        InventoryVo vo = inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(10).operatorUserId(22L).build());

        assertThat(vo.getQty()).isZero();
        // 恰好 0 后 listInStockSkusFor 不应再含该 sku（qty>0）
        assertThat(inventoryService.listInStockSkusFor(w)).noneMatch(i -> i.getSkuId().equals(sku));
    }

    // ======================================================================
    // S5/异常：库存不足拒绝，不产生流水
    // ======================================================================

    @Test
    @DisplayName("INV-S5-01 库存不足出库被拒（STOCK_NOT_ENOUGH）且不产生流水")
    void s5_notEnough() {
        long w = nextWholesaler();
        long sku = nextSku();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(5).operatorUserId(11L).build());

        BizException ex = Assertions.assertThrows(BizException.class, () ->
                inventoryService.deductStock(OutboundContext.builder()
                        .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(6).operatorUserId(22L).build()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);

        // 库存未变 + 无 OUTBOUND 流水
        assertThat(inventoryService.queryInventory(w, sku).get(0).getQty()).isEqualTo(5);
        assertThat(countMovements(w, sku, StockMovement.TYPE_OUTBOUND)).isZero();
    }

    @Test
    @DisplayName("INV-S5-02 库存行不存在出库被拒（INVENTORY_NOT_FOUND）")
    void s5_inventoryNotFound() {
        long w = nextWholesaler();
        long sku = nextSku();
        BizException ex = Assertions.assertThrows(BizException.class, () ->
                inventoryService.deductStock(OutboundContext.builder()
                        .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(1).operatorUserId(22L).build()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
    }

    // ======================================================================
    // S7 并发：50 库存 + 100 虚拟线程各扣 1 → 恰好 50 成功、库存=0、不超卖
    // ======================================================================

    @Test
    @DisplayName("INV-S7-01 50 库存 + 100 虚拟线程各扣1 → 恰好50成功、库存=0、不超卖")
    void s7_concurrentDeductNoOversell() throws Exception {
        long w = nextWholesaler();
        long sku = nextSku();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku).qty(50).operatorUserId(11L).build());

        int threads = 100;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger notEnough = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        inventoryService.deductStock(OutboundContext.builder()
                                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                                .qty(1).refDocNo("OUT-C").operatorUserId(22L).build());
                        ok.incrementAndGet();
                    } catch (BizException e) {
                        if (e.getErrorCode() == ErrorCode.STOCK_NOT_ENOUGH) notEnough.incrementAndGet();
                        else other.incrementAndGet();
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                }));
            }
            for (var f : futures) f.get();
        }

        int finalQty = inventoryService.queryInventory(w, sku).get(0).getQty();
        long outboundCount = countMovements(w, sku, StockMovement.TYPE_OUTBOUND);

        assertThat(ok.get())
                .as("应恰好 50 次扣减成功（ok=%d notEnough=%d other=%d）；>50 说明超卖",
                        ok.get(), notEnough.get(), other.get())
                .isEqualTo(50);
        assertThat(notEnough.get()).isEqualTo(50);
        assertThat(other.get()).as("不应有锁失败/异常").isZero();
        assertThat(finalQty).as("最终库存必须为 0，不超卖").isZero();
        assertThat(outboundCount).as("成功扣减数 = OUTBOUND 流水数").isEqualTo(50);
    }
}
