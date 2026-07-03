# 07 · 老板多仓设计（Owner Multi-Warehouse）

> 编写：架构师 Agent · 2026-07-03
> 依据：现状代码实测（`tenant`/`account`/`common`/`storefront` 模块 + `MultiWarehouseScenarioTest` 4 用例通过）
> 定位：据**后端已实现**（main 分支）反向描述设计——概念、数据模型、关键流程、鉴权隔离、接口契约、边界与未做项。
> 范围（用户已定）：① TA 登录后**直接建仓**（无需再走审核入驻的自助流程）；② 顶栏**仓库切换器**。
> 关联：[08-service-split-and-evolution.md](08-service-split-and-evolution.md)（模块化单体/数据自治）、[06-phase1-wholesaler-selling-plan.md](06-phase1-wholesaler-selling-plan.md)、[../product/01-roles-and-permissions.md](../product/01-roles-and-permissions.md)、[05-secure-coding-guardrails.md](05-secure-coding-guardrails.md)

---

## 0. 一句话结论

「老板多仓」= **一个账号（user）持有多条 `TA` 角色记录（`user_roles` 多行，各绑一个 `tenant_id`）**。仓库 = 租户（`tenant`）= 店铺（`store`）仍是 **1:1:1**，与单仓完全一致；多仓**不引入任何新表**，复用现有 `users / user_roles / tenants / stores / tenant_settings` 结构。前端顶栏切换器只是选一个当前 `tenantId`，每次请求带 `X-Tenant-Id`，由 `TenantInterceptor` 校验归属后交给 MyBatis-Plus `TenantLine` 做行级隔离。

---

## 1. 概念模型

| 概念 | 说明 |
|---|---|
| 账号（老板） | 一个 `users` 行。手机号唯一，登录后凭 Sa-Token 得 `userId`。 |
| 仓库 | 一个 `tenants` 行（含 `tenant_simple_code` 简码、`status`）。 |
| 店铺 | 一个 `stores` 行，`tenant_id` 与仓库 1:1（建仓时自动建默认 store + `tenant_settings`）。 |
| 多仓归属 | `user_roles` 中该 `userId` 下**多条 `role=TA` 记录**，每条 `tenant_id` 指向一个仓库。 |

> **老板多仓 ≡ 同一 `user_id` 下的多条 `(role=TA, tenant_id=X)` 角色行。** 无「主仓/子仓」层级，各仓平权，数据互相隔离。

与角色矩阵一致：仓库 = 租户 = 店铺 1:1:1（见 [../product/01-roles-and-permissions.md](../product/01-roles-and-permissions.md) §1）。多仓仅是「TA 这一角色被复制多份、各绑不同租户」，不新增角色类型。

---

## 2. 数据模型与 ER 说明

**不新增表**。关键结构（实测自 `03-database-schema.sql`）：

```
users (1) ────< user_roles (N, role=TA) >──── (1) tenants (1) ─── (1) stores
                                                        │
                                                        └─── (1) tenant_settings
```

- **`user_roles`**（归属 `account` 域）
  - 唯一键 `uk_user_role_scope (user_id, role, tenant_id, wholesaler_id)` → **同一账号可有多条 `(TA, 不同 tenant_id)`**，这正是多仓成立的结构基础。
  - `status`：`ACTIVE` / `DISABLED` / `REMOVED`；`priority`：TA=10（登录路由用）。
  - `tenant_id` 可空（OPS/RT 为 NULL；TA 注册壳阶段亦可短暂为 NULL，见 §3）。
- **`tenants`**（归属 `tenant` 域）：`tenant_simple_code`（4 位随机码，唯一键 `uk_simple_code`）、`status`（`PENDING`/`ACTIVE`/`REJECTED`）、`created_by_ops`。
- **`stores`**：`tenant_id` 1:1，唯一键 `uk_tenant_id_name (tenant_id, name)`。
- **`tenant_settings`**：`tenant_id` 1:1（`uk_tenant`）。

> 数据自治边界（见 [08](08-service-split-and-evolution.md)）：`user_roles` 归 `account` 域，`tenant` 域不直连该表，所有角色读写经 `AuthService`（G-S1/G-S2）。

---

## 3. 关键流程（据实现）

### 3.1 建仓 `createWarehouse`（本期入口①：TA 登录后直接建仓）

`TenantServiceImpl.createWarehouse(userId, TenantApplyDto)`：

