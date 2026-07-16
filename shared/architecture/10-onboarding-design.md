# 10 · P2 入驻生态设计（Wave1 入驻主链 + Wave2 R13 退驻/R14 强制下架）

> 据实现编写（backend feat/p2-onboarding，2026-07-16 更新至 Wave2）。蓝图依据
> `03-database-schema.sql` §3.2/§3.3、`04-api-spec.md`，先例复用 tenant_applications 双轨审批（决策 O-1）。
> §1–§9 为 Wave1（入驻主链），§10 起为 Wave2（R13/R14）。WE 员工 → Wave3，本文档暂不含。

## 1. 表结构（V10__init_onboarding.sql）

### 1.1 wholesaler_applications（批发商入驻申请，TenantLine 隔离）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 ID |
| tenant_id | BIGINT NOT NULL | 目标租户（TA 审批侧隔离维度） |
| applicant_user_id | BIGINT NOT NULL | 申请人（WA 账号） |
| name | VARCHAR(128) NOT NULL | 商户名称 |
| contact_name / contact_phone | VARCHAR(64)/VARCHAR(20) | 联系人 / 联系电话（黑名单键） |
| license | VARCHAR(512) | 营业执照号/凭证（黑名单键；与 wholesalers.license 现状对齐，蓝图 license_no/url 合并占位） |
| status | VARCHAR(16) | PENDING / APPROVED / REJECTED |
| source | VARCHAR(24) | SELF_APPLY / OPS_CREATED / TA_SELF_OPERATED |
| auth_basis | VARCHAR(512) | OPS 代建授权依据（TA 授权凭据文本或客诉单号，OPS_CREATED 必填留痕） |
| audit_user_id / audited_at / audit_remark | — | 审核三件套（驳回必填 remark） |
| wholesaler_id | BIGINT NULL | 通过后回填的主体 id（双轨回填） |
| created_at / updated_at / deleted_at | — | 通用三列（软删） |

索引：`idx_wsapp_tenant_status(tenant_id,status,created_at)`、`idx_wsapp_applicant(applicant_user_id)`。

### 1.2 blacklist（平台黑名单，PLATFORM_TABLE，决策 O-6 不做租户隔离）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 ID |
| target_type | VARCHAR(16) | PHONE / LICENSE_NO（手机号/执照号双键） |
| target_value | VARCHAR(64) | 被拉黑的值，`uk_blacklist_type_value(target_type,target_value)` 唯一 |
| reason | VARCHAR(512) NOT NULL | 加黑原因 |
| operator_user_id | BIGINT NOT NULL | OPS 操作人（= 任务口径的 created_by 语义） |
| status | VARCHAR(16) | ACTIVE / REMOVED（解除保留追溯，不物理删） |
| removed_at | DATETIME | 解除时间 |

### 1.3 wholesalers 补列

- `withdraw_apply_at DATETIME NULL`——退驻申请时间（60 天可恢复）。**本波只建列**，R13 逻辑 Wave2 落地。

## 2. 端点清单

| 方法/路径 | 角色 | 说明 |
|---|---|---|
| POST `/api/v1/wholesaler/applications` | 登录用户(WA) | 自助申请。body: `{targetTenantId, name, contactName?, contactPhone?, license?}` → `{applicationId, status}` |
| GET `/api/v1/tenant/wholesaler-applications?page&size&status` | TA | 分页列表（TenantContext 推导租户 + TenantLine 兜底 + 显式 eq 双保险）→ `{records, total, page, size}` |
| POST `/api/v1/tenant/wholesaler-applications/{id}/audit` | TA | AuditDto `{action: APPROVED\|REJECTED, remark}`；驳回必填 remark → `{applicationId, status, wholesalerId?}` |
| POST `/api/v1/admin/wholesalers` | OPS | 代建。body: `{tenantId, name, waPhone, contactName?, license?, authBasis(必填)}` → `{wholesalerId, tenantId, waUserId, waRoleId, applicationId, status, source}` |
| GET/POST/DELETE `/api/v1/ops/blacklist(/{id})` | OPS | 黑名单管理（list 可按 status 过滤；add 校验 type 白名单；delete=置 REMOVED） |

