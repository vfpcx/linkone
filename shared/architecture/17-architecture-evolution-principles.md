# 仓储云 · 全系统架构演进原则（北极星，v1）

> 角色：架构师 Agent（arch-w8）｜ 日期：2026-09-01｜ 基线：main 当前（08 之后全部合入）
> 性质：**一级架构文档（全系统架构基线）**——适用于所有现有与未来新增模块，不只是 W8/PII 专项；产品/开发在每次设计评审时对照
> 文档层级：17（一级·长期原则）＞ 08（二级·拆分演进操作细则，G-S1~G-S7）＞ 各功能/专项文档（09~16、18+，须声明符合 17 的哪条原则）
> 与 16 的关系：`16-pii-w8-shrink-plan.md` 是 W8 PII 收缩专项实施，**从属于**本文（它是原则 2"PII 横切豁免收口"的一次具体执行），不是并列关系

---

## 0. 一句话结论

**我们是模块化单体（Modular Monolith）**：边界按微服务终局设计，部署保持单体。四条低成本原则（限界上下文固定 / 禁跨域直连 mapper / 共享内核只放无状态设施 / 跨域交互即接口契约）**现在就必须固化，覆盖全部现有与未来模块**；**不提前上事件总线、分布式事务、拆部署单元、充血模型**——等真实触发条件出现再沿既定边界演进。

---

## 1. 路线图定位：模块化单体（全系统基线）

### 1.1 现状（代码实测，2026-09-01）

- **1 个部署单元**：1 `pom.xml` + 1 Spring Boot 应用（`com.cangchu.CangchuApplication`）+ 1 个数据库。
- **单数据源单事务**：`application-dev.yml` / `application-prod.yml` 均为单一 `spring.datasource`（dev 默认 `jdbc:mysql://localhost:3306/cangchu_dev`）；跨域编排用本地 `@Transactional`（08 §3 G-S5）。
- **域间是进程内 Java 方法调用**，共享同一库表；跨域访问由 08 §3 G-S1~G-S7 治理（历史直连债 08 §4 已于 2026-07-03 偿还 commit 1882851 + f159fa3，**但实测仍有残留**，见 §5 审计台账）。

### 1.2 边界按微服务终局设计，部署保持单体

- **终局形态**（08 §5）：account / tenant(+wholesaler) / product(+pricing) / inventory / trade(document) / storefront(BFF) / billing 各服务独立库、独立部署、REST/gRPC + 内部事件。
- **现阶段**：保持单体部署，但代码结构、依赖方向、数据归属、接口形态都按终局写——让将来能"沿缝抽取"而非"大爆炸重构"。
- **边界即缝**：每个跨域依赖点、每张表的归属，都是将来拆分的切割线。现在写错一条线，将来多付一次重构成本。**本原则适用所有域，包括未来新增域。**

### 1.3 "何时才拆"的触发条件（非预判，看真实信号）

拆分是**成本动作**（运维、网络、可观测、最终一致性），只有真实信号出现才做：

| 触发信号 | 判定口径 | 对应抽取（08 §7） |
|---|---|---|
| 真实跨域异步场景 | 有明确"本地事务表达不了、需要事件语义"的业务（如跨服务通知、对账补偿链），且有存量流量验证 | 对应域 + Saga/事件（08 §6 已留形） |
| 规模 | 单表/单域 QPS 达到独立伸缩阈值，或单体内热点资源（连接池/锁/Redis key 空间）互相挤压 | 读侧先行（storefront）→ 边界清晰域（account/product）→ 核心交易（inventory/trade） |
| 合规驱动 | 数据隔离/审计/驻留要求某个域独立物理边界（如 PII、账单） | 该域独立部署 + 独立库 |
| 团队并行 | 单仓单模块频繁合入冲突，团队规模需要独立发布节奏 | 边界清晰域先行 |

> 禁止以"将来可能需要"为由提前拆分；禁止用预判替代以上信号。

---

## 2. 四条低成本原则（固化，设计评审必查）

### 原则 1：限界上下文固定——核心边界不可侵蚀

**核心边界**（对全系统所有域生效）：
- **account**：账号身份（users/user_roles/login_sessions/sms_codes/password_history/invite_codes）。
- **tenant**：入驻与租户（tenants/stores/tenant_settings/tenant_applications/wholesalers/wholesaler_applications/wholesaler_withdraw_applications；blacklist 为平台级共享表）。
- **document**：单据交易（inbound/inquiry/outbound/return/correction/count_sheet/clearance/arbitration/notifications）。
- **pii**：敏感数据横切面（**不是业务域**，物理位置 `common.pii`），约束各域的 PII 读改写方式。
- 其余既有域（inventory/product/storefront/billing/pricing）与新域同规则；**新增域必须先声明归属表与依赖方向再开发**（§6 准入规则）。
- 证据：08 §3 G-S1~G-S4；08 §2 数据归属表；07 文档 user_roles 归属 account 的先例。

