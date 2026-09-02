package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.ClearanceRequestService;
import com.cangchu.document.service.CountSheetService;
import com.cangchu.document.service.DocumentStatsService;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.common.tenant.TenantScopeAuthSupport;
import com.cangchu.tenant.entity.CapacityPublish;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.WholesalerApplication;
import com.cangchu.tenant.mapper.CapacityPublishMapper;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.WholesalerApplicationMapper;
import com.cangchu.tenant.service.TenantDashboardService;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.TenantBatchConfigVo;
import com.cangchu.tenant.vo.TenantDashboardVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * TA 租户工作台聚合实现（P5-C，19 §3）。
 *
 * <p>安全规约：
 * <ul>
 *   <li>gate=TA 或 WK（requireTaOrWk，写法对齐 requireStOrTa）：WK「回 TA 台」复用工作台
 *       （router meta 注释同），findBoundTenantId 登录态推导、不取客户端 tenantId；
 *       WE 42004、其余 42001。</li>
 *   <li>G-S1/G-S2：document/inventory 域计数经 Service 出口；tenant 域数据域内直连 mapper。</li>
 *   <li>容量精确值返回（TA/WK 仓库侧视角）；公示脱敏仅作用于公开 getCapacity，语义不变。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDashboardServiceImpl implements TenantDashboardService {

    /** 临期 3 天内（与前端 mock 口径一致，19 §3） */
    private static final int EXPIRING_WINDOW_DAYS = 3;

    private final AuthService authService;
    private final TenantScopeAuthSupport tenantScopeAuthSupport;
    private final TenantService tenantService;
    private final StoreMapper storeMapper;
    private final CapacityPublishMapper capacityPublishMapper;
    private final WholesalerApplicationMapper wholesalerApplicationMapper;
    private final CountSheetService countSheetService;
    private final ClearanceRequestService clearanceRequestService;
    private final ArbitrationService arbitrationService;
    private final DocumentStatsService documentStatsService;
    private final BatchService batchService;

    @Override
    public TenantDashboardVo dashboard(Long userId) {
        Long tenantId = requireTaOrWk(userId);
        TenantBatchConfigVo batchConfig = tenantService.getBatchConfig(tenantId);
        boolean batchEnabled = batchConfig != null && Integer.valueOf(1).equals(batchConfig.getBatchEnabled());
        var today = documentStatsService.todayCounts(tenantId);
        return TenantDashboardVo.builder()
                .storeName(tenantService.getTenantName(tenantId))
                .kpi(TenantDashboardVo.KpiVo.builder()
                        .pendingInbound(countPendingInbound(tenantId))
                        .pendingCount(countSheetService.countPendingApprovalForTenant(tenantId))
                        .pendingClearance(clearanceRequestService.countPendingApprovalForTenant(tenantId))
                        .pendingDispute(arbitrationService.countPendingForTa(tenantId))
                        .build())
                .capacity(buildCapacity(tenantId))
                .today(TenantDashboardVo.TodayVo.builder()
                        .inboundCount(today.getInboundCount())
                        .outboundCount(today.getOutboundCount())
                        .inquiryCount(today.getInquiryCount())
                        .expiringBatches(batchEnabled
                                ? batchService.countExpiringWithinDays(tenantId, EXPIRING_WINDOW_DAYS) : 0)
                        .build())
                .batchEnabled(batchEnabled ? 1 : 0)
                .build();
    }

    /** 待审入驻申请（tenant 域内直连，域内合规）。 */
    private long countPendingInbound(Long tenantId) {
        Long cnt = wholesalerApplicationMapper.selectCount(new LambdaQueryWrapper<WholesalerApplication>()
                .eq(WholesalerApplication::getTenantId, tenantId)
                .eq(WholesalerApplication::getStatus, "PENDING"));
        return cnt != null ? cnt : 0;
    }

    /** 容量精确值（照抄 TenantServiceImpl.getCapacity 快照/回退逻辑，去 TIER 脱敏）。 */
    private TenantDashboardVo.DashboardCapacityVo buildCapacity(Long tenantId) {
        CapacityPublish snapshot = capacityPublishMapper.selectOne(new LambdaQueryWrapper<CapacityPublish>()
                .eq(CapacityPublish::getTenantId, tenantId)
                .orderByDesc(CapacityPublish::getSnapshotAt)
                .last("LIMIT 1"));
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().eq(Store::getTenantId, tenantId));

        if (snapshot == null) {
            return TenantDashboardVo.DashboardCapacityVo.builder()
                    .usedQty(0)
                    .usedPallet(0)
                    .totalQty(nvl(store != null ? store.getTotalCapacityQty() : null))
                    .totalPallet(nvl(store != null ? store.getTotalCapacityPallet() : null))
                    .utilization(0)
                    .visibility(store != null && store.getCapacityVisibility() != null
                            ? store.getCapacityVisibility() : "PRIVATE")
                    .snapshotAt(LocalDateTime.now())
                    .build();
        }
        return TenantDashboardVo.DashboardCapacityVo.builder()
                .usedQty(snapshot.getUsedQty())
                .usedPallet(snapshot.getUsedPallet())
                .totalQty(snapshot.getTotalQty())
                .totalPallet(snapshot.getTotalPallet())
                .utilization(calcUtilization(snapshot.getUsedQty(), snapshot.getTotalQty()))
                .visibility(store != null && store.getCapacityVisibility() != null
                        ? store.getCapacityVisibility() : "PUBLIC")
                .snapshotAt(snapshot.getSnapshotAt())
                .build();
    }

    /** 已用/总容量百分比（0-100 取整；与前端 CapacityBar 自算口径一致，不依赖快照表值域）。 */
    private int calcUtilization(Integer used, Integer total) {
        if (used == null || total == null || total <= 0) {
            return 0;
        }
        return (int) Math.round(used * 100.0 / total);
    }

    /** null → 0（新仓 store 容量列允许为空）。 */
    private int nvl(Integer v) {
        return v != null ? v : 0;
    }

    /** TA/WK 工作台 gate（19 §4；20 §2 多仓收敛：该仓 TA 或 WK，无上下文回退登录态推导）。 */
    private Long requireTaOrWk(Long userId) {
        Long tenantId = tenantScopeAuthSupport.scopedTaOrWkTenantId(userId);
        if (tenantId == null) {
            if (authService.hasRole(userId, "WE")) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004);
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅仓库管理员/库管员可查看工作台");
        }
        return tenantId;
    }
}
