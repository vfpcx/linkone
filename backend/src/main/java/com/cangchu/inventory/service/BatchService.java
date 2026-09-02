package com.cangchu.inventory.service;

import com.cangchu.inventory.dto.BatchBackfillDto;
import com.cangchu.inventory.dto.BatchLocationUpdateDto;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.InboundBatchContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.BatchLocationLogVo;
import com.cangchu.inventory.vo.BatchRecalcResultVo;
import com.cangchu.inventory.vo.BatchToggleVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 批次登记簿服务（P3b T4-W1，13 §3；D-11=C：批次登记 + FIFO 离线推算，交易路径零改动）。
 *
 * <p>铁律：本服务<b>不进出入库交易路径</b>——{@code InventoryService} 的 addStock/deductStock
 * 等对批次零感知；登记簿追加与流水 batch_id 回填均为单据事务内的<b>后置钩子</b>。
 */
public interface BatchService {

    /**
     * TA 批次开关专用端点（13 §3.5，D-13 禁改在通用设置接口保留）。
     * 24h ≤2 次（Redis 计数 {@code batch:toggle:{tenantId}}，超限 50361）；目标状态=当前状态时
     * 幂等空转（不计次、不生成默认批次、不动 batch_enabled_at）；实际翻转须 confirmed=true。
     * 关→启：batch_enabled_at=now + 为全部 in_stock>0 的 (w,sku) 生成默认批次 DEFAULT-{YYYYMMDD}
     * （initial_qty=当刻池 qty 快照；再启用生成新默认批次，同日冲突追加序号后缀，不复活 CLOSED）。
     * 启→关：全部非终态批次标 CLOSED（登记簿冻结）。
     */
    BatchToggleVo toggle(Long taUserId, BatchToggleDto dto);

    /** 批次号占用预检（提交时友好拦截，50362；登记 insert 撞 uk 为权威兜底）。 */
    void assertBatchNoAvailable(Long wholesalerId, Long skuId, String batchNo);

    /**
     * 入库登记后置钩子（正向/代建登记事务内、addStock 之后调用）：
     * 追加登记簿行（一批一行，uk 冲突 50362 整体回滚）+ 回填该单 INBOUND 流水 batch_id。
     * 初始状态：到效期−今日 ≤ 租户阈值 → EXPIRING（临期警告放行、立即进入临期列表），否则 IN_STOCK。
     */
    Batch registerInboundBatch(InboundBatchContext ctx);

    /**
     * R3 纠错流水批次标识回填（方案 C：CORRECTION_IN/OUT 落 batch_id 供 FIFO 直扣）。
     * 原入库单无批次或批次行不存在时静默跳过（不阻断纠错主链）。
     */
    void tagCorrectionMovement(Long movementId, Long wholesalerId, Long skuId, String batchNo);

    /**
     * 单租户 FIFO 离线推算（13 §3.2 六步；纯读流水、不加锁、幂等重跑）。
     * 覆写各批次 remaining_qty 并联动 IN_STOCK/EXPIRING/SOLD_OUT 三态（不触
     * PENDING_CLEARANCE/CLEARED/CLOSED）；批次功能未启用的租户空转返回。
     * <b>T4-W2 契约</b>：BatchRecalcJob（02:00）对每租户调用本方法（或 {@link #recalcAll}），
     * 用返回的 {@code newlyExpiringBatchIds} 发 BATCH_EXPIRING 首发通知并落 expiring_notified_at。
     */
    BatchRecalcResultVo recalcTenant(Long tenantId);

    /** 全平台推算：对全部 batch_enabled=1 租户逐个 {@link #recalcTenant}（单租户失败记日志不阻断）。 */
    List<BatchRecalcResultVo> recalcAll();

    // ==================== T4-W2：临期 Job 体 + 通知 + 预警（13 §3.3） ====================

    /**
     * 02:00 Job 体（BatchRecalcJob 调用，测试可直驱）：{@link #recalcAll} 后逐
     * {@code newlyExpiringBatchIds} 发 BATCH_EXPIRING 站内信（库管+商户管理员各一条，D-12）。
     * 去重锚点：条件更新 {@code expiring_notified_at IS NULL} 的赢者才发——重复跑/并发恰发一次，
     * 状态不变不重发；单批次通知失败记日志不阻断。
     */
    List<BatchRecalcResultVo> runDailyRecalcAndNotify();

    /**
     * 02:30 Job 体（BatchExpiryMarkJob 调用，测试可直驱）：按 batch_enabled=1 租户扫
     * {@code expiry_date < CURDATE() ∧ status ∈ (IN_STOCK, EXPIRING) ∧ remaining_qty > 0}
     * → 标 PENDING_CLEARANCE 并通知库管发起清库（BATCH_EXPIRED）。SQL 内比数据库时间——
     * 当日到期不标、昨日标；remaining=0 者由 02:00 推算落 SOLD_OUT 不清库。
     * 单租户失败记日志不阻断。
     *
     * @return 本次标记的批次数
     */
    int markExpiredBatches();

