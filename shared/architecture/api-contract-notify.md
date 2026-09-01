# 站内信与公告模块 · 接口契约（权威 · 以实现为准）

> 项目：仓储云
> 版本：v1 · 2026-09-01
> 编写：架构师 Agent
> 依赖：04-api-spec.md（通用约定）/ 05-error-codes.md（错误码）/ 18-p5-design.md（设计口径 §4.1/§4.2/§4.4/§5）
> 状态：**已对齐当前后端实现**（P5-A W3 合入，commit c366077；单一事实源 / Single Source of Truth）
> 归属：notify 域（`com.cangchu.notify`；`notifications`、`announcements` 表唯一归属 notify 域，见 17 §4/08 §2）

---

## 0. 文档说明

本文档固化 **notify 域**（站内信 / 平台公告）的请求/响应/错误码契约，**以后端实际代码为准**，覆盖两类接口：

- **站内信**（`NotificationController`，路径前缀 `/api/v1/notifications`）：本人消息列表（含分组筛选）/ 单条已读 / 全部已读 / 未读数。登录用户即可访问（收件人恒为本人）。
- **平台公告**（`AnnouncementController`，路径前缀 `/api/v1/ops/announcements`）：公告创建 / 列表 / 详情 / 发布 / 下架。仅平台 OPS 角色可操作（Service 层 `hasRole("OPS")` 校验，不信任客户端）。

权威来源（核对依据）：
- Controller：`backend/.../notify/controller/NotificationController.java`、`AnnouncementController.java`
- Service：`backend/.../notify/service/impl/NotificationServiceImpl.java`、`AnnouncementServiceImpl.java`
- 请求 DTO：`backend/.../notify/dto/AnnouncementCreateDto.java`
- 响应 VO：`backend/.../notify/vo/NotificationVo.java`、`AnnouncementVo.java`

---

## 1. 通用约定

- 统一响应包装 `R<T>`：`{ code, message, data }`，`code=0` 成功；非 0 见 §5 错误码。
- 雪花 ID（`id` / `refId` / `publishedBy`）后端用 `ToStringSerializer` 序列化为 **string**，前端按 string 处理。
- 时间字段（`readAt` / `createdAt` / `publishedAt`）为 `LocalDateTime`（**无时区偏移**）——注意区别于 account 域 `LoginVo.expireAt` 的 `OffsetDateTime`（带 `+08:00`）。
- 分页：MyBatis Plus `Page<T>` 结构：`{ records, total, size, current, pages }`，`records` 为 VO 数组。
- 鉴权：`/api/v1/notifications/**` 需登录（收件人=当前登录用户，前端不可指定他人）；`/api/v1/ops/announcements/**` 需平台 OPS 角色。

---

## 2. 站内信接口（`/api/v1/notifications`，登录用户）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | GET | `/api/v1/notifications?page=&size=&unreadOnly=&group=` | 我的消息列表（分页，按 `createdAt` 倒序） |
| 2 | POST | `/api/v1/notifications/read-all` | 本人全部已读（幂等） |
| 3 | GET | `/api/v1/notifications/unread-count` | 我的未读数（铃铛角标轮询） |
| 4 | POST | `/api/v1/notifications/{id}/read` | 标记单条已读（本人校验，幂等） |

### 2.1 我的消息列表 `GET /api/v1/notifications`

Query 参数：

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `page` | int | 否 | 1 | 页码（≥1） |
| `size` | int | 否 | 20 | 每页条数 |
| `unreadOnly` | boolean | 否 | false | `true` 仅返回未读 |
| `group` | string | 否 | 空=全部 | 分组筛选：`BIZ` / `ANNOUNCE` / `SYS` / `ALL`（非法值 → `40003`） |

`group` 分组口径（type→group 映射，18-p5-design §4.4）：

| group | 口径 |
|---|---|
| `BIZ` | 全部业务通知（type ≠ `PLATFORM_ANNOUNCEMENT`） |
| `ANNOUNCE` | 平台公告（type = `PLATFORM_ANNOUNCEMENT`） |
| `SYS` | 系统通知（本期无数据，恒返回空） |
| `ALL` / 空 | 全部 |

响应：`R<Page<NotificationVo>>`，`records` 元素见 §4.1。