1. **资格校验**：`authService.hasRole(userId, "TA")` —— 仅**已是 TA（老板）**可再建仓；纯员工/无 TA 角色 → `42001`（`PERMISSION_ROLE_001`）。
2. **建租户**（`createTenant(...)`，`createdByOps=false`）：
   - 新 `tenant`，`status=PENDING`；
   - `tenant_simple_code` = `"CC" + 4 位随机字母数字`，撞 `uk_simple_code` 唯一索引则换码重试（上限 5 次，超限抛 `90001`）——F6 修复，杜绝回绕撞码 / 先查后拼的 TOCTOU；
   - 自动建默认 `store`（`status=PENDING`、`capacity_visibility=PUBLIC`、`capacity_precision=TIER`）+ 默认 `tenant_settings`（批次关、QTY 计费、临期 30 天等）。
3. **绑角色**：`authService.ensureTenantRole(userId, "TA", newTenantId, userId)` —— 为该账号**新增一条 `(TA, newTenantId, ACTIVE)`**（已存在则跳过）。这是「多仓」在数据上的落点。
4. **返回** `{ tenantId, simpleCode, status }`。

> 与「自助入驻」`apply` 的区别：`apply` 走「申请→OPS 审核」并含 D-16「注册壳复用（不新建第二租户）」逻辑；而 `createWarehouse` 是**老板已登录后主动再开一仓**，直接建 `PENDING` 租户并绑角色，不进审核申请队列（`tenant_applications`）。二者复用同一 `createTenant` 私有方法。

### 3.2 列仓 `listMyWarehouses`（本期入口②数据源：顶栏切换器）

`TenantServiceImpl.listMyWarehouses(userId)`：

1. `authService.listActiveRoles(userId)` 取该账号全部 `ACTIVE` 角色；
2. 过滤 `role=TA && tenant_id != null`，`distinct` 得 `tenantId` 列表；
3. 逐个 `tenantMapper.selectById` 取概要，组装 `WarehouseVo[]`（`tenantId / name / simpleCode / status`）。

> 该接口**不依赖 `X-Tenant-Id`**——它要跨所有仓聚合，故按 `userId` 从角色表解析仓集合，而非当前租户上下文。

### 3.3 顶栏仓库切换器（前端交互 + 后端隔离链路）

- **前端**：登录后调 `GET /warehouses` 填充切换器；用户选中的 `currentTenantId` 存内存 + `localStorage` 持久化；此后**每个业务请求都带 `X-Tenant-Id: <currentTenantId>`**。
- **后端 `TenantInterceptor.preHandle`**：
  - 取登录 `userId` → `authService.listActiveRoles` 得可信角色集；
  - 若带 `X-Tenant-Id`：**校验该 tenantId 确在用户绑定集合内**，否则 `42101`（`PERMISSION_TENANT_001`，记 warn 日志）；通过则写入 `TenantContext`，并把主角色修正为该租户下角色；
  - 未带头且只有唯一非空绑定租户 → 用它；多仓且未指定 → **不强选**，留给业务接口按 `userId` 解析（如 `getMyStore` 用 `findBoundTenantId`）。
- **`MybatisPlusConfig` TenantLine**：`TenantContext.getTenantId()` 非空时，对白名单业务表（`stores/tenant_settings/wholesalers/skus/inventories/stock_movements/inbound_requests/inquiry_requests/outbound_requests`）自动追加 `tenant_id=?`，实现行级隔离兜底。

> 隔离链路一句话：**前端选仓 → 头带 tenantId → 拦截器验归属 → 上下文 → SQL 自动加租户条件**。测试 `MW-S1-02` 验证：以 `X-Tenant-Id=仓A` 查商户列表只见仓A商户，不含仓B。

---

## 4. 鉴权与隔离规约

| 关注点 | 实现 | 失败码 |
|---|---|---|
| 谁能建仓 | 仅具 `ACTIVE TA` 角色者（`hasRole(userId,"TA")`）；非 TA（如 OPS）拒绝 | `42001` |
| `X-Tenant-Id` 可信性 | **不信裸传**——拦截器用登录态角色集校验归属（G-2.1） | `42101` |
| 行级隔离兜底 | TenantLine 对白名单表自动注入 `tenant_id`，防跨仓越权查询 | —— |
| 跨域读 `user_roles` | 一律经 `AuthService`（`account` 域出口），`tenant` 域不直连 | —— |

