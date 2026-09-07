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
| **P5** | 运营增强与正式多端 | 🟡 收官在即（**F 波正式多端 ✅ 收官 2026-09-07**：F2 RT 买家 / F3 WA+WE / F4 ST / F5 WK（W1 出入库 + W2 库存·批次·临期·盘点）/ F6 RT 登录 五业务子波全链闭合，后端全量 **536 绿**；仅 E/P5-B OSS+ASR 挂起待云账号与选型）；回看：**P5-A 全绿收官** + **P5-C Dashboard(TA+OPS) 真实接口 ✅** + **TA 一账号多仓收敛 ✅** + **B D56 商品档案 ✅** + **C 小项池 C1/C2/C3 ✅** + **D X 期本地收尾 ✅**；2026-09-02 拍板排期 **A ✅ → B ✅ → C ✅ → D ✅ X 期本地收尾 → F 正式多端**；E/P5-B OSS+ASR 维持挂起）|
| **X** | 生产硬化（贯穿，上线前必过）| 🟡 收尾（PII 三段式全 ✅；部署侧 W8-L1~L6：**D 波本地项 L1 ✅ 还原演练脚本固化 + L4 ✅ CVE 复扫修复（Tomcat 10.1.59）+ L5 🟡 配置落地（手测待重启）**，L2/L3/L6 仍待环境）|

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
  - **C · 小项池 ✅ 2026-09-02 已收官**（P5-D 收尾，`product/17` v1.6，架构 `23-p5-c-c1` + `24-p5-c-c3` + `25-p5-c-c2`）：**C1 US-RT-05 专属价目复购**（POST /rt/my-pricelist + RtPriceListScenarioTest 12/12 + Store.vue「我的价目」抽屉 + 契约 `api-contract-storefront` §3.3）+ **C3 US-WE-04 客户跟进**（V39 `customer_followups`/`followup_reminders` + /api/v1/tenant/customers 5 端点 + FollowupReminderJob 每 5 分钟到点站内信 + CustomerFollowupScenarioTest CF-01~05 5/5 + 前端 Customers.vue + 9 个 wa 视图菜单「客户跟进」+ 铃铛标签 + customers.ts/api-types）+ **C2 US-WK-05 货位功能**（V40 4 加列 + `batch_location_logs` + 仓级开关 locationEnabled 走 PUT /tenant/me + 出入库登记货位必填 50822 + 批次移库/变更记录 + LocationScenarioTest LV-01~06 7 例 + 前端 Settings/Inbound/Outbound/Batches 货位全链）；三子项回归基线与全量：C1+C3 = **517 全绿**，C2 补完后全量 **524**（首跑 3 flake 均为环境/顺序问题并已修复：PiiWrite 41205 Redis `sms:daily` 跨运行累积清键恢复 + CustomerFollowup cf05 简码顺序耦合改 `TestUniq`）；roadmap v3.2 收官
  - **D · X 期本地收尾 ✅ 2026-09-03 已收官**：W8-L1 还原演练脚本固化 `shared/ops/`（restore-drill-w8.py + v33-reverse-rename.sql + README，全量演练 PASS：37 表 27700 行行数全对 + PII 8 表逐行值比对全 PASS）+ W8-L4 CVE 复扫（**发现并修复 1 项**：Boot 3.5.16 BOM Tomcat 10.1.55 受 CVE-2026-55956/59083 → `pom.xml` `tomcat.version=10.1.59`，依赖树归档同步，diff 仅三件套；OWASP dep-check/Trivy 工具门禁待正式环境）+ W8-L5 graceful shutdown 硬化（`server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s` 落地，Windows 停服手测手册见 13 报告 §8.5.2，待本机重启人工实测）；docs 落 06 §7 / 13 §8.5
  - **F · 正式多端**（P5-C 余下）✅ 已收官（2026-09-03 开工 → **2026-09-07 F2–F6 五业务子波全 ✅**，后端全量回归 **536 绿**）：新建 `frontend/apps/uni`（@cangchu/uni，uni-app Vue3+TS 编译器 5.25 / vite 5.2.8 / vue 3.4.21，@dcloudio 全链 `3.0.0-alpha-5020520260829001`）——**H5(5175) + 微信小程序同源码**，`build:h5`/`build:mp-weixin` 双端产物通过；admin 内 RT 最小 H5 过渡态待正式端替换；端口径修正：TA=仓库老板，**WA/WE 批发商一定走移动端**，**结算员 ST 亦支持移动端**（D20 用户补遗重申，2026-09-03）——uni 承载 WA/WE·WK·ST·RT，admin 承载 OPS/TA（+ST 电脑全功能）；**F2 RT 买家正式端 ✅ 2026-09-03 落地**（uni 内 3 页：RT 首页进店 / store 店铺页 / 我的意向单，US-RT-01~04 全链；后端新增 `POST /rt/my-inquiries`（document 域 hmac 盲查，RtMyInquiriesScenarioTest 7/7 绿，契约 §3.4/v1.2）；admin 内 RT 最小 H5 过渡态保留待 RT 登录子波收尾替换）；**F3 WA+WE 批发商移动端 ✅ 2026-09-03 落地**（uni：WA 登录/工作台/询价处理（议价沉淀确认）/客户跟进/我的商品；账号体系 + 多仓 X-Tenant-Id 会话；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE）；**F4 ST 结算员移动端 ✅ 2026-09-03 落地**（uni：结算工作台（本月应收/已收/未收汇总）/ 账单一览（月份+状态筛选 + 汇总条 + 分页）/ 账单详情（下发 DRAFT·撤回 DISPATCHED·回款登记 PENDING_PAYMENT/PARTIAL_PAID·回款冲销 R12 二次确认 + 明细/回款/申诉只读核对）/ 申诉处理（三态分段 + 成立/驳回处理留痕）；session 支持 ST + 登录页泛化（WA/WE/ST 统一入口按角色分流）+ 角色区自愈防串仓；契约对照后端 StBillController 零漂移；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE）；**F5 WK 库管移动端 🚧 2026-09-03 W1 落地**（uni：库管工作台（仓卡+出入库待办统计+入口）/ 入库作业（正向申请链 SUBMITTED 受理→ACCEPTED 登记入库→CONFIRMED，驳回留痕、实登≠申请备注必填、过期批次二次确认、货位开关联动）/ 出库作业（PENDING_ACCEPT→PRINTED→COMPLETED 分页队列、手机打印标记（纸单电脑补打）、登记出库（货位/托盘释放）、WA 撤回二次确认·拒绝、回退待受理）/ 代建出库（现场卖货直达 COMPLETED；confirmed 凭据 + >50% 在库复述件数、在库不足预检、货位联动）；session 支持 WK（wkEntries/hasWkScope/healWork wk 区）+ 登录页泛化加 WK 分流 + RT 入口文案；ui/wk.ts + utils/warehouse 端点/话术逐项对照 InboundController·TenantOutboundController·Wholesaler·Sku·Inventory 零漂移（契约据实查证）；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE（仅 legacy-sass 警告）；**F5-W2 WK 库存·批次·临期·盘点核对 ✅ 2026-09-04 落地**（四页 = 库存查询（按商户查在库行含 0 + 托盘、名称走 /tenant/skus）/ 批次登记簿（六态筛选+搜索；详情弹层：C2 移库改货位+位置变更记录、默认批次生产/到效期补录）/ 临期预警（EXPIRING∪PENDING_CLEARANCE 升序 + 合计/过期数 + 逐批一键通知商户 + 24h 冷却倒计时展示）/ 库存盘点（盘点单全链：建草稿按商户（在途护栏提示条 + SKU 账面预填 + 差异实时预览）→ 存草稿/提交审批（systemQty 定格）→ 驳回修正重提（REJECTED 回 DRAFT）/删除（仅 DRAFT）/只读详情含 appliedDiff 生效值）；api/wk.ts 扩 batch/expiry/stocktake 三组 + warehouse 增批次/盘点状态话术与临期文案；盘点/批次/临期契约逐项对照 CountSheetServiceImpl（50355/50356、在途出库 PENDING_ACCEPT+PRINTED 口径、D-10 封顶预览）与 BatchController/BatchConfig 零漂移（含 api-types 既有 Batch/CountSheet 类型直接用）；vue-tsc 0 错 + lint 0 + 双端 DONE（仅 legacy-sass 警告）；F 波库管移动端主体收官，**剩余扩展**：现场代建入库（拍照附件·走商户 72h 确认）与盘点批次分支（盘盈按批入库/差异建议值录入）可在 P5 或后续子波补；工程踩坑与验证见 progress 2026-09-04；**F6 RT 登录子波 ✅ 2026-09-04 落地**（US-RT-04 验收补全 · D-RT-01 落地载体）：后端 `PiiRevealService` 新增 BIZ_RT_WHOLESALER（id=意向单 id）——四重闸门 = RT 角色 + 登录手机号 hmac 须等于询价提交手机号 + 仅 CONFIRMED/COMPLETED 放行（PENDING/VOIDED 一律 50402）+ 联系人解析 = 该批发商绑定的 ACTIVE WA（user_roles 唯一可信来源，SELF_OPERATED 不走 owner_user_id；多 WA 取首个有号者，无绑定 50401）；全号经 account 域唯一出口 `AccountService.getPhoneByUserId`（禁直连 UserMapper）；零新错误码（50401/50402 复用）；RtWholesalerContactRevealScenarioTest REV-07~11 5 例全绿 + 相邻回归 PII/RT/Account 31 例绿；uni：新增 `pages/rt/login` 买家登录页（免密验证码 scene=RT_LOGIN + POST /account/login/rt 首登自动建号，D-49/D-50）+ session `hasRtScope` + request 41001 按 RT 退回买家首页（不再一律回 WA 登录页）+ 首页登录态卡（锁定手机号为登录号 + 退出）+ 意向单页登录态（手机号恒锁定登录号自动重查 + CONFIRMED/COMPLETED 单「查看批发商电话」→ reveal 弹层复制 / 拨打（H5 tel: · 微信 makePhoneCall））；复用既有 RtSmsLoginRequest/LoginResponse api-types 零新增；vue-tsc 0 错；admin 内 RT 最小 H5 过渡态**改「仅测试兼容」**（Playwright 4+ spec 依赖 /rt/store 作回归目标，即时删除将破坏无法当场回归的 E2E 基线；uni 已成唯一用户入口）——物理删除转 Backlog（待 E2E 迁 uni 后收）；契约更新见 api-contract-storefront v1.3；详见 progress 2026-09-04
  - **挂起不排**：E/P5-B（OSS/ASR 待外部选型+云账号）、capacity 快照 job（待环境）、US-WA-01b 容量告警（暂缓）