### 2.2 全部已读 `POST /api/v1/notifications/read-all`

无 body。本人 scope，幂等（重复调用不报错）。响应 `R<Void>`。

### 2.3 未读数 `GET /api/v1/notifications/unread-count`

响应：`R<{ count: number }>`（本人未读消息总数，公告新 type 自动计入角标）。

### 2.4 标记已读 `POST /api/v1/notifications/{id}/read`

- `{id}`：消息 id（string 雪花 ID）。
- 本人校验：**非本人 / 不存在一律抛 `50341`**（不泄漏他人消息存在性）；重复标记幂等。
- 响应 `R<Void>`。

---

## 3. 平台公告接口（`/api/v1/ops/announcements`，OPS）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | POST | `/api/v1/ops/announcements` | 创建公告草稿（status=DRAFT），返回新公告 id |
| 2 | GET | `/api/v1/ops/announcements?page=&size=&status=` | 公告列表（可按 status 过滤，`createdAt` 倒序） |
| 3 | GET | `/api/v1/ops/announcements/{id}` | 公告详情 |
| 4 | POST | `/api/v1/ops/announcements/{id}/publish` | 发布：DRAFT→PUBLISHED + 同事务批量写目标角色站内信 |
| 5 | POST | `/api/v1/ops/announcements/{id}/inactivate` | 下架：PUBLISHED→INACTIVE（已发站内信保留） |

> 非 OPS 角色操作任一接口 → `42002`。公告状态机：`DRAFT → PUBLISHED → INACTIVE`（**不可逆**；非法迁移 → `50502`）。

### 3.1 创建公告 `POST /api/v1/ops/announcements`

```json
{ "title": "平台维护公告", "content": "本周六 02:00-04:00 系统维护", "targetRoles": ["ALL"] }
```

| 字段 | 必填 | 校验 | 说明 |
|---|---|---|---|
| `title` | 是 | 非空，≤128 字 | 公告标题 |
| `content` | 是 | 非空，≤512 字 | 公告正文 |
| `targetRoles` | 是 | 非空数组；每个元素须为合法角色组 KEY | 目标角色组（见 §4.3），非法组 → `50503` |

响应：`R<Long>`（新公告 id，string）。

### 3.2 公告列表 `GET /api/v1/ops/announcements`

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `page` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 20 | 每页条数（上限 100） |
| `status` | string | 否 | 空=全部 | 按状态精确过滤：`DRAFT` / `PUBLISHED` / `INACTIVE` |

响应：`R<Page<AnnouncementVo>>`，按 `createdAt` 倒序，元素见 §4.2。

### 3.3 公告详情 `GET /api/v1/ops/announcements/{id}`

- `{id}`：公告 id（string）。不存在 → `50501`。
- 响应：`R<AnnouncementVo>`。

### 3.4 发布公告 `POST /api/v1/ops/announcements/{id}/publish`

- 状态机：仅 `DRAFT` 可发布；非 DRAFT → `50502`。状态机先行保证**幂等**（已 PUBLISHED 再发 → `50502`）。
- 同事务（`@Transactional`）：① `status=DRAFT→PUBLISHED` + `publishedAt`/`publishedBy`；② 按 `targetRoles` 展开角色 → `AuthService.listActiveUserIdsByRoles` / `listAllActiveUserIds` 推导收件人 → `NotificationService.sendToAll` 批量写站内信（平台级 `tenantId=null`；type=`PLATFORM_ANNOUNCEMENT`、refType=`REF_ANNOUNCEMENT`、refId=公告 id）。任一失败整体回滚。
- 目标角色组无 ACTIVE 用户时：仅落状态不发信（不报错）。
- 响应 `R<Void>`。

### 3.5 下架公告 `POST /api/v1/ops/announcements/{id}/inactivate`

- 状态机：仅 `PUBLISHED` 可下架；非 PUBLISHED → `50502`。已发站内信保留。
- 响应 `R<Void>`。

---

## 4. 字段与枚举

### 4.1 NotificationVo（消息）

