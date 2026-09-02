# 25 · P5-D C2「货位功能（US-WK-05）」设计与契约定稿

> 项目：仓储云 · 波次 spec（C2 实现波，随 17-p5-c-smallpool §2 需求档案 + D-C-1~1d 拍板）
> 状态：**已实现（2026-09-02 C2 波收官）**——本文件为设计定稿 v1.0，落地实现同波完成（见 §5/§6 改动清单 → 代码）；迁移 V40 + LocationScenarioTest LV-01~06 7 例 + 全量回归 524（3 flake 环境/顺序修复见 §7）
> 版本：v1.1 · 2026-09-02
> 编写：架构师 Agent（实现前定稿）
> 依赖：17-p5-c-smallpool.md（需求档案 §2 与 DECISION D-C-1~1d）、04-api-spec、05-error-codes、13-p3b-design（批次方案 C：batches 登记簿 + FIFO 离线推算 + 开关先例）、12-p3-design（出入库状态机）、20-p5-ta-multi-warehouse（TA 一账号多仓收敛）

---

## 1. 目标与本波范围（最小可验证）

US-WK-05 货位功能三件套（D-C-1，用户 2026-09-02 拍板「记录下来后面还是要做」→ 本波执行）：

1. **仓级开关 `locationEnabled`**：默认 0=关闭——出入库登记**零货位字段/零校验**（现状零改动）；=1 时出入库登记货位必填、批次登记簿行可移库；
2. **入库登记货位**：WK 登记入库时填货位号（自由文本 ≤64），落单据 + 批次登记簿；
3. **出库登记拣出货位**：WK 登记出库/代建出库时指定拣出货位（落单明细留痕，**不做选批次/批次级扣减**——D-C-1c，batches 非记账铁律不变）；
4. **批次移库**（US-WK-05 验收）：批次登记簿行内改货位 → 更新 `batches.location` + 落 `batch_location_logs`（from/to/操作人/时间）。

不做（见 17 §7 C2）：货位主档/字典表（D-C-1b）、per-location 库存（D-C-1d）、选批次出库/批次级扣减、跨仓调拨、托盘与货位绑定、按货位盘点、移库审批流、库存可视化。

---

## 2. 关键口径定稿（草案 → 定稿差异备注）

| # | 口径 | 定稿 |
|---|---|---|
| K-1 | 货位形态 | **自由文本货位号 ≤64**（D-C-1b，不做字典/主档；如 `A-01-03`） |
| K-2 | 开关归属 | `tenant_settings.location_enabled` 默认 0；**无副作用（纯字段显隐+必填校验）→ 走通用店铺设置 `PUT /tenant/me`**（对照 batchEnabled 因「启→关冻结批次/启→开生成默认批次」副作用必须走专用 batch-toggle——50360 禁改保留不动，与本波无关） |
| K-3 | 必填校验时点 | 入库=**登记动作**（代建 `registerByWk` / 正向 `registerForwardByWk`），按**当刻** locationEnabled 判定；出库=**登记出库**（`registerByWk`）+ **WK 代建出库**（`createByWk`）两入口，按当刻开关判定。WA 提交入库申请（InboundSubmitDto）/WA 手动出库申请（OutboundSubmitDto）**不涉及**货位——放置/拣货是仓库作业语义，申请侧无货位概念 |
| K-4 | 挂载 | 单据留痕列 `inbound_requests.location` / `outbound_requests.location` + 批次货位列 `batches.location`（入库登记带货位**且有批次号**时随 `registerInboundBatch` 写入）；**不建 per-location 库存维度**（D-C-1d，计费/对账/FIFO 铁律零触碰） |
| K-5 | 移库语义 | 改 `batches.location` + 落 `batch_location_logs`（from/to/operator/created_at）；`location` 传 null=清空货位；**新旧相同=幂等空转不落日志**；移库**零记账副作用**（不动 initial_qty/remaining_qty/库存/流水） |
| K-6 | 拣货联想 | 前端基于既有 `GET /api/v1/tenant/batches?wholesalerId=&skuId=`（本波给 BatchVo 增 `location`）在库批次货位清单联想/建议；**后端不新增建议接口** |
| K-7 | 移库/日志鉴权 | 操作者=本租户 **WK 或 TA**（listForTenant 同款角色校验）；批次不存在或跨租户（TenantLine 兜底）→ 一律 **50363 BATCH_NOT_FOUND**（防枚举，沿用批次域语义） |
| K-8 | 版本号修正 | 需求档案草案写「V39/V39b」为早期假设——**实测最新迁移 V39 已被 C3 占用（customer_followups）→ 本波实际为 V40**，单文件（含建表 + 4 处加列） |
| K-9 | 货位 × 批次解耦 | 两开关独立：`locationEnabled=1` 而 `batchEnabled=0` 时——入库货位落**单据留痕**（无批次可挂）、出库拣货位落单、登记簿空故无移库对象（合法可用，非错误态）。完整闭环用例锚定「两开关皆开」组合，另覆盖「仅货位开批次关」落单场景 |

