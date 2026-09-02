# 17 · P5-D「小项池 C（US-WK-05 货位功能 / US-RT-05 专属价目复购 / US-WE-04 客户跟进）」需求拆解 v1.5

> 编写：Team Lead · 2026-09-02
> 依据：`02-user-stories.md`（US-WK-05/RT-05/WE-04，均 P1；US-WA-10/11 价格管理）+ `14-p5-requirements.md` §3 P5-D 小项池 + `99-open-questions.md` Q-D02/Q-D03 + 代码实测（pricing/inventory/document/tenant/notify 域现状）
> 状态：**C1/C3 已实现（2026-09-02 双波收官，全量回归 517 全绿）**：C1 专属价目复购（架构 `architecture/23-p5-c-c1` v1.1：后端 POST /rt/my-pricelist + RtPriceListScenarioTest 12/12 绿 + 前端 Store.vue「我的价目」抽屉 + 契约 `api-contract-storefront` §3.3）；C3 客户跟进（架构 `architecture/24-p5-c-c3` v1.1：V39 两表 + /api/v1/tenant/customers 5 端点 + FollowupReminderJob + CustomerFollowupScenarioTest CF-01~05 5/5 绿 + 前端 wa 客户跟进页/菜单/铃铛标签 + customers.ts/api-types 契约，§3.3 已修订为落地契约）。**C2 货位功能用户指示「记录下来后面还是要做」→ 后续需求池（backlog）**，§2 需求记录完整保留，不随本波执行
> 定位：roadmap v3.1 排期 **C · 小项池**（P5-D 收尾；C1→C3 双波，2026-09-02 收官）

---

## 0. 三子项总览（均 P1 小项，各自一波）

| 子项 | US | 一句话 | 现状落差（代码实测 2026-09-02） | 建议顺序 |
|---|---|---|---|---|
| C1 专属价目复购 | US-RT-05 | RT 基于**客户专属价格表**（customer_prices）快速发起新一轮询价 | 无 RT 侧「我的价目」查询端点/视图；专属价体系（匹配/议价沉淀）后端已闭环 | **① 最先**：无新表，仅新增 RT 价目查询端点 + Store.vue 入口，风险最低 |
| C2 货位功能 | US-WK-05 | 仓级**货位启用开关**；启用后出入库登记货位、批次可移库 | 全仓无货位概念；无 location 表/列；无移库流水/操作日志表（Q-D02/D03 未敲） | 📌 **后续池**：需求记录在案（2026-09-02 用户拍板），不随本波执行 |
| C3 客户跟进 | US-WE-04 | WE 给重要客户打备注、设跟进提醒（站内信） | 无客户主档（客户=rt_phone）；无备注/提醒先例；无 WE 消息中心落地页 | ③ 最后：最大（新表 + Job + 页面） |

> 用户拍板 2026-09-02：**C1/C3 确认执行；C2（货位+批次）记录为后续需求池**。实际执行顺序 **C1→C3**；D-C-1~1d（C2）随需求记录待后续拍板，D-C-2~9 全默认采纳。

---

## 1. C1 · US-RT-05 专属价目表复购（口径修订 2026-09-02）

> **口径修订**：Team Lead 原草案「基于历史询价单复制 SKU/件数发起新一轮询价」被用户否决——方式不合适。正确形态为**价格表**：P2 已落地的客户专属价体系（`customer_prices` + 议价沉淀 `from_inquiry` + RT 浏览按手机号匹配专属价 `matchedPrice`）即客户价格表底座；本波为 RT 补齐「我的价目」视图，把询价从「翻历史单复制」改为「对着自己的专属价目续购」。与 US-WA-10 RT 端「我的意向单复购：自动按当前匹配规则取价」同源（取价一律走专属价 > 公开价 + 起批量 匹配）。

