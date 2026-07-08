# 09 · P2 定价能力 · Wave 1 设计说明（专属价 + 价格解析）

> 分支 `feat/p2-pricing` · 后端 Wave 1 落地。仅含：客户专属价 CRUD、`resolvePrice` 价格解析（含 Redis 缓存）、
> 两张表的迁移与实体、错误码与租户白名单。批量调价 / 沉淀 / RT-match 属 Wave 2/3，本波未实现。

## 1. 范围

| 项 | 内容 |
|---|---|
| 迁移 | `V9__init_pricing.sql`：`customer_prices` + `price_change_logs`（后者本波仅建表，不写入） |
| 领域包 | `com.cangchu.pricing`（entity / mapper / dto / vo / service / controller） |
| 能力 | 专属价 set(upsert) / list / update(PATCH) / revoke(DELETE) + 内部 `resolvePrice` |
| 缓存 | `resolvePrice` 的「专属价命中/未命中」结论走 Redis（RBucket + TTL 60s） |

## 2. 数据模型

### 2.1 `customer_prices`（客户专属价）
- 身份口径：客户 = `rt_phone`（与 `inquiry_requests.rt_phone` 一致）。
- 唯一键 `uk_custprice_wh_phone_sku (wholesaler_id, rt_phone, sku_id)`；索引 `idx_custprice_sku(sku_id)`、`idx_custprice_phone(rt_phone)`。
- 金额 `unit_price DECIMAL(12,2)`；`status` ∈ {ACTIVE, DISABLED, EXPIRED}；`source` ∈ {manual, from_inquiry}（Wave 1 只产生 manual）。
- 雪花 `BIGINT` 主键（无 AUTO_INCREMENT）；`tenant_id` 由 `MetaObjectHandler` 自动填充；软删列 `deleted_at`（`@TableLogic`，与全局 `logic-delete-field` 对齐）。

### 2.2 `price_change_logs`（价格变更日志）
- Wave 2/3 批量调价沉淀用。本波建表 + 实体/Mapper，**暂不写入**。
- 无软删；`created_at` 由 `MetaObjectHandler` 填充。索引 `idx_pricelog_wholesaler(wholesaler_id)`、`idx_pricelog_batch(batch_no)`。
- `adjust_mode` ∈ {PCT_UP, PCT_DOWN, SET_VALUE, DELTA, DISABLE, SET_EXPIRE}；`change_type` ∈ {PUBLIC_PRICE, CUSTOMER_PRICE}。

DDL 完全对齐 V8 约定（snake_case、字符串枚举、列 COMMENT、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`、表内 `UNIQUE KEY`/`INDEX`），H2 MySQL-mode 直接通过、无需改写。

## 3. 鉴权与隔离（沿用 A2 SkuServiceImpl 口径）
- 写操作归属：`operator` 须为该 `wholesaler` 的 **WA**（`authService.hasWholesalerRole(uid,"WA",wholesalerId)`）
  或该商户所属租户的 **TA**（`authService.hasRole(uid,"TA",tenantId)`）；皆非 → `42101 PERMISSION_TENANT_001`。
- `tenant_id` 以 `wholesaler.getTenantId()` 为准（不信任客户端传参）。
- `customer_prices` / `price_change_logs` 已加入 `MybatisPlusConfig.TENANT_FILTER_TABLES`，TenantLine 兜底过滤跨租户不可见。
- 跨域读 SKU 公开价只经 `SkuService.getById(skuId) -> SkuVo`（G-S1/G-S2），**不直连 SkuMapper**。

## 4. `resolvePrice` 优先级（PRD §14b.1）
```
resolvePrice(wholesalerId, skuId, rtPhone, qty):
    sku = skuService.getById(skuId)            # null -> 50302 PRICE_MATCH_FAILED
    publicPrice = qty >= sku.moqQty ? sku.moqPrice : sku.unitPrice
    if rtPhone 为空/空白: return publicPrice
    custom = 命中 ACTIVE 且 isActive() 的专属价.unitPrice（带缓存）
    return custom != null ? custom : publicPrice
