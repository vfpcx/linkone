# P2 入驻生态 · 测试计划（04-onboarding）v1

> 编写：测试&审查 Agent · 2026-07-16
> 依赖：`../findings.md`（状态机 / R13 / R14 / 黑名单 / WE / 决策 O-1~O-6）· `../task_plan.md`（五波拆分 + 接口契约）· `00-overview.md`（金字塔 + §3.5/§3.6 视觉验收）· `02-scenario-test-plan.md`（S1–S9 场景法 + 用例格式）· `../architecture/05-secure-coding-guardrails.md`（G-x 规约）· `../architecture/05-error-codes.md`（50201–50205）
> 定位：Wave 5 验收基线。Wave1/2/3 后端与 Wave4/4b 前端以本文用例为**合并门槛**（缺 S2/S4/S5/S6 视为不达标，见 02 §8）。

---

## 0. 范围与约定

**范围**：入驻申请与审批（含注册接入 / OPS 代建 / TA 自营）、全平台黑名单、R13 退驻（含 60 天恢复/归档）、R14 强制下架（老单放行）、WE 员工（码/授权/R17 禁用）。
**出圈**（不测，按 task_plan「不做」）：账单争议仲裁全流程（billing P4，仅测状态位落地）、入驻条件设置、归档数据迁移、真实短信/推送。

**用例 ID 惯例**（沿 02 §3：`<模块>-<场景码>-<序号>`）：

| 前缀 | 模块 | 前缀 | 模块 |
|---|---|---|---|
| ONB | 入驻申请与审批 | FOF | R14 强制下架 |
| BLK | 黑名单 | WEM | WE 员工 |
| WDR | R13 退驻 | SEC | 安全专项（S4） |
| CON/BND | 并发/边界专项 | ONB-E2E | Playwright 链路 |

**错误码基线**（05-error-codes §STATE_WHOLESALER，本期落地）：
50201 入驻审核中 · 50202 已退驻 · 50203 退驻前需结清账单/未结单据（P4 前占位复用）· 50204 退驻前需清空库存 · 50205 黑名单拦截。溢出新码走 50310–50329（O-3）。凡下表写「语义码」处，指 Wave 开发落码后回填本表——**禁止**以 code=0 或裸 90001 交差（G-8.2）。

**优先级**：P0 = 阻塞合并；P1 = 交付前必须；P2 = 可随回归补。

---

## 1. 后端场景测试矩阵

> 层次默认「集成」（H2 + embedded-redis，00 §2.1 工程配置；工厂复用 `TestFixtures` / `TenantSetupHelper`）。状态机纯转移判定可下沉单元层。每条落 JUnit `@DisplayName("<用例ID> <描述>")`。

### 1.1 ONB · 入驻申请与审批（含代建/自营/注册接入）

