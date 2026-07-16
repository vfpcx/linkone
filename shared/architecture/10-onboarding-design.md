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

---

# Wave3 · WE 批发商员工 + 收尾端点

## 17. 表结构（V12__we_permissions.sql）

### 17.1 授权位存储选型（决策 O-4 落地——user_roles 加列，不建独立权限表）

| 方案 | 结论 | 理由 |
|---|---|---|
| **user_roles.permissions 列（采用）** | ✅ | ① 授权位仅 2 枚且与 WE 角色绑定行一一对应，独立表徒增 JOIN；② 读取永远伴随角色行（`hasWholesalerPermission` 单行查询命中）；③ 无按授权位反查员工的需求（列表页整行取回解码即可） |
| 独立 user_permissions 表 | ❌ | 授权粒度会随行数膨胀而复杂化；phase 内无按位审计要求 |
| MySQL JSON 类型 | ❌ | 值域为固定白名单标识符，无 JSON 函数检索需求；H2(MODE=MySQL) 测试库 JSON 语义不一致 |

- `user_roles.permissions VARCHAR(255) NULL`：JSON 数组文本（如 `["PRICE_EDIT","INQUIRY_CONFIRM"]`），NULL/空 = 无授权（只读）。
- `invite_codes.permissions VARCHAR(255) NULL`：WE 码初始授权快照，注册消费时原样落 user_roles。
- 编解码：`common/util/WePermissions`（白名单 `PRICE_EDIT`/`INQUIRY_CONFIRM`；解码丢弃白名单外条目，防脏数据放大权限）。

## 18. 端点清单（Wave3 新增）

| 端点 | 角色 | 说明 |
|---|---|---|
| `POST /api/v1/wholesaler/employee-invites` | WA | 生 WE 码 `{expireDays,maxUses,permissions}`；targetRole 固定 WE、绑定登录 WA 的 wholesaler_id；permissions 越界 50319 |
| `GET /api/v1/wholesaler/employee-invites` | WA | 本商户 WE 码列表（含 permissions/remaining/status） |
| `DELETE /api/v1/wholesaler/employee-invites/{id}` | WA | 作废（REVOKED）；跨商户/非 WE 码按不存在 50291 |
| `GET /api/v1/wholesaler/employees` | WA | 本商户 WE 列表（含禁用；id=user_roles.id，permissions/status/disabledAt） |
| `PUT /api/v1/wholesaler/employees/{id}/permissions` | WA | 授权整体替换 `{permissions}`；越界 50319、跨商户 50320 |
| `POST /api/v1/wholesaler/employees/{id}/disable` | WA | R17：ACTIVE→DISABLED（CAS，重复禁用 50321）+ disabled_at + 提交后 `StpUtil.kickout` |
| `POST /api/v1/wholesaler/employees/{id}/restore` | WA | 30 天内恢复（数据库时间窗口，逾期 50322）；授权保持禁用前设置 |
| `GET /api/v1/admin/tenants?status&page&size` | OPS | 租户列表（前端契约 AdminTenantItem；PageData 形状 list/total/page/pageSize/totalPages；非 OPS 42002） |

控制器分域：invite 三端点归 tenant 域（invite_codes 归属）；employees 四端点归 account 域
（本体是 user_roles 行与登录会话）——`WholesalerEmployeeController` + `WholesalerEmployeeService`。

## 19. WE 注册与登录（D52）

- `consumeInviteForRegister` 白名单 WK/ST → **WK/ST/WE**；WE 码必须携带 wholesaler_id（缺失按无效码 41301 拒，防绑空商户）。
- 注册落 user_roles：role=WE + tenant_id + **wholesaler_id** + 码上初始 permissions；新用户注册无既有会话，无需踢出。
- **白名单影响面收敛（WEM-S2-02）**：TA 端 `/tenant/employee-invites` 生码白名单仍仅 WK/ST（50290）；
  且 TA 端码列表/作废也过滤为 WK/ST——WA 生成的 WE 码对 TA 端不可见、不可作废。
- **D52 登录路由修正**：`resolveRouter` WA/WE → `/wa/inquiry`（原占位 /ta/dashboard）；多角色按 priority
  升序取主角色（TA10>ST20>WK30>WA40>WE50 既有值即 D52 优先级）。
