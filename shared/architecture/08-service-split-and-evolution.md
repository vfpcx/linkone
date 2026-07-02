# 仓储云 · 服务拆分设计与演进路线（v1）

> 编写：Team Lead / 架构 · 2026-07-02
> 依据：现状代码实测（1 pom / 1 Spring Boot 应用 / 1 库 cangchu_dev / 7 域模块）+ 用户给定的 4 条拆分原则
> 定位：① 明确现状与目标；② 把"守模块边界为将来拆分做准备"固化为**现阶段强制治理规约**（后续所有开发照此执行）；③ 给出何时/如何演进到微服务。

---

## 0. 一句话结论

当前是**模块化单体（Modular Monolith）**，模块边界按 DDD 领域划分（符合原则 #1/#4），但**未满足微服务数据自治（#3）与低耦合 API 化（#2）**——这是 MVP 阶段的**正确选择**。策略：**现在不拆服务，但严守模块边界防腐化**，待业务/流量验证后沿既定边界抽取。

---

## 1. 现状（实测）

- 部署单元：1 个 `pom.xml`、1 个 Spring Boot 应用 `CangchuApplication`、1 个数据库 `cangchu_dev`。
- 域模块（`com.cangchu.*`）：`account` `tenant` `product` `inventory` `storefront` `document` `common`。
- 模块间：**进程内 Java 方法调用**（如 `document` 直接 import 调用 `inventory/product/tenant/storefront` 的 service 与 mapper），共享同一库表。

对照用户 4 原则：

| 原则 | 现状 | 达标 |
|---|---|---|
| #1 单一职责 | 按域分模块，各管一类业务 | ✅ 模块级 |
| #4 DDD 业务边界划分 | 按业务能力分，非技术层 | ✅ |
| #3 数据自治（红线） | 一库共享表，跨模块直连对方 mapper/表 | ❌ |
| #2 高内聚低耦合 | 内聚够；模块间编译期耦合、非 API | 🟡 |

---

## 2. 数据归属（域 → 表）—— 数据自治的基准线

明确每张表**唯一归属**一个域；这是防腐化与将来拆库的依据。

| 域（未来服务） | 拥有的表 |
|---|---|
| **account** 账号 | users、user_roles、login_sessions、sms_codes、password_history、invite_codes（员工/入驻码）|
| **tenant** 仓库 | tenants、stores、tenant_settings、tenant_applications、capacity_publish |
| **wholesaler**（现寄居 tenant 模块）| wholesalers |
| **product** 商品 | skus（未来含 spu、定价/专属价）|
| **inventory** 库存 | inventories、stock_movements |
| **document/trade** 单据 | inbound_requests、inquiry_requests、inquiry_items、outbound_requests |
| **storefront** 门店聚合 | 无自有表（只读聚合，天然是 BFF/聚合层）|
| **billing**（未实现）| bills、bill_items、billing_rules、daily_snapshots |

> `invite_codes` 归 account（账号身份域）；`wholesalers` 当前在 tenant 模块，未来可独立为 wholesaler 服务。

---

## 3. 现阶段强制治理规约（★ 后续动作照此执行）

**目标：单体内也要像微服务一样守边界，让将来能"沿缝抽取"而非"大爆炸重构"。**

- **G-S1 数据自治**：一张表只被其归属域的 mapper 读写。**禁止**跨域直接 `SELECT/UPDATE` 别人的表或注入别人的 Mapper。需要别域数据 → 调**该域的 Service 接口**（现在是进程内调用，将来平滑替换为远程 API）。
- **G-S2 依赖只走 Service 接口**：跨模块交互只依赖对方 `xxxService` 的公开方法与 VO/DTO，不依赖其 entity/mapper/内部实现。
- **G-S3 依赖方向无环**：允许 编排域(document) → 基础域(inventory/product/tenant)；**禁止**基础域反向依赖编排域，禁止循环依赖。storefront 只读向下聚合。
- **G-S4 编排集中**：跨域业务编排（如"确认询价→建出库单→扣库存"）放在明确的编排域（document），基础域只暴露原子能力（`deductStock` 等）。编排域负责事务/一致性。
- **G-S5 事务边界显式**：单体内用本地 `@Transactional`；但**编排逻辑要写成"可补偿"的顺序**（先做可回滚的、幂等的），为将来 Saga 化留形。confirmByWa 的"状态 CAS + 逐 item 扣减 + 失败整体回滚"已是这种形态。
- **G-S6 契约即接口**：域间 Service 方法签名视为"内部 API 契约"，变更需同步文档（api-contract）+ 通知下游域，等同对外接口管理。
- **G-S7 共享内核最小化**：`common` 只放真正通用的（错误码、鉴权、雪花 ID、租户上下文、Redisson 封装），不放业务逻辑。