### 1.1 现状基线（代码实测）
| 项 | 现状 | 影响 |
|---|---|---|
| 专属价底座 | `customer_prices`（V9：wholesaler_id+rt_phone[hmac/cipher/last4]+sku_id+price+有效期+source[from_inquiry/manual/batch]）；`PricingService` CRUD/批量六式/失效/过期 + `resolvePrice`（Redis 缓存）；`settleFromInquiry` 议价沉淀 | **价格表 = 直接读 customer_prices 有效期行，不新建价目实体** |
| RT 浏览取价 | StoreFront `buildOnSaleSkus` 按 rtPhone（StpUtil.isLogin 可选登录）命中专属价 → `StoreSkuVo.matchedPrice`（Store.vue 已有「专属优惠」标） | 匹配规则后端已闭环，价目页可直接复用 |
| RT 页面 | 仅 `views/rt/Store.vue`（`/rt/store`、`/rt/:code`，meta.public 无登录守卫）；提交成功仅单号提示 | 价目入口落于此页 |
| RT 身份 | 匿名为主：提交询价当场填手机号（PII）；无会话历史读取 | 价目查询以 rtPhone 入参即可，不引入账号体系 |
| 询价提交 | `POST /rt/inquiry`（wholesalerId+items，submitByRt 现价快照） | 价目勾选后复用现提交链路，无新提交端点 |

### 1.2 范围（默认，D-C-3/4/5 拍板）
- **RT「我的价目」入口**（Store.vue）：RT 输入手机号 → 打开「我的价目」抽屉 → 展示**当前店**为该手机号维护的**客户专属价清单**（`customer_prices` 有效期内），按 wholesaler 分组；行内含商品名、SKU 规格、专属价（标「专属优惠」）、公开价对照。
- **按价目询价**：价目行勾选 SKU、填数量 → 提交走现有 `POST /rt/inquiry`（后端现价快照=专属价命中价）；一次询价限单一 wholesaler（沿用 Store.vue 既有约束，跨组自动提示）。
- **无价目降级**：该 phone 无任何专属价 → 抽屉空态提示「暂无专属价目，可在店铺页按公开价询价」，不影响现状店铺浏览/询价。
- **明细容错**：价目中 SKU 已下架 → 行置灰不可勾并提示「商品已下架」；专属价已过期/失效 → 行不出现（后端按 `expire_at`/status 过滤）。
- **不做**：历史询价单列表/复制、「我的意向单」历史页（取价规则 US-WA-10 已定义，待意向单模块实现时按匹配规则落地）、收货信息字段引入。

### 1.3 契约草案
- `GET /rt/my-pricelist?code=&rtPhone=`（公开只读，Rt 侧新端点）→
  `[{wholesalerId, wholesalerName, items:[{skuId, skuName, spec, listed, unitPrice, customerPrice, expireAt, source}]}]`
  - 仅返回该 phone 在**当前店**各 wholesaler 下的**有效** customer_prices（`expire_at` 为空或未来、来源行未失效）；按 wholesaler 分组；组内 createdAt 倒序
  - rtPhone 入参 PII 加密后按 hmac 盲查（对齐 customer_prices 检索口径：wholesaler+rt_phone_hmac）
  - 行价格：`customerPrice`=专属价现值，`unitPrice`=SKU 当前公开价对照；不返回任何其它手机号数据
- 提交：复用 `POST /api/v1/rt/inquiry`（无新提交端点）
- 无新表/迁移；错误码 0~2 个（架构阶段定稿，预占 5080x 段草案）

### 1.4 测试要点
1. PR-01 价目正确返回：议价沉淀（from_inquiry）+ WA 手工（manual）+ 批量调价三条来源的专属价均入清单；过期/失效行不出现；下架 sku 行带 `listed=false`
2. PR-02 分组与隔离：A/B 两 wholesaler 各有价目 → 两组返回；同一 phone 换 code（别店）查不到（跨店隔离）
3. PR-03 勾选提交询价成功：价目展示价=专属价；提交后 `inquiry_items` 快照=专属价；新单 PENDING 落库（对账零副作用）
4. PR-04 明细容错：全下架 → 组内全置灰禁提交；无价目 phone → 空态；无价目客户走公开价询价不受影响（回归）
5. PR-05 匿名安全：价目响应不含他人数据；纯浏览不落任何单

