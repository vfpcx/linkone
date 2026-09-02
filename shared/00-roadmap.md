# 仓储云 SaaS · 整体路线图（Master Roadmap）v2

> 编写：Team Lead · 2026-06-28（v2 2026-09-01 更新：P1–P4 状态按各期交付报告/归档计划校正，X 期细化 PII 三段式进度）
> 依据：`architecture/02-modules.md`（模块全集）+ `product/`（PRD/故事）+ 用户阶段决策
> 角色：OPS 平台运营 · TA 仓库主体(店铺=TA 1:1) · WK 仓管 · ST 财务 · WA 批发商(店内卖家) · WE 批发商员工 · RT 终端买家(H5/小程序)
> 说明：按**能力闭环**分期，非按模块。每期内部仍走 产品→架构→并行开发→审查→合并；每期贯穿安全规约自检 + 回归测试绿。

---

## 进度总览

| 期 | 主题 | 状态 |
|---|---|---|
| **P0** | 基础底座（账号/租户/安全/契约/测试基建）| ✅ 已完成 |
| **P1** | 批发商卖货最小闭环（到出库）+ RT H5 验证 | ✅ 已完成 |
| **P2** | 入驻生态 + 定价能力 | ✅ 已完成 |
| **P3** | 完整单据与履约异常 | ✅ 已完成 |
| **P4** | 计费结算 | ✅ 已完成 |
| **P5** | 运营增强与正式多端 | 🟡 进行中（**P5-A 全绿收官** + **P5-C Dashboard(TA+OPS) 真实接口 ✅** + **TA 一账号多仓收敛 ✅** + **B D56 商品档案(P5-D 起步) ✅**；2026-09-02 拍板排期 **A ✅ → B ✅ D56 商品档案 → C 小项池 → D X期本地 → F 正式多端**；E/P5-B OSS+ASR 维持挂起）|
| **X** | 生产硬化（贯穿，上线前必过）| 🟡 收尾（PII 三段式全 ✅；部署侧 W8-L1~L6 待环境，其中本地可做项 L1/L4/L5 已排期 D 波 2026-09-02）|

---

## P0 · 基础底座 ✅
- 账号/鉴权：注册/登录(密码+验证码)/找回/改密/换绑，Sa-Token 会话，登录锁定限流，防账号枚举
- 租户：TA 自助注册仓库(PENDING)→OPS 审核→ACTIVE；店铺与 TA 1:1；店铺设置
- 安全加固：鉴权 path、跨租户隔离(TenantLine)、验证码 mock 隔离、短信防刷、状态机/唯一性/范围校验
- 工程：前后端契约对齐(roles/路由)、错误码、Redis 稳态、**场景测试(S1–S9)+Playwright E2E+CI**、安全编码规约
- 产出：后端 47 测试绿 / 前端 Playwright 8 绿 / 已上 GitHub

## P1 · 批发商卖货最小闭环 ✅（详见 `architecture/06-phase1-wholesaler-selling-plan.md`）
**目标**：TA 自营建商户 → 上架SKU(公开价) → WK入库 → RT(H5)扫码进店询价 → WA确认 → 自动转出库 → WK出库
- 模块切片：wholesaler(仅TA自营) · product(SKU+公开价) · inventory(入/出库,批次关闭) · document(入库单/询价/出库单) · store-front · **RT 最小 H5**
- 不含：自助入驻审批、专属价沉淀、账单、退货盘点临期、批次复杂度
- 执行：6 波单切片 Agent，按余额窗口推进
- 产出：2026-07-02 交付（`test-plan/05-phase1-delivery-report.md`）：闭环打通、测试全绿、审查闭环