路由登录拦截：`/api/v1/wholesaler/**`、`/api/v1/ops/**` 已加入 SaTokenConfig checkLogin 段（Wave1 新增）。
鉴权惯例：无注解式，Service 内 `authService.hasRole(...)` 显式校验（TA 带租户维度、OPS 平台维度）；
tenantId 一律 TenantContext 推导，绝不信任客户端（伪造 X-Tenant-Id 由 TenantInterceptor G-2.1 拦为 42101）。

## 3. 状态机（申请单）

```
[*] ──WA自助/注册直申──▶ PENDING ──TA APPROVED──▶ APPROVED（建 Wholesaler ACTIVE + 回填 wholesaler_id + WA 角色绑定）
                          │
                          └────TA REJECTED（remark 必填）──▶ REJECTED（可重新申请）
[*] ──OPS 代建（authBasis 必填）──▶ APPROVED（直挂，主体 source=OPS_CREATED）
[*] ──TA 自营 createSelfOperated──▶ APPROVED（留痕，主体行为兼容仍 SELF_OPERATED）
```

- **仅 PENDING 可审**；跨租户/不存在统一 50203（不泄漏他租户申请存在性）。
- **并发审批 CAS**（测试计划 CON 组）：状态翻转用数据库条件更新
  `UPDATE ... SET status=?,audit_* WHERE id=? AND tenant_id=? AND status='PENDING'`，
  affected=0 → 50203；不依赖内存判断。APPROVED 副作用（建主体/绑角色/回填）在 CAS 抢占成功后同事务执行，失败整体回滚。
- **唯一性**：一个 WA 账号仅一个 ACTIVE 入驻（listActiveWholesalerIds 判定 → 50204）/
  仅一个 PENDING 申请（→ 50201）；审批建主体撞 `uk_wholesaler_tenant_id_name` → 50231。

## 4. 三条入驻路径与黑名单（R-04）

| 路径 | 入口 | 黑名单检查 | 留痕 |
|---|---|---|---|
| WA 自助 | POST /wholesaler/applications | 申请账号手机号 + 表单联系电话 + license | PENDING 申请单 |
| WA 注册直申 | AccountServiceImpl.register（targetTenantId 非空） | 注册手机号；命中 50205 → **注册整体回滚** | PENDING 申请单（source=SELF_APPLY） |
| OPS 代建 | POST /admin/wholesalers | waPhone + license（**决策 O-2：代建同样拦截**） | APPROVED 申请单 + auth_basis + 日志 |
| TA 自营 | POST /tenant/wholesalers（既有） | waPhone + license（**测试计划 BLK-S1-05：防第三条路径绕过**） | APPROVED 申请单（source=TA_SELF_OPERATED），主体行为兼容不变 |

黑名单解除：`DELETE /ops/blacklist/{id}` 置 REMOVED（保留追溯）；重加同键值复活该条目。

## 5. WA 账号开通复用

- `WholesalerService.provisionWaAccount(tenantId, wholesalerId, waPhone, operator)`：
  原私有 ensureWaAccount 公开化——幂等查/建 User（临时密码，registerSource=WA_PROVISION）+
  `authService.ensureWholesalerRole` 幂等绑定 (WA, tenantId, wholesalerId, ACTIVE)。
- `ensureWaUser(waPhone)`：仅查/建用户，供 OPS 代建在插 wholesalers（owner_user_id NOT NULL）前取负责人 id。
- 审批通过路径申请人账号已存在，直接 `ensureWholesalerRole` 绑定。

## 6. 错误码（详见 05-error-codes.md）

50201 审核中重复申请 / 50202 已退驻(Wave2 用) / 50203 申请不存在或状态不可审(含跨租户、并发抢占) /
50204 重复入驻 / 50205 黑名单拦截（三路径同检）；溢出段：50310 黑名单条目已存在 / 50311 条目不存在。