---

## 2. C2 · US-WK-05 货位功能（启用开关 + 出入库登记货位 + 批次移库）

> **口径扩展 2026-09-02（用户补充）**：货位需有「是否启用」配置——**未启用时出入库不需要填货位**。故 C2 从原「批次库位标注」草案升级为完整货位功能：仓级开关 + 开启后出入库登记货位 + 批次移库（US-WK-05）。实现沿用例已存在的「批次开关 batchEnabled」全套机制（tenant_settings 列 + TA 开关 + 各端读开关显隐字段）。

### 2.1 现状基线（代码实测）
| 项 | 现状 | 影响 |
|---|---|---|
| 货位体系 | **全仓零 location/货位列与表**；库存仅 qty/palletQty（托盘），无货位维度 | 全新引入；货位**不建 per-location 库存**（否则计费/对账/FIFO 全炸），挂批次与单据行 |
| 批次 | `batches`（V22）：tenant/wholesaler/sku/batch_no/保质期/remaining_qty(**每日 02:00 FIFO 推算，非记账**)/status/source；入库登记后置钩子 `registerInboundBatch`（仅 batchEnabled=1 触发）登记批次 | 货位天然挂 `batches.location`；出入库登记处做后置写 |
| 出库 | 纯总账扣减 `deductStock`（inventories.qty），**不选批次、不扣 batch**；托盘释放 `releaseOutboundPallet` | 出库不能做批次级扣减（铁律）；拣出货位只做「登记指示 + 留痕」 |
| 开关先例 | `tenant_settings.batchEnabled/batchEnabledAt` + TA `POST /tenant/settings/batch-toggle`（有生成/冻结批次副作用故专用端点）+ 通用店铺设置 GET/PUT `/tenant/me`（StoreSettingsDto）+ 各端读 `GET /wholesaler/tenants/{tenantId}/batch-config` | 货位开关**无副作用**（纯字段显隐+必填校验）→ 走通用设置 PUT 即可 |
| 前端 | `ta/Inbound.vue`（入库登记，批次字段按 batchEnabled 显隐）、`ta/Outbound.vue`（出库作业）、`ta/Batches.vue`（批次登记簿）、`ta/Settings.vue`（店铺设置=批次开关 UI）、`wa/Inbound.vue` | 货位入口落这几页 |

### 2.2 范围（默认，D-C-1~1d 拍板）
- **仓级开关 `locationEnabled`**（tenant_settings 新列，默认 0=关闭；TA 在店铺设置开关，无 toggle 副作用故入通用设置）：
  - =0：出入库界面**不出现货位字段、不做必填校验**（现状零改动）；批次登记簿货位列仍显示（存量空值显示「—」）但不影响流程
  - =1：入库登记货位必填；出库登记需指定拣出货位；批次登记簿可移库
- **入库登记填货位**（ta/Inbound.vue，WK 代建/受理正向两步登记）：行级入参 `location`（自由文本货位号，≤64，如 `A-01-03`，不做货位主档字典——D-C-1b）；登记时随 `registerInboundBatch` 落 `batches.location`
- **出库登记填货位**（ta/Outbound.vue，WK 出库作业）：登记时按 SKU 行填/选「拣出货位」——前端从该 SKU 在库批次的货位清单给建议，可自填；落出库单行（记录指示与留痕）。**不做批次选择/批次级扣减**（batches 非记账 + FIFO 推算 + 对账铁律不变，D-C-1c）
- **批次移库（US-WK-05 验收）**：ta/Batches.vue 批次登记簿行内改「货位」→ 更新 `batches.location` + 落 `batch_location_logs`（from/to/操作人/时间）；移库不影响库存数
- 不新建货位字典表/不做 per-location 库存/不新增 stock_movements type（架构层把关零副作用）