### 原则 2：禁止跨域直连 mapper——跨域只走显式服务接口；PII 横切为唯一例外并持续收口

- 跨域需要别域数据 → 调该域 **Service 公开方法 + VO/DTO**；**禁止**注入他域 `*Mapper`、`*Entity` 或直接写他域表。本原则覆盖**所有域**（不止 W8 相关）。
- **PII 横切是唯一豁免**：`common.pii` 可直连各域 mapper，其余业务代码仍禁止（此豁免是"收口"的）。
  - 证据：`PiiRevealService`（`common/pii`）类注释明确写着"PII 横切模块直连各域 mapper（G-S1/G-S2 的既定例外，其余业务代码仍禁止直连他域 mapper）"；它注入 `BlacklistMapper`/`TenantMapper`/`WholesalerApplicationMapper`（tenant）+ `InquiryRequestMapper`（document）+ `AuthService`（account）。
- **收口方向**：豁免随 PII 收缩持续收窄——W8 后 `PiiReadRouter`/`PiiShadowReader`/`PiiBackfillService` 下线（16 §6.2），豁免面收窄为 `PiiRevealService`（cipher 解密）+ `PiiCrypto`/`PiiHmacQueries`（纯工具）。将来 PII 域独立时，该豁免就变成对 `PiiService` 的正常服务调用（豁免关闭）。
- 正面先例：08 §4 债务已偿（document 对 SkuMapper/TenantMapper 直连改为 Service 调用）；14 文档 billing 经 `InventoryService.listMovementsForBilling` 只读出口取值；**已知残留**见 §5 台账第 2 项（storefront）。

### 原则 3：共享内核只放无状态设施——不放业务状态

- `common` 允许放：**租户透传**（`common.tenant.TenantContext`）、**加解密/哈希**（`common.pii.PiiCrypto`，含 KAT）、**ID 生成**（雪花 / Redis INCR）、错误码、鉴权入口、通用工具、全局无状态拦截。
- `common` 禁止放：任何业务实体、业务规则、跨域编排、状态字段。**新模块不得往 common 塞业务代码。**
- 证据：
  - `MybatisPlusConfig`：`TenantLineInnerInterceptor`（`TENANT_FILTER_TABLES` 白名单约 30 张租户业务表，无 TenantContext 不注入）+ `MetaObjectHandler` 自动填充 `createdAt/updatedAt/tenant_id`——全局**无状态**设施，可整体下沉。
  - 08 §3 G-S7：共享内核最小化。
  - `TenantContext` 在 `PiiRevealService.selectIgnoreTenant/selectInquiryIgnoreTenant` 中被 clear/set 包裹做跨租户显式查询——透传机制是横切设施，业务归属校验仍留在业务方法内。

### 原则 4：跨域交互现在就是"接口约定"——接口签名即未来 API 契约

- 域间 Service 方法签名、入参/出参 VO 视为**正式契约**：变更需同步文档（`api-contract-*.md`）并通知下游域，等同对外接口管理。
- 证据：08 §3 G-S6（契约即接口）；`api-contract-account.md` 等既有契约文档；`PiiRevealService` 注释"W8 收缩后改为 cipher 解密，**接口形态不变**"——先例：实现换、契约不变。
- 落地动作：新增/变更跨域接口时，在 PR 描述标注"契约变更"并更新对应 api-contract；接口退化（减少能力）也要走同样流程。**新模块首次发布即契约。**（§6 准入第 4 条）

---

## 3. 明确不做清单（附理由，全系统适用）

| 不做 | 理由 |
|---|---|
| 不提前上事件总线 / MQ | 单体进程内调用已能满足一致性，事件引入的是最终一致性 + 消息幂等/乱序/丢失复杂度；08 §6 已把现有编排写成"可 Saga 化"（状态 CAS + 幂等 + 失败回滚），触发条件（§1.3）出现再上 |
| 不引入分布式事务 | 单库本地 `@Transactional` 可用且正确；2PC/分布式事务带来可用性、协调器、隔离性复杂度；拆分触发后再按"幂等键 + 状态机 + 补偿"改造（08 §6 已留形） |
| 不拆部署单元 | 无触发信号（§1.3）；拆部署 = 运维/网络/可观测/发布节奏成本；storefront 读侧抽取是风险最低的第一步，但不意味着现在做 |
| 不强行充血模型 / 事件溯源 | 现有 MyBatis Plus 贫血模型 + 表驱动 + 29+ 个 Flyway 迁移长期稳定（全量 488 测试绿基线）；DDD 战术模式是手段不是目标，原则 1~4 已覆盖核心价值 |