## 7. 隔离与配置

- `wholesaler_applications` **已加入** `MybatisPlusConfig.TENANT_FILTER_TABLES`（SEC-S4-06）；
  `blacklist` 平台级**不加**（决策 O-6）。
- 申请人（WA 无租户绑定）上下文 tenantId 为空 → TenantLine 不注入，重复申请计数为全平台维度
  （与「一个 WA 账号只入驻一个仓库」一致）；TA 侧查询自动注入 + 显式 eq 双保险。

## 8. 测试（OnboardingScenarioTest，15 用例全绿；backend 全量 142 绿）

ONB-01 主链通过+角色生效 / ONB-02 驳回流 / ONB-03 重复 PENDING 50201 / ONB-04 OPS 代建+缺 authBasis /
ONB-05 注册直申建单 / ONB-06 TA 自营留痕 / BLK-01 拦自助+解除放行 / BLK-02 拦 OPS 代建 /
BLK-03 重复加黑 50310+越权 42002 / BLK-04 拦注册直申 / BLK-S1-05 拦 TA 自营 /
SEC-01 非 OPS 代建 42002 / SEC-S4-06 跨租户不可见+审批 50203 / SEC-02 伪造 X-Tenant-Id 42101 /
CON-01 并发 approve/reject 仅一方成功（CAS）。

## 9. 决策引用与遗留

- O-1 双轨复用 tenant 审批模式；O-2 黑名单拦 OPS 代建（+测试计划扩展到 TA 自营）；
  O-3 错误码 50201-50205 落地+50310 溢出；O-6 blacklist 不进 TenantLine。
- 遗留到后续 Wave：WE 员工码（Wave3）、
  审核通过/驳回通知（现有 mock 短信基建，本波仅日志）、blacklist evidence_urls（蓝图列本波未建，需要时补迁移）。
- ~~R13 退驻链 / R14 强制下架~~ → **Wave2 已落地，见 §10 起**。

---

# Wave2 · R13 退驻 + R14 强制下架

## 10. 表结构（V11__withdraw_offline.sql）

### 10.1 wholesaler_withdraw_applications（退驻申请，TenantLine 隔离，已入白名单）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 ID |
| tenant_id | BIGINT NOT NULL | 所属租户（TA 审批侧隔离维度） |
| wholesaler_id | BIGINT NOT NULL | 退驻的批发商主体 |
| applicant_user_id | BIGINT NOT NULL | 申请人（该商户 WA） |
| reason | VARCHAR(512) | 退驻原因（选填） |
| status | VARCHAR(16) | PENDING / APPROVED / REJECTED / **CANCELLED**（WA 撤回） |
| audit_user_id / audited_at / audit_remark | — | 审核三件套（驳回必填 remark）；**audited_at=通过时刻，是 60 天恢复/归档窗口的唯一时间起点** |
| created_at / updated_at / deleted_at | — | 通用三列（软删） |

### 10.2 wholesalers 补列（withdraw_apply_at V10 已建）

| 列 | 说明 |
|---|---|
| withdrawn_at | 退驻生效时间 = 审批通过时刻（audited_at 快照）。60 天窗口/归档任务的唯一时间基准 |
| offline_at / offline_reason | R14 强制下架时间/原因（reason 必填留痕） |
| archived_at | 归档时间（数据库 NOW() 写入） |

## 11. 状态机（wholesalers.status，集中收口于 WholesalerStateMachine）

```
ACTIVE ──R13 退驻审批通过──▶ WITHDRAWN ──60 天内恢复──▶ ACTIVE
  │                              └──超 60 天归档任务──▶ ARCHIVED（终态）
  └──R14 强制下架（TA 单方即时）──▶ OFFLINE（终态，不可原地恢复，需重新入驻）

不可达（写死在转移表 + 场景测试断言）：
  WITHDRAWN→OFFLINE（50202）  OFFLINE→ACTIVE（50318，且无任何端点）  OFFLINE→WITHDRAWN（P4 仲裁再开）  ARCHIVED→任意
```