---

## 3. 数据模型（V40__p5d_c2_location.sql）

单文件标准 SQL（沿 V36/V39 先例，H2 MODE=MySQL 兼容；4 处 `ALTER TABLE ADD COLUMN` 沿 V22 先例）。

### 3.1 加列（4 处）
| 表 | 列 | 类型 | 说明 |
|---|---|---|---|
| tenant_settings | location_enabled | TINYINT NOT NULL DEFAULT 0 | 货位功能开关（默认关；读侧/必填校验统一经 TenantBatchConfigVo） |
| batches | location | VARCHAR(64) NULL | 批次货位号（登记簿行；移库/登记写入） |
| inbound_requests | location | VARCHAR(64) NULL | 入库登记货位（单据留痕；locationEnabled=1 登记必填） |
| outbound_requests | location | VARCHAR(64) NULL | 拣出货位（单据留痕；locationEnabled=1 出库登记/代建必填） |

### 3.2 `batch_location_logs`（批次移库变更记录，新表）
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id | BIGINT NOT NULL | 归属租户（TenantLine 兜底白名单） |
| wholesaler_id | BIGINT NOT NULL | 归属商户（冗余展示） |
| sku_id | BIGINT NOT NULL | 商品 SKU（冗余展示） |
| batch_id | BIGINT NOT NULL | 批次 id |
| from_location | VARCHAR(64) NULL | 原货位（首次登记后移库才可能有旧值；可空） |
| to_location | VARCHAR(64) NULL | 新货位（清空时为 NULL） |
| operator_user_id | BIGINT NOT NULL | 操作人（WK/TA） |
| created_at | DATETIME | 自动填充 |
| KEY `idx_bll_batch`(batch_id) | | 变更记录按批次查 |
| KEY `idx_bll_tenant`(tenant_id) | | |

### 3.3 注册
- MybatisPlusConfig `TENANT_FILTER_TABLES` 追加 `batch_location_logs`（tenant_id 兜底隔离；日志写入在 TenantContext 会话内 → 正常注入）。
- 实体/迁移：`Batch`/`InboundRequest`/`OutboundRequest`/`TenantSettings` 四实体加列 + 新实体 `BatchLocationLog`（inventory 域）。

---

## 4. API 契约

### 4.1 开关读写（扩展既有，**无新端点**）
- `PUT /api/v1/tenant/me` body `StoreSettingsDto` + `locationEnabled`（Integer 0/1）：`TenantServiceImpl.updateMyStore` 扩 `hasAnySwitch`/`applySettingsDto`（settings 行缺失按默认 0 补建）；**不做 D-13 禁改**（无副作用，K-2）。
- `GET /api/v1/tenant/me` → `TenantDetailVo` + `locationEnabled`（buildTenantDetail 装配）。
- `GET /api/v1/wholesaler/tenants/{tenantId}/batch-config` → `TenantBatchConfigVo` + `locationEnabled`（**各端读开关统一入口**：WA/WK/TA 前端显隐均读此；沿 P3b 收口 L-1 先例）。

### 4.2 入库登记（扩展既有 DTO，无新端点）
- `POST /api/v1/tenant/inbound` body `InboundRegisterDto` + `location`（≤64；当刻 locationEnabled=1 → 必填 50822）→ `inbound_requests.location` 落值；`batchNo` 非空 → `InboundBatchContext.location` 透传 → `batches.location`。
- `POST /api/v1/tenant/inbound/{id}/register` body `InboundForwardRegisterDto` + `location`（同上）。