| 用例ID | 场景 | 前置 | 步骤/输入 | 期望 | 优先级 |
|---|---|---|---|---|---|
| ONB-S1-01 | S1 | WA 账号已注册、未入驻任何仓库；目标租户 ACTIVE | `POST /api/v1/wholesaler/applications` | code=0；申请单 PENDING；未产生 ACTIVE wholesaler | P0 |
| ONB-S1-02 | S1 | 新手机号 | 注册携 targetTenantId + wholesalerName（AccountServiceImpl:222 接入点） | 注册成功且**自动创建** PENDING 申请单（不再只打日志） | P0 |
| ONB-S1-03 | S1 | 存在 PENDING 申请 | TA `POST .../wholesaler-applications/{id}/approve` | 申请 APPROVED；wholesaler ACTIVE；user_roles 增 WA 记录且 **wholesaler_id 回填**；通知（mock 短信）发出 | P0 |
| ONB-S1-04 | S1 | 存在 PENDING 申请 | TA `POST .../{id}/reject` + reason | REJECTED；驳回理由落库可查；通知发出 | P0 |
| ONB-S1-05 | S1 | TA 登录 | TA 建自营 WA（统一入驻链路） | 成功；source=SELF_OPERATED **留痕**；D15 不自动绑定 | P1 |
| ONB-S1-06 | S1 | OPS 登录；authBasis（TA 授权凭据/客诉单号）齐 | `POST /api/v1/admin/wholesalers` | 成功；source=OPS_CREATED；**authBasis 落库留痕**可审计 | P0 |
| ONB-S1-07 | S1 | 手机号 P 已在 A 仓注册 WA | 用 P 在 B 仓注册另一账号（05 §6.3） | 允许（同手机号跨仓不同账号），互不影响 | P1 |
| ONB-S2-01 | S2 | PENDING 申请 | TA 驳回 reason 缺失/空串/纯空白 | 40001 必填校验；状态不变 | P0 |
| ONB-S2-02 | S2 | OPS 登录 | OPS 代建**缺 authBasis** | 40001 拒绝；不建任何数据 | P0 |
| ONB-S2-03 | S2 | — | 申请缺必填（wholesalerName/targetTenantId）、名称超长串 | 40001；不建数据 | P1 |
| ONB-S4-01 | S4 | 租户 A 有 PENDING 申请 | 租户 B 的 TA `GET .../wholesaler-applications` | **看不到** A 的申请（TenantLine；`wholesaler_applications` 必须已加入 `MybatisPlusConfig.TENANT_FILTER_TABLES`） | P0 |
| ONB-S4-02 | S4 | 同上 | 租户 B 的 TA 直接 approve A 的申请 id | 拒绝/视同不存在（50210 语义）；状态不变 | P0 |
| ONB-S5-01 | S5 | 申请已 APPROVED | 再次 approve / reject | 拒绝（**仅 PENDING 可审**，仿 TenantServiceImpl.audit 先例）；状态与副作用不重复 | P0 |
| ONB-S5-02 | S5 | 申请已 REJECTED | 再次审批 | 拒绝；状态不变 | P0 |
| ONB-S6-01 | S6 | 已有 PENDING 申请 | 同一 WA 重复提交（同租户） | 50201（审核中）；DB 不产生第二条申请 | P0 |
| ONB-S6-02 | S6 | WA 已入驻 A 仓（ACTIVE） | 再向 B 仓提申请 | 拒绝（一个 WA 只能入驻一个仓库，01 §3.5）；语义码回填 | P0 |
| ONB-S8-01 | S8 | mock 短信抛异常 | TA 审批通过 | 主流程成功不回滚，通知失败仅记日志（通知不得反噬审批事务） | P2 |

### 1.2 BLK · 全平台黑名单

| 用例ID | 场景 | 前置 | 步骤/输入 | 期望 | 优先级 |
|---|---|---|---|---|---|
| BLK-S1-01 | S1 | OPS 登录 | `POST /api/v1/ops/blacklist`（手机号键 / 执照号键各一条） | 加入成功；`GET` 列表可查 | P0 |
| BLK-S1-02 | S1 | 手机号 P 在黑名单 | P 的 WA 向任一租户提交申请 | **50205** 拒绝；不建申请单 | P0 |
| BLK-S1-03 | S1 | 执照号 L 在黑名单（手机号未拉黑） | 用 L 提交申请 | 50205（**双键各自独立命中**） | P0 |
| BLK-S1-04 | S1 | 手机号在黑名单 | OPS 代建该手机号 WA | 50205 拒绝（决策 **O-2：代建同样拦截**） | P0 |
| BLK-S1-05 | S1 | 手机号在黑名单 | TA 建自营 WA 用该手机号 | 50205（统一入驻链路三条路径**全部**过黑名单检查） | P0 |
| BLK-S1-06 | S1 | 黑名单手机号 | 注册携 targetTenantId（注册接入链路） | 注册本身成功与否按契约，但**必须不产生 PENDING 申请**，提示 50205 语义 | P0 |
| BLK-S1-07 | S1 | 已拉黑后 `DELETE /ops/blacklist/{id}` | 同键再提交申请 | 移除成功；此后申请放行（走正常 PENDING） | P1 |
| BLK-S3-01 | S3 | 多租户各有数据 | OPS 查黑名单列表 | 平台级全量可见——`blacklist` **不在** TenantLine 白名单（O-6），且不因租户上下文漏行/多行 | P2 |
| BLK-S6-01 | S6 | 键已在黑名单 | 重复加入同键 | 幂等或语义拒绝；DB 唯一索引兜底，不产生重复行（G-5.1） | P2 |