- 所有转换必须经 `WholesalerStateMachine.assertTransition(from, to)`（单点真相），
  from=WITHDRAWN 的非法转移抛 **50202**（已退驻），其余抛 **50318**。
- 状态翻转一律 CAS：`UPDATE wholesalers SET status=… WHERE id=? AND status=期望态`，affected=0 即中止。
- storefront 聚合仅取 `status=ACTIVE` 商户 → WITHDRAWN/OFFLINE **店铺页自动隐藏**，无需额外动作。

## 12. 端点清单（Wave2 新增）

| 方法/路径 | 角色 | 说明 |
|---|---|---|
| POST `/api/v1/wholesaler/withdraw` | WA | 发起退驻。body 可空：`{reason?}` → `{applicationId, wholesalerId, status}`。前置不满足：50312 库存 / 50314 未结单 / 50316 重复申请 |
| GET `/api/v1/wholesaler/withdraw/precheck` | WA | 前置自查（只读，与提交校验同一份逻辑）→ `{wholesalerId, status, stockCleared, openDocs:{cleared,count}, billing:{cleared:null}}`（billing 恒 null=P4 灰态） |
| POST `/api/v1/wholesaler/withdraw/cancel` | WA | 撤回本人 PENDING 申请（CAS）→ `{applicationId, status:CANCELLED}`；无可撤/已被审批 50315；撤回后可重新发起 |
| GET `/api/v1/wholesaler/withdraw/mine` | WA | 本人最近一次退驻申请（status/reason/auditRemark/auditedAt）；从未申请 data=null |
| POST `/api/v1/wholesaler/withdraw/restore` | WA | 60 天内恢复（WITHDRAWN→ACTIVE）→ `{wholesalerId, status}`；超窗/已归档 50317 |
| GET `/api/v1/tenant/wholesaler-withdraw-applications?page&size&status` | TA | 分页列表（含 wholesalerName 冗余）→ `{records, total, page, size}` |
| POST `/api/v1/tenant/wholesaler-withdraw-applications/{id}/audit` | TA | AuditDto `{action: APPROVED\|REJECTED, remark}`（驳回必填 remark）；CAS 败者 50315 |
| POST `/api/v1/tenant/wholesalers/{id}/force-offline` | TA | R14 单方即时下架。body `{reason}`（@NotBlank）→ `{wholesalerId, status:OFFLINE}` |
| GET `/api/v1/wholesaler/applications` | WA | （契约补齐）本人入驻申请列表（含 status/auditRemark），仅 applicant_user_id=登录用户 |

无 OFFLINE→ACTIVE 端点（状态机不可达，R14 不可原地恢复）。

## 13. R13 退驻链

- **前置校验**（发起与审批通过**双检**，防申请后又入库/开单）：
  1. 库存清零——`InventoryService.listInStockSkusFor`（qty>0 行）非空 → 50312；
  2. 无未结单据——document 域新出口 `InquiryService.countOpenDocsForWholesaler`
     （询价 PENDING/CONFIRMED + 出库非 COMPLETED）> 0 → 50314；
  3. 账单结清——**TODO 占位（决策 O-5）**：BillingService P4 落地后接 `assertAllSettled`，错误码预留 50319 起。
- **审批通过副作用链（同一事务，任一环失败整体回滚）**：
  ① 主体 ACTIVE→WITHDRAWN（CAS）+ withdrawn_at=audited_at；
  ② 全部 SKU 下架（`SkuService.delistAllByWholesaler`，partial update 只动 listed）；
  ③ 店铺页隐藏（随状态生效）；
  ④ CustomerPrice 全部失效（`PricingService.disableByWholesaler`：DB 置 DISABLED + 复用 F3
     **提交后**逐行删 Redis 价格匹配缓存，resolvePrice 回退公开价）；
  ⑤ **踢 token（WDR-S1-02 高危）**：`AuthService.listActiveUserIdsOfWholesaler`（**不限角色**，
     WA 与全部 WE 一并返回）→ 事务 **afterCommit** 逐个 `StpUtil.kickout`（回滚不误踢，无会话吞异常）。
