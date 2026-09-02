# 24 · P5-D C3「客户跟进（US-WE-04）」设计与契约定稿

> 项目：仓储云 · 波次 spec（C3 实现波，随 17-p5-c-smallpool §3 需求档案）
> 版本：v1.1 · 2026-09-02
> 编写：架构师 Agent（v1.0 实现前定稿 → v1.1 实现收官标记）
> 依赖：17-p5-c-smallpool.md（需求与 DECISION D-C-6~9）、04-api-spec、05-error-codes、09-pricing-design（客户=rt_phone 口径）、15-pii-hardening-v2（PII 盲查/脱敏/查全号）、12 §4.3（通知）、18 §4.4（消息中心）

---

## 1. 目标与本波范围（最小可验证）

WE/WA 在 wa 侧查看「本商户询价买家」并按客户维护备注、设置跟进提醒；提醒到点经站内信送达创建人（WE）。全链路单闭环：

1. wa 客户列表：当前工作空间 tenant + 当前登录人归属 wholesaler 下的询价买家，按 **（wholesaler × rt_phone_hmac）归并** 统计（每商户一份客户档案，D-C-6）；
2. 行操作（点行抽屉）：查看打码手机号 + 统计 + **备注编辑（覆盖式）** + **跟进提醒管理（到点站内信）**；
3. 查全号：**复用既有** `GET /pii/phone-reveal?biz=INQUIRY&id={lastInquiryId}`，不新增 biz/端点；
4. 通知：Notification 新 type `CUSTOMER_FOLLOWUP`（前端铃铛中文「客户跟进」）。

不做（见 17 §7）：RT 买家全平台主档/画像、微信/短信外发、分组/标签、自动跟进建议、列表关键字搜索（keyword 涉及明文手机号落 GET 日志 → 本轮不做；后续如需走 POST body hmac 精确匹配）。

---

## 2. 关键口径定稿（草案→定稿差异备注）

| # | 口径 | 定稿 |
|---|---|---|
| K-1 | 客户行粒度 | **（wholesaler × rt_phone_hmac）**：列表天然含 wholesalerId 行维度；同 hmac 跨商户 = 独立客户行（各自档案），与 D-C-6「本商户买家」一致 |
| K-2 | 客户操作键 | `customerKey` = URL-safe Base64(hmac)（去 `=` padding、`+`→`-`、`/`→`_`）；所有 detail/备注/提醒端点以 path 传 key + query 回传 `wholesalerId`（归属收敛用），**明文手机号永不入 URL/query/日志** |
| K-3 | 备注形态 | `customer_followups` 单行/商户/客户；remark 覆盖式（≤200，空串=清除备注）；无 remark 且无未删提醒 → 清理档案行（不留空壳） |
| K-4 | 提醒建档 | 首次设提醒即建 followup 档案（remark 可空），reminder 行绑 followup_id；同时冗余存 `rt_phone_cipher`（Job 站内信正文打尾号用，避免跨域回查询价单） |
| K-5 | 版本号修正 | 需求档案草案写「V40」为早期旧序假设；**实测最新迁移 V38 → 本波实际为 V39**（C2 backlog 届时顺延） |
| K-6 | 列表搜索 | **无 keyword**（明文落日志 + 无打码可搜串）；全量分页（页内按最近询价倒序）；文档 17 §3.3 契约草案据此修订 |
| K-7 | 越权语义 | wholesaler 不在当前登录人 scope → 一律 **50840 CUSTOMER_NOT_FOUND**（假装不存在，防枚举，沿用 PII 404 语义）；本商户 WE（含无授权位）可看列表与设备注/提醒（D-C-9，不新增授权位） |
| K-8 | Job 通知正文 | title「客户跟进提醒」；content=`{内容}（客户尾号{last4}）`（last4 由 followup.cipher 解密；失败回落空尾号不阻断） |

---

## 3. 数据模型（V39__p5d_c3_customer_followups.sql）

单文件标准 SQL（沿 V35/V36 先例，H2 MODE=MySQL 兼容；索引名加 `cf_`/`fr_` 前缀防 H2 全局重名）。

### 3.1 `customer_followups`（客户跟进档案，单行/商户/客户）
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id | BIGINT NOT NULL | 归属租户（TenantLine 白名单） |
| wholesaler_id | BIGINT NOT NULL | 归属商户（业务层 wholesaler 收敛） |
| rt_phone_hmac | VARCHAR(64) NOT NULL | RT 手机号盲索引 |
| rt_phone_cipher | VARCHAR(255) NULL | 冗余密文（Job 正文尾号；建档/更新时自最新询价单复制） |
| remark | VARCHAR(200) NULL | 跟进备注（覆盖式，空=清除） |
| created_by | BIGINT NOT NULL | 建档操作人（WE/WA） |
| updated_by | BIGINT NULL | 最后更新人 |
| created_at / updated_at | DATETIME | 自动填充 |
| UNIQUE `uk_cf_wholesaler_hmac`(wholesaler_id, rt_phone_hmac) | | 每商户每客户唯一档案 |
| KEY `idx_cf_tenant`(tenant_id) | | |

