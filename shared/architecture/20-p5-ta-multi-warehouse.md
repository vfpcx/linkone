# 20 P5-C「TA 一账号多仓」X-Tenant-Id 收敛

> 状态：已实现 · 2026-09-02（后端全量回归 486 例全绿）
> 范围：TA 端「按当前仓隔离」的接口全部收敛到 `X-Tenant-Id`（TenantContext），与 WA 一账号多仓同构
> 关联：19-p5c-dashboard-design.md §7（遗留项）、18-p5-design.md §P5-C、WA 多仓落地（V37 / 036d133 / 3461e58）
> 前提：TA 一账号多仓场景已确认成立（2026-09-02 用户拍板）

## 1. 现状结论

TA 端接口（tenant 域 / billing 域 / inventory 域相关）目前**全部**走 `authService.findBoundTenantId(userId, "TA")`（`AuthServiceImpl` L56-65，`LIMIT 1` 取第一条），多仓 TA 时结果**不确定**——前端已切换工作空间（X-Tenant-Id），后端仍可能落到任意仓库。

前端侧已天然就位（WA 多仓改造时一并完成，无需改动）：
- `api/http.ts`（L69-85）：**所有请求**注入 `X-Tenant-Id`（无白名单跳过），TA 端 `/tenant/**`、`/billing/**` 均覆盖
- `views/WorkspaceSwitcher.vue`：按 `role+tenantId` 渲染 entry（TA 多仓自动出现多个仓），跨仓整页 reload 让 onMounted 带新头
- `utils/currentTenant.ts`：按 userId 存当前仓，失配即忽略
- TA 端全部接口只在 TA 工作区页面（有 WarehouseSwitcher）调用

## 2. 收敛策略（契约）

所有 TA 端 gate 统一改为**「TenantContext 优先 + 该仓角色校验」**：

```
gate(userId):
  scoped = TenantContext.getTenantId()          // X-Tenant-Id 注入后由 TenantInterceptor 写入（已校验「用户确属该仓」）
  if scoped != null:
    该仓有目标角色 hasRole(userId, "TA", scoped) ? return scoped : return null
  （null 时回退逻辑：无上下文才回退 findBoundTenantId；scoped 分支失败不回退，直接交给调用方按原错误码语义拒绝）
```

要点：
- **安全关键**：TenantInterceptor 只校验「用户属该仓任一角色」，不校验「该仓 TA」。gate 必须补 `hasRole(userId, "TA", scoped)` 二次校验（`AuthService` 三参重载现成），否则跨仓角色组合（A 仓 TA + B 仓 WA）可借 X-Tenant-Id=B 用 TA 端接口操作 B 仓。
- **校验失败返回 null、不抛异常**：由调用方沿用**原错误码语义**拒绝——单仓 WE（非 TA）仍 42004、无 TA 绑定仍 50210/42001。回归中发现若在支持类内直接抛 42001 会破坏既有「WE→42004」权限矩阵（4 例回归失败，已按此修正）。
- **回退保兼容**：无 X-Tenant-Id 时回退 `findBoundTenantId`，单仓 TA、既有场景测试、内部调用全部不挂（WA 多仓先例：`listActiveWholesalerIds` 的 `tenantId=null` 恒真条件）。
- **复用同一 gate 结构**：TA / TA-or-ST / TA-or-WK 三个变体共用「scoped + 角色校验 + 回退」骨架，仅角色集不同。

## 3. 后端改造清单

统一新增私有方法（各 Service 内，或抽 `TenantScopedAuthSupport` 避免三处复制——见 §7 决策）：