- **全角色禁用登录拒绝（WEM-S5-01）**：`resolvePrimaryRole` 发现有角色记录但全部非 ACTIVE → 41110
  语义拒绝（原逻辑兜底 TA 会放行被禁用的 WE）；无任何角色记录的历史账号维持兜底 TA。

## 20. 授权校验切点（PRICE_EDIT / INQUIRY_CONFIRM）

| 域 | 切点 | 读/写 | 规则 |
|---|---|---|---|
| pricing | `requirePriceEditor`：setCustomerPrice / update / revoke（经 requireOwnedCustomerPrice）/ 批量公开价 / 批量专属价 | 写 | WA/TA 不受限；WE 须 PRICE_EDIT，未授 **42004** |
| pricing | `requireWaOrTa`：listCustomerPrices / listPriceChangeLogs | 读 | WE 不限授权位可读（价格页只读可见，产品 §6.1） |
| document | `requireWaRole`：confirmByWa（确认即原子转出库） | 写 | WA 不受限；WE 须 INQUIRY_CONFIRM，未授 **42004**；非本商户 50286 |
| document | `listForWa` | 读 | 扩展纳入 WE 绑定的 wholesaler（询价列表 WE 可见，按钮态由前端授权位控制） |

- **公开价单 SKU 编辑路径说明**：SKU 域（create/update）仍 WA/TA-only——WE 不可改商品资料；
  WE 的公开价编辑走 pricing 批量端点（`skuIds=[单个] + SET_VALUE`），切点已覆盖。
- 账单对 WE **永不可见**（无对应授权位）：billing 域 P4 未建，无任何账单端点；P4 落地时 WE 必须整域拒绝（WEM-S4-03 防回归用例已占位）。
- 询价「拒绝」端点 phase-1 不存在（只有 confirm）；将来补 reject 时须同挂 INQUIRY_CONFIRM 切点。

## 21. R17 禁用/恢复

- 禁用：CAS `status=ACTIVE→DISABLED`（affected=0 → 50321，防并发重复禁用改写 disabled_at 变相续期窗口）+
  事务提交后 `StpUtil.kickout`（回滚不误踢）。
- **草稿单据作废 = 空操作**：phase-1 WE 无可持有的草稿态单据——询价由 RT 买家创建（PENDING 归单不归人）、
  出库单生成即 COMPLETED、入库单归 WK。待未来出现 WE 起草的单据类型时在 `disableEmployee` 挂作废钩子。
- 恢复：CAS `status=DISABLED` + `disabled_at > TIMESTAMPADD(DAY,-30,NOW())`（数据库时间，口径同 Wave2
  60 天窗口：<30 天整可恢复 / >=30 天整 50322 互补无缝隙）；permissions 不动（授权保持禁用前设置）。

## 22. OPS 租户列表（顺路补齐，前端契约先行）

- `pageTenantsForAdmin`：hasRole OPS（42002）→ tenants 全平台分页（不在 TenantLine 白名单，无隔离干扰）；
  status 可选过滤（PENDING/ACTIVE/REJECTED——通过态 **ACTIVE 非 APPROVED**）。
- 字段映射：tenantId/name/legalName/contactPhone/status/appliedAt(=created_at)/auditedAt/auditRemark 取 tenants；
  applicantName = contact_user 的 realName ?: nickname；addressText = tenant_applications 同租户最新一条快照（批量查询防 N+1）。

## 23. Wave3 测试（WeEmployeeScenarioTest，18 用例全绿）

WEM-S1-01/02 生码绑定+注册落位（wholesaler_id+permissions）/ WEM-S1-03 D52 WE→/wa/inquiry /
D52 多角色 TA+WE 主角色 TA / WEM-S1-04+S4-01 PRICE_EDIT 正反（成功/42004/WA 不受限）/
SEC-S4-09+WEM-S4-02 INQUIRY_CONFIRM 反正（42004→授权后放行，Service 层校验）/
WEM-S4-03 账单类端点防回归 / SEC-S4-10 跨商户改价拒绝 / WEM-S1-06 R17 禁用即踢(41001)+重复禁用 50321 /
WEM-S5-01 禁用登录 41110 / WEM-S1-07 29 天恢复+授权保持 / BND 30/31 天恢复 50322（数据库时间拨盘）/
员工列表商户隔离 / WEM-S2-01 授权越界 50319 / WEM-S2-02 TA 端仍拒 WE(50290) / 非 WA 调用拒绝(50230) /
WEM-S6-01 作废码注册 50292+跨商户作废 50291 / 授权变更越界 50319 / 跨商户 50320 / admin/tenants 分页+过滤+字段+42002。