## P2 · 入驻生态 + 定价能力 ✅
- **入驻**：WA 自助入驻申请 → TA 审批；OPS 代建(需授权/客诉单)；退驻(R13)/强制下架(R14)/全平台黑名单
- **定价**：客户专属价((rt_phone,sku))、议价沉淀(询价确认内沉淀)、批量调价(涨降%/改值, Redisson锁)、调价历史、价格匹配≤200ms(Redis缓存)
- WE（批发商员工）账号与权限
- 产出：入驻（`shared/archive/task_plan-p2-onboarding.md`，Wave1 主链 + Wave2 R13/R14，158/158 绿）；定价（`test-plan/06-p2-pricing-delivery-report.md`，127 绿 + 视觉验收）
- **2026-09-02 增量 · WA 一账号多仓**（产品决策 2026-09-01）：V37 `uk_applicant_pending` 按 (账号, 目标租户) 维度；入驻/代建/审批仅拦同仓重复（50204 文案改「本仓库」）；WA/WE 各接口按 `X-Tenant-Id` 收敛当前仓（inquiry/inbound/outbound/return/batches/billing/员工/退驻）；登录响应 `roles[].storeName` 实际下发 + `tenantInfo`（M-02）；前端工作空间切换跨仓整页刷新。验证：后端 472 全绿（OnboardingScenarioTest 增多仓场景）+ 前端 typecheck/build 通过（提交 036d133/3461e58/943f8fd）

## P3 · 完整单据与履约异常 ✅
- 入库：WK 代建 72h 默认接受 + WA 异议 → 反向冲销 + TA 仲裁；拍照入库 + 展示图同步
- 出库：代建出库不可异议、大额二次确认、WA 客诉 → OPS 仲裁
- 退货单、盘点单(盘盈/盘亏)
- 批次(Batch)管理 + 临期预警 + 强制清库(ExpiryClearance) + 批次开关联动副作用
- 单据状态机引擎抽象、单据打印(PDF)、单据号(Redis INCR)
- 产出：`shared/archive/task_plan-p3-documents.md`（T5/T1/T2 异常链+双仲裁）、`task_plan-p3b.md`（T1 正向申请链/T3 退货盘点/T4 批次临期，337 绿 / E2E 38）；交付报告 `test-plan/10-p3-delivery-report.md`、`11-p3b-delivery-report.md`

## P4 · 计费结算 ✅
- DailySnapshot(每日0点) → 月度账单(Bill/BillItem)生成
- 账单调整(折扣/减免/冲销)、下发WA、已收款登记/冲销、账单申诉、导出(PDF/Excel)
- 分段计费(R20 规则变更)、BillingRule(在 tenant)
- 产出：2026-08-10 收官（`test-plan/12-p4-delivery-report.md`，八段合并，408 绿×4 遍 / E2E 45）