```
- `CustomerPrice.isActive()` = `status==ACTIVE && (expireAt==null || expireAt 在未来)`。过期专属价 → 回退公开价。
- 专属价为「一口价」，命中后**不受 qty/起批价影响**。

## 5. Redis 缓存（≤200ms）
- Key：`price:match:{wholesalerId}:{rtPhone}:{skuId}`（**不含 qty**——只缓存专属价命中/未命中的决策）。
- 值：命中存 `unitPrice.toPlainString()`；未命中存哨兵 `"NONE"`。`RBucket<String>.set(v, 60, SECONDS)`。
- 失效：`invalidate(wholesalerId, rtPhone, skuId)` = `bucket.delete()`，在 set/update/revoke **每次写后**调用。
- 公开价部分不缓存（SkuService 调用轻量）。

## 6. 错误码（新增 pricing 段，50300 起）
| 码 | 常量 | 文案 |
|---|---|---|
| 50300 | `CUSTOMER_PRICE_NOT_FOUND` | 客户专属价不存在 |
| 50301 | `CUSTOMER_PRICE_INVALID` | 专属价必须大于0 |
| 50302 | `PRICE_MATCH_FAILED` | 价格匹配失败，请稍后重试 |

复用：越权 `42101 PERMISSION_TENANT_001`、商户不存在 `50230 WHOLESALER_NOT_FOUND`、参数校验 `40001 VALIDATION_BASIC_001`。

## 7. API（`/api/v1/tenant/customer-prices`）
| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| POST | `/` | `SetCustomerPriceDto`（wholesalerId, skuId, rtPhone, unitPrice, expireAt?） | 按唯一键 upsert |
| GET | `/?wholesalerId=` | — | 列该商户全部未软删专属价（含 DISABLED） |
| PATCH | `/{id}` | `UpdateCustomerPriceDto`（unitPrice?, expireAt?, status?） | 部分更新，非空才改 |
| DELETE | `/{id}` | — | 作废（status→DISABLED） |

返回统一 `R<T>`；雪花 id 序列化为 String（`@JsonSerialize(ToStringSerializer)`）。userId 取 `StpUtil.getLoginIdAsLong()`。

## 8. 测试
`backend/src/test/java/com/cangchu/pricing/PricingScenarioTest.java`（HTTP 黑盒 Style A + 少量 Style B 直调 service）：
- PRICE-S1-01 set→list→PATCH 改价→DELETE 作废（作废后 status=DISABLED）。
- PRICE-S2-01 价<=0 被拒（DTO `@DecimalMin` 兜底 40001 / 服务层 50301）；S2-02 缺 rtPhone→40001；S2-03 直调 `setCustomerPrice(unitPrice=0)`→50301。
- PRICE-S4-01 跨租户 TA 设/列他人商户专属价→42101/50230。
- PRICE-S5-01 `resolvePrice`：空 phone→公开价（qty<moq 单价 / qty>=moq 起批价）；有 phone+ACTIVE→专属价（忽略 qty）；过期专属→公开价。

**结果：全量 105 tests，0 failures / 0 errors（本波新增 6，原有 99 未回归）。** 命令：`mvn test`。

## 9. 交给 Wave 2/3 的接口约定
- Service 签名（`com.cangchu.pricing.service.PricingService`）：
  - `CustomerPriceVo setCustomerPrice(SetCustomerPriceDto dto, Long operatorUserId)`
  - `CustomerPriceVo updateCustomerPrice(Long id, UpdateCustomerPriceDto dto, Long operatorUserId)`
  - `void revokeCustomerPrice(Long id, Long operatorUserId)`
  - `List<CustomerPriceVo> listCustomerPrices(Long wholesalerId, Long operatorUserId)`
  - `BigDecimal resolvePrice(Long wholesalerId, Long skuId, String rtPhone, int qty)`
- 缓存 key 格式：`price:match:{wholesalerId}:{rtPhone}:{skuId}`（TTL 60s，哨兵 `NONE`）。Wave 2 批量调价后**务必**对受影响 (wholesaler,phone,sku) 逐个 `invalidate`（或按前缀清理）。
- `price_change_logs` 表已就绪，Wave 2 批量调价请写入该表（batch_no 分组、before_after_json 存快照）。
- `source=from_inquiry` + `source_doc_no` 字段已预留给 Wave 3「询价沉淀专属价」。