- **60 天恢复**：CAS `UPDATE … WHERE status='WITHDRAWN' AND withdrawn_at > TIMESTAMPADD(DAY,-60,NOW())`
  ——窗口条件直接写进 SQL（数据库时间）。恢复后 SKU 保持下架需手动上架、专属价不复活。
- **归档定时任务**（WholesalerArchiveJob，每日 03:40 + SchedulingConfig @EnableScheduling）：
  `UPDATE … SET status='ARCHIVED', archived_at=NOW() WHERE status='WITHDRAWN' AND withdrawn_at <= TIMESTAMPADD(DAY,-60,NOW())`。
  **时间口径（BND-S3-01）**：起点=audited_at（通过时刻）快照的 withdrawn_at；比较全在 SQL 内用数据库时间；
  边界 **>=60 天整归档 / <60 天可恢复**，两口径互补无缝隙。第 59/60/61 天边界有场景测试。

## 14. R14 强制下架

- TA 单方即时（不需审批），reason 必填留痕（offline_at/offline_reason）；ACTIVE→OFFLINE（CAS）。
- **新拒老放分界 = 下架时刻的单据状态**（document 域校验点，非一刀切）：
  - 新询价创建（`submitByRt`）：商户属本店但非 ACTIVE → **50313**；
  - 未确认（PENDING）询价不可再确认（`confirmByWa` 在 CAS 前校验商户状态）→ **50313**；
  - **已确认询价与已生成出库单允许走完**：本实现确认即原子转出库（CONFIRMED→COMPLETED 同事务），
    下架前完成的单据原样保留，不回滚不作废。
- 踢 token 同 R13（WA+WE，afterCommit）；店铺隐藏随状态生效。
- 未结账单转「争议中」——**TODO 占位（P4 billing）**，代码中 forceOffline 处标注。
- 不可原地恢复：restore 仅服务 WITHDRAWN；OFFLINE 调 restore/withdraw 均 50318。

## 15. Wave2 测试（WithdrawOfflineScenarioTest，16 用例全绿；backend 全量 158 绿）

WDR-01 库存未清 50312+precheck 同口径 / WDR-02 未结询价 50314+openDocs.count /
WDR-03 副作用链四段+TA 列表+**WA/WE 双踢（WDR-S1-02）** / WDR-04 重复 50316+已退驻 50202 /
WDR-05 恢复：SKU 保持下架+专属价不复活 / WDR-06 mine 本人可见他人为空 /
WDR-07 precheck 三态结构（billing 灰态 null）/ WDR-08 撤回 CANCELLED+重新发起+审批后 50315 /
BND-01/02/03 第 59/60/61 天边界（数据库时间 seed+判定）/ CON-02 并发审批 CAS 恰一方成功 /
FOF-01 下架 reason 必填+OFFLINE+隐藏+踢 token / FOF-02 新拒老放（老出库保留、新询价 50313、旧 PENDING 确认 50313）/
FOF-03 不可达转移（50202/50318+转移表断言）/ ONB-08 本人申请列表契约。

## 16. Wave2 决策引用与遗留

- O-5 账单校验 TODO 占位（applyWithdraw 前置 + forceOffline 争议中，两处标注，错误码预留 50319+）。
- 撤回态 CANCELLED 为前端 Wave4b 契约补充（Team Lead 2026-07-16 指令）；仅 PENDING 可撤（CAS）。
- 归档只做状态位 ARCHIVED（findings 缺口 #3，Q-D07），不做数据迁移。
- 遗留：已下架→争议中→已退驻 OPS 仲裁闭环（P4 billing 后补）；退驻/下架通知（mock 短信基建，本波仅日志）。