### 4.3 出库登记（扩展既有 DTO，无新端点）
- `POST /api/v1/tenant/outbound-requests/{id}/register` body `OutboundRegisterDto` + `location`（≤64；当刻 locationEnabled=1 → 必填 50822）→ `outbound_requests.location` 落值（拣货指示留痕；**批次/流水零触碰**——K-4/13 方案 C 铁律）。
- `POST /api/v1/tenant/wk/outbound-requests` body `WkOutboundCreateDto` + `location`（代建直达 COMPLETED，同上）。

### 4.4 批次移库 + 变更日志（新端点，BatchController，均走 `/api/v1/tenant/**` 登录拦截）
- `PUT /api/v1/tenant/batches/{id}/location` body `BatchLocationUpdateDto { location }`（≤64，**null=清空**）→ `R<BatchVo>`（含新 location）。语义：批次不存在/跨租户 50363（K-7）；新旧相同幂等空转不落日志；有差异同事务 `update batches.location` + `insert batch_location_logs`（from=旧值 to=新值，操作人=登录者）。
- `GET /api/v1/tenant/batches/{id}/location-logs?page=&size=`（默认 1/50，size ≤50）→ `R<Page<BatchLocationLogVo>>`（含 id/batchId/fromLocation/toLocation/operatorUserId/createdAt；倒序）。
- 鉴权：WK 或 TA（该租户 ACTIVE 角色，listForTenant 同款；TA 一账号多仓经 TenantContext 收敛）。

### 4.5 读侧字段扩展
- `BatchVo` + `location`（`/tenant/batches`、`/wholesaler/batches`、`/tenant/batches/expiring` 共用 —— 拣货联想的货位清单数据源 K-6）。
- `InboundRequestVo` / `OutboundRequestVo` + `location`（登记单展示；wa/ta 列表与详情可见登记货位）。

### 4.6 错误码（ErrorCode 5082x 段，归属 inventory 域）
| code | 常量 | 场景 |
|---|---|---|
| 50822 | LOCATION_REQUIRED | locationEnabled=1 时入库/出库登记未填货位 |
| 50823 | BATCH_LOCATION_TOO_LONG | 货位号超 64 字（DTO `@Size` 400 之外的防御性兜底） |
| 50363 | BATCH_NOT_FOUND（沿用） | 移库/日志目标批次不存在或跨租户 |

---

## 5. 后端改动清单

- **迁移**：`V40__p5d_c2_location.sql`（§3 单文件）。
- **tenant 域**：`TenantSettings` +locationEnabled；`StoreSettingsDto` +locationEnabled；`TenantDetailVo` +locationEnabled；`TenantServiceImpl.updateMyStore/hasAnySwitch/applySettingsDto/buildTenantDetail/getBatchConfig` 扩展；`TenantBatchConfigVo` +locationEnabled。
- **inventory 域**：`Batch` +location；`BatchVo` +location；新实体 `BatchLocationLog` + Mapper + `BatchLocationLogVo`；`InboundBatchContext` +location；`BatchServiceImpl.registerInboundBatch` 落 location；`listForTenant/listForWholesaler/listExpiring/getTenantBatch` 装配 location；新增 `updateBatchLocation`/`listLocationLogs`；`BatchController` +2 端点；`BatchLocationUpdateDto`。
- **document 域**：`InboundRequest`/`OutboundRequest` +location；`InboundRegisterDto`/`InboundForwardRegisterDto`/`OutboundRegisterDto`/`WkOutboundCreateDto` +location（`@Size(max=64)`）；`InboundRequestServiceImpl` 两登记方法货位校验+落值+透传钩子；`OutboundRequestServiceImpl.registerByWk/createByWk` 货位校验+落值；`InboundRequestVo`/`OutboundRequestVo` +location。
- **错误码**：`LOCATION_REQUIRED(50822)/BATCH_LOCATION_TOO_LONG(50823)` + 5082x 段注释。
- **TenantLine**：MybatisPlusConfig 白名单追加 `batch_location_logs`。
- **不动**：batch-toggle（50360 禁改）、InventoryService（addStock/deductStock/releaseOutboundPallet 零改动）、DocStateMachine、stock_movements type。