## P5 · 运营增强与正式多端 🟡
- **P5-A 通知中心 + 平台公告 + 撮合运营**（拍板采纳 D-P5-1~5，D-P5-6/7 暂缓/取消；`product/14-p5-requirements.md` + `architecture/18-p5-design.md`）
  - **W3 后端 ✅（2026-09-01，457 绿）**：notify 域首次实现——通知中心增强（分组筛选 ANNOUNCE/BIZ/ALL + 全部已读 readAll）；平台公告 `announcements`（V35 迁移）+ OPS 管理（创建/列表/详情/发布/下架）+ 发布同事务批量写目标角色站内信（target_roles 展开收件人）；AuthService 新增平台级收件人反查出口（`listActiveUserIdsByRoles`/`listAllActiveUserIds`）；错误码 50501-50503（以实测定稿，见 `api-contract-notify.md`）；通知中心/公告集成测试覆盖收件人推导/状态机/权限/分组
  - **W4 ✅（2026-09-01）**：V36 撮合配置 `storefront_featured`（b2cb572）+ StorefrontFeature 模块（TA 配置 GET/PUT，mainSkuIds≤20/pinWaIds≤5，覆盖保存、数组顺序落 sort_order、校验 50711-50714）+ storefront 出参前置排序与 featured/pinned 标记（B1 租户过滤修复 4fc717b）；前端（6f0ca67）消息中心页/公告管理页/公告弹窗（登录即弹 B3 修复 315257c）/店铺撮合区块；E2E 公告 13/13 + 撮合 7/7 全绿（ad2c915）；契约文档补齐（a595db4：api-contract-account §5.9 + 新建 api-contract-notify/storefront）
  - **W5 ✅（2026-09-01）**：E2E 全量 18 spec/129 例全绿（6bf99b9 回归 121 + 9ee9eb7 视觉矩阵 8/8 + 41001 去重）+ 收尾修复：ONB-E2E-02 黑名单 REMOVED 摘要撞唯一键（backend 9104adf，BLK-05 红→绿双证，470 绿）+ ONB-E2E-04 http.ts 41001 弹窗去重（frontend 9ee9eb7）+ 公告弹窗 375 溢出修复（frontend 27bad02）+ 去 workaround 终验 129/129（1dd627e/8ba046a）；交付报告定稿 `test-plan/14-p5a-delivery-report.md`（d2c1483，470 后端 + 129 E2E + 8 视觉全绿），手动测试问题登记模板 `test-plan/15-manual-test-findings.md`（11058f0）
  - **P5-C · Dashboard 真实接口（TA）✅（2026-09-02）**：`architecture/19-p5c-dashboard-design.md`——GET /tenant/dashboard 真实数据（店铺概要+容量三档+待办计数），TenantDashboardVo/Service/Controller 新建 + CountSheetService.countPendingApprovalForTenant；TenantDashboardScenarioTest；提交 89bfb6d（backend）/0ac4fc8（frontend）/fb900f4（docs）
  - **TA 一账号多仓收敛 ✅（2026-09-02）**：`architecture/20-p5-ta-multi-warehouse.md`——TA 端接口 X-Tenant-Id 收敛：公共支持类 `TenantScopeAuthSupport`（TenantContext 优先 + 该仓角色二次校验防跨仓越权 + 回退登录态推导）；tenant 域 5 gate + dashboard（requireTaOrWk）+ billing 域 4 gate（requireTa/requireStOrTa）+ batch toggle 共 11 处改造，`apply`（注册建仓）/OPS/公开目录不收敛；前端零改动（http.ts 全量注入 + WarehouseSwitcher 已就位）；TenantMultiWarehouseScenarioTest 7 例（S1 隔离/S2 跨仓角色越权拒绝/S3 单仓兼容/S4 写操作落仓）+ 全量回归 486 绿
- 其余（OPS 控制台指标、ST/RT 正式多端、语音 ASR 录单、文件/OSS、capacity 快照 job【挂待环境档】）未拍板
- **后续排期（2026-09-02 用户拍板按 A→B→C→D→F 推进）**：
  - **A · OPS 控制台真实接口 ✅ 2026-09-02 已收官**（P5-C 完成）：`/ops/dashboard` 占位页转真实接口——后端 `OpsDashboardServiceImpl`（tenant 域聚合 + requireOps 42002）+ document/notify 跨域出口（countPendingForOps / countComplaintsCreatedToday / countDrafts）+ `OpsDashboardScenarioTest` 7 例（基线差分）；前端 `views/ops/Dashboard.vue` + OPS 菜单 5 项统一；全量回归 493 全绿；口径 `product/15`、设计 `21-p5c`
  - **B · D56 商品档案体系 ✅ 2026-09-02 已收官**（P5-D 起步）：OPS 标品库（US-OPS-02，SpuCatalog.vue 搜索/新增两级品类联动/合并/下架）+ SKU 挂 SPU（TA 建 SKU 选 ACTIVE 标品回填 spuId + 列表展示所属标品）+ V38 `spus` 平台级表落地 + `skus` 标品快照 3 列（挂接/合并原子刷新）；口径 `product/16`（D-B-1~7 全采纳）、设计 `architecture/22`；backend 63917bc / frontend fb01030；全量回归 500 全绿
  - **C · 小项池**（P5-D 顺延）：US-WK-05 移库 / US-RT-05 历史询价复购 / US-WE-04 客户跟进，逐项拆解每项一波
  - **D · X 期本地收尾**（不待环境）：W8-L4 CVE 复扫（OWASP dep-check/Trivy）+ W8-L5 graceful shutdown 实测 + W8-L1 还原演练脚本固化 `shared/ops/`
  - **F · 正式多端**（P5-C 余下）：ST 全功能 H5（US-ST-06 移交）+ RT 正式小程序/H5（uni-app 栈，D09）
  - **挂起不排**：E/P5-B（OSS/ASR 待外部选型+云账号）、capacity 快照 job（待环境）、US-WA-01b 容量告警（暂缓）