### 2.3 契约草案
- 迁移 V39：`ALTER TABLE tenant_settings ADD COLUMN location_enabled TINYINT NOT NULL DEFAULT 0`；`ALTER TABLE batches ADD COLUMN location VARCHAR(64) NULL`
- 迁移 V39b：新表 `batch_location_logs`（id/batch_id/tenant_id/wholesaler_id/sku_id/from_location/to_location/operator_user_id/created_at）
- 开关读写：`PUT /api/v1/tenant/me`（StoreSettingsDto+locationEnabled）/ `GET /api/v1/tenant/me`；各端读 `GET /api/v1/wholesaler/tenants/{tenantId}/batch-config` 返回体加 locationEnabled
- 入库：InboundRegisterDto/正向登记行加 `location`（locationEnabled=1 时必填；≤64）
- 出库：出库登记行加 `locationFrom`（拣出货位；=1 时必填；≤64）；落单明细
- 移库：`PUT /api/v1/tenant/batches/{id}/location` body `{location}`（可空=清空，仅差异落 log）+ `GET /tenant/batches/{id}/location-logs`（≤50/页）
- 货位建议（可选）：入库登记/出库登记处由批次列表已有货位即时联想，不单独建接口（架构阶段定）
- 错误码草案：`LOCATION_REQUIRED 50822 / BATCH_LOCATION_TOO_LONG 50823`（沿用 5xxx 分段，架构定稿）

### 2.4 测试要点
1. LV-01 默认关闭：出入库界面无货位字段、不校验，现有出入库回归全绿
2. LV-02 开启后：入库登记缺货位拒绝、带货位成功且落 `batches.location`
3. LV-03 开启后出库：登记可填/选拣出货位落单明细；`inventories.qty` 不变、无新增 stock_movements type（零副作用断言）
4. LV-04 移库：改货位成功 + logs 记录 from/to 两态；清空货位亦可；批次不存在/超长/越权拒绝
5. LV-05 关闭开关：存量货位数据保留展示，出入库恢复免填；再次开启不受影响
6. LV-06 各端读开关：WA/WK/TA 侧出入库与登记簿界面按 locationEnabled 显隐正确

---

## 3. C3 · US-WE-04 客户跟进

### 3.1 现状基线（代码实测）
| 项 | 现状 | 影响 |
|---|---|---|
| 客户身份 | **无 customer/contact/buyer 主档**；全仓「客户=rt_phone」唯一口径（inquiry_requests / customer_prices 先例） | 客户=该商户询价过的 RT（按 rt_phone 归并） |
| 备注/提醒 | `inquiry_requests` 无 remark；全仓无 reminder/remind_at/follow_up 字段表 | 需新建 |
| 站内信 | notify 域 `NotificationService.send/sendToAll`（recipient_user_id 隔离）+ 铃铛抽屉（NotificationBell.vue，60s 轮询）+ type→中文标签表；定时样板=BatchRecalcJob(02:00)→notifyExpiringOnce sendToAll | 到点提醒可照搬（但为「单用户自设时点」语义） |
| WE 前端 | WE 无独立端，共用 `wa/` 视图集；wa/Inquiry.vue 未接功能铃铛；无客户/跟进页面 | 需新建 wa/ 客户跟进页 + 菜单 + 铃铛类型标签 |
| 授权 | `WePermissions` 白名单（PRICE_EDIT/INQUIRY_CONFIRM/…），无跟进位 | 跟进操作授权位可选 |