### 1.3 WDR · R13 退驻（申请 → 审批 → 副作用 → 60 天恢复/归档）

| 用例ID | 场景 | 前置 | 步骤/输入 | 期望 | 优先级 |
|---|---|---|---|---|---|
| WDR-S1-01 | S1 | WA ACTIVE；库存已清零；无未结单据 | `POST /api/v1/wholesaler/withdraw` | 申请成功，进入待审（PENDING_WITHDRAW 或申请单 PENDING，按 Wave2 契约）；`withdraw_apply_at` 写入 | P0 |
| WDR-S1-02 | S1 | 退驻申请待审 | TA 审批通过 | WITHDRAWN + 副作用链**全部断言**：① SKU 全部下架 ② 店铺对 RT 隐藏 ③ CustomerPrice（客户专属价）**全部失效** ④ 该商户 **WA 与全部 WE** token 即时踢出（旧 token 复用 → 41001） | P0 |
| WDR-S1-03 | S1 | 退驻申请待审 | TA 驳回（带理由） | 回 ACTIVE；SKU/价格/会话均不受影响 | P1 |
| WDR-S1-04 | S1 | WITHDRAWN 未满 60 天 | `POST .../restore` | 回 ACTIVE，可重新登录营业；恢复后 SKU 上架状态按 PRD 契约断言（不静默复活专属价） | P0 |
| WDR-S2-01 | S2 | 该商户库存 > 0 | 发起退驻 | **50204** 拒绝；不建申请 | P0 |
| WDR-S2-02 | S2 | 存在未结单据（在途入库/出库、未终态询价） | 发起退驻 | **50203** 拒绝（账单校验 P4 占位 O-5：本期为 库存+未结单据 两道前置；若单据校验另落新码则回填本表） | P0 |
| WDR-S3-01 | S3 | WITHDRAWN 恰第 60 天边界前一刻 | restore | 成功——60 天判定的 ≥/> 语义两侧各测一例，与 PRD「60 天内可恢复」一致 | P0 |
| WDR-S3-02 | S3 | 第 61 天 / 归档 job 已跑 | restore | 拒绝；状态 **ARCHIVED**（本期仅状态位，无数据迁移） | P0 |
| WDR-S3-03 | S3 | 造 58/60/61 天三样本 | 手动触发归档定时任务 | 仅逾期样本归档；job **幂等**（重跑不重复处理、不误伤未逾期） | P1 |
| WDR-S5-01 | S5 | 退驻申请已通过 | 再次审批 | 拒绝（仅待审可审）；副作用链不重复执行 | P0 |
| WDR-S5-02 | S5 | WITHDRAWN | 调上架 SKU/确认询价等业务接口 | **50202** | P0 |
| WDR-S5-03 | S5 | ARCHIVED | restore | 拒绝（归档不可恢复，需重新入驻） | P1 |
| WDR-S5-04 | S5 | WITHDRAWN | TA force-offline 该商户 | 拒绝（状态机不可达：已退驻→已下架 ❌） | P1 |
| WDR-S6-01 | S6 | 已有退驻申请待审 | 重复发起退驻 | 幂等拒绝；不产生第二条申请 | P1 |

### 1.4 FOF · R14 强制下架（即时生效 · 新拒老放 · 不可原地恢复）

