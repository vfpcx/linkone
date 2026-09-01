# 15 · 手动测试问题清单（Manual Findings）

> 登记：Team Lead / 用户手动测试 · 持续更新
> 编号规则：`M-xx`（Manual，手动测试发现）；与 D-xx（自动化/审查发现）体系并行，D-xx 明细见 `03-defect-findings.md`。
> 登记模板：**环境 / 步骤 / 期望 vs 实际 / 严重级别 / 归属**（后端 / 前端 / 数据 / 文档 / 产品）。

---

## 0. 一句话结论

1. **M-01**：TA 端侧边栏菜单 16 个页面各自维护，9 页漏「入库」项（仓管员登录默认页 Dashboard 看不到入库）——已补全。
2. **M-02**：后端登录响应从不携带 `tenantInfo`，批发商入库登记弹窗只显示占位「当前入驻仓库」，看不到真实仓库名——已修复（登录响应补租户上下文）；并随多仓决策落地 `roles[].storeName` 下发。

---

## 1. 问题总表

| ID | 级别 | 问题概述 | 复现环境 | 归属 | 状态 |
|---|---|---|---|---|---|
| M-01 | 🟠 P2 | TA 端 9 个页面侧边栏菜单缺「入库」项，仓管员登录默认页 Dashboard 看不到入库 | 前端 admin · WK 仓管员登录 | 前端 | 🟢 已修复待验证 |
| M-02 | 🟠 P2 | 批发商入库登记看不到在哪个仓库入库（后端登录响应无 tenantInfo，弹窗仅占位） | 前端 admin · WA 登录 → 入库确认 → 新建入库申请 | 后端 | 🟢 已修复待验证 |

---

## 2. 明细

### M-01 TA 端侧边栏菜单缺「入库」项
- **级别**：🟠 P2（功能可见性问题，非阻断）
- **环境**：前端 `apps/admin` · 仓管员（WK）登录进 TA 端工作台
- **步骤**：
  1. 仓管员账号登录
  2. 默认落在「工作台」（/ta/dashboard）
  3. 观察左侧菜单——只有「出库作业」，没有「入库」
  4. 点一次「出库作业」进入 /ta/outbound 后，左侧才出现「入库」
- **期望**：登录后侧边栏直接可见「入库」菜单
- **实际**：Dashboard 等 9 个页面菜单数组缺 `{ key: '/ta/inbound', label: '入库' }`
- **根因**：TA 端菜单非公共组件，16 个页面各自维护 menus 数组，9 个页面漏项
- **影响文件**：Dashboard / Wholesalers / WholesalerApplications / Skus / Pricing / Settings / Employees / Messages / BillsOverview（缺）；Inbound / Outbound / Returns / Stocktake / Batches / Clearance / Approvals（有）
- **归属**：前端
- **修复**：9 个缺项页面在「出库作业」前补 `{ key: '/ta/inbound', label: '入库', icon: Box }` + import Box + 跳转白名单；后续建议抽公共 TA 菜单组件根治
- **状态**：🟢 已修复待验证

### M-02 批发商入库登记看不到仓库名
- **级别**：🟠 P2（信息可见性问题，非阻断）
- **环境**：前端 `apps/admin` · 批发商登录 → 入库确认 → 新建入库申请
- **步骤**：
  1. 批发商账号登录
  2. 进入「入库确认」→ 点「新建入库申请」
  3. 弹窗顶部「入库到：当前入驻仓库」——看不到真实仓库名
