package com.cangchu.inventory;

import com.cangchu.CangchuApplication;
import com.cangchu.inventory.dto.DisputeReversalResult;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.InboundDisputeContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 回归（08-p3-review）：库存「读-算-写」必须读到最新已提交值。
 *
 * <p>旧实现 {@code lockRowForUpdate} 是普通 SELECT，两个并发窗口未关：
 * A）调用方自带 @Transactional 时内层并入外层事务，MySQL RR 下读外层事务开始时的旧快照；
 * B）Redisson 锁在内层方法返回即释放，外层事务尚未提交，后进线程读不到未提交变更。
 * 修复后 SELECT ... FOR UPDATE：锁定读永远读最新已提交版本，且阻塞在未提交行锁上直至对方提交。
 *
 * <p>本用例复刻审查报告的资损场景（窗口 B 的确定性形态）：售出 20 件的外层事务尚未提交时
 * 发起异议冲销——冲销必须等待售出提交后按剩余在库 10 封顶，而不是读旧值 30 全额冲销
 * （旧实现会把 reversedQty=30 固化进仲裁单并把库存覆写为错值，公式不变量破坏）。
 */
@SpringBootTest(classes = CangchuApplication.class)
class InventoryLockRegressionTest {

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private PlatformTransactionManager txManager;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() & 0x7FFFFFFFL);
    private static final long TENANT_ID = 700_000_000_002L;

    @Test
    @DisplayName("B1 异议冲销读到最新已提交在库：未提交售出 20 → 冲销等待提交后按 10 封顶（非旧值 30）")
    void disputeReversalSeesLatestCommittedQty() throws Exception {
        long w = 900_000_000_000L + SEQ.incrementAndGet();
        long sku = 800_000_000_000L + SEQ.incrementAndGet();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                .qty(30).palletQty(0).refDocNo("WK-B1-" + sku).operatorUserId(11L).build());

        // 预热另一 (w,sku) 的冲销链路（类加载/SQL 预编译），压缩正式用例中的调度不确定性
        long warmSku = 800_000_000_000L + SEQ.incrementAndGet();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(warmSku)
                .qty(1).palletQty(0).refDocNo("WK-B1W-" + warmSku).operatorUserId(11L).build());
        inventoryService.reverseInboundForDispute(InboundDisputeContext.builder()
                .wholesalerId(w).tenantId(TENANT_ID).skuId(warmSku)
                .registeredQty(1).palletQty(0).refDocNo("WK-B1W-" + warmSku).operatorUserId(22L).build());

        CountDownLatch deductedUncommitted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1（售出方）：外层编程式事务内 deductStock 20——内层 REQUIRED 并入外层，
            // Redisson 锁在返回时已释放，但行变更保持未提交，直到收到 allowCommit
            Future<?> seller = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
                inventoryService.deductStock(OutboundContext.builder()
                        .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                        .qty(20).refDocNo("CK-B1-" + sku).operatorUserId(22L).build());
                deductedUncommitted.countDown();
                try {
                    // 持有未提交变更，等待异议方先进入锁行读
                    allowCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null; // execute 返回即提交
            }));
            assertThat(deductedUncommitted.await(10, TimeUnit.SECONDS)).isTrue();

            // T2（异议方）：Redisson 锁空闲可得；FOR UPDATE 应阻塞在 T1 的未提交行锁上
            Future<DisputeReversalResult> disputer = pool.submit(() ->
                    inventoryService.reverseInboundForDispute(InboundDisputeContext.builder()
                            .wholesalerId(w).tenantId(TENANT_ID).skuId(sku)
                            .registeredQty(30).palletQty(0)
                            .refDocNo("WK-B1-" + sku).operatorUserId(22L).build()));
            // 给 T2 足够时间抵达锁行读（旧实现此刻会读到旧值 30 并继续计算）
            Thread.sleep(200);
            allowCommit.countDown(); // T1 提交 → T2 解除阻塞，读到最新已提交 qty=10

            DisputeReversalResult r = disputer.get(30, TimeUnit.SECONDS);
            seller.get(10, TimeUnit.SECONDS);

            // 封顶按提交后的真实在库：reversed=10 / shortfall=20（旧实现为 30/0 错值）
            assertThat(r.getReversedQty()).isEqualTo(10);
            assertThat(r.getShortfallQty()).isEqualTo(20);

            // 公式不变量：30 入 − 20 出 − 10 冲销 = 0
            InventoryVo inv = inventoryService.queryInventory(w, sku).get(0);
            assertThat(inv.getQty()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