| 用例ID | 场景 | 前置 | 步骤/输入 | 期望 | 优先级 |
|---|---|---|---|---|---|
| FOF-S1-01 | S1 | WA ACTIVE（本租户） | TA `POST .../wholesalers/{id}/force-offline` | **即时** OFFLINE，无审批环节；店铺对 RT 隐藏；通知发出 | P0 |
| FOF-S1-02 | S1 | 下架前已有 **已确认意向单**（CONFIRMED 询价） | 继续走完转出库/出库 | **放行完成**（03 §7 规则 7 老单放行） | P0 |
| FOF-S1-03 | S1 | 下架前已生成出库单 | WK 执行出库 | 放行完成，库存流水正常 | P0 |
| FOF-S1-04 | S1 | OFFLINE + 存在未结账单标记 | 查询商户状态 | 标「争议中」仅状态位（billing P4 前不做仲裁流转） | P2 |
| FOF-S5-01 | S5 | OFFLINE | RT 对该商户提交**新询价** | 拒绝（语义码回填，禁止 code=0/90001） | P0 |
| FOF-S5-02 | S5 | OFFLINE | 创建**新出库申请** | 拒绝（document 域校验点，跨域改动重点回归） | P0 |
| FOF-S5-03 | S5 | OFFLINE | 任何「原地恢复」尝试（restore/直接改状态接口） | 拒绝——**不可原地恢复，需重新入驻**（已下架→正常 ❌） | P0 |
| FOF-S5-04 | S5 | OFFLINE | 再次 force-offline | 幂等或语义拒绝，不重复副作用 | P2 |

### 1.5 WEM · WE 员工（码 / 绑定 / 授权位 / R17 禁用）

| 用例ID | 场景 | 前置 | 步骤/输入 | 期望 | 优先级 |
|---|---|---|---|---|---|
| WEM-S1-01 | S1 | WA ACTIVE 登录 | `POST /api/v1/wholesaler/employee-invites` 生 WE 码 | 成功；`invite_codes.wholesaler_id` = 本商户（**必须写入**，否则 WE 绑空商户） | P0 |
| WEM-S1-02 | S1 | 有效 WE 码 | 新手机号凭码注册 | 成功；user_roles：role=WE 且 **wholesaler_id 绑定**（consumeInviteForRegister:454 白名单已放开 WE） | P0 |
| WEM-S1-03 | S1 | WE 账号 | 登录 | 落 **WA 端路由**（D52 优先级 TA>ST>WK>WA>WE 修正；不再占位跳 /ta/dashboard，findings §四风险项） | P0 |
| WEM-S1-04 | S1 | WE 已授 PRICE_EDIT | WE 改价 | 成功，效果同 WA 改价 | P0 |
| WEM-S1-05 | S1 | WE 已授 INQUIRY_CONFIRM | WE 确认询价 | 成功；后续转出库链路同 WA 确认 | P0 |
| WEM-S1-06 | S1 | WE 有活跃 token + 草稿单据 | WA 禁用该 WE（R17） | token **即时踢出**（旧 token → 41001）+ 该 WE 草稿**作废** | P0 |
| WEM-S1-07 | S1 | 禁用 ≤30 天 | WA 撤销禁用 | 恢复可登录；授权位按 PRD 契约断言 | P1 |
| WEM-S2-01 | S2 | WA 生码 | role 传白名单外值（TA/OPS/WA） | 拒绝（EmployeeInviteCreateDto 白名单，G-3.1） | P0 |
| WEM-S2-02 | S2 | TA 登录 | TA 端 `/tenant/employee-invites` 生 **WE** 码 | 拒绝——WE 码只能 WA 端生成；TA 端白名单仍仅 WK/ST（白名单放开的影响面收敛测试） | P1 |
| WEM-S4-01 | S4 | WE 未授 PRICE_EDIT | 改价 | 42xxx 拒绝（只读） | P0 |
| WEM-S4-02 | S4 | WE 未授 INQUIRY_CONFIRM | 确认询价 | 拒绝 | P0 |
| WEM-S4-03 | S4 | WE 任意授权组合 | 调账单查询接口 | 拒绝（账单对 WE **永不可见** ❌，无对应授权位） | P0 |
| WEM-S5-01 | S5 | 已禁用 WE | 登录 | 拒绝，语义提示 | P0 |
| WEM-S6-01 | S6 | WE 码作废 / maxUses 用尽 | 凭码注册 | 拒绝（复用 EI-S4-02/EI-S6-01 先例；配额走 CAS 条件自增 §10 P3） | P1 |

---

## 2. 安全用例专项（S4 · 对照 guardrails G-1/G-2 与自检卡）

> 规约映射：G-1.3「越权必测」/ G-2.1「tenantId 只信登录态」/ G-2.3「跨租户必测」。全部为集成层，P0 除注明外。