## X · 生产硬化（贯穿，上线前必过）🟡 进行中
- **手机号明文加密(PII, H1)**——三段式，**W8 收口完成**（2026-09-01，main=72c5597，后端 H2 451 绿，架构师 §8.2 终验通过）：
  - S0 加列双写 ✅（V27/V30 五表 hmac 双写+回填+对账，419 绿）
  - S1 影子灰度+切读 ✅ 代码就位：Step1 影子双查（登录 8 切点）→ Step2 非命门切读（blacklist/sms/pricing + Redis 键 HMAC 化）→ Step3 登录双读切换（2026-08-31，488 绿）；**生产切读执行待环境**
  - S2 明文收缩（V31-V34 删明文列，唯一不可逆段）**[完成 2026-09-01]**：V31 cipher/last4 补列 + V32 hmac 唯一索引 + V33 RENAME + V34 DROP + blacklist last4 摘要；删双写/开关 6 类 + 4 Service 直连 hmac；AES-GCM + 确定性 KAT；F1 前端 D1/D4 合入。**V31-V34 真实 MySQL 执行；§8.1 回填闸门真实库核对通过（缺口数据已整链清除，9/1，备份 backup_w8_gap_delete_20260901.sql；uk_phone_hmac 唯一索引完好、hmac 零重复）+ V34 观察期闸门待发布窗口**
  - 产品决策 D1-D4 全部定稿（wa/Inquiry 查全号放开、wa/Staff 全号显式例外 G-8.6）
- 其余硬化项 ✅ 代码落地（`test-plan/09-hardening-w1-report.md`）：H2 Redis 密码+ACL（prod fail-fast，ACL username 留注释）/ H3 Sa-Token active-timeout（主配 1800s）/ H4 SQL stdout 关闭+日志 profile 化 / H5 Boot 3.2.5→3.5.16 CVE 根治
- 上线检查单余项（**待环境**，`task_plan.md` 验收条目）：CVE 复扫、prod 冒烟、graceful shutdown、Redis 实际启用密码
- 对应缺陷清单 D-14（见 `test-plan/03-defect-findings.md`）

---

## 依赖主线（粗粒度）
```
P0 账号/租户/安全 ──> P1 卖货闭环 ──> P2 入驻+定价 ──> P3 完整单据 ──> P4 计费 ──> P5 运营/多端
                          └─ store-front/inventory/document 在 P1 打基础，P3 扩异常分支
                          └─ pricing 在 P1 只做公开价，P2 补专属价/议价/批量
```

## 节流与质量约束（每期适用）
- **API 余额**为节流阀：单切片 Agent(≤~100k)、每波 1–2 个、完成即验证+提交，撞墙即停等恢复。
- 每个新接口按 `architecture/05-secure-coding-guardrails.md` 自检（鉴权/租户隔离/S2/S4/S5 用例）。
- 每波合并 main + 重跑场景测试，保持回归绿；E2E 随接口就绪扩链路。

