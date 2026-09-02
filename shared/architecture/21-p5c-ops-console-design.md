# 21 P5-C「OPS 平台运营控制台」设计

> 状态：✅ 已实现 · 2026-09-02（口径 D-OPS-1~6 全采纳，product/15；实现见下；全量回归 493 全绿）
> 范围：`/ops/dashboard` 占位页 → 真实接口（P5-C 收官，最后一个纯占位工作台）
> 关联：19-p5c-dashboard-design.md（TA 工作台同款先例）、15-p5c-ops-console.md（口径）、02-user-stories.md（US-OPS 各页）、05-secure-coding-guardrails.md（S4 越权）、15-pii-hardening-v2.md

## 1. 现状结论

| 项 | 现状 |
|---|---|
| `/ops/dashboard` | 路由指向 `PlaceholderDashboard.vue`（纯占位）；OPS 登录默认落此页（stores/auth.ts） |
| 后端 | OPS 端点零 dashboard 存量；但各计数数据源实体/列表端点均已存在（/ops/blacklist、/ops/arbitrations、/ops/announcements、/admin/tenants） |
| OPS 角色语义 | 无租户上下文 → **平台级查询天然不进 TenantLine**（arbitrations 虽有 tenant_id 但属跨租户无上下文先例，同 OpsArbitrationController） |
| gate 先例 | BlacklistServiceImpl.requireOpsRole = `authService.hasRole(userId,"OPS")` else **42002**（`PERMISSION_ROLE_002`「平台操作仅限平台运维角色」） |

## 2. 契约

`GET /api/v1/ops/dashboard`，requireOps，无入参：

```json
{
  "platform": { "activeTenantCount": 0, "wholesalerBindingCount": 0, "activeBlacklistCount": 0 },
  "pending": { "pendingTenantAudits": 0, "pendingComplaints": 0, "draftAnnouncements": 0 },
  "today": { "newTenantToday": 0, "newComplaintsToday": 0 }
}
```

## 3. 字段口径（全平台，15 §3 拍板）

| 字段 | 口径 | 数据源（G-S1/G-S2：tenant 域直连 mapper，跨域经 Service 出口） |
|---|---|---|
| `platform.activeTenantCount` | tenants `status=ACTIVE`（营业仓库数，自助+代建合计） | tenant 域 mapper 直连 |
| `platform.wholesalerBindingCount` | wholesaler_applications `status=APPROVED`（**绑定数**，多仓计 N） | tenant 域 mapper 直连 |
| `platform.activeBlacklistCount` | blacklist `status=ACTIVE` | tenant 域 mapper 直连 |
| `pending.pendingTenantAudits` | tenants `status=PENDING`（租户审核队列） | tenant 域 mapper 直连 |
| `pending.pendingComplaints` | arbitrations `bizType=OUTBOUND_COMPLAINT` ∧ `status=PENDING`（客诉仲裁队列） | **新增** document 域出口 |
| `pending.draftAnnouncements` | announcements `status=DRAFT`（草稿待发布） | **新增** notify 域出口 |
| `today.newTenantToday` | tenants `created_at ≥ 今日 0 点` | tenant 域 mapper 直连 |
| `today.newComplaintsToday` | arbitrations `bizType=OUTBOUND_COMPLAINT` ∧ `created_at ≥ 今日 0 点` | **新增** document 域出口 |

口径钉死：
- bill_disputes 归 ST（resolver_user_id），**不进 OPS 队列**（平台仲裁属 P5-D/D62）。
- 入库异议（INBOUND_DISPUTE）归 TA 审批中心，不进 OPS 待办。
- capacity 快照 job（US-TA-10）独立能力，不进控制台。
- 越权码：非 OPS → **42002**（对齐黑名单 requireOpsRole；与 TA dashboard 的 42001/42004 语义不同，OPS 用 42002）。

## 4. 后端改动清单