## X · 生产硬化（贯穿，上线前必过）🟡 进行中
- **手机号明文加密(PII, H1)**——三段式，**W8 收口完成**（2026-09-01，main=72c5597，后端 H2 451 绿，架构师 §8.2 终验通过）：
  - S0 加列双写 ✅（V27/V30 五表 hmac 双写+回填+对账，419 绿）
  - S1 影子灰度+切读 ✅ 代码就位：Step1 影子双查（登录 8 切点）→ Step2 非命门切读（blacklist/sms/pricing + Redis 键 HMAC 化）→ Step3 登录双读切换（2026-08-31，488 绿）；**生产切读执行待环境**
  - S2 明文收缩（V31-V34 删明文列，唯一不可逆段）**[完成 2026-09-01]**：V31 cipher/last4 补列 + V32 hmac 唯一索引 + V33 RENAME + V34 DROP + blacklist last4 摘要；删双写/开关 6 类 + 4 Service 直连 hmac；AES-GCM + 确定性 KAT；F1 前端 D1/D4 合入。**V31-V34 真实 MySQL 执行；§8.1 回填闸门真实库核对通过（缺口数据已整链清除，9/1，备份 backup_w8_gap_delete_20260901.sql；uk_phone_hmac 唯一索引完好、hmac 零重复）+ V34 观察期闸门待发布窗口**
  - 产品决策 D1-D4 全部定稿（wa/Inquiry 查全号放开、wa/Staff 全号显式例外 G-8.6）