## 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-28 | 首版：P0–P5 + 贯穿硬化 的整体路线图 |
| v2 | 2026-09-01 | 进度校正：P1–P4 全 ✅（按交付报告/归档计划）；P5 未拍板保持规划；X 细化 PII 三段式进度（S2/W8 收口完成，main=72c5597，451 绿，V31-V34 发布窗口待环境）+ 硬化 H2–H5 落地状态 |
| v2.1 | 2026-09-01 | 校准进度总览：X 期改「收尾（PII 全 ✅，仅剩部署侧 W8-L1~L6）」，与正文一致 |
| v2.2 | 2026-09-01 | P5 拍板 P5-A 并启动：进度总览改进行中；正文增 P5-A 三段进度（W3 后端✅ 457 绿，W4/W5 待排期）|
| v2.3 | 2026-09-01 | P5-A W4 完成：撮合配置+storefront 出参（b2cb572，468 绿）+ 公告租户过滤修复（4fc717b）+ 前端四件套与登录即弹修复（6f0ca67/315257c）+ E2E 公告 13/13 撮合 7/7（ad2c915）+ 契约文档补齐（a595db4）；W3 公告错误码校正为实测 50501-50503（草案 50701-50703 作废）|
| v2.4 | 2026-09-01 | **P5-A 全绿收官**：W5 全量回归 470 后端 + 129 E2E + 8 视觉（6bf99b9/9ee9eb7/1dd627e/8ba046a）+ 历史链路修复 ONB-E2E-02（9104adf）/ ONB-E2E-04（9ee9eb7）+ 375 适配（27bad02）+ 交付报告定稿（d2c1483）+ manual findings 模板（11058f0）；P5 余下子项（viewer 脱敏/ASR/OSS/小程序/Dashboard）仍待拍板 |
| v2.5 | 2026-09-02 | **WA 一账号多仓落地**（产品决策 2026-09-01）：V37 uk 维度调整 + 入驻同仓唯一 + 各接口 X-Tenant-Id 收敛 + 登录下发 storeName/tenantInfo；M-01/M-02 手动测试修复验证通过（472 后端全绿 + 前端 typecheck/build）；提交 036d133（backend）/3461e58（frontend）/943f8fd（docs） |
| v2.6 | 2026-09-02 | **P5-C Dashboard(TA) 真实接口 + TA 一账号多仓收敛**：19-p5c TA 工作台真实接口（89bfb6d/0ac4fc8/fb900f4）+ 20-p5 TA 端 X-Tenant-Id 收敛（TenantScopeAuthSupport：scoped + 该仓角色二次校验 + 回退；tenant/dashboard/billing/batch 共 11 处 gate；前端零改动）；TenantMultiWarehouseScenarioTest 7 例 + 全量回归 486 全绿 |
| v2.7 | 2026-09-02 | **后续排期拍板 A→B→C→D→F**（TA/WA 多仓 + P5-A/P5-C(TA) 收官后）：A OPS 控制台真实接口（P5-C 收官）→ B D56 商品档案（P5-D 起步）→ C 小项池（移库/复购/客户跟进）→ D X 期本地收尾（CVE 复扫/graceful shutdown/还原演练脚本，不待环境）→ F 正式多端（ST 全功能 H5 + RT 小程序/H5）；E/P5-B（OSS+ASR）维持挂起待云账号与选型 |
| v2.8 | 2026-09-02 | **A OPS 平台运营控制台真实接口（P5-C 收官）**：15-p5c 口径拍板（D-OPS-1~6）+ 21-p5c 设计定稿 + 后端实现（tenant 域聚合 OpsDashboardServiceImpl + document/notify 跨域计数出口 + OpsDashboardScenarioTest 7 例基线差分）+ 前端 Dashboard.vue（占位页转真实）+ OPS 菜单 5 项统一；全量回归 493 全绿 |
| v2.9 | 2026-09-02 | **B D56 商品档案收官（P5-D 起步）**：product/16 口径（D-B-1~7 全采纳）+ architecture/22 设计定稿并实现——V38 `spus` 平台级表 + `skus` 标品快照 3 列（逐条 ADD 兼容 H2）；product 域 Spu 全套（requireOps 42002 / ACTIVE/OFFLINE/MERGED 状态机 / 合并源 MERGED+引用 SKU 单 SQL 原子重指+快照刷新 / 自动编码唯一）+ OpsSpuController + CatalogSpuController（登录态只读 /catalog/spus，补 TA 选标品越权盲点）+ SpuCatalog 两级品类字典；SkuServiceImpl 挂 ACTIVE 校验 + 快照写（backend 63917bc）；前端 SpuCatalog.vue + OPS 菜单 5→6 统一 + TA Skus.vue 选标品（frontend fb01030）；OpsSpuScenarioTest 7 例 + 全量回归 500 全绿 |
