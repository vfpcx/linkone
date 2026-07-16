# Findings · P2 定价能力（探索实测）

> 来源：3 个 Explore Agent 对 backend/frontend 的只读探索。为 `task_plan.md` 提供落地依据。

## 1. product 域 SKU 定价现状
- `product/entity/Sku.java` @TableName("skus")：价格三件套 `unitPrice`（单价,>0）、`moqPrice`（起批价,≥0,默认0）、`moqQty`（起批量,≥1,默认1）。精度 `DECIMAL(12,2)`，DTO `@Digits(integer=10,fraction=2)`。**无客户专属价**，公开价直接挂 skus 行。
- `SkuService.getById(skuId)->SkuVo`：跨域读价的唯一出口（已「去跨域直连」，勿碰 SkuMapper）。`listByTenantForRt(tenantId,wholesalerId)` 只回公开价。
- `SkuServiceImpl.requireOwnedSku` / `requireWaOrTa`：鉴权模板——`hasWholesalerRole(userId,"WA",whId) || hasRole(userId,"TA",tenantId)`，否则 42101。`updateSku` 用 LambdaUpdateWrapper 部分更新防 lost-update。
- Controller 基路径 `/api/v1/tenant/skus`；返回 `R<T>`；userId=`StpUtil.getLoginIdAsLong()`。

## 2. DB 迁移与约定
- Flyway `backend/src/main/resources/db/migration/V{n}__*.sql`，最新 **V8**（inquiry_requests/inquiry_items/outbound_requests）。定价迁移 = **V9__init_pricing.sql**。
- 约定：snowflake BIGINT PK（无 AUTO_INCREMENT）；snake_case；字符串枚举 `VARCHAR DEFAULT 'ACTIVE'`；`tenant_id`；金额 `DECIMAL(12,2)`；`created_at/updated_at` DEFAULT CURRENT_TIMESTAMP；软删 `deleted_at`；**约束名全局唯一带表前缀**（H2 要求）如 `uk_custprice_wh_phone_sku`；V8 用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` + COMMENT。
- 本机 dev 库 `cangchu_dev`（MySQL 9.7 @3306）已存在、20 表。

## 3. 错误码
- `common/exception/ErrorCode.java` 单枚举。域资源码集中在 **50xxx**：SKU=50240–50242，最高到 50292（员工邀请）。**定价新开 50300 段**。抛错 `new BizException(ErrorCode.X)`；权限失败复用 `PERMISSION_TENANT_001=42101`；参数失败 40001。

## 4. Redis / 并发（无 Spring @Cacheable，全走 Redisson）
- `common/config/RedisConfig.java`：RedissonClient + RedisTemplate。
- **分布式锁**（批量调价模板）：`inventory/.../InventoryServiceImpl` addStock/deductStock——`redissonClient.getLock("lock:...")` `tryLock(30,15,SECONDS)`，**先锁后开事务**，经 self `@Lazy` 代理跑 @Transactional，finally 解锁。
- **RBucket 带 TTL 缓存**（价格匹配模板）：`account/.../AccountServiceImpl` sms cooldown——`getBucket(key).set(v,ttl,SECONDS)` / `.get()` / `.delete()`。价格匹配 key = `price:match:{wholesalerId}:{rtPhone}:{skuId}`，写后 delete 失效。
- 原子计数带日 TTL：`document/.../DocumentNumberServiceImpl` RAtomicLong。

## 5. TenantLine 行级隔离
- `common/config/MybatisPlusConfig.java` 白名单 `TENANT_FILTER_TABLES`：stores/tenant_settings/wholesalers/skus/inventories/stock_movements/inbound_requests/inquiry_requests/outbound_requests。**追加 customer_prices、price_change_logs**。同文件 MetaObjectHandler 自动填充 tenantId/createdAt/updatedAt。

## 6. 集成点（定位到行）
- RT 浏览返回价：`storefront/.../StoreFrontServiceImpl.buildOnSaleSkus` L128-154（`.unitPrice(...)`），需透传 rtPhone。
- 询价提交快照：`document/.../InquiryServiceImpl.submitByRt` L77-148（L140 `dealPrice=unitPrice`）。
- **沉淀写入**：`InquiryServiceImpl.confirmByWa` for 循环 L188-215（单 @Transactional）；当前签名 `confirmByWa(inquiryId, waUserId)` **不带成交价/沉淀选项**，需扩 DTO。CAS 状态守卫 L168-179。`inquiry_items.deal_price` 列已存在。
- RT 端点：`storefront/controller/RtStoreController` @ `/api/v1/rt`（store/skus/wholesalers）**当前公开无鉴权**，`SaTokenConfig` 未纳入；RT 登录 `/api/v1/account/login/rt` 发 Sa-Token 但未被 rt 端点消费 → 浏览时无 rt_phone。**需改可选鉴权**。

## 7. 测试范式
- Style A（HTTP 黑盒）：`product/SkuScenarioTest`——`@SpringBootTest(RANDOM_PORT)`+TestRestTemplate+H2，唯一手机号隔离，mock SMS `888888`，裸 token 头，`R<T>` 解码，seeding `registerTaWithTenant/createWholesaler/createSku`。
- Style B（service 集成）：`document/InquiryScenarioTest`——mapper 直接 seed + `TenantContext.set(...)` 模拟鉴权。
- 并发（虚拟线程）：`InquiryScenarioTest` L416-464（CountDownLatch + `Executors.newVirtualThreadPerTaskExecutor`）——批量调价并发测试模板。命名 `s<N>_camelCase` + `@DisplayName("PRICE-S<N>-<NN> ...")`；S1 正常/S2 非法/S4 越权/S5 回滚/S6 幂等/S7 并发。

## 8. 前端结构
- api：`apps/admin/src/api/http.ts`（axios baseURL `/api/v1`，snowflake→string，注入 `Authorization`+`satoken`+`X-Tenant-Id`，`request<T>` 解包 R）。资源模块 `api/*.ts` 导出 `xxxApi` 对象。→ 新增 `api/pricing.ts`；类型 `packages/api-types/src/pricing.ts`（+ index 再导出）。
- views：`src/views/ta/*`（Skus.vue 参考）、`wa/Inquiry.vue`（询价确认，onConfirm 用 ElMessageBox→改自定义弹窗）、`rt/Store.vue`（H5，`.rt-sku__price` 渲染价）。→ 新增 `views/ta/Pricing.vue`（路由 `/ta/pricing` meta.role=TA）、`views/wa/PriceSettleDialog.vue`。
- 状态：Pinia `stores/auth.ts`（持久化 localStorage）；当前租户在 `utils/currentTenant.ts`（http 自动注入 X-Tenant-Id）。
- E2E：`apps/admin/e2e/*.spec.ts` + `helpers/sell.ts`（`seedSellChain` 全链 seed）。→ 新增 `e2e/pricing-flow.spec.ts`；仅 S1+抽样，S2/S4/S5/S7 留后端。

## 9. 运行环境（手工测试）
- 后端 :8080，profile `dev,local`（local 覆盖 MySQL 密码）；依赖 MySQL@3306（root/见 application-local.yml）+ Redis/Memurai@6379（无密码，需手动拉 `C:\Program Files\Memurai\memurai.exe`）。mvnw wrapper jar 缺失，用系统 `mvn`（D:\apache-maven-3.6.3）。短信 mock 888888。
- 前端 Vite dev（:5173），代理 → :8080。