### 3.2 `followup_reminders`（跟进提醒）
| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id | BIGINT NOT NULL | TenantLine 白名单 |
| wholesaler_id | BIGINT NOT NULL | 冗余（收敛校验/Job 直取） |
| customer_followup_id | BIGINT NOT NULL | 档案外键（无级联，删除提醒单独处理） |
| content | VARCHAR(200) NOT NULL | 提醒内容（≤200） |
| remind_at | DATETIME NOT NULL | 提醒时点（须晚于 now，K-4/50841） |
| reminded_at | DATETIME NULL | 触发时刻（空=未触发；Job 防重 CAS 位） |
| created_by | BIGINT NOT NULL | 创建人（=站内信收件人） |
| created_at | DATETIME | 自动填充 |
| KEY `idx_fr_due`(remind_at, reminded_at) | | Job 扫描谓词 |
| KEY `idx_fr_followup`(customer_followup_id) | | |

### 3.3 注册
- MybatisPlusConfig `TENANT_FILTER_TABLES` 追加 `customer_followups`、`followup_reminders`（走 tenant_id 兜底隔离；Job 系统态无 TenantContext → 不注入，扫描全量，行内带 tenant 入通知）。
- Notification.type 追加 `CUSTOMER_FOLLOWUP`；REF 不新增（站内信无跳转落地页）。

---

## 4. API 契约（前缀 `/api/v1/tenant/customers`，SaInterceptor 登录拦截覆盖）

> 均为 WA/WE 视图（前端路由 /wa/customers）。tenantId 一律取登录态 TenantContext（X-Tenant-Id 工作空间），wholesaler scope 取 `AuthService.listActiveWholesalerIds(user,"WA",t) ∪ listActiveWeWholesalerIds(user,t)`（同 listForWa 口径）。

### 4.1 客户列表 `GET /api/v1/tenant/customers?page=&size=`
响应 `R<Page<CustomerListItemVo>>`：

| 字段 | 说明 |
|---|---|
| wholesalerId / wholesalerName | 商户（行维度 = 商户 × 客户） |
| customerKey | K-2 URL-safe base64(hmac)，detail/编辑回传用 |
| maskedPhone | 138\*\*\*\*6666（最新单 cipher 解密打码；解密失败回落 `尾号****`，不 500） |
| inquiryCount / lastInquiryAt | 询价次数 / 最近询价时间（该商户下） |
| lastConfirmedAt | 最近成交（CONFIRMED 单）时刻；无则 null |
| lastInquiryId | 最新询价单 id（**查全号锚点**：reveal biz=INQUIRY） |
| remark / remarkUpdatedAt | 档案备注（无档案 null） |
| nextReminderAt | 最近未触发提醒时点（min remind_at where reminded_at IS NULL）；无 null |
| dueReminderCount | 已到点未触发条数（≤now 且 reminded_at null，前端可标红提示；Job 触发后归零） |

分页：`Page` 通用出参（total/records）；SQL = `inquiry_requests` 按 (wholesaler_id, rt_phone_hmac) GROUP BY 聚合，join 无（档案/提醒按页内 hmacs 二次查询内存装配）。

### 4.2 客户详情 `GET /api/v1/tenant/customers/{customerKey}/detail?wholesalerId=`
响应 `R<CustomerDetailVo>`：CustomerListItemVo 全部 + reminders 列表（倒序，各含 id/content/remindAt/remindedAt/createdBy/createdAt）。

### 4.3 备注覆盖 `PUT /api/v1/tenant/customers/{customerKey}/remark`
body `{ wholesalerId, remark }`（remark ≤200 可空串）。空串=清除备注（无提醒则清档）。响应 `R<CustomerFollowupVo>`（或空）。

### 4.4 新建提醒 `POST /api/v1/tenant/customers/{customerKey}/reminders`
body `{ wholesalerId, content, remindAt }`（content ≤200 必填；remindAt 须未来 → 50841）。无档案自动建档。响应 `R<FollowupReminderVo>`。

### 4.5 删除提醒 `DELETE /api/v1/tenant/customers/{customerKey}/reminders/{reminderId}?wholesalerId=`
仅本人创建可删？——否：本商户 scope 均可删（WE 共治，与备注同权，K-7）；删除后清档规则同 4.3。响应空。

### 4.6 查全号（复用，无新端点）
`GET /api/v1/pii/phone-reveal?biz=INQUIRY&id={lastInquiryId}`（PII 鉴权/审计既有；WE 需 INQUIRY_CONFIRM，WA 直通——与 15 §4 阶段2 一致，不改）。

### 4.7 错误码（ErrorCode 508xx 首占用）
| code | 常量 | 场景 |
|---|---|---|
| 50840 | CUSTOMER_NOT_FOUND | customerKey/wholesalerId 不匹配本 scope 内任何询价客户（含越权假装不存在） |
| 50841 | REMIND_TIME_INVALID | remindAt 不在未来（≤now） |
| 50842 | REMINDER_NOT_FOUND | 提醒不存在或不属于该客户档案 |
| 40001 | VALIDATION_BASIC_001 | content/remark 超长等参数校验 |

---

## 5. Job 与通知

