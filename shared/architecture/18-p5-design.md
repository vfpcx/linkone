# 18 · P5-A 架构设计 v1（通知中心 + 平台公告 + 撮合运营）

> 角色：架构师 Agent ｜ 日期：2026-09-01 ｜ 基线：main @ 70a924f
> 依据：`14-p5-requirements.md` v1.1（DECISION D-P5-1~5 采纳，D-P5-6/7 暂缓/取消）
> 符合声明：本设计符合 17 的**原则 1（边界声明）/ 2（禁跨域直连 mapper）/ 3（共享内核无业务状态）/ 4（接口即契约）**；**不触碰** 17 §3 不做清单（MQ/分布式事务/拆部署/充血模型）；notify 域首次实现，过 17 §6 准入（§8 对照表）。
> 架构符合性：notify 域为新建业务域（17 §4 目录已建、0 文件），首次落地按新模块准入；`notifications` 表归属由 document 正式划归 notify（**仅文档口径校正，零代码改动**，见 §2.1）。

---

## 0. 一句话结论

notify 域首次落地：**通知中心增强（批量已读/分组/公告类）** + **平台公告（OPS 发布 → 站内信 + 登录弹窗）**；tenant 域新增**店铺撮合配置（主推商品/置顶批发商）**，storefront 只读消费；新增 2 张表归属唯一、跨域全部走既有/新增 Service 出口（无 mapper 直连）、本地事务、不碰不做清单。

---

## 1. 范围

| 需求项 | 内容 | 状态 |
|---|---|---|
| A1 通知中心增强 | 批量/全部已读、分类分组（业务/公告/系统）、公告类通知 type、前端消息中心页 | 本期 |
| A2 平台公告 | `announcements` 表 + OPS 管理（发布/下架/列表）+ 发布写站内信 + 登录弹窗 1 次 | 本期 |
| A3 撮合运营 | `storefront_featured` 表 + 店铺设置（主推商品 ≤20 / 置顶批发商 ≤5）+ storefront 出参 | 本期 |
| 容量告警订阅 | — | 暂缓（D-P5-6）|
| 容量公示 viewer 脱敏 | — | 取消（D-P5-7）|

**不做**：短信通道、实时推送（WebSocket）、MQ、分布式事务、公告单用户定向。

---

## 2. 数据模型与归属

### 2.1 归属调整（文档口径校正，零代码改动）

| 表 | 原归属（17 §4）| 新归属 | 理由 |
|---|---|---|---|
| `notifications` | document | **notify** | 实体/Service/Controller 自 P3 起即在 `com.cangchu.notify` 包（12 §4.3），仅 17 §4 与 08 §2 文档口径标错；document 域 30+ 触发点已走 `NotificationService`（合规 G-S2），无直连，校正不影响任何代码 |

> 需同步：17 §4 模块全景表（notify 行改"已实现"，document 行去掉 notifications）；08 §2 数据归属表。

### 2.2 新增表

**`announcements`（归属 notify 域，平台级）**

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| title | VARCHAR(128) | ≤128（与 notifications.title 一致）|
| content | VARCHAR(512) | ≤512 |
| target_roles | VARCHAR(255) | 角色组 KEY 逗号分隔：`ALL` / `OPS` / `TA` / `WK_ST` / `WA_WE`，发布时展开为具体角色 |
| status | VARCHAR(16) | `DRAFT` / `PUBLISHED` / `INACTIVE` |
| published_at | DATETIME | 可空（DRAFT 时为空）|
| published_by | BIGINT | OPS 发布人 |
| created_at / updated_at | DATETIME | 自动填充 |

- **平台级表，无 tenant_id 列**：加入 `MybatisPlusConfig.TENANT_FILTER_TABLES` 忽略名单（先例：`blacklist` 平台级共享表），OPS 管辖，不做租户隔离。

**`storefront_featured`（归属 tenant 域，租户级）**

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id | BIGINT | 纳入 TenantLine 白名单（租户内数据）|
| store_id | BIGINT | 店铺（仓库）|
| kind | VARCHAR(16) | `MAIN_SKU`（主推商品）/ `PIN_WA`（置顶批发商）|
| ref_id | BIGINT | SKU id 或 wholesaler id |
| sort_order | INT | 展示顺序（0 起）|
| created_at / updated_at | DATETIME | 自动填充 |

- 唯一约束 `uk(store_id, kind, ref_id)`（防重复置顶）；`(store_id, kind, sort_order)` 有序。
- 设计取舍：**新表而非 tenant_settings 加列**——列表型配置（可增删/排序/事务）用行式表更干净，且归属唯一（tenant 域），不污染开关型配置表；FK 不进库（项目惯例）。

### 2.3 Flyway

- `V35__p5a_announcements.sql`
- `V36__p5a_storefront_featured.sql`

---

## 3. 模块结构与依赖

### 3.1 新增/增强