| 域 | Service | 方法 | gate 现状 | 改造点 |
|---|---|---|---|---|
| tenant | `TenantServiceImpl` | `getMyStore`（L248） | `findBoundTenantId` | 换 `scopedTaTenantId` |
| tenant | `TenantServiceImpl` | `updateMyStore`（L259，写） | `findBoundTenantId` | 换 `scopedTaTenantId`（写操作落当前仓） |
| tenant | `TenantServiceImpl` | `getStoreQr`（L336） | `findBoundTenantId` | 换 `scopedTaTenantId` |
| tenant | `TenantServiceImpl` | `generateInviteCode`（L355，写） | `findBoundTenantId` | 换 `scopedTaTenantId` |
| tenant | `TenantServiceImpl` | `createEmployeeInvite` / `listEmployeeInvites` / `revokeEmployeeInvite` | `requireTaRole`（L884） | `requireTaRole` 换 `scopedTaTenantId` 骨架 |
| tenant | `TenantDashboardServiceImpl` | `dashboard`（gate `requireTaOrWk` L139） | TA→WK 依次 `findBoundTenantId` | 换 `scopedTaOrWkTenantId`（该仓 TA 或 WK） |
| billing | `BillingServiceImpl` | 账单总览及 `requireTa`（L1166） | `findBoundTenantId` | 换 `scopedTaTenantId` |
| billing | `BillingServiceImpl` | `requireStOrTa`（L1178） | TA→ST 依次 `findBoundTenantId` | 换 `scopedTaOrStTenantId`（该仓 TA 或 ST） |
| billing | `BillingRuleServiceImpl` | `requireTaRole`（L266）/ `requireStOrTa`（L278） | `findBoundTenantId` | 同上两个变体 |
| inventory | `BatchServiceImpl` | `toggle`（L110，写） | `findBoundTenantId` | 换 `scopedTaTenantId` |

> 注：`TenantInterceptor` 未带头且仅绑 1 仓时自动 `setTenantId`（L78-89）——单仓 TA 即使前端带 X-Tenant-Id 也走 scoped 分支，行为一致。

## 4. 前端现状与适配

**无需改动**（已核实）：
- http.ts 全量注入 X-Tenant-Id ✓
- TA 工作区有 WarehouseSwitcher + 跨仓 reload ✓
- 登录/切换器对多仓 TA 天然支持（entry 按 tenantId 区分）✓

**已知缺口（本轮不做）**：
- ST 工作区无 WarehouseSwitcher：ST 若一账号多仓，工作区内无切换入口。当前 ST 多为 TA 兼岗（同仓），随 WorkspaceSwitcher 进入时 currentTenant 正确；纯 ST 多仓留待 ST 多仓拍板后统一补。
- 登录响应 `tenantInfo` 仅代表默认仓（后端语义不变），顶栏店名由 warehouseStore 校正。

## 5. 测试计划

新增 `TenantMultiWarehouseScenarioTest`（tenant 域，风格照 TenantScenarioTest）：
1. **MTA-S1-01** 多仓 TA 带 X-Tenant-Id=A → dashboard/店铺设置/账单总览落到 A 仓（数据隔离）
2. **MTA-S1-02** 多仓 TA 带 X-Tenant-Id=B → 落 B 仓，与 A 仓数据互不可见
3. **MTA-S2-01** 跨仓角色组合防越权：A 仓 TA + B 仓 WA，带 X-Tenant-Id=B 调 TA 端接口 → 拒绝
4. **MTA-S3-01** 单仓 TA 无 X-Tenant-Id → 回退 findBoundTenantId 正常（兼容回归）
5. **MTA-S4-01** 写操作（updateMyStore / generateInviteCode / batch toggle）落当前仓
6. 回归：既有 TA 相关场景测试（TenantScenarioTest / TenantDashboardScenarioTest / billing / BatchServiceTest）全绿

## 6. 不做（本轮）

- ST 一账号多仓（前端切换入口，需 ST 多仓拍板）
- `TenantServiceImpl.apply`（注册建仓流程，识别 PENDING 租户，非当前仓语义）
- OPS 管理端接口、公开目录、`/tenant/capacity`（传参接口）——不属 TA 当前仓隔离
- capacity 快照 job（US-TA-10，已挂「待环境」档）

## 7. 待拍板

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 角色校验放行语义 | scoped 分支校验失败**返回 null**（不抛异常），调用方沿用原错误码语义（WE→42004、无 TA 绑定→50210/42001）。已实现：跨仓越权在 tenant 端接口（getMyStore）为 50210、billing 端为 42001 |
| 2 | 代码组织 | 已抽 `TenantScopeAuthSupport`（common/tenant，3 个变体 + 4 份重复 gate） |
| 3 | 文档状态 | 实现后本文档状态改「已实现」+ roadmap v2.6 更新 |