    /**
     * WK 手动一键通知商户（13 §5.3）：仅 EXPIRING/PENDING_CLEARANCE 批次（否则 50330 语义）；
     * 同批次 24h 限 1（条件更新 manual_notified_at 比对 SQL 数据库时间，超限 50367）；
     * 站内信发商户管理员（BATCH_EXPIRING，不发短信）。
     */
    void notifyWholesalerManually(Long batchId, Long userId);

    /** 临期预警列表（WK/TA，13 §5.3）：EXPIRING ∪ PENDING_CLEARANCE，剩余天数升序。 */
    BatchListVo listExpiring(Long tenantId, Long userId);

    /**
     * TA 工作台「临期 N 天内」批次数（P5-C，19 §3）：expiry_date ≤ 今日+days 且未处置
     * （status ∉ CLEARED/CLOSED/SOLD_OUT）。基于日期口径而非状态——02:00 推算阈值（默认 30 天）
     * 通常宽于窗口，故直接按到期日命中「窗口内到期未处置」。纯读、无鉴权（调用方已 requireTa）。
     */
    long countExpiringWithinDays(Long tenantId, int days);

    /**
     * TA 临期看板汇总（PRD §3.6-A）：临期/待清理批次数与推算件数、已清库累计、按 SKU 分组。
     * 清库单待审批数由 Controller 经 ClearanceRequestService 编排填充（G-S1：不跨域直连）。
     */
    com.cangchu.inventory.vo.ExpiryDashboardVo expiryDashboard(Long tenantId, Long userId);

    // ==================== T4-W2：清库联动出口（document 域经此接入，G-S1） ====================

    /** 取本租户批次（清库单建单校验用；不存在/跨租户按不存在 50363）。 */
    com.cangchu.inventory.vo.BatchVo getTenantBatch(Long tenantId, Long batchId);

    /**
     * 清库生效（QK 审批通过事务内调用）：remaining_qty=0、status=CLEARED、cleared_at=now。
     * 无条件覆写——启→关冻结（CLOSED）期间的在途清库单按提交时策略走完（13 §3.5）。
     */
    void markCleared(Long batchId);

    /** 租户侧批次列表/下钻（WK/TA；wholesalerId+skuId 齐时附「无批次在池量」）。 */
    BatchListVo listForTenant(Long tenantId, Long userId, Long wholesalerId, Long skuId, String status);

    /** 商户侧批次列表（WA/WE 只读，跨自己名下全部商户绑定）。 */
    BatchListVo listForWholesaler(Long userId, Long skuId, String status);

    /** 默认批次补录 production/expiry（WK/TA；仅 source=DEFAULT 且未终态，50363/40205/40206）。 */
    com.cangchu.inventory.vo.BatchVo backfillDefaultBatch(Long batchId, BatchBackfillDto dto, Long userId);

    /**
     * 租户批次配置只读（P3b 收口 L-1，11 报告遗留）：{batchEnabled, batchEnabledAt, expiryThresholdDays}。
     * 鉴权=登录用户在该租户下持任一 ACTIVE 角色即可读（WA/WE 经商户绑定亦携 tenant_id，
     * user_roles 登录态推导为唯一可信来源）；供商户端按开关隐藏关闭档批次字段、租户端独立拉取阈值。
     * 纯读 tenant_settings（经 TenantService.getBatchConfig，G-S1），无副作用。
     */
    com.cangchu.tenant.vo.TenantBatchConfigVo getConfigForMember(Long tenantId, Long userId);

    // ==================== P5-D C2：批次移库 + 变更日志（25-p5-c-c2 §4.4，US-WK-05 验收） ====================

    /**
     * 批次移库（WK/TA，PUT /api/v1/tenant/batches/{id}/location）：更新 {@code batches.location}
     * 并按差异落 {@code batch_location_logs}（from/to/操作人）。location=null 表示清空货位；
     * 新旧相同=幂等空转不落日志；批次不存在/跨租户 50363。零记账副作用（方案 C 铁律 D-C-1c/1d）。
     */
    com.cangchu.inventory.vo.BatchVo updateBatchLocation(Long tenantId, Long batchId, BatchLocationUpdateDto dto, Long userId);

    /**
     * 批次移库变更记录（WK/TA，GET /api/v1/tenant/batches/{id}/location-logs）：按批次倒序分页，
     * page≥1、size≤50（默认 1/20）；批次不存在/跨租户 50363。每行含 from/to/操作人/时间。
     */
    Page<BatchLocationLogVo> listLocationLogs(Long tenantId, Long batchId, long page, long size, Long userId);
}