| 域 | 新增/增强 | 说明 |
|---|---|---|
| **notify** | `Announcement` entity / `AnnouncementMapper` / `AnnouncementService` / `AnnouncementController`；`NotificationService` 增 `readAll(userId)`、`listMine(..., group)`；`Notification` 增 `TYPE_PLATFORM_ANNOUNCEMENT` + `REF_ANNOUNCEMENT` 常量 | 通知中心增强 + 公告模块 |
| **account** | `AuthService` 增 **`listActiveUserIdsByRoles(roles)`**：平台级反查全平台 ACTIVE 角色用户 id（distinct）| 公告批量写收件人推导（新契约，见 §4）|
| **tenant** | `StorefrontFeatureService`（读写主推/置顶 + 上限/存在性校验）+ `StorefrontFeatureController`（店铺设置接口）| 撮合运营配置 |
| **storefront** | `StoreFrontServiceImpl` 增读撮合配置（走 tenant 域 Service 出口），出参加主推/置顶序 | 只读消费，零 mapper 直连 |

### 3.2 依赖方向（无环，G-S3）

```
document ──▶ notify ──▶ account（AuthService.listActiveUserIdsByRoles）
   ▲             ▲
   │             │
   └─(既有)──────┘        tenant ──▶ account（既有）
                              ▲
storefront ──▶ tenant ──▶ (既有 tenant/product/inventory/pricing 出口)
```

- notify 依赖 account（收件人推导，只读出口）；不依赖 document/tenant 业务域。
- tenant 自持撮合配置（同域 mapper，合规）；storefront 经 tenant 域 Service 读（合规 G-S2，无 mapper 直连，不触碰 17 §5.3 债项 2 的解法）。

---

## 4. 接口契约（api-contract）

### 4.1 notify（扩展既有 + 新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET（扩展）| `/api/v1/notifications?page&size&unreadOnly&group` | `group`：`BIZ`/`ANNOUNCE`/`SYS`（缺省=全部），type→group 映射见 §4.4 |
| POST（新增）| `/api/v1/notifications/read-all` | 本人全部已读（幂等）|
| POST（既有）| `/api/v1/notifications/{id}/read` | 单条已读（本人校验，幂等）|
| GET（既有）| `/api/v1/notifications/unread-count` | 角标轮询 |

### 4.2 公告管理（OPS，新增 `AnnouncementController`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/announcements` | 创建（body：title/content/targetRoles/status=DRAFT）|
| GET | `/api/v1/announcements?page&size&status` | 列表 |
| GET | `/api/v1/announcements/{id}` | 详情 |
| POST | `/api/v1/announcements/{id}/publish` | 发布：DRAFT→PUBLISHED + 同事务批量写站内信 |
| POST | `/api/v1/announcements/{id}/inactivate` | 下架：PUBLISHED→INACTIVE（已发通知保留）|

### 4.3 撮合运营（tenant，新增 `StorefrontFeatureController`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tenant/storefront/featured` | 店铺设置回显（mainSkuIds[]/pinWaIds[] 有序）|
| PUT | `/api/v1/tenant/storefront/featured` | 覆盖保存（body：mainSkuIds[] ≤20、pinWaIds[] ≤5；校验存在性/上限/重复）|

### 4.4 storefront 出参（聚合扩展）

- `StoreFrontVo` 增：`featuredSkuIds`（主推商品 id 序）、`pinnedWholesalerIds`（置顶批发商 id 序）；`StoreSkuVo`/`StoreWholesalerVo` 增 `featured`/`pinned` 布尔标记。
- 列表服务端按主推/置顶**前置排序**，前端直接消费标记（"主推"标）。
- **契约变更登记**：AuthService 新增 `listActiveUserIdsByRoles` 为跨域新契约（原则 4），同步 `api-contract-account.md`；notify/storefront 接口新增同步 api-contract 文档。

### 4.5 type→group 映射

| group | type 前缀 |
|---|---|
| 业务 BIZ | 除 ANNOUNCE/SYS 外全部既有 TYPE_* |
| 公告 ANNOUNCE | `PLATFORM_ANNOUNCEMENT` |
| 系统 SYS | 预留（本期无）|

---

## 5. 错误码（新增段，段位按项目规约核对）

| 码 | 含义 |
|---|---|
| 50701 | 公告不存在 |
| 50702 | 公告状态非法（非 DRAFT 不可发布 / 非 PUBLISHED 不可下架 / 重复发布）|
| 50703 | 目标角色组非法 |
| 50711 | 主推商品超上限（>20）|
| 50712 | 置顶批发商超上限（>5）|
| 50713 | 主推/置顶条目重复 |
| 50714 | 引用无效（非本店在售 SKU / 非本店入驻批发商）|

（保留既有 50341 通知非本人。）

---

## 6. 事务、幂等与弹窗去重