| 用例ID | 前置 | 步骤/输入 | 期望 | 优先级 |
|---|---|---|---|---|
| SEC-S4-01 | 无 token | 逐一裸调 6 组新端点：applications / 审批(approve·reject) / withdraw·restore / force-offline / ops/blacklist×3 / wholesaler/employee-invites | 全部 41001（每个新接口必纳入拦截器或注解，G-1.1） | P0 |
| SEC-S4-02 | WA token | 调 TA 审批端点 approve/reject（**WA 审自己的申请**） | 42xxx 拒绝（Service 内 `authService.hasRole` 显式校验惯例） | P0 |
| SEC-S4-03 | WA token | 调 `force-offline`、TA/OPS token 调 `wholesaler/withdraw` | 全部角色拒绝 | P0 |
| SEC-S4-04 | TA / WA token | 调 `/ops/blacklist` 三端点 | 后端拒绝；同时前端 OPS 路由**补角色守卫**（findings §四：现守卫只查登录不查 role，router/index.ts:137-153） | P0 |
| SEC-S4-05 | 租户 A 有申请/商户 | 租户 B 的 TA 改 URL id 审批 A 的申请、force-offline A 的商户 | 拒绝/视同不存在；TenantLine + 显式校验双保险（G-2.2） | P0 |
| SEC-S4-06 | 合法登录态 | 请求头伪造 `X-Tenant-Id` 指向他租户后 提交申请/查审批列表 | 头被忽略或校验拒绝——租户上下文**只信登录态**（G-2.1） | P0 |
| SEC-S4-07 | 键在黑名单 | 变体绕过：手机号首尾空格、执照号大小写/全半角 | 归一化后仍拦截；至少精确键必拦、变体行为有明确定义并测两侧 | P1 |
| SEC-S4-08 | 手机号 P + 执照号 L 双键其一在黑名单 | 换新手机号但同执照号申请；换执照号但同手机号申请 | **任一键命中即 50205**（双键独立防绕过） | P0 |
| SEC-S4-09 | WE 未授权 | 绕开前端按钮，直调改价/询价确认 API | 拒绝——授权校验必须在 Service 层，不得仅靠前端隐藏按钮 | P0 |
| SEC-S4-10 | WE 属商户 A | 操作商户 B 的 SKU/询价/员工 | 拒绝（wholesaler_id 归属校验） | P0 |
| SEC-S4-11 | 已被踢出（退驻通过 / R17 禁用）的旧 token | 重放调任意业务接口 | 41001（kickout 真实生效，非只删前端存储） | P0 |

---

## 3. E2E 链路（Playwright）

> 工程约定沿 00 §4：预发/本地 `dev,local`:8080 + :5173；登录态注入复用 pinia-persist key `cangchu-admin-auth`；造数复用 `seedSellChain`；用例 ID 进 test 标题。

| 用例ID | 链路 | 步骤 | 关键断言 | 优先级 |
|---|---|---|---|---|
| ONB-E2E-01 | **入驻主链**（= 00 §4.2 E2 落地） | ① WA 注册页携 targetTenantId 提交 → ② 登录提示「审核中」（50201 文案）→ ③ TA 登录审批列表可见该申请 → ④ 通过 → ⑤ WA 重新登录落 WA 工作台 → ⑥ 上架 1 个 SKU 成功 | 申请单自动创建；审批后角色/wholesaler_id 生效；全链无 90001 | P0 |
| ONB-E2E-02 | **黑名单全平台拦截链** | ① OPS 登录黑名单页添加手机号 → ② 该手机号向租户 A 申请被拒 → ③ 向租户 B 申请同样被拒（**全平台**）→ ④ OPS 移除 → ⑤ 再申请成功进 PENDING | 页面提示 50205 语义文案；移除后放行；OPS 页有角色守卫（非 OPS 直达被挡） | P0 |
| ONB-E2E-03 | **退驻链** | ① 造数：ACTIVE WA 带库存 → ② 发起退驻被拒并提示清库存（50204 文案）→ ③ 清零后发起成功 → ④ TA 审批通过 → ⑤ WA 已登录会话被踢回登录页 → ⑥ RT 扫码进店不见该商户 → ⑦（API 步）restore 后恢复可登录 | 前置校验文案可读；踢出即时；买家侧隐藏 | P0 |
| ONB-E2E-04 | **WE 员工链** | ① WA 员工管理页生 WE 码 → ② WE 凭码注册 → ③ 登录落 WA 端路由 → ④ 未授权时改价入口只读/不可用 → ⑤ WA 授 PRICE_EDIT → ⑥ WE 改价成功 → ⑦ WA 禁用 → ⑧ WE 会话被踢出 | 授权位实时生效；禁用踢出 | P1 |