### 3.2 范围（默认）
- **客户跟进页（wa/ 侧，WE/WA 可见）**：「客户」= 当前商户（登录态归属 wholesaler，同 listForWa 口径）按 rtPhoneHmac 归并的询价买家列表；列：打码手机号、询价次数、最近询价时间、最近成交（CONFIRMED 单）、备注、下次跟进时间。点行 → 查全号（复用 `PII-REVEAL biz=INQUIRY` 先例，WA/授权 WE）+ 备注编辑 + 设提醒。
- **备注**：新表 `customer_followups`（客户跟进档案，单行每客户）：id/tenant_id/wholesaler_id/rt_phone_hmac/rt_phone_cipher/remark(≤200 覆盖式或追加历史？见 D-C-8)/created_by/created_at/updated_at/updated_by；**加 TenantLine 白名单 + 业务层 wholesaler 收敛**（备注仅本商户可见=tenant+wholesaler 双层）。
- **提醒**：新表 `followup_reminders`：id/tenant_id/wholesaler_id/customer_followup_id(或 rt_phone)/content/remind_at/reminded_at(空=未触发)/created_by；**新 Job**（SchedulingConfig 登记，如每 5 分钟）扫 `remind_at<=now AND reminded_at IS NULL` → `notificationService.send(tenantId, created_by 该 WE, TYPE_CUSTOMER_FOLLOWUP, ...)` 同事务置 reminded_at（对标 notifyExpiringOnce 防重样板）。
- **通知**：Notification 新 type `CUSTOMER_FOLLOWUP` + 前端 NotificationBell/type 标签中文「客户跟进」登记。
- 越权：跟进页仅本商户（WA 全量，WE 需无/有授权位——见 D-C-9）；OPS/TA 不可见。

### 3.3 契约（已落地 · 2026-09-02）
> C1 已入 `api-contract-storefront` §3.3（`POST /rt/my-pricelist`）；C3 完整契约以架构 `architecture/24-p5-c-c3` §4 为权威，此处摘录定稿基线（同前端 `api/customers.ts` 代码契约）：

- 前缀 `GET|PUT|POST|DELETE /api/v1/tenant/customers`（登录态 TenantContext；wholesaler scope = WA 全量 ∪ WE 授权位）；**customerKey = URL-safe Base64(hmac)** + 回传 `wholesalerId` 收敛（K-2；明文手机号永不入 URL/query/日志）
- `GET /api/v1/tenant/customers?page=&size=` 客户列表（按 wholesaler×rt_phone_hmac 归并、最近询价倒序）：wholesalerName / maskedPhone（打码） / inquiryCount / lastInquiryAt / lastConfirmedAt / lastInquiryId（查全号锚点）/ remark / nextReminderAt / dueReminderCount —— **无 keyword 搜索（K-6）**
- `GET /api/v1/tenant/customers/{customerKey}/detail?wholesalerId=` 详情（列表字段 + reminders 倒序）
- `PUT /api/v1/tenant/customers/{customerKey}/remark` body `{wholesalerId, remark}`（覆盖式 ≤200；空串 = 清除；无提醒清档 K-3）
- `POST /api/v1/tenant/customers/{customerKey}/reminders` body `{wholesalerId, content, remindAt}`（remindAt 须未来 50841；无档案自动建档 K-4）
- `DELETE /api/v1/tenant/customers/{customerKey}/reminders/{reminderId}?wholesalerId=`（本商户 WE 共治可删；清档规则同 remark）
- 查全号：复用 `GET /pii/phone-reveal?biz=INQUIRY&id={lastInquiryId}`，不新增 biz/端点（K-7）
- 迁移 **V39**（K-5 修正：草案 V40 为旧序假设）：`customer_followups` + `followup_reminders`（TenantLine 白名单追加 + cf_/fr_ 前缀索引）
- 错误码（实测）：`CUSTOMER_NOT_FOUND 50840 / REMIND_TIME_INVALID 50841 / REMINDER_NOT_FOUND 50842`

### 3.4 测试要点
1. CF-01 客户列表归并正确（同 phone 多单合并、跨商户隔离、打码）
2. CF-02 备注写读 + 仅本商户可见（B 商户查不到 A 商户备注）
3. CF-03 提醒到点触发：remind_at 过去 → Job 触发站内信给创建 WE + reminded_at 置位（防重：二次跑不重发）
4. CF-04 越权：WE 无授权位不可设提醒/备注（若启用）；WA 可；TA/OPS 不可见
5. CF-05 列表越权盲点：换 wholesaler 收敛

---