- **公告发布**（`@Transactional`，同事务）：① `announcements.status=DRAFT→PUBLISHED` + published_at/by；② 按 target_roles 展开角色 → `AuthService.listActiveUserIdsByRoles` 推导收件人 → `NotificationService.sendToAll` 批量写站内信（同事务，失败整体回滚）。**状态机保证幂等**：已 PUBLISHED 再发 → 50702。
- **弹窗去重**：**复用 `notifications.readAt`，不新增 Redis seen 键**——登录后首页拉取未读公告通知（最新一条），弹窗展示，用户确认/关闭即 `markRead`（幂等），天然"只弹一次"。与 D-P5-2"Redis 按 (user, announcement) 去重"的差异说明：readAt 语义等价且省设施；若产品后续要求"关闭但保留未读"，再引入 seen 键（留扩展点）。
- **撮合保存**（`@Transactional`，覆盖写）：DELETE `(store_id, kind)` → INSERT 新序列表，同事务；PUT 天然幂等；上限/存在性校验在写前。
- **性能**：公告批量写为平台级全量通知，用户量级小（单仓模式），`sendToAll` 逐条 INSERT 同事务可接受；量级上来后再评（届时再议 MQ，触发条件见 17 §1.3）。

---

## 7. 前端改动

| 位置 | 改动 |
|---|---|
| `apps/admin` | 顶栏铃铛下拉（现有）+ 完整消息中心页（分组 Tab/全部已读/跳转）；登录公告弹窗组件；OPS 公告管理页（列表/发布/下架）；店铺设置新增「撮合运营」区块（主推商品/置顶批发商多选排序）|
| `packages/ui-shared` | `NotificationList` 通用组件（列表/分组/已读/全部已读/跳转），admin 与司机端复用 |
| 司机端入口 | 店铺页主推"标" + 置顶批发商序（storefront 出参消费）；公告弹窗与消息中心复用 ui-shared |
| 角标 | `unread-count` 轮询既有，公告新 type 自动计入 |

---

## 8. 17 评审对照

### 8.1 §6 新模块准入自检（notify 域）

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 边界声明：announcements→notify、storefront_featured→tenant，归属唯一；notifications 归属校正为 notify（文档口径）| ✅ 通过 |
| 2 | 依赖声明：notify→account（AuthService 只读）、storefront→tenant（Service），无 mapper 直连、无他域 entity 直用 | ✅ 通过 |
| 3 | 共享内核：未向 `common` 放业务状态/规则 | ✅ 否 |
| 4 | 契约声明：§4 接口清单 + api-contract-account 同步，首版即契约 | ✅ 提供 |
| 5 | 不做清单：未触碰 MQ/分布式事务/拆部署/充血模型 | ✅ 未触碰 |
| 6 | 编排/事务：公告发布/撮合保存均本地 `@Transactional` + 状态机/覆盖写幂等 | ✅ 通过 |

### 8.2 §8 评审清单

1. 核心边界：notifications 从 document 移出→notify（**边界调整**，需 17 §4/08 §2 同步 + 评审放行；零代码改动）；account/tenant 边界未侵蚀。
2. 跨域直连：无新增（storefront 撮合读取走 tenant Service；notify 收件人走 account Service）。
3. 共享内核：未塞业务。
4. 跨域接口变更：AuthService 新增 `listActiveUserIdsByRoles` → api-contract-account 同步，下游（notify）知晓。
5. 不做清单：未触碰。
6. 依赖无环：§3.2 图 ✅；编排集中在 notify（公告发布）与 tenant（撮合）域内；事务可补偿（回滚）/幂等（状态机/覆盖写）。
7. 新模块准入：§8.1 通过。

---

## 9. 波次映射

| 波次 | 交付物 |
|---|---|
| W2（本文档）| 设计定稿 + 评审通过 |
| W3 | 后端：V35 迁移 + notify 增强（read-all/group/TYPE_PLATFORM_ANNOUNCEMENT）+ Announcement 模块 + AuthService 新增出口 + 错误码；后端全量绿 |
| W4 | 后端：V36 迁移 + StorefrontFeature 模块 + storefront 出参；前端：消息中心/公告弹窗/公告管理/撮合区块；E2E 新增链路 |
| W5 | E2E 全量 + 视觉验收 + 交付报告（`test-plan/14-p5a-delivery-report.md`）|

---

## 10. 待确认

1. **错误码段位**：建议 507xx（notify）/ 5071x（tenant 撮合），需按项目错误码规约核对后定稿。
2. **司机端入口位置**：前端仅 `apps/admin` + `ui-shared`，司机端 H5 页面所在应用/入口需 W4 落实时确认（复用 ui-shared 组件，不新增应用）。
3. **公告"关闭但保留未读"**：本期按"关闭=已读"（readAt 去重）；如产品要求区分，再引入 seen 键（§6 留扩展点）。

---

## 11. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-01 | 首版：notify 域首次实现（通知中心增强 + 平台公告）+ tenant 撮合运营 + storefront 出参；17 §6/§8 对照通过 |