---

## 4. 视觉验收清单（§3.5/§3.6 · 强制门槛）

> 规则重申（00 §3.5）：每页 Playwright 截图 **≥1280 / 768 / 375** 三断点，由**能读图的审阅者（主会话/人）逐张肉眼审**——视觉判断不得下放给文本子 Agent。问题登记 `03-defect-findings.md`，未过视觉审不合 main。根治按 §3.6：判组件误用/规范缺失 → 抽 `@cangchu/ui-shared` → 补 `design-system/MASTER.md` → 全仓扫同类。

通用检查项（每页必查）：① 元素对齐（标签/图标/文字基线）② 无溢出/截断 ③ 间距走 design tokens ④ 空态/加载态/错误态有呈现 ⑤ 三断点不错版。以下为每页**专项**检查点：

| # | 页面（Wave4/4b） | 专项检查点 | 优先级 |
|---|---|---|---|
| V-01 | WA 入驻申请表单页 | 表单标签列对齐、必填星号一致；校验错误文案出现时不挤压布局；提交后「审核中」状态呈现（50201）；375 下表单无横向滚动 | P0 |
| V-02 | TA 审批页（列表 + 通过/驳回弹窗） | 状态 tag（PENDING/APPROVED/REJECTED）配色走 tokens 且与 tenant 审批页一致；**驳回弹窗理由必填红字校验**；弹窗按钮对齐、ESC/遮罩行为；长商户名/长理由省略不撑破列；列表空态 | P0 |
| V-03 | OPS 黑名单管理页（**OPS 端第一个真实页面**） | 整体导航/布局与 admin 壳一致（无占位残留）；添加弹窗双键（手机号/执照号）表单切换清晰；长执照号不溢出；删除二次确认弹窗；空态文案 | P0 |
| V-04 | WA 退驻发起页 + TA 强制下架确认弹窗 | 前置校验失败提示（50203/50204）清晰可读且不叠层；危险操作按钮 danger 色、二次确认弹窗长文案换行不溢出；退驻后「60 天内可恢复」提示呈现 | P0 |
| V-05 | WA 员工管理页（仿 ta/Employees.vue） | 员工码/二维码区块对齐；授权开关（PRICE_EDIT/INQUIRY_CONFIRM）行内对齐一致；禁用员工行灰化态；空态（无员工）；开关与状态 tag 不混用组件（§3.6 先例：裸计数禁用 el-badge） | P0 |
| V-06 | WE 注册流适配 | 邀请码输入/预填页；码作废/用尽错误提示可读（对应 WEM-S6-01）；注册成功跳转正确端；375 断点主流程 | P1 |

---

## 5. 边界与并发专项（S3/S7 · 对照 guardrails §10）

> S7 统一 Java 21 虚拟线程压测（02 §6 模板）。红线复核：状态流转必须条件 UPDATE CAS（affected==1），唯一性必须 DB 唯一索引 + DuplicateKey 捕获——**发现「先查后写」实现直接登记缺陷**。

