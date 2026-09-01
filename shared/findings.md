# Findings · P2 入驻生态（Onboarding）

> 调查时间 2026-07-16。来源：产品 PRD 提取 Agent + 代码现状调查 Agent。旧定价三件套已归档至 `shared/archive/`。

## 一、架构已有全局设计（P0 蓝图），无需重做架构设计

- `architecture/03-database-schema.sql`：`wholesaler_applications`、`blacklist`、`wholesalers.withdraw_apply_at`、`user_roles.wholesaler_id`、`invite_codes.wholesaler_id` 均已定义
- `architecture/04-api-spec.md`：入驻申请 `POST /api/v1/wholesaler/applications`、TA 审批列表 `GET /api/v1/tenant/wholesaler-applications`、退驻 `POST /api/v1/wholesaler/withdraw`、OPS 黑名单 3 端点（§331-333）
- `architecture/02-modules.md §2.3`：domain-wholesaler 职责与接口名（applyToTenant/approveByTa/createByOps/createSelfOperated/withdraw/forceOffline + BlacklistService）

## 二、产品规则要点（提取自 product/01~06,99）

### 状态机（04-core-flows §1.8）
```
[*]→待审核(WA申请) →已驳回(TA驳回) | →正常(TA通过)
[*]→正常(OPS代建,需TA授权/客诉单)
正常→已下架(TA强制下架R14，不需审批，不可原地恢复，需重新入驻)
正常→已退驻(WA退驻R13，需TA审批)
已下架→争议中(有未结账单)→已退驻(OPS仲裁)
已退驻→正常(60天内恢复) | →归档(60天后)
不可达：已退驻→已下架 ❌；已下架→正常 ❌
```

### 关键业务规则
- **入驻**：一个批发商账号可入驻多个仓库（2026-09-01 多仓决策，手机号=唯一账号；仅拦同仓重复 50204，05 §6.3）；黑名单命中不能提交新申请；驳回需填理由；TA 可建自营 WA（D15 不自动绑定）
- **R13 退驻**：前置 = 库存 0 + 账单全结清 + 无未结单据（05 §4.2，委托 InventoryService.assertZeroStock + BillingService.assertAllSettled——billing 是 P4，本期用占位/仅库存+单据校验）；通过后 SKU 全下架、店铺隐藏、CustomerPrice 全部失效（05 §14b.5）、WA/WE token 即时踢出（05 §16.5）；60 天可恢复
- **R14 强制下架**：TA 单方即时，不需审批；店铺隐藏/新询价新出库拒绝；**已确认意向单+已生成出库单允许完成**（03 §7 规则7）；未结账单标"争议中"（billing P4，本期只落 wholesaler 状态位）
- **黑名单**：OPS 专属，手机号/营业执照号双键；全平台入驻申请必检；解除流程产品未定义（架构有 removeFromBlacklist）
- **WE 员工**：WA 生成员工注册码（复用 invite_codes，wholesaler_id 字段已预留）；权限=授权制（改价/询价确认 🔵授权，账单 ❌ 不可见）；R17 禁用即踢出+草稿作废、30 天可撤销
- **通知**：审核通过/驳回、OPS 代建、强制下架均要求 站内信+短信+推送（本期按现有 mock 短信基建处理）

### 产品设计缺口（开发时按现有设计系统自行补齐，标注到交付文档）
1. 线框缺失：WA 入驻申请表单、TA 审批详情、退驻发起页、强制下架确认弹窗、黑名单管理页、WA 员工管理页（有 TA Employees.vue 先例）
2. 黑名单是否拦截 OPS 代建 → **决策：拦截**（防绕过，R-04 风险缓解一致）
3. 退驻归档形态（Q-D07 未决）→ 本期只做状态位 ARCHIVED，不做数据迁移
4. "入驻条件设置"字段未细化 → 本期不做，仅通过/驳回
5. WE 可授权权限点 → 本期最小集：`PRICE_EDIT`（改价）、`INQUIRY_CONFIRM`（询价确认）两个授权位

## 三、代码现状（决定波次切分）

- **Flyway 已到 V9，下一版 V10**；`wholesalers` 表无审批字段（status 默认 ACTIVE、source 默认 SELF_OPERATED），需 V10 建 `wholesaler_applications` + `blacklist` + wholesalers 补列（withdraw_apply_at、offline 相关）
- **可复用先例**：
  - tenant 审批双轨模式（tenant_applications 申请表 + 主体表 audit 三件套）：`TenantServiceImpl.audit` :122-159、仅 PENDING 可审、hasRole 显式校验
  - WA 账号开通 `WholesalerServiceImpl.ensureWaAccount` :145-171（幂等建 User+UserRole）
  - 员工码机制完整，WE 只差两处白名单：`TenantServiceImpl.consumeInviteForRegister:454`（仅 WK/ST）+ `EmployeeInviteCreateDto`（role 白名单）；WE 码需写 wholesaler_id
- **直接接入点**：`AccountServiceImpl.java:222-226` WA 注册带 targetTenantId/wholesalerName 目前只打日志——P2 在此接入自动建申请单
- **错误码**：文档 STATE_WHOLESALER 50201-50205 已预留未落地（50201 审核中/50202 已退驻/50205 黑名单）；枚举实际已占用 50210-50306；**新码用 50201-50205（补落地文档预留）+ 溢出走 50310+**
- **前端**：api-types `WholesalerApplication` 等契约雏形已预写（`api/tenant.ts:156-190` 标 NOT IMPL）；WA 端仅 Inquiry.vue；OPS 端全是占位——**OPS 黑名单页是 OPS 端第一个真实页面**；生码 API 类型已含 WE
- **鉴权惯例**：无注解式，Service 内 `authService.hasRole` 显式校验；TenantLine 白名单在 `MybatisPlusConfig.TENANT_FILTER_TABLES`（wholesaler_applications 含 tenant_id 应加入；blacklist 是平台级**不加**）

## 四、风险/注意

- WE 登录跳转 `AccountServiceImpl:672-693` 现在占位跳 /ta/dashboard，需按 D52 优先级 TA>ST>WK>WA>WE 修正为 WA 端路由
- R14 "老单据放行" 需要 document 域配合校验点（新询价/新出库拒绝、已确认的放行）——改动跨域，测试要覆盖
- OPS 端守卫目前只查登录不查 role（router/index.ts:137-153），OPS 黑名单页落地时要补角色守卫