- **期望**：显示所在仓库（入驻的 TA 仓库）名称
- **实际**：显示占位符「当前入驻仓库」
- **根因**：后端 `AccountServiceImpl.doLogin` 构建登录响应时**从不设置 `tenantInfo`**（RT 路径同样不设），`LoginVo.tenantInfo` 恒缺省 → 前端 `auth.currentStoreName`（=`tenantInfo.tenantName`）恒空 → 批发商端顶栏/入库弹窗 storeName 全走占位
- **归属**：后端
- **修复**：`doLogin` 遍历角色，取首个有 tenantId 的角色，经 `TenantService.getTenantName/getSimpleCode` 填充 `LoginVo.tenantInfo`（跨域走 service 不直连 mapper，符合 G-S1/G-S2；OPS/RT 无租户角色不受影响，NON_NULL 序列化兼容）
- **多仓（2026-09-01 决策落地）**：产品确认「一个批发商账号可入驻多个仓库」（手机号=账号唯一标识，不再支持同手机号重复注册不同账号进多仓，05 §6.3 同步修订）。本次已落地：
  - 后端三处 50204（申请提交 / 审批通过复查 / OPS 代建）由「全平台维度」改为「同租户重复」维度——他仓绑定不再拦截；
  - 50201 PENDING 按 (账号, 目标租户) 计数；V37 迁移将 `uk_applicant_pending` 唯一索引扩为 `(applicant_user_id, tenant_id, pending_flag)`（MySQL/H2 双变体）；
  - `ErrorCode.WHOLESALER_ALREADY_ONBOARDED` 文案更新为「该账号已入驻本仓库批发商」；
  - 登录响应 `roles[].storeName` 新增下发（`LoginVo.RoleInfo`），供前端工作空间切换器展示仓库名；`tenantInfo` 保持「默认工作空间」单值；
  - 新增回归测试 ONB-01b（一账号入驻两仓均成功 + 同仓重复仍 50204 + 登录 roles[] 全量断言）。
  - **阶段 2 多仓路由（已实施，2026-09-01）**：批发商业务接口按当前工作空间（X-Tenant-Id → `TenantContext.tenantId`）收敛——
    - `AuthService` 新增 `listActiveWholesalerIds(userId, role, tenantId)` / `listActiveWeWholesalerIds(userId, tenantId)` 重载（tenantId 为 null 等价全量，兼容单仓）；
    - 3 处 `requireOwnWholesaler`（退驻 WholesalerLifecycleServiceImpl / 员工码 TenantServiceImpl / 员工管理 WholesalerEmployeeServiceImpl）改为按当前仓定位，多仓无上下文时明确提示「请先在顶栏选择当前仓库」（PERMISSION_TENANT_001）；
    - Inquiry/Inbound/Outbound/Return/Batch/Bill 各批发商列表接口集合按当前仓收敛（`listForWholesaler` 的「本仓唯一下→未入池量可算」随之成立）；
    - 前端：登录后自动补写默认工作空间（`currentTenant` → X-Tenant-Id 注入），角色切换器跨仓切换时整页刷新携带新仓标识；
    - 新增回归测试 ONB-01c（WE 注册码按仓定位 + 两仓码互异 + WE 码列表隔离 + 各列表按仓收敛不报错）。回归：162 测试全过 + 前端 typecheck 通过。
- **状态**：🟢 已修复待验证（多仓入驻 + 业务数据隔离已全部落地）

---

## 3. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-01 | 建文件：手动测试问题登记清单（M-xx 编号体系） |
| v1.1 | 2026-09-01 | 登记 M-01（TA 端菜单缺入库项），已补 9 页缺项 |
| v1.2 | 2026-09-01 | 登记 M-02（WA 入库看不到仓库名），后端登录响应补 tenantInfo |
| v1.3 | 2026-09-01 | M-02 多仓决策落地：一账号可入驻多仓（50204/50201 改租户维度 + V37 索引 + 50204 文案 + roles[].storeName），新增 ONB-01b 多仓回归；登记批发商业务接口多仓路由为阶段 2 演进 |
| v1.4 | 2026-09-01 | 阶段 2 多仓路由实施完成：AuthService 租户收敛重载 + 3 处 requireOwnWholesaler + 各列表按 X-Tenant-Id 收敛 + 前端默认工作空间/跨仓刷新；新增 ONB-01c 隔离回归；162 后端测试 + 前端 typecheck 通过 |