| 用例ID | 场景 | 步骤 | 期望 | 优先级 |
|---|---|---|---|---|
| CON-S7-01 | S7 | 同一 WA 50 虚拟线程并发提交入驻申请 | 仅 1 条 PENDING 落库；其余 50201/唯一约束语义码；无重复行（§10 P5） | P0 |
| CON-S7-02 | S7 | 两会话并发审批同一申请（approve vs reject 同时发） | CAS 只一个赢，另一方状态冲突码；终态唯一、副作用只执行一次（§10 P2） | P0 |
| CON-S7-03 | S7 | 退驻审批通过 与 新出库申请创建 并发 | 不出现「已退驻却新单成立」的终态；任一先后均一致 | P1 |
| CON-S7-04 | S7 | TA 强制下架 与 WA/WE 确认询价 并发 | 确认先完成→按老单放行；下架先完成→确认被拒；无中间态泄漏 | P1 |
| CON-S7-05 | S7 | WA 禁用 WE 与该 WE 提交改价 并发 | 改价要么完整成功要么拒绝；禁用后 token 立即不可用 | P2 |
| CON-S7-06 | S7 | OPS 加黑 与 同键申请提交 并发 | 无「已拉黑却存在新 PENDING」终态（或契约明确补偿口径并测之） | P2 |
| BND-S3-01 | S3 | `withdraw_apply_at` 与归档 job 的**时区一致性**：跨时区/UTC 混用样本（23:59 边界申请） | 60 天窗口判定不因时区提前/滞后一天；≥/> 口径与 WDR-S3-01/02 一致 | P0 |
| BND-S3-02 | S3 | 归档 job 连跑两遍（当天重复触发） | 幂等：第二遍 0 处理，无重复通知/重复状态写 | P1 |
| BND-S3-03 | S3 | 黑名单键归一化边界：11 位手机号带首尾空格、执照号大小写混合 入库与命中两侧 | 存取两侧同一归一化规则；不出现「存的时候没洗、查的时候洗了」的单侧漏拦 | P1 |

---

## 6. 覆盖度自检矩阵（S1–S9 × 模块）

✓=已列必测 ○=抽样 —=不适用（数字=本文用例数）。

| 模块 | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | 小计 |
|---|---|---|---|---|---|---|---|---|---|---|
| ONB 申请/审批 | 7 | 3 | — | 2(+SEC) | 2 | 2 | 2(CON) | 1 | — | 17 |
| BLK 黑名单 | 7 | — | 1 | (SEC×3) | — | 1 | 1(CON) | — | — | 9 |
| WDR 退驻 R13 | 4 | 2 | 3(+BND×2) | (SEC) | 4 | 1 | 1(CON) | — | — | 14 |
| FOF 下架 R14 | 4 | — | — | (SEC) | 4 | — | 1(CON) | — | — | 8 |
| WEM 员工 WE | 7 | 2 | — | 3(+SEC×2) | 1 | 1 | 1(CON) | — | — | 14 |
| SEC 安全专项 | — | — | — | 11 | — | — | — | — | — | 11 |
| CON/BND 专项 | — | — | 3 | — | — | — | 6 | — | — | 9 |
| E2E 链路 | 4 | ○ | ○ | ○ | ○ | — | — | — | — | 4 |

**用例总数：86**（后端场景 62 + 安全 11 + 并发/边界 9 + E2E 4）+ 视觉验收 6 页。
**优先级分布**：P0 = 61 · P1 = 18 · P2 = 7（含 E2E 3/1/0；视觉页 5 P0 + 1 P1 另计）。

S8/S9 说明：本期抽样（ONB-S8-01 通知故障）；黑名单/审批无外部依赖、锁等待超时由 §10 模式统一保障，不单列 S9。

---

## 7. 执行与门槛

1. **后端**：Wave1/2/3 各自交付时按本表模块子集先行落 JUnit（`mvn test` 全量绿）；Wave5 统一核对 `@DisplayName` 与本表 ID 一一对应，缺 S2/S4/S5/S6 阻塞合并（02 §8）。
2. **前端**：`pnpm test:e2e` ONB-E2E-01~03 绿为合并门槛（04 为 P1）；视觉验收 V-01~V-06 截图过审后方可合 main（00 §3.5.3）。
3. **错误码回填**：表中「语义码回填」项在 Wave1/2 契约（10-onboarding-design.md / api-types）定稿后回填本文，作为 v1.1。
4. **缺陷登记**：全部问题进 `03-defect-findings.md`，按 §3.6 根治原则修复，禁止单点补丁。

---

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-16 | 首版：P2 入驻生态 86 用例（ONB/BLK/WDR/FOF/WEM/SEC/CON/BND/E2E）+ 6 页视觉验收清单 |