**FollowupReminderJob**（document/job，`@Scheduled(cron="0 5/5 * * * ?")` 每 5 分钟，SchedulingConfig 注释清单登记）：
- 扫描：`remind_at <= now AND reminded_at IS NULL`（取前 N 批防长阻塞，如 LIMIT 200）；
- 逐条同事务：`notificationService.send(tenantId, createdBy, CUSTOMER_FOLLOWUP, "客户跟进提醒", content(含尾号), null, null)` + CAS `UPDATE followup_reminders SET reminded_at=now WHERE id=? AND reminded_at IS NULL`（0 行 = 并发重复 → 跳过不重发）；
- 收件人为 null/通知失败等异常：记日志不中断（Job 惯例），reminded_at 未置位下次轮询重试；
- 单实例假设沿 12 §8.4（多副本前须 ShedLock，SchedulingConfig 注明）。

通知落地页：站内信无跳转（refType=null）；NotificationBell type 中文「客户跟进」。

---

## 6. 归属与改动清单

后端（全部 document 域，遵循 G-S1/G-S2）：
- `document/entity/CustomerFollowup.java`、`document/entity/FollowupReminder.java`
- `document/mapper/CustomerFollowupMapper.java`、`FollowupReminderMapper.java`；`InquiryRequestMapper` 增聚合查询（GROUP BY 分页）
- `document/service/CustomerFollowupService.java`（wa 客户：list/detail/remark/reminders/delete + 清档规则）；`document/controller/CustomerController.java`（`/api/v1/tenant/customers`）
- `document/vo/CustomerListItemVo`/`CustomerDetailVo`/`FollowupReminderVo`/`CustomerFollowupVo`；`document/dto/CustomerRemarkDto`/`CustomerReminderDto`
- `document/job/FollowupReminderJob.java`
- 跨域出口消费：AuthService（wholesaler scope）、NotificationService.send、PiiCrypto、SmsUtil.maskPhone、tenant 域 WholesalerService 名称读取（G-S2，不直连 mapper）
- 横切：`notify/entity/Notification` type 常量、`common/config/MybatisPlusConfig` 白名单、`common/exception/ErrorCode` 50840-42、SchedulingConfig 注释、迁移 V39

前端（apps/admin）：
- 新页 `views/wa/Customers.vue`（列表 table + 详情抽屉：备注编辑 + 提醒列表/新建/删除 + 查全号按钮复用 Inquiry.vue 先例）
- 路由 `/wa/customers`（meta role WA）+ 各 wa 视图 `menus` 数组追加「客户跟进」项（图标准入）
- NotificationBell type→中文「客户跟进」
- api：`api/wa`（或就近模块）增 customers；api-types 增 Customer 系列类型

测试（后端场景 `CustomerFollowupScenarioTest`，CF-01~05）：
- CF-01 列表归并：同 phone 2 单 → inquiryCount=2 单行；另一 wholesaler 客户互不串（WA scope 只含本商户）
- CF-02 备注写读覆盖 + 隔离：A 商户建档，B 商户（同 hmac）detail/remark 均 50840/无档案
- CF-03 提醒到点：remind_at 过去 → Job 触发站内信（收件=创建人）+ reminded_at 置位；重跑不重发
- CF-04 越权：非本商户 wholesalerId → 50840；OPS/TA 不属 wa scope → 空列表/不可见
- CF-05 清档规则：删唯一提醒 + 空 remark → followup 行清除；非空 remark 保留

---

## 7. 验证清单
- [x] 后端场景测试 CF-01~05 全绿（`mvn test "-Dtest=CustomerFollowupScenarioTest"`）+ 全量回归 **517 全绿**（500 基线 + C1 12 + C3 5，0 失败 0 错误）
- [x] 前端 vue-tsc typecheck 0 error（仓库未配置 eslint 配置文件，lint 不适用；格式/风格手动对齐 wa 系页面）+ vite build 通过
- [x] 契约文档补齐：本文档 §4 为 C3 端点权威契约（同 04-api-spec 风格字段表）；产品文档 17 §3.3 已修订为落地契约摘录；代码契约 `api/customers.ts` + api-types `tenant.ts` C3 类型
- [ ] 全量回归终跑 + 提交（随 C 小项池双波 commit）

## 8. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1.1 | 2026-09-02 | C3 实现收官：V39 两表 + TenantLine 白名单 + ErrorCode 50840-42 + Notification TYPE_CUSTOMER_FOLLOWUP + CustomerFollowupServiceImpl（聚合/备注覆盖/提醒 CRUD/Job CAS 防重/清档）+ CustomerFollowupScenarioTest CF-01~05 5/5 绿 + 全量回归 517 全绿；前端 Customers.vue（列表+详情抽屉）+ 9 个 wa 视图菜单「客户跟进」+ 路由 + NotificationBell 标签 + customers.ts/api-types（vue-tsc 0 错 + vite build 通过）；§3.3/§7 相应标记落地；提交随 C 小项池双波 docs 收官 |
| v1.0 | 2026-09-02 | 首版：C3 契约定稿（customerKey/wholesaler 行粒度/清档规则/Job 防重/50840-42；V39 修正） |
