# 19 P5-C「各角色 Dashboard 真实接口」设计

> 状态：已实现（TA 工作台真实接口） · 2026-09-02 · 后端 7 用例绿 / 前端 typecheck 绿
> 范围：TA 租户工作台真实接口（当前唯一纯 mock 的 Dashboard）。OPS 控制台/RT 首页已为真实接口，本轮不动。
> 关联：18-p5-design.md §P5-C、02-user-stories.md US-TA-10、15-pii-hardening-v2.md（出参脱敏规约）

## 1. 现状结论

| 角色 | 页面 | 数据来源 | 结论 |
|---|---|---|---|
| TA | `/ta/dashboard` | 前端 `mocks/dashboard.ts` 纯 mock | **本轮改造** |
| OPS | `/ops/dashboard` | 占位页（无数据） | 暂缓（需 OPS 指标口径拍板） |
| ST | `/st/dashboard` | 组合真实接口 | 已真实 |
| WA/WE | 首页=询价列表 | 真实接口 | 已真实 |
| WK | 复用 TA dashboard | — | 随 TA 一并真实 |
| RT | 进店浏览页 | 真实接口 | 已真实 |

前端已定义 `tenantApi.getDashboard()`（GET `/tenant/dashboard`，contract `TenantDashboardResponse`），后端未实现——缺口明确。

## 2. 契约（对齐前端 `api-types/tenant.ts`）

`GET /api/v1/tenant/dashboard`，requireTa，无入参：

```json
{
  "storeName": "string",
  "kpi": { "pendingInbound": 0, "pendingCount": 0, "pendingClearance": 0, "pendingDispute": 0 },
  "capacity": { "usedQty": 0, "totalQty": 0, "usedPallet": 0, "totalPallet": 0, "utilization": 0, "visibility": "PUBLIC", "snapshotAt": "ISO" },
  "today": { "inboundCount": 0, "outboundCount": 0, "inquiryCount": 0, "expiringBatches": 0 },
  "batchEnabled": false
}
```

## 3. 字段口径（TA 视角，全部按当前租户）

| 字段 | 口径 | 数据源（G-S1/G-S2：跨域经 Service） |
|---|---|---|
| `storeName` | tenant.name | TenantService.getTenantName（tenant 域内直查） |
| `kpi.pendingInbound` | `wholesaler_applications` status=PENDING | tenant 域 mapper（域内直连） |
| `kpi.pendingCount` | `count_sheets` status=PENDING_APPROVAL | **新增** CountSheetService.countPendingApprovalForTenant（照抄 Clearance 出口） |
| `kpi.pendingClearance` | `expiry_clearances` status=PENDING_APPROVAL | ClearanceRequestService.countPendingApprovalForTenant（现成） |
| `kpi.pendingDispute` | `arbitrations` status=PENDING（= TA 审批中心角标口径，listForTa 不分 bizType） | **新增** ArbitrationService.countPendingForTa |
| `capacity` | 最新 `capacity_publish` 快照；无快照回退 store 默认（used=0、total=store 容量） | tenant 域 mapper 直查（域内） |
| `capacity.utilization` | used/total×100（快照缺失时 0；避免依赖无写入者的表值域） | 计算 |
| `today.inboundCount/outboundCount/inquiryCount` | 今日（0 点起）created_at 创建的对应单据总数（不限状态） | **新增** DocumentStatsService.todayCounts（document 域出口） |
| `today.expiringBatches` | 批次启用时：expiry_date ≤ 今日+3 天 且 status ∉ 终态(CLEARED/CLOSED/SOLD_OUT) 的批次数 | **新增** BatchService.countExpiringWithinDays（inventory 域出口） |
| `batchEnabled` | tenant_settings.batch_enabled | TenantService.getBatchConfig（tenant 域） |

口径说明：
- TA 工作台为 TA 本人查看，容量展示**精确值**（不做 TIER 精度脱敏；公示脱敏仅作用于对外公开接口 getCapacity，ADR-009 语义不变）。
- `expiringBatches` 基于 expiry_date 计算而非状态：02:00 推算/标记 job 仅覆盖阈值内批次，3 天窗口可能窄于阈值，故用日期口径直接命中「3 天内到期未处置」。
- 今日单据计数含各状态（含草稿在途），贴近「今日新增业务量」语义；跨日回看由单据列表页承担。

## 4. 后端改动清单

1. **document 域**
   - `CountSheetService.countPendingApprovalForTenant(Long)` + Impl（照抄 ClearanceRequestServiceImpl L371-377）
   - `ArbitrationService.countPendingForTa(Long)` + Impl（照抄 countPendingForWholesaler，条件改 tenant_id）
   - 新建 `DocumentStatsService` + Impl + `TodayCountsDto`：今日三单计数（InboundRequest/OutboundRequest/InquiryRequest，`tenant_id` + `created_at ≥ 今日 0 点`）
2. **inventory 域**
   - `BatchService.countExpiringWithinDays(Long, int)` + Impl：`expiry_date ≤ now+days` 且 status ∉ (CLEARED, CLOSED, SOLD_OUT)
3. **tenant 域**
   - 新建 `TenantDashboardVo`（含 KpiVo/TodayVo）、`TenantDashboardService` + Impl（gate=**requireTaOrWk**：WK 回 TA 台复用工作台，写法对齐 requireStOrTa；WE 42004、其余 42001）、`TenantDashboardController`（GET `/tenant/dashboard`）

## 5. 前端改动清单

- `ta/Dashboard.vue`：删除 mock 分支，`onMounted` 调 `tenantApi.getDashboard()` 填 storeName/kpi/capacity/today/batchEnabled；mock 文件保留（其他页面可能引用）但移除 dashboard 段引用。
- 空态处理：`batchEnabled=false` 时临期卡片隐藏（对齐 mock 的 v-if）。

## 6. 测试

`TenantDashboardScenarioTest`（tenant 域，风格照 TenantScenarioTest）：
1. DB-S1-01 TA 正常：新仓全零回退（storeName + capacity 默认 + batchEnabled=0）
2. DB-S1-02 待审入驻申请计数：WA 申请→1、TA 审核通过→0
3. DB-S1-03 容量快照精确值：used/total 精确返回 + utilization=used/total×100（30）
4. DB-S1-04 临期窗口边界：3 天整（≤）计入、4 天不计入、CLEARED 不计入、批次未启用恒 0
5. DB-S1-05 今日计数跨日隔离：今日 1 单计入、昨日 1 单不计
6. DB-S2-01 WK 兼岗：邀请码注册 WK → dashboard 成功且同仓数据
7. DB-S4-01 越权：WE→42004、OPS→42001

## 7. 不做（本轮）

- OPS 控制台指标（口径待拍板）
- capacity 快照生成 job（US-TA-10 独立能力，需产品定刷新频率/算法）
- 多仓 TA 的 X-Tenant-Id 收敛（与 TA 端账单总览同口径，后续统一收敛）