| 字段 | 类型 | 必返回 | 说明 |
|---|---|---|---|
| `id` | string(雪花) | 是 | 消息 ID |
| `type` | string | 是 | 通知类型（`TYPE_*`，P5-A 新增 `PLATFORM_ANNOUNCEMENT` 平台公告） |
| `title` | string | 是 | 标题（≤128） |
| `content` | string | 是 | 正文（≤512） |
| `refType` | string \| null | 否 | 跳转引用类型（`REF_*`，可空） |
| `refId` | string(雪花) \| null | 否 | 跳转引用 id（可空） |
| `readAt` | string(datetime) \| null | 否 | 已读时间；`null` = 未读 |
| `createdAt` | string(datetime) | 是 | 创建时间 |

### 4.2 AnnouncementVo（公告）

| 字段 | 类型 | 必返回 | 说明 |
|---|---|---|---|
| `id` | string(雪花) | 是 | 公告 ID |
| `title` | string | 是 | 标题 |
| `content` | string | 是 | 正文 |
| `targetRoles` | string[] | 是 | 角色组 KEY 数组（出参展开为数组，便于前端渲染） |
| `status` | string | 是 | `DRAFT` / `PUBLISHED` / `INACTIVE` |
| `publishedAt` | string(datetime) \| null | 否 | 发布时间（DRAFT 为 null） |
| `publishedBy` | string(雪花) \| null | 否 | 发布人（DRAFT 为 null） |
| `createdAt` | string(datetime) | 是 | 创建时间 |

### 4.3 角色组 KEY 与展开映射

| 组 KEY | 发布时展开为具体角色 | 收件人范围 |
|---|---|---|
| `ALL` | 全部 | 全平台全部 ACTIVE 用户（`AuthService.listAllActiveUserIds`） |
| `OPS` | OPS | 全平台 ACTIVE OPS |
| `TA` | TA | 全平台 ACTIVE TA |
| `WK_ST` | WK、ST | 全平台 ACTIVE WK + ST |
| `WA_WE` | WA、WE | 全平台 ACTIVE WA + WE |

> 展开后收件人推导走 account 域 `AuthService` 平台级反查（无租户过滤，18-p5-design §3.2；契约登记见 `api-contract-account.md` §5.9）。

### 4.4 通知类型与公告常量

- `Notification.TYPE_PLATFORM_ANNOUNCEMENT = "PLATFORM_ANNOUNCEMENT"`（P5-A W3：目标角色「公告」分组常驻 + 登录弹窗 1 次，弹窗去重口径见 18-p5-design §6）。
- `Announcement.VALID_GROUPS = {ALL, OPS, TA, WK_ST, WA_WE}`；`Announcement.GROUP_*` 常量即上表组 KEY。

---

## 5. 错误码（notify 相关，详见 05-error-codes.md）

| code | 常量 | 场景 |
|---|---|---|
| 50341 | `NOTIFICATION_NOT_FOUND` | 消息不存在（markRead 非本人一律按不存在处理，不泄漏存在性） |
| 50501 | `ANNOUNCEMENT_NOT_FOUND` | 公告不存在 |
| 50502 | `ANNOUNCEMENT_STATE_INVALID` | 公告状态不允许此操作（非 DRAFT 发布 / 非 PUBLISHED 下架 / 重复发布） |
| 50503 | `ANNOUNCEMENT_TARGET_ROLES_INVALID` | 目标角色组非法 |
| 40003 | `VALIDATION_BASIC_003` | 站内信列表 `group` 参数非法 |
| 40001 | `VALIDATION_BASIC_001` | 公告创建参数校验失败（标题/正文/角色组非空或超长） |
| 42002 | `PERMISSION_ROLE_002` | 平台操作仅限平台运维角色（非 OPS 操作公告） |

> ⚠️ 与 18-p5-design §5 草案差异：设计稿建议公告错误码 `50701-50703`；**实现定稿为 `50501-50503`**（`ErrorCode.java` 注释「P5-A 平台公告，50500+，归属 notify 域」）。撮合 `50711-50714`（tenant 域）与设计稿一致，见 `api-contract-storefront.md`。**以本契约为准。**

---

## 6. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-01 | 首版：固化 notify 域契约——站内信（列表含 `group` 分组 / read-all / unread-count / markRead）与平台公告（创建 / 列表 / 详情 / 发布 / 下架 + 收件人推导 + 状态机），错误码以实现为准（50501-50503）。 |
