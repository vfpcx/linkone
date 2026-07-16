# Task Plan · P2 入驻生态（Onboarding）

> 规则依据：CLAUDE.md 全局协作规则 §6（编码阶段自主执行）§7（planning-with-files）。架构全局设计 P0 已有（见 findings.md §一），本期为落地实现，不重做架构设计。

## 目标与范围（用户已拍板按 Team Lead 思路推进）

**做**：
1. WA 自助入驻申请 → TA 审批（通过/驳回带理由）；WA 注册链路接通（AccountServiceImpl:222 接入点）
2. OPS 代建 WA（需 TA 授权凭据或客诉单号，留痕；黑名单同样拦截）
3. TA 建自营 WA 走统一入驻链路（对齐 D15，现有 createSelfOperated 保留兼容）
4. R13 退驻：前置校验（库存 0+无未结单据；账单校验 P4 占位）→ TA 审批 → SKU 下架+店铺隐藏+专属价失效+踢 token → 60 天恢复/归档（定时任务）
5. R14 强制下架：TA 单方即时；新业务拒绝、老单据放行；不可原地恢复
6. OPS 全平台黑名单：手机号/执照号双键，入驻申请+OPS 代建必检
7. WE 员工：WA 生成员工码（invite_codes 复用）、WE 注册绑定 wholesaler、最小授权集（PRICE_EDIT/INQUIRY_CONFIRM）、R17 禁用踢出、登录路由修正

**不做**（明确出圈）：
- 账单争议中仲裁全流程（billing P4）、客诉单实体（Q-D04 未决）
- 入驻条件设置字段、退驻数据归档迁移（只做 ARCHIVED 状态位）
- 仓库广场地图/距离（P5 运营）、容量公示精度
- 通知走现有 mock 短信基建，不建推送通道

## 决策记录（Decisions）

| # | 决策 | 依据 |
|---|---|---|
| O-1 | 申请表+主体表双轨，复用 tenant_applications 审批模式 | 代码先例一致性 |
| O-2 | 黑名单拦截 OPS 代建路径 | 防绕过（R-04），产品缺口补位 |
| O-3 | 错误码落地文档预留 50201-50205，溢出用 50310-50329 | findings §三 |
| O-4 | WE 授权最小集两枚：PRICE_EDIT、INQUIRY_CONFIRM，存 user_roles 扩展或独立权限表（开发时按 V10 设计定） | 产品缺口 #5 |
| O-5 | R13 账单结清校验留 TODO 接口占位（BillingService P4） | 依赖未建 |
| O-6 | blacklist 不进 TenantLine 白名单（平台级表） | 隔离模型 |

## 阶段（Phases / Waves）

- [待办] **Wave 1 后端·入驻主链**：V10 迁移（wholesaler_applications + blacklist + wholesalers 补列 + WE 授权位）；WholesalerApplication/Blacklist 实体+Mapper；申请（自助/注册接入/OPS 代建/TA 自营统一）+ TA 审批 + 黑名单检查 + BlacklistService(OPS CRUD)；错误码 50201-50205；状态机校验；场景测试；10-onboarding-design.md。〔worktree: onboard / branch feat/p2-onboarding〕
- [待办·并行] **Wave 4 前端·第一批**：api-types 补全 + api/tenant.ts NOT-IMPL 落地；WA 入驻申请表单页；TA 审批页（列表+通过/驳回弹窗）；OPS 黑名单管理页（含 OPS 路由角色守卫）。〔worktree: onboard-fe / branch feat/p2-onboarding-fe〕
- [待办] **Wave 2 后端·R13/R14**：退驻申请+TA 审批+副作用链（SKU 下架/店铺隐藏/CustomerPrice 失效/kickout）；60 天恢复+归档定时任务；强制下架+document 域新业务拒绝校验点；场景测试。〔等 Wave1〕
- [待办] **Wave 3 后端·WE 员工**：员工码白名单放开（WE+wholesaler_id）；WE 注册绑定；授权位读写+校验切点（pricing/inquiry）；R17 禁用踢出；登录路由 D52 修正；场景测试；**顺路补 `GET /api/v1/admin/tenants?status&page&size`（OPS 租户列表，前端已契约先行，字段见 api-types/ops.ts AdminTenantItem，注意通过态是 ACTIVE 非 APPROVED）**。〔等 Wave2（授权切点碰 pricing 代码）〕
- [待办] **Wave 4b 前端·第二批**：WA 退驻发起页+强制下架确认；WA 员工管理页（生码/授权/禁用，仿 ta/Employees.vue）；WE 注册流适配。〔等 Wave2/3 接口〕
- [待办] **Wave 5 测试/审查/合并**：onboarding-flow E2E（Playwright）+ 视觉验收（§3.5/3.6）；code-review + 修复；合并双分支；回归绿（后端全量+前端 E2E）；交付报告。

## 接口契约（据 04-api-spec，落地时可微调路径归属）

- `POST /api/v1/wholesaler/applications`（WA 提交申请）
- `GET /api/v1/tenant/wholesaler-applications`（TA 列表）+ `POST .../{id}/audit`（通过/驳回，仿 tenant audit DTO）
- `POST /api/v1/admin/wholesalers`（OPS 代建，authBasis 必填）
- `POST /api/v1/wholesaler/withdraw`（R13）+ TA 审批端点 + `POST .../restore`（60 天恢复）
- `POST /api/v1/tenant/wholesalers/{id}/force-offline`（R14）
- `GET/POST/DELETE /api/v1/ops/blacklist`
- 员工码/员工管理（Team Lead 定稿，Wave3 后端与 Wave4b 前端共同遵守）：
  - `POST /api/v1/wholesaler/employee-invites` body:{expireDays,maxUses,permissions:string[]}（targetRole 固定 WE；permissions ⊆ [PRICE_EDIT,INQUIRY_CONFIRM]）
  - `GET /api/v1/wholesaler/employee-invites` / `DELETE .../{id}`（作废）
  - `GET /api/v1/wholesaler/employees`（本商户 WE 列表含 permissions/status/禁用时间）
  - `PUT /api/v1/wholesaler/employees/{id}/permissions` body:{permissions}
  - `POST /api/v1/wholesaler/employees/{id}/disable`（R17 踢出+草稿作废）/ `POST .../{id}/restore`（30 天内）

## 验证（Verification）

- 后端：`mvn test` 全量绿（含新增场景测试：申请→审批→驳回→黑名单拦截→代建→退驻前置失败/成功→下架老单放行→WE 授权/未授权）
- 前端：`pnpm test:e2e` onboarding-flow 绿 + Playwright 截图视觉自查（§3.5/3.6）
- 本地联调：后端 `dev,local` profile（勿忘）:8080 + 前端 :5173

## 未做 / 后续
- 争议中→OPS 仲裁闭环（P4 billing 后补）
- 仓库广场（P5）
- 通知升级为真实短信/推送（X 硬化）