## 4. 后端改动清单汇总（架构阶段细化）
- **C1**：Rt 侧新端点 `GET /rt/my-pricelist`（RtInquiry/RtStore Controller 择一放置；PricingService 增 listCustomerPriceForRt：code+phone → 有效期专属价 + sku 公开价对照 + 按 wholesaler 分组，rt_phone_hmac 盲查）；无迁移
- **C2**：V39/V39b（tenant_settings.location_enabled + batches.location + batch_location_logs）；TenantSettings/TenantBatchConfigVo/batch-config 返回体加 locationEnabled；Inbound/Outbound 登记 dto 加 location（=1 必填校验）；BatchService 登记钩子带 location；BatchController 增 location 编辑/日志端点；ErrorCode 50822-50823
- **C3 ✅ 已实现（2026-09-02）**：迁移 **V39**（`customer_followups` wholesaler×rt_phone_hmac 唯一 + `followup_reminders` idx_fr_due/reminded_at CAS，TenantLine 白名单追加）；document 域 CustomerFollowupService/Controller（/api/v1/tenant/customers：list/detail/remark/reminders/delete，PII 解密打码 + 清档规则 + 50840-42）；FollowupReminderJob（每 5 分钟扫描到点提醒，逐条 CAS + 同事务站内信防重发）；Notification type 常量 CUSTOMER_FOLLOWUP；InquiryRequestMapper GROUP BY 聚合查询（无明文/无 keyword）

## 5. 前端改动清单
- **C1**：`rt/Store.vue` 手机号旁「我的价目」入口 → 抽屉（按 wholesaler 分组价目：专属价/公开价对照 +「专属优惠」标）→ 勾选填数量提交询价（下架置灰/空态降级）；api/rt.ts + api-types
- **C2**：`ta/Settings.vue` 加「启用货位」开关；`ta/Inbound.vue` 登记行货位字段（按开关显隐/必填）；`ta/Outbound.vue` 登记拣出货位；`ta/Batches.vue` 货位列 + 行内移库 + 变更记录（时间线）；wa 侧只读相关按开关显隐；api/inbound、outbound、batch、tenant 扩展
- **C3 ✅ 已实现**：`wa/Customers.vue`（列表表格 + 详情抽屉：备注编辑/提醒新建删除/查全号复用）+ 9 个 wa 视图菜单追加「客户跟进」（ChatDotRound 图标，紧跟询价确认）+ 路由 /wa/customers + NotificationBell 新类型中文标签「客户跟进」+ `api/customers.ts` + api-types `tenant.ts` C3 类型（WaCustomer/WaCustomerDetail/WaFollowupReminder + Save/Add Request）

## 6. DECISION 清单（拍板结果 2026-09-02）

| # | 决策点 | 拍板口径 | 状态 |
|---|---|---|---|
| D-C-1 | C2 口径（货位启用开关，关闭时出入库免填） | 货位功能三件套：仓级开关 locationEnabled + 开启后出入库登记货位 + 批次移库（US-WK-05） | 📌 记录（C2 backlog，见 §2 档案） |
| D-C-1b | 货位形态 | 自由文本货位号（≤64，不做货位主档/字典表，预留升级） | 📌 记录 |
| D-C-1c | 出库货位语义 | 登记出库按行指定拣出货位落单明细；不做选批次/批次级扣减（batches 非记账铁律） | 📌 记录 |
| D-C-1d | 货位挂载 | 挂 batches.location + 出库单行；不建 per-location 库存维度 | 📌 记录 |
| D-C-2 | 执行顺序 | **C1→C3**（C2 顺延后续池）；每波一个子项，完成即验证+提交 | ✅ 已落地（双波 2026-09-02 收官） |
| D-C-3 | C1 复购形态（否决「复制历史询价单」） | 走**客户专属价格表**：RT 填手机号 → 取当前店有效 customer_prices 价目 → 勾选 SKU/数量提交询价（取价=专属价命中） | ✅ 已落地 |
| D-C-4 | C1 价目数据范围 | 以 customer_prices 有效行为准，不另建价目表实体；来源不限 | ✅ 已落地 |
| D-C-5 | C1 价格口径 | 提交沿用 submitByRt 现价快照（=当前匹配价专属价） | ✅ 已落地 |
| D-C-6 | C3 客户实体 | 客户=本商户按 rt_phone 归并的询价买家；customer_followups 单行每客户 remark 覆盖式 | ✅ 已落地 |
| D-C-7 | C3 提醒触发 | followup_reminders + 5 分钟扫描 Job 到点站内信给创建 WE（reminded_at 防重） | ✅ 已落地 |
| D-C-8 | C3 备注形态 | remark 单行覆盖式（保留 updated_by/at） | ✅ 已落地 |
| D-C-9 | C3 WE 操作授权 | WE 均可看客户列表、设备注/提醒（不新增授权位） | ✅ 已落地 |