- 其余硬化项 ✅ 代码落地（`test-plan/09-hardening-w1-report.md`）：H2 Redis 密码+ACL（prod fail-fast，ACL username 留注释）/ H3 Sa-Token active-timeout（主配 1800s）/ H4 SQL stdout 关闭+日志 profile 化 / H5 Boot 3.2.5→3.5.16 CVE 根治
- **D 波 · X 期本地收尾 ✅（2026-09-03，不待环境项全闭环）**：W8-L1 还原演练脚本固化 `shared/ops/` 并全量演练 PASS（37 表 27700 行 + PII 8 表逐行值比对；V33 反向 rename 回滚 SQL 同入库，适用窗口 V33 后 V34 前）/ W8-L4 CVE 复扫修复真实 CVE（Boot 3.5.16 BOM Tomcat 10.1.55 → `tomcat.version=10.1.59`，CVE-2026-55956/59083；依赖树基线归档同步，其余核对项无未修复）/ W8-L5 graceful shutdown 硬化（`server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s`，Windows 停服手测手册 13 报告 §8.5.2）；记录：roadmap v3.3 + 06 §7 + 13 §8.5 + progress
- 上线检查单余项（**待环境**，`task_plan.md` 验收条目）：OWASP dep-check/Trivy 工具门禁（命令 06 §7.4，本机未装）、prod 冒烟、graceful shutdown 人工停服实测（手册 §8.5.2，本机重启后执行）、Redis 实际启用密码（W8-L6）
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
| v3.0 | 2026-09-02 | **C 小项池口径定稿**：C1（US-RT-05 专属价目复购，否决历史询价单复制走 customer_prices 价目）/C3（US-WE-04 客户跟进）确认执行；C2 货位功能（US-WK-05：启用开关+出入库登记货位+批次移库）用户指示记录后续做 → Backlog（`product/17` v1.3，D-C-1~1d 记录在案）；C1 架构/实现启动 |
| v3.1 | 2026-09-02 | **C 小项池 C1+C3 双波收官（P5-D 收尾）**：C1 专属价目复购（`23-p5-c-c1` 设计 + pricing/product 出口 + POST /rt/my-pricelist + RtPriceListScenarioTest 12/12 绿 + Store.vue 价目抽屉 + `api-contract-storefront` §3.3）；C3 客户跟进（`24-p5-c-c3` 设计 v1.1 + V39 customer_followups/followup_reminders + TenantLine 白名单 + ErrorCode 50840-42 + Notification TYPE_CUSTOMER_FOLLOWUP + CustomerFollowupServiceImpl 聚合/备注/提醒/清档 + FollowupReminderJob 5 分钟 CAS 防重 + CustomerFollowupScenarioTest CF-01~05 5/5 绿 + 前端 Customers.vue 列表/抽屉 + 9 个 wa 视图菜单「客户跟进」+ 路由 + 铃铛标签 + customers.ts/api-types，vue-tsc 0 错 + build 通过）；全量回归 **517 全绿**；C2 货位功能留 Backlog（`product/17` v1.5） |
| v3.2 | 2026-09-02 | **C 波 C2 货位功能收官（C1/C2/C3 三子项全落地）**：`25-p5-c-c2` 设计 v1.0 → 实现——V40（tenant_settings.location_enabled 默认 0 + batches/inbound_requests/outbound_requests 3 处 location + batch_location_logs 新表）；tenant 域开关走通用 `PUT /tenant/me`（无冻结副作用，对照 batchEnabled 专用 toggle）；inventory 域批次 location 登记落值 + `updateBatchLocation`（幂等空转/清空/零记账）+ `listLocationLogs` + Controller 2 端点；document 域出入库登记 4 DTO +location 按当刻开关必填 50822；ErrorCode 50822/50823；前端 Settings/Inbound/Outbound/Batches 货位全链 + api-types 同步；LocationScenarioTest LV-01~06 7 例；全量回归 **524**（首跑 3 flake 均为环境/顺序问题并修复：PiiWrite 41205 = Redis sms:daily 当日多次运行累积清键恢复；CustomerFollowup cf05 = C3 遗留 seedTenant `id%1000` 简码顺序耦合 → 改 `TestUniq` 全局唯一，随波收口） |
| v3.3 | 2026-09-03 | **D 波 X 期本地收尾收官（roadmap D，不待环境项全闭环）**：W8-L1 还原演练脚本固化 `shared/ops/`（restore-drill-w8.py + v33-reverse-rename.sql + README；全量演练 PASS：37 表 27700 行行数 + PII 8 表逐行值比对，Decimal 精度归一）+ W8-L4 CVE 复扫（**修复真实 CVE**：Boot 3.5.16 BOM Tomcat 10.1.55 受 CVE-2026-55956(中)/CVE-2026-59083(低) → `pom.xml` `tomcat.version=10.1.59`，官方安全页 10.1.59 为最新修复版；`dependency:tree` 确认仅 tomcat-embed 三件套 10.1.55→10.1.59、其余 162 依赖零变化，基线归档同步）+ W8-L5 graceful shutdown 硬化（`application.yml` `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`，Windows 停服手测手册 13 报告 §8.5.2，实测待本机重启）；POI/PDFBox/OpenHTMLtoPDF 等 22 新增依赖核对无未修复 CVE；OWASP dep-check/Trivy 工具门禁留正式环境（命令 06 §7.4）；docs：06 §7 / 13 §8.5 / progress 2026-09-03 |
| v3.4 | 2026-09-03 | **F 波正式多端开工（F1 = apps/uni 工程落地）**：拍板新建 `frontend/apps/uni`（@cangchu/uni）——uni-app Vue3+TS 编译器 5.25 / vite 5.2.8 / vue 3.4.21 / @dcloudio 全链 `3.0.0-alpha-5020520260829001`（npm vue3 tag 2026-08-29）；H5(5175)+微信小程序同源码，`build:h5`/`build:mp-weixin` 双端产物通过、vue-tsc 0 错；scss 主题对齐 design-tokens；端口径修正（TA=仓库老板；WA/WE 批发商一定走移动端；uni 承载 WA/WE·WK·RT，admin 承载 OPS/TA/ST）；环境踩坑：GitHub 443 不通改手写骨架、pnpm install 需 `CODEBUDDY_SAFE_DELETE_ENABLED=0`（safe-delete 批量保护）、`frontend/_tmp_*` 残留待清、@vueuse/core@14 peer 警告容忍；F2 起子波拆分（RT 正式页/WA 移动端/ST 全功能 H5）待拍板；详见 progress 2026-09-03 |
| v3.5 | 2026-09-03 | **端口径补正：结算员（ST）支持移动端**（D20 用户补遗重申）——ST 由「仅 admin 电脑端」改为 admin 电脑全功能 + uni 移动端（H5+小程序同源码，账单核对/回款登记等 P0 操作手机可用）；端划分 = uni 承载 WA/WE·WK·ST·RT，admin 承载 OPS/TA（+ST 电脑全功能）；14-p5 §P5-C / 99 D20 / roadmap F 行 / progress / apps/uni 角色描述同步 |
| v3.6 | 2026-09-03 | **F2 RT 买家正式端落地（F 波第一业务子波，US-RT-01~04 全链）**：后端 `POST /rt/my-inquiries`（document 域 `InquiryServiceImpl.listForRt`：store→tenant 复用 getStorePage + tenantId×hmac 显式过滤 + 商户名/SKU 名经 tenant/product 出口补全 + RtInquiryListVo 无明文仅尾号 + 零新错误码）+ RtMyInquiriesScenarioTest 7/7 绿 + `api-contract-storefront` §3.4/v1.2（D-RT-01：商户联系方式 PII 留 RT 登录子波）+ api-types RtMyInquiries 全套；前端 uni 3 页（index 首页进店/rt-store 店铺页/rt-inquiries 意向单）+ request/storage/format/api 基础层 + uni 直引 @cangchu/api-types（tsconfig paths + vite alias）；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE；详见 progress 2026-09-03 |
| v3.7 | 2026-09-03 | **F3 WA/WE 批发商移动端落地（F 波第二业务子波）**：uni 引入账号会话（`utils/session`：auth + 当前工作仓 work）+ request 自动注入 Authorization/satoken + X-Tenant-Id（多仓切换即切请求头）、41xxx 登出级自动回登录；页面 6 个 = WA 登录页 / 批发商工作台（仓卡+待处理统计+入口+多仓切换+退出）/ 询价处理（待确认·全部·历史过滤、明细、逐行议价 + 沉淀专属价确认、查全号 pii reveal）/ 客户跟进（分页、备注、提醒增删、查全号）/ 我的商品（SKU 上下架）；api-types `LoginRoleEntry.storeName` 注释纠正（后端已下发）；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE；详见 progress 2026-09-03 |
| v3.8 | 2026-09-03 | **F4 ST 结算员移动端落地（F 波第三业务子波）**：uni session 支持 ST（`stEntries/hasStScope/healWork` 角色区自愈，防 WA/ST 双角色串仓）+ 登录页泛化（WA/WE/ST 统一入口，落地页按可用工作区角色分流）+ RT 首页补商户/结算工作台入口；页面 4 个 = 结算工作台（本月汇总+入口+多仓切换）/ 账单一览（月份+状态筛选、汇总条、分页触底）/ 账单详情（三金额+明细+回款+申诉只读；操作按状态机：DRAFT 下发 / DISPATCHED 撤回 / PENDING_PAYMENT·PARTIAL_PAID 回款登记不超收 / 回款行 R12 冲销二次确认）/ 申诉处理（待处理·已成立·已驳回 + 成立/驳回处理留痕）；ui/st.ts 端点逐项对照后端 StBillController 零漂移；utils/billing 话术对齐 13-p4-prd；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE（仅 legacy-sass 警告）；详见 progress 2026-09-03 |
| v3.9 | 2026-09-03 | **F5 WK 库管移动端 W1 落地（F 波第四业务子波起步）**：session 支持 WK（`wkEntries/hasWkScope` + `healWork` 增加 wk 区）+ 登录页泛化分流补 WK + RT 首页「批发商/结算员」文案含库管；页面 4 个 = 库管工作台（仓卡+入库/出库待办统计+入口+多仓切换+退出）/ 入库作业（正向申请链执行：SUBMITTED 受理 / ACCEPTED 登记入库（实登≠申请备注必填、过期批次二次确认、货位开关联动）/ 驳回留痕，名称映射走 tenant 商户+SKU 只读端点）/ 出库作业（PENDING_ACCEPT→PRINTED→COMPLETED 分页队列：打印标记（纸单电脑补打）、登记出库（托盘释放/货位）、WA 撤回二次确认（同意=CANCELLED 回补/拒绝继续履约）、回退待受理）/ 代建出库（现场卖货 POST /tenant/wk/outbound-requests 直达 COMPLETED：商户→货品（在库展示）→件数 → confirmed 凭据 + >50% 在库复述件数 → 提交）；ui/wk.ts + utils/warehouse 端点/话术逐项对照 InboundController·TenantOutboundController·Wholesaler·Sku·Inventory 契约零漂移（读放宽与路径据实查证）；vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE（仅 legacy-sass 警告）；F5-W2（库存查询/批次·移库/临期预警/盘点核对）下轮续；详见 progress 2026-09-03 |
| v4.0 | 2026-09-04 | **F5 WK 库管移动端 W2 落地（F 波库管主体收官）**：四页 = 库存查询 / 批次登记簿（六态筛选+搜索+详情弹层 C2 移库+位置变更记录+默认批次补录效期）/ 临期预警（汇总 + 一键通知商户 24h 冷却）/ 库存盘点（建草稿按商户（在途护栏+账面预填+差异实时预览）→ 提交审批（systemQty 定格）→ 驳回修正重提 / 删除 / 只读详情含 appliedDiff）；api/wk.ts 扩 batch/expiry/stocktake + utils/warehouse 批次/盘点话术；工作台入口分组改「作业 + 查看与管理」；盘点/批次契约对照 CountSheetServiceImpl（50355/50356、在途口径、REJECTED→DRAFT 重提）与 BatchController 零漂移；vue-tsc 0 错 + 双端 DONE；剩余扩展：现场代建入库（拍照附件）与盘点批次分支；详见 progress 2026-09-04 |
| v4.1 | 2026-09-04 | **F6 RT 登录子波落地（US-RT-04 验收补全 · D-RT-01 落地载体）**：后端 BIZ_RT_WHOLESALER 查全号（RT 本人 + CONFIRMED/COMPLETED 闸门 + ACTIVE WA 反查，四重校验零新错误码）+ RtWholesalerContactRevealScenarioTest REV-07~11 5 例 + 相邻回归 31 例全绿；uni 买家登录页（免密验证码）+ hasRtScope + 41001 按身份退回 + 首页/意向单登录态与「查看批发商电话」reveal 弹层（复制/拨打）；vue-tsc 0 错；admin RT 过渡态改「仅测试兼容」（删除转 Backlog，防破坏 E2E 基线）；契约 api-contract-storefront v1.3；详见 progress 2026-09-04 |
| v4.2 | 2026-09-07 | **F 波正式多端收官（roadmap F 🟢，P5 仅剩 E/P5-B 挂起）**：F2–F6 五业务子波全链闭合——uni 承载 WA/WE·WK·ST·RT 全角色全功能（每子波 vue-tsc 0 错 + build:h5/build:mp-weixin 双端 DONE），backend 2 笔 + frontend 6 笔 + docs 6 笔提交；后端全量回归 **536 全绿**（0 失败/0 错误/0 跳过，524 → +12 = F2 RtMyInquiries 7 + F6 RtWholesalerContactReveal REV-07~11 5）；后续项（admin RT 过渡态物理删除 / E2E 迁 uni / 现场代建入库拍照 / 盘点批次分支）待排期；详见 progress 2026-09-07 |