**核心安全立场**：`tenantId` 的唯一可信来源是**登录用户的角色绑定**，`X-Tenant-Id` 只是多仓用户的「选择器」，必须二次校验归属。派发实现时须遵 [05-secure-coding-guardrails.md](05-secure-coding-guardrails.md) 并过自检卡。

---

## 5. 接口契约

| 方法 | 路径 | 入参 | 出参 | 鉴权 |
|---|---|---|---|---|
| 建仓 | `POST /api/v1/tenant/warehouses` | `TenantApplyDto`（`name`✱、`contactPhone`✱、`legalName`/`licenseNo`/`licenseUrl`/`addressText`/`lng`/`lat` 可选） | `{ tenantId, simpleCode, status }` | 登录 + `TA` 角色 |
| 列仓 | `GET /api/v1/tenant/warehouses` | 无（按登录 `userId`） | `WarehouseVo[]`：`{ tenantId, name, simpleCode, status }` | 登录 + `TA` 角色 |

约定：
- `tenantId` 序列化为 **String**（雪花 ID 防前端精度丢失）。
- `status` 取值 `PENDING` / `ACTIVE` / `REJECTED`。
- `createWarehouse` 新建仓恒为 `PENDING`（`createdByOps=false`）。
- 建仓入参复用 `TenantApplyDto`（与 `apply` 同 DTO），`lng∈[-180,180]`、`lat∈[-90,90]` 有 Bean Validation 校验。

---

## 6. 边界与未做项（观察点）

| 项 | 现状（实测） | 处置 |
|---|---|---|
| **新建仓 `PENDING` 能否被 RT 扫码卖货** | ⚠️ `StoreFrontServiceImpl.resolve(storeId/code)` **进店解析不校验 `tenant.status`**——`PENDING` 仓的简码/storeId 可被 RT 解析进店。实际「卖货」入口另有闸门：进店页仅聚合**店内 `ACTIVE` 批发商 + 在售 SKU**，新仓尚无 ACTIVE 批发商 → 进店页为空壳。 | **观察点，待产品/Team Lead 定调**（见 §7 待确认） |
| 仓库删除 / 停用 | 未实现（无删仓/停仓接口） | 后续 |
| 仓库转让（换老板） | 未实现（`user_roles` 无转移逻辑） | 后续 |
| 跨仓聚合报表 | 未实现（各仓数据 TenantLine 隔离，无跨仓汇总视图） | 后续 |
| 建仓数量上限 | 无上限校验（可无限建 `PENDING` 仓） | 观察点 |
| `apply` 与 `createWarehouse` 语义并存 | 二者都能产生新 `TA` 角色/租户，入口语义不同（审核 vs 直建），需产品确认对老板的呈现口径 | 待产品对齐 |

---

## 7. 本期范围 vs 后续 / 待确认

**本期（已实现）**
- ① TA 登录后 `POST /warehouses` 直接建仓（`PENDING` + 默认 store/settings + 简码唯一 + 绑 TA 角色）；
- ② `GET /warehouses` 供顶栏切换器；前端存 `currentTenantId` + `localStorage`，每请求带 `X-Tenant-Id`；
- 鉴权（仅 TA 建仓 42001）+ 归属校验（42101）+ TenantLine 行级隔离，`MultiWarehouseScenarioTest` 4 用例覆盖（建仓列表 / 多仓隔离 / 越权 / 简码唯一）。

**后续（未做，已标注）**：删仓 / 停用 / 转让 / 跨仓聚合报表 / 建仓数量上限。

**需 Team Lead / 产品确认的观察点**
1. **`PENDING` 新仓可否被 RT 扫码进店？** 当前进店解析不卡 `tenant.status`，靠「无 ACTIVE 批发商→空店」隐性兜底。是否需在 `resolve` 显式拒绝非 `ACTIVE` 租户（或允许「预览空店」）？——建议产品明确。
2. **建仓是否需要数量上限 / 二次确认？** 现无上限，老板可连开多仓。
3. **`createWarehouse`（直建 PENDING）与 `apply`（审核入驻）对老板的入口如何统一呈现？** 直建仓 `PENDING` 后由谁/何时转 `ACTIVE`（现仅 OPS `audit` 可转 ACTIVE）需产品/运营流程对齐。

---

## 变更记录

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-07-03 | v1 | 据 main 分支实现补写老板多仓设计文档（含 PENDING 仓 RT 进店观察点） | 架构师 Agent |