> 每一项都可在"触发条件出现 + 单独设计评审"后推翻；在无触发前，默认不做。**新模块默认同样适用。**

---

## 4. 模块全景（全系统域地图，代码实测 2026-09-01）

| 域（package） | 职责 | 关键表 / 对象 | 状态 | 拆分终局归属（08 §5） |
|---|---|---|---|---|
| `account`（30 文件） | 账号身份、登录会话、短信码、角色权限 | users、user_roles、login_sessions、sms_codes、password_history、invite_codes | 已实现 | account 服务（鉴权中心） |
| `tenant`（62） | 入驻、租户、店铺、批发商、黑名单 | tenants、stores、tenant_settings、tenant_applications、wholesalers、wholesaler_applications、wholesaler_withdraw_applications、blacklist | 已实现 | tenant + wholesaler 服务 |
| `document`（91） | 单据交易、询价、入库/出库/退货、盘点、清库、仲裁、站内信 | inbound/inquiry/outbound/return/correction/count_sheet/clearance/arbitration/notifications 等 | 已实现 | trade 服务 |
| `inventory`（41） | 库存、流水、批次登记簿 | inventories、stock_movements、batches 等 | 已实现 | inventory 服务 |
| `product`（小） | 商品 SKU | skus | 已实现 | product 服务 |
| `storefront`（小） | RT 门店前台聚合（只读 BFF，无自有表） | 聚合 tenant/inventory/pricing/product 数据 | 已实现 | BFF/聚合服务 |
| `pricing`（15） | 定价与专属价 | customer_prices、price_change_logs | 已实现（P3） | product/pricing 服务 |
| `billing`（60） | 计费规则、快照、账单、支付流水 | billing_rules、daily_snapshots、bills、bill_items、payment_records、bill_disputes | 已实现（P4） | billing 服务 |
| `notify`（0） | 站内信/通知（目录已建） | notifications（现归 document 域承载） | 未实现 | notification 服务 |
| `common`（29） | 横切设施：config/exception/file/pii/response/tenant/util | —（无业务表） | 已实现 | 共享库/公共依赖 |

> 数据归属基准见 08 §2；本表为 2026-09-01 实测口径（billing/pricing 已从 08 的"未实现"变为已实现）。

---

## 5. 跨域依赖全图审计（存量债台账，2026-09-01 实测）

### 5.1 审计方法

对 `backend/src/main/java` 全量 grep：跨域 `import com.cangchu.<他域>.mapper.*`（G-S1 直连）与跨域 `import com.cangchu.<他域>.service.*`（G-S2 合规调用）。结果分级如下。

### 5.2 审计结果

| 级别 | 类型 | 现状 | 阻碍等级 | 说明 |
|---|---|---|---|---|
| A-1 | mapper 级直连（豁免） | `common.pii` 4 文件直连 4 域 mapper：`PiiRevealService`（tenant×3 + document×1）、`PiiShadowReader`（account×2 + pricing×1 + tenant×1）、`PiiReadRouter`（同 Shadow）、`PiiBackfillService`（+document×1） | **高** | 唯一豁免（原则 2）；W8 收口后 Shadow/ReadRouter/Backfill 下线，剩 Reveal（16 §6.2 表）；豁免关闭条件=PII 域独立时 |
| A-2 | mapper 级直连（**残留债**） | `storefront.StoreFrontServiceImpl` 直连 `tenant.mapper.StoreMapper` / `TenantMapper`（08 §4 声称"已基本合规"但实测仍有残留） | 低 | 只读 + store→tenant 映射是 RT 可信租户唯一来源；待解耦=tenant 域提供只读出口（如 `resolveStoreToTenant`） |
| B | Service 级跨域调用（G-S2 合规） | 代表：document→`InventoryService`/`SkuService`/`TenantService`/`AccountService(AuthService)`；billing→`InventoryService.listMovementsForBilling`（14 §4）；storefront→`WholesalerService`/`SkuService`/`InventoryService`/`PricingService`；各域 requireXxRole→`AuthService.hasRole`；`PiiRevealService`→`AuthService` | **低** | 已接口化，拆分时替换实现即可（契约不变，原则 4） |
| C | 同域 mapper（合规） | 各域 ServiceImpl 仅注入本域 mapper（tenant/document/inventory/account/billing/pricing/product 均实测同域） | 无 | 保持 |