---

## 6. 前端改动清单（apps/admin）

- `views/ta/Settings.vue`：店铺设置区加「启用货位」开关卡片（el-switch v-model `form.locationEnabled`，随通用设置 PUT 提交——不走专用 toggle/before-change）。
- `views/ta/Inbound.vue`：登记表单（代建登记 + 正向受理登记）货位输入行，`locationEnabled` 读 `GET /wholesaler/tenants/{tenantId}/batch-config`（新字段）控制显隐与必填标；提交带 `location`。
- `views/ta/Outbound.vue`：登记出库弹窗（`register`）+ 代建出库表单（`createByWk`）拣货位输入行（同开关显隐/必填；可基于该 SKU 在库批次货位联想下拉，读 `/tenant/batches?skuId=` 的 `location` 去重）；提交带 `location`。
- `views/ta/Batches.vue`：批次登记簿加「货位」列（空值展示「—」，任意开关状态均显示——货位是登记簿字段）+ 行内「移库」操作（弹窗输入新货位/清空确认 → PUT）+「变更记录」抽屉（GET location-logs 时间线 from→to/操作人/时间）。
- `api/batch.ts`（+location、`updateBatchLocation`、`listLocationLogs`）、`api/inbound.ts`/`api/outbound.ts`（登记 body 带 location）、`api/tenant.ts`（StoreSettings 类型 +locationEnabled）；api-types 对应类型同步（batch/tenant/inbound/outbound）。
- wa 侧零新增页面；wa/Inbound.vue 等若展示登记单 VO 带货位字段则自然透出（只读），不做开关强控。

---

## 7. 测试要点（后端场景测试 `LocationScenarioTest`，inventory/document 脚手架沿用 BatchChain 先例）

1. **LV-01 默认关闭**：locationEnabled=0 → 出入库登记不带 location 成功、单据 location 为 NULL、无货位校验（零回归兼容）。
2. **LV-02 开启后入库**：PUT /tenant/me 开 locationEnabled → 代建 registerByWk 缺货位 50822；带 location 成功且落 `inbound_requests.location`；batchEnabled=1 时同落 `batches.location`（批次行）。正向 registerForwardByWk 同口径。
3. **LV-03 开启后出库**：registerByWk（PRINTED→COMPLETED）缺拣货位 50822、带 location 成功落 `outbound_requests.location`；`inventories.qty` 不变且无新增 stock_movements type（**零副作用断言**：货位只留痕不动账）。WK 代建 createByWk 同口径。
4. **LV-04 移库**：PUT /tenant/batches/{id}/location 改货位成功 + location-logs 记录 from/to 两态；清空（null）成功且 to=null；相同值幂等不落新日志；批次不存在/跨租户 50363；超长 50823（或 400 防御）。
5. **LV-05 关闭开关**：locationEnabled 开→关后，存量货位保留（单据/批次展示不变），出入库登记恢复免填（不带 location 成功）。
6. **LV-06 各端读开关与隔离**：`GET batch-config` 响应含 locationEnabled；WA/WK/TA 侧均可读；跨租户批次移库拒绝 50363。

---

## 8. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1.1 | 2026-09-02 | **实现落地（C2 波收官）**：V40 迁移 + tenant/inventory/document 三域改动全按 §5 落地；前端 §6 全链（Settings/Inbound/Outbound/Batches + api-types）；LocationScenarioTest LV-01~06 7 例绿；全量回归 **524**（首跑 3 flake 均环境/顺序问题并修复：PiiWrite 41205 = Redis `sms:daily` 当日多次运行累积 → 清键恢复；CustomerFollowup cf05 = 该 C3 测试类 seedTenant 简码 `id%1000` 在新增测试类后跨类撞唯一键 → 改 `TestUniq.tenantSimpleCode()` 全局唯一，随波收口） |
| v1.0 | 2026-09-02 | 首版定稿：D-C-1~1d 落地口径（K-1~9）；V40 单文件迁移（4 加列 + batch_location_logs）；契约=开关走通用 PUT /tenant/me + 出入库登记 DTO 扩展 + BatchController 新 2 端点（移库/日志）；错误码 50822/50823；LV-01~06 测试要点 |