> 审查（code-review Agent）新增一项：**是否存在跨域直连表/注入他域 mapper（违反 G-S1/G-S2）** → 视为架构债，🟠 及以上。

---

## 4. 当前架构债（拆分前需偿还的跨域直连点）

从现状代码识别（示例，非穷举）：

| 位置 | 现状 | 目标（拆分时） |
|---|---|---|
| `document.InquiryServiceImpl` | 直接 import 调用 `InventoryService`/`SkuMapper`/`TenantMapper`/`StoreFrontService` | 只经各域 Service 接口；`SkuMapper`/`TenantMapper` 直连改为 `SkuService`/`TenantService` 调用 |
| `document.InboundServiceImpl` | 调 `InventoryService.addStock` | ✅ 已是 Service 调用，保持 |
| `storefront` | 聚合读 wholesaler/sku/inventory | ✅ 走各域 Service（已基本合规），保持 |
| 各域 requireXxRole | 直接查 `user_roles`(account 表) | 抽 account 的 `AuthService.hasRole(...)` 统一入口 |

**当务之急（不拆也该做）**：把 document 里对 `SkuMapper`/`TenantMapper` 的**直连改为调对应 Service**，消除跨域直读表（符合 G-S1）。列为 P2 技术债。

---

## 5. 目标服务边界（未来微服务形态）

```
                   ┌───────────────┐
  RT/H5/小程序 ──▶ │ API 网关 / BFF │ ──▶ storefront(聚合)
                   └───────────────┘
   ┌──────────┬──────────┬───────────┬───────────┬──────────┬──────────┐
   ▼          ▼          ▼           ▼           ▼          ▼
 account    tenant   wholesaler   product    inventory   trade(document)   [billing]
 账号鉴权   仓库/店铺  批发商入驻   SKU/定价    库存/流水    入库/询价/出库单据   计费结算
 (own DB)   (own DB)  (own DB)    (own DB)    (own DB)     (own DB)          (own DB)
```

每服务：独立库、独立部署、对外 REST/gRPC + 内部事件。storefront 作为读侧聚合（BFF），不拥有数据。

---

## 6. 跨服务一致性（拆分后的关键改造）

单体里的本地事务，拆服务后变跨服务，用**编排式 Saga + 事件**：

- 例：`confirmByWa`（trade 服务）需要 inventory 服务扣库存。
  - 单体（现在）：一个 `@Transactional`，失败整体回滚。
  - 微服务（将来）：trade 本地把 inquiry 置 CONFIRMED（状态 CAS 幂等）→ 调 inventory 扣减 → 成功则建 outbound、置 COMPLETED；inventory 扣减失败 → **补偿**：回滚 inquiry 到 PENDING、不建 outbound。用**幂等键 + 状态机 + 补偿动作**保证最终一致。
- 库存扣减的 Redisson 锁 → 拆分后由 inventory 服务内部持有（锁 key 不变）。
- 单据号（Redis INCR）→ 由 trade 服务持有其 Redis 命名空间。

> 现在的实现（状态 CAS、幂等、失败回滚、Redisson 锁）已按"可 Saga 化"的形态写，拆分时改造成本低——这正是 §3 治理规约的价值。

---

## 7. 演进路线（分阶段，按需触发）

| 阶段 | 动作 | 触发条件 |
|---|---|---|
| **P-now（当前）** | 保持模块化单体；严守 §3 治理规约；偿还 §4 跨域直连债 | MVP 验证期 |
| **P-抽读侧** | 先抽 `storefront` 为独立读服务/BFF（无自有数据，风险最低）；只读，不涉分布式事务 | RT 流量上来、读写需分离 |
| **P-抽边界清晰域** | 抽 `account`（鉴权中心）、`product` | 团队并行开发受单体制约 / 独立伸缩需求 |
| **P-抽核心交易** | 抽 `inventory` + `trade(document)`，引入 Saga/事件 | 交易量大、需独立伸缩与容错 |
| **P-补域** | `billing`、`notification`、`wholesaler` 独立 | 对应业务成熟 |

**原则：读侧先行、边界清晰先行、核心交易最后**；每抽一个都要先做到"该域数据自治 + 上游改调 API"。

---

## 8. 现阶段结论与建议

1. **不拆**：phase-1/MVP 用模块化单体是对的，避免分布式复杂度过早引入。
2. **守边界**：立即执行 §3 治理规约，把 code-review 的架构债检查加上；这是低成本、高回报的"为将来拆分买保险"。
3. **还债**：把 §4 的跨域直连表（尤其 document→SkuMapper/TenantMapper）改为 Service 调用，列入 P2。
4. **待触发再拆**：按 §7 路线，读侧/边界清晰域先行，核心交易最后。

---

## 9. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-02 | 首版：现状(模块化单体)对照4原则 + 数据归属基准 + 现阶段治理规约 + 目标服务边界 + Saga一致性 + 演进路线 |
