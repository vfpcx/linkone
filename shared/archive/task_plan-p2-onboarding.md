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

- [完成] **Wave 1 后端·入驻主链**：142/142 绿，commit 至 feat/p2-onboarding（已合并 c6b3b52）
- [完成] **Wave 4 前端·第一批**：3 页+守卫，typecheck 绿（已合并 8843141）
- [完成] **Wave 2 后端·R13/R14**：158/158 绿含 16 新增，9 端点+归档 job
- [完成] **Wave 3 后端·WE 员工**：176/176 绿含 18 新增，8 端点+D52 路由
- [完成] **Wave 4b 前端·第二批**：退驻/下架/员工页+WE 注册流，24 截图自查
- [完成] **Wave 5 测试/审查/合并**：E2E 4 链路绿+回归 30/30+45 截图目检；报告 07（80d4c06）；双分支已合并；遗留缺陷 DEF-1~DEF-6 转 Wave 6
- [完成] **Wave 6 收尾·缺陷修复（2026-07-23 完成）**：DEF-1~6 全部修复并复验（后端 fix/p2-defects 5 commits + 前端 fix/p2-defects-fe 6 commits，前端二批项已随一批完成）；合并 main 后回归 187/187 绿 + typecheck 绿 + E2E 12/12 绿；报告 07 v2 增补复验记录。**P2 入驻生态全部交付。**

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