### 5.3 待解耦清单（存量债台账）

| # | 债项 | 位置 | 阻碍 | 处理动作 | 责任/时机 |
|---|---|---|---|---|---|
| 1 | PII 横切直连 4 域 mapper | `common/pii/*`（4 文件） | 高 | 按 16 文档 W8 收口：下线 Shadow/ReadRouter/Backfill；Reveal 改 cipher 解密；长期目标=PiiService 出口关闭豁免 | B3/B4（进行中） |
| 2 | storefront 直连 tenant mapper | `storefront/service/impl/StoreFrontServiceImpl.java`（StoreMapper/TenantMapper） | 低 | tenant 域新增只读出口（store→tenant 解析），storefront 改走 Service | 列入技术债队列（非阻塞） |
| 3 | 跨域 Service 契约归档 | 各域 Service 接口（现状无系统化契约文档，仅 account 有 api-contract） | 低 | 按原则 4 逐步补 `api-contract-*.md`，接口变更走契约流程 | 持续 |

---

## 6. 新模块准入规则（设计评审门槛）

任何新增业务模块（功能）在开发前必须通过以下自检与边界审查，作为评审门槛（新人/新功能照此执行）：

| # | 检查项 | 依据 | 结论要求 |
|---|---|---|---|
| 1 | **边界声明**：归属哪个限界上下文？新建表归属是否唯一？（不可跨域共享表） | 原则 1 / 08 §2 | 通过或驳回 |
| 2 | **依赖声明**：依赖哪些域？是否只走他域 Service 接口（无 mapper 直连、无他域 entity 直用）？ | 原则 2 / G-S3 无环 | 通过或驳回 |
| 3 | **共享内核检查**：是否往 `common` 放业务状态/业务规则？ | 原则 3 | 必须为否 |
| 4 | **契约声明**：对外 Service 接口签名 + 入参出参 VO 文档化（`api-contract-*.md`），首个发布即契约 | 原则 4 | 必须提供 |
| 5 | **不做清单自检**：是否触碰 MQ / 分布式事务 / 拆部署 / 充血模型？若触碰，是否有 §1.3 真实触发条件？ | §3 | 无触发即默认不做 |
| 6 | **事务与编排**：编排是否集中在本域编排 Service？事务是否可补偿、可幂等（G-S5）？ | 08 §3 | 通过或驳回 |

流程：设计评审对照本表 → 架构师/Team Lead 批准 → 开发。**未过评审不得写代码。**

---

## 7. 与既有文档的关系

- **17（本文，一级）**：全系统架构演进原则，长期北极星。
- **08（二级）**：服务拆分设计与演进路线 v1（G-S1~G-S7 治理规约 + 分阶段路线），是本文的操作细则层，继续有效。
- **16（专项，从属）**：W8 PII 收缩专项实施拆解，从属本文——是原则 2"PII 横切豁免收口"的一次具体执行（Reveal 保持横切豁免但收口、ReadRouter 下线、接口形态不变），W8 验收 grep（16 §6.1 R1~R7）即原则 1/2 在 PII 面上的执行检查。
- **其余功能/专项文档（09~15、18+）**：须在开头声明"本设计符合 17 的第几条原则 / 是否触碰不做清单"。

---

## 8. 评审对照清单（每次设计评审逐项过）

1. 是否侵蚀核心边界（account/document/tenant/pii）？（新增/移动表归属、实体、依赖方向）
2. 是否新增跨域直连 mapper / 直写他域表？（原则 2；唯一豁免 = `common.pii` 且需注明收口方向）
3. 共享内核（`common`）是否被塞入业务状态/业务规则？（原则 3）
4. 跨域接口是否有新增/变更？api-contract 是否同步？下游是否知晓？（原则 4）
5. 是否触碰"不做清单"（MQ / 分布式事务 / 拆部署 / 充血模型）？触发条件是否真实、是否经单独评审？（§3 / §1.3）
6. 新增依赖方向是否无环（G-S3）？编排是否集中在编排域（G-S4）？事务是否可补偿、可幂等（G-S5）？
7. 新模块是否已过 §6 准入评审？

---

## 9. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-01 | 首版：全系统模块化单体路线图定位 + 4 条低成本原则 + 不做清单 + 模块全景地图 + 跨域依赖审计台账（含 storefront 残留债）+ 新模块准入规则 + 与 16 的从属关系 |