> C1/C3 全默认采纳 → 均已落地（架构 `23-p5-c-c1` / `24-p5-c-c3` → 实现）；C2 作为需求档案记录于 §2，后续单独排波。

## 7. 不做（本轮）
- C1：历史询价单复制、「我的意向单」历史页（待意向单模块按 US-WA-10 匹配规则实现）、收货信息/配送字段、RT 账号体系、语音复购、价目导出/打印
- C2：货位主档/字典表、per-location 库存、选批次出库/批次级扣减、跨仓调拨、托盘与货位绑定、按货位盘点、移库审批流、库存可视化
- C3：RT 买家全平台主档/画像、跟进提醒的微信/短信外发（仅站内信）、客户分组/标签体系、自动跟进建议

## 8. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1.5 | 2026-09-02 | **C3 第二波实现完成（P5-D 小项池 C1+C3 收官）**：架构 `24-p5-c-c3` v1.1（customerKey/wholesaler 行粒度、清档规则、Job CAS 防重、50840-42、V39 修正）；后端全落地（V39 两表 + TenantLine 白名单 + InquiryRequestMapper 聚合 + CustomerFollowupService/Controller 5 端点 + FollowupReminderJob 每 5 分钟 + Notification TYPE_CUSTOMER_FOLLOWUP）+ CustomerFollowupScenarioTest CF-01~05 5/5 绿 + 全量回归 517 全绿；前端 Customers.vue + 9 个 wa 视图菜单 + 路由 + NotificationBell 标签 + customers.ts/api-types（vue-tsc 0 错 + vite build 通过）；§3.3 契约草案修订为落地契约；D-C-2~9 全标记已落地；C2 货位功能仍留 Backlog |
| v1.4 | 2026-09-02 | C1 第一波实现完成：架构 `23-p5-c-c1` v1.1 + 后端（pricing/product 出口 + storefront 编排 + POST /rt/my-pricelist）+ RtPriceListScenarioTest 12/12 绿 + 前端（价目抽屉 + api/api-types）+ 契约 `api-contract-storefront` §3.3；待全量回归与提交后启动 C3 |
| v1.3 | 2026-09-02 | 用户拍板：C1/C3 确认执行（D-C-2~9 全采纳）；**C2（货位+批次）记录为后续需求池 backlog**（D-C-1~1d 记录在案，§2 作需求档案保留）；执行顺序 C1→C3，C1 第一波启动 |
| v1.2 | 2026-09-02 | C2 口径升级：用户补充「货位需启用配置，关闭时出入库免填」→ 货位功能三件套（开关 + 出入库登记货位 + 批次移库），沿 batchEnabled 开关先例；D-C-1~1d 相应重写 |
| v1.1 | 2026-09-02 | C1 口径修订：用户否决「历史询价单复制」，改为**客户专属价目表复购**（US-WA-10 价格体系同源，无新表）；D-C-3/4/5 相应重写 |
| v1 | 2026-09-02 | 首版：C1/C2/C3 三子项现状基线 + 范围 + 契约草案 + DECISION D-C-1~9 待拍板 |