1. **tenant 域**（聚合驻留域，同 TenantDashboardServiceImpl 先例）
   - 新建 `OpsDashboardVo`（PlatformVo/PendingVo/TodayVo 三层 builder）
   - 新建 `OpsDashboardService` + Impl + `OpsDashboardController`（GET `/ops/dashboard`）
   - Impl gate：`requireOps(userId)`（照 BlacklistServiceImpl，42002）；tenant 域 5 个计数 mapper 直连：
     `TenantMapper.selectCount(status)` ×3（ACTIVE / PENDING / 今日）、`WholesalerApplicationMapper.selectCount(status=APPROVED)`、
     `BlacklistMapper.selectCount(status=ACTIVE)`
2. **document 域**
   - `ArbitrationService.countPendingForOps()` + Impl（照 countPendingForTa，条件 `bizType=OUTBOUND_COMPLAINT` + `status=PENDING`，无租户维度）
   - `ArbitrationService.countComplaintsCreatedToday()` + Impl（`bizType=OUTBOUND_COMPLAINT` + `created_at ≥ 今日 0 点`）
   - 两方法均无 gate（由 OpsDashboardService 统一 gate；对称 countPendingForTa 先例）
3. **notify 域**
   - `AnnouncementService.countDrafts(Long opsUserId)` + Impl（**带 requireOps**——与 AnnouncementServiceImpl 全部公开方法签名一致，内部照现有 requireOps 42002；`status=DRAFT` count）

## 5. 前端改动清单

- 新建 `views/ops/Dashboard.vue`：OPS shell（顶栏 + 左侧菜单）复用；三块 KPI 卡 + 待办卡片点击跳对应页；数据源 `opsApi.getDashboard()`
- 路由 `/ops/dashboard` 改指 `Dashboard.vue`（移除 PlaceholderDashboard 引用）
- `api/ops.ts` 增 `getDashboard()`；`api-types/ops.ts` 增 `OpsDashboardResponse`
- **菜单统一（D-OPS-6）**：OPS 4 页 menus 补齐为 5 项——运营控制台 / 租户审核 / 黑名单 / 公告管理 / 客诉仲裁（TenantAudit.vue 补公告项；Dashboard.vue 用同一份）

## 6. 测试

`OpsDashboardScenarioTest`（真实 HTTP + OPS 登录态，风格照 TenantScenarioTest 系列）：
1. OPS-01 平台规模：自助注册 1 仓 + OPS 代建 1 仓（审核过）→ activeTenantCount=2；REJECTED 不计
2. OPS-02 绑定数：WA 入驻 A 仓 + 同账号入驻 B 仓 → wholesalerBindingCount=2（绑定口径）
3. OPS-03 待办-租户：PENDING 租户 1 → pendingTenantAudits=1；OPS 审核后归 0
4. OPS-04 待办-客诉：WA 对出库单投诉 → pendingComplaints=1；OPS 裁决后归 0
5. OPS-05 待办-公告：建草稿 1 → draftAnnouncements=1；发布后归 0
6. OPS-06 黑名单计数：加黑 ACTIVE 1 → activeBlacklistCount=1；解除后归 0
7. OPS-07 今日动态跨日隔离：今日新仓 1 计入、昨日新仓不计入
8. OPS-S2 越权：TA/WA/ST/WK 登录 → 42002

## 7. 不做（本轮）

- OPS 运营 BI（逐仓营收/批发商活跃/趋势图等）——口径不在本期
- bill_disputes 平台仲裁（P5-D/D62）
- capacity 快照 job（US-TA-10 待产品定刷新频率/算法，挂待环境档）
- PlaceholderDashboard.vue 删除（其他占位路由仍引用；仅移除 OPS 引用）

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-02 | 首版（15 口径拍板后）；定稿 3 层 Vo 契约 + 跨域出口边界 + 42002 越权码 |
| v2 | 2026-09-02 | 已实现：后端 `OpsDashboardServiceImpl`（tenant 域聚合 + requireOps 42002）+ `OpsDashboardVo`/`OpsDashboardController`；document 域 `ArbitrationService.countPendingForOps/countComplaintsCreatedToday`；notify 域 `AnnouncementService.countDrafts`；`OpsDashboardScenarioTest` 7 例（基线差分规避全局统计跨用例叠加）全绿；前端 `views/ops/Dashboard.vue`（OPS 5 项菜单统一锚点 + 待办跳转）+ 路由/api 契约；回归 493 全绿 |