## 24. Wave3 决策引用与遗留

- 50319–50322 占用 O-3 溢出段（原 billing 占位顺延 50323+，05-error-codes.md 已同步）。
- WE 对 SKU 域（商品资料）与员工管理端点无任何权限；员工码生成 WA 专属（WE ❌，01 §4.1）。
- 遗留：禁用超 30 天的"已永久移除"仅表现为恢复被拒（50322），不做行删除/归档任务；
  WE 通知（禁用/恢复告知）走 mock 短信基建，本波仅日志。

---

# 审查修复批次（F1/F4/F5/F7，2026-07-16）

## 25. F1（BLOCKER）：一账号仅一 PENDING 的数据库级唯一（V13__application_pending_unique.sql）

- 原「先查后写」（selectCount→insert）存在并发窗口，同账号可产生双 PENDING。
- 部分唯一索引模式：`wholesaler_applications.pending_flag TINYINT NULL`（PENDING=1，终态 NULL）
  + `uk_applicant_pending(applicant_user_id, pending_flag)`——NULL 不参与唯一冲突（MySQL/H2 一致），
  终态任意多条共存、PENDING 至多一条；插入唯一键冲突捕获 `DuplicateKeyException` → 50201。
- flag 生命周期：插入置 1；TA 审批 CAS 翻转同 SQL 置 NULL；OPS 代建自动关闭置 NULL。
  存量 PENDING 迁移内回填 1（若历史脏数据双 PENDING 会使建索引失败——宁失败不静默）。
- 预检查保留为快速路径（常规请求仍拿 50201 语义），索引是并发兜底（CON-S7-01 双线程恰一方成功）。

## 26. F4（MAJOR）：ACTIVE 绑定复查 + 代建自动关闭存量申请

- TA 审批通过路径新增复查：申请提交与审批之间账号可能已被 OPS 代建/他仓通过，
  `listActiveWholesalerIds` 非空 → 50204（抛出随事务回滚 CAS 翻转，申请保持 PENDING）。
- OPS 代建路径原有 50204 前检保留；代建成功后将该账号存量 PENDING 申请统一置
  REJECTED + pending_flag=NULL + audit_remark「OPS 代建入驻生效，存量待审申请自动关闭」（留痕可追溯）。

## 27. F5（MAJOR）：入驻目标租户必须 ACTIVE

- `requireTenantExists` 升级为存在性 + 状态检查，自助申请/注册直申/OPS 代建三路径同检：
  PENDING→50101（审核中）、FROZEN→50102（已冻结）、其余非 ACTIVE→50103（已下线）——复用
  STATE_TENANT 既有段，未新增错误码。

## 28. F7（MAJOR）：日志脱敏

- `SmsUtil.maskPhone` 作为统一脱敏出口（138****5678；异常长度打星兜底不回退明文）。
- 修复点：WholesalerServiceImpl.ensureWaUser（删除明文临时密码+手机号脱敏）、
  TenantServiceImpl.createByOps 代建租户日志（同前）、AccountServiceImpl mock 验证码命中日志（手机号脱敏）。
- 全域复扫 wholesaler/account：其余日志仅打 userId，无明文 phone/password 残留
  （pricing 域 rtPhone 日志不属本批次范围，遗留给下轮审查）。

## 29. 修复批次测试（OnboardingReviewFixScenarioTest，5 用例全绿；backend 全量 181 绿）

CON-S7-01 并发重复申请恰一方成功+库中 PENDING 恰一条 / F4a 审批复查 50204+事务回滚保持 PENDING /
F4b 代建自动关闭（REJECTED+remark 留痕+flag 释放+重申命中 50204 闭环）/
F5 三状态×自助+代建六断言 / SEC-S4-01 入驻/退驻/员工 21 端点无 token 全扫 41001。
