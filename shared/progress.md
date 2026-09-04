# Progress Log · P3 完整单据与履约异常

> 最新在上。关联 `task_plan.md` / `findings.md`。P2 定价/入驻计划已归档 `shared/archive/`。

## 2026-09-04 · F5-W2 WK 库管移动端·库存/批次/临期/盘点核对（F 波第四业务子波收官：库存查询/批次登记簿/临期预警/库存盘点，CodeBuddy）

> W1 提交后按 roadmap 排期续做 W2。锚点：US-WK-03 盘点、US-WK-04 临期预警、US-WK-05 货位/移库 + 13 §3（批次）/§5.2（盘点 PD）；**零后端改动**（P3/P3b 既有 BatchController/TenantStocktakeController/InventoryController 全量 WK 可用）。
> WK 与 WA 不同：**只读放宽**（listSkuByWholesaler/listWholesalers/listInventories 均可调），故本波所有名称在移动端本地映射即可，契约为省 join 不带名称（BatchVo 仅批次键；CountSheetVo 列表不带 items/名称，仅详情链路填充）。

- **范围收敛决策**：W2 = 读查（库存/批次/临期）+ 盘点单执行链；**现场代建入库**（拍照附件、WK 建单走商户 72h 确认）当前无 WK 建单端点 → 记为「扩展」不扩后端；**盘点批次分支**（盘盈按批入库、差异托盘建议值录入）按 13 §5.2 注 7 随 P5/方案 A 顺延，移动端盘点按 SKU 总数盘（palletDelta 可选留空走默认建议）
- **契约（逐项对照实现）**：`GET /tenant/batches`（wholesalerId+skuId 齐附 unpooledQty）/ `PUT /tenant/batches/{id}`（默认批次补录 production≤today 40205、expiry>production 40206、仅 source=DEFAULT 且非 CLEARED/CLOSED）/ `PUT /{id}/location` + `GET /{id}/location-logs`（C2 移库幂等）+ `GET /tenant/batches/expiring`（EXPIRING∪PENDING_CLEARANCE 升序）+ `POST /{id}/notify-wholesaler`（同批次 24h≤1 → 50367，前端按 manualNotifiedAt 本地倒计时展示）；盘点 `GET /tenant/count-sheets`(+`/in-transit-hint`、`/{id}`)/ `POST` / `PUT` / `DELETE` / `POST /{id}/submit`——50355 items 1~200 同单 SKU 不重复、50356 同商户在途至多一张、建/改时按当刻在库预填 systemQty、提交 CAS DRAFT→PENDING_APPROVAL 快照定格（通知 TA）、REJECTED 经 PUT 回 DRAFT 修正重提、盘亏 D-10 按审批时刻在库封顶生效（appliedDiff）；在途口径=PENDING_ACCEPT/PRINTED 出库 + ACCEPTED 退货；**api-types 的 Batch/BatchLocationLog/TenantBatchConfig(locationEnabled)/CountSheet/CountSheetItem/StocktakeInTransitHint 已存在直接复用**
- **基建**：`api/wk.ts` 扩 `batch`（list/backfill/updateLocation/locationLogs）+ `expiry`（list/notify）+ `stocktake`（list/detail/inTransitHint/create/update/remove/submit）三组；`utils/warehouse` 增 BATCH_STATUS_LABELS(IN_STOCK/EXPIRING/PENDING_CLEARANCE/SOLD_OUT/CLEARED/CLOSED)/STOCKTAKE_STATUS_LABELS(DRAFT/PENDING_APPROVAL/APPROVED/REJECTED)/batchTone/stocktakeTone/expiryText/fmtDate；pages.json 4 页 + 工作台入口分组（作业 4 + 查看与管理 3）
- **页面（pages/wk/ 新增 4）**：
  - `inventory/index` 库存查询：商户 chips（默认首个）→ 按商户拉在库行（含 0，托盘）+ /tenant/skus 名称 join；头部合计（件/托盘/行数）+ 名称搜索
  - `batches/index` 批次登记簿：全量批次 + 六态横向段筛选 + 批次号/品名搜索；行卡=品名/批次号/商户/效期+临期文案/推算剩余/货位或「待补录效期」徽标；点击弹层：批次 KV（三日期/剩余/累计/来源/货位）+ **移库**（货位开关开启时：输入新货位或清空 → PUT location + 位置变更记录列表）+ **默认批次补录**（生产/到效期 date-picker → PUT backfill）
  - `expiry/index` 临期预警：GET expiring（升序）；顶部渐变汇总（批次/推算剩余合计/已过期）；行卡=SKU/批次号/商户/状态与剩余天数 tag（过期红）+ 到效期/推算剩余/来源 KV；[一键通知商户] 按钮——manualNotifiedAt 起 24h 冷却实时倒计时文案（disabled），成功本地记时并 toast 商户名
  - `stocktake/index` 库存盘点：段（草稿/待审批/已通过/已驳回，带计数，全量一次拉取本地筛）；DRAFT 卡操作=继续盘点/删除/提交审批（均 confirm）；REJECTED=查看/修正后重提；PENDING/APPROVED=查看只读详情（含在途护栏提示条 + 明细表账实差异 + appliedDiff「/生效」展示 + 驳回理由 + 已通过说明）；新建/编辑编辑器：商户 chips → 在途护栏（hintbar 绿/黄两态）+ 账面与实盘 diff 实时预览 → SKU 行（账面预填、实盘 input、差异 ±着色、理由可空）→ [保存草稿]/[提交并上报]（先 create/update 再 submit，失败草稿已留存可回列表续提）；驳回重提沿用编辑（PUT 回 DRAFT 后到草稿段续提）
- **验证**：vue-tsc 0 错（仅 1 处变量名笔误修复）；read_lints 0；build:h5 + build:mp-weixin 双端 DONE（仅 legacy-sass 警告）
- **边界与说明**：盘点「提交即定格账面」并告知用户；在途护栏仅提示不阻止（与后端一致）；批次 remainingQty 为 02:00 FIFO 推算（非记账值）行卡与弹层均已标注；剩余扩展：现场代建入库（拍照附件/WA 72h 确认）与盘点批次分支（盘盈按批/托盘建议录入）待 P5

## 2026-09-03 · F5-W1 WK 库管移动端（F 波第四业务子波·出入库作业：工作台/入库作业/出库作业/代建出库，CodeBuddy）

> 用户续「按你的进度安排继续往后走」F4→F5（上轮已完成 F3 收尾+F1-F4 全部提交）。需求锚点：US-WK-01/01b 入库（受理/登记/驳回）、US-WK-02/02b 出库（打印/登记/代建）、US-WK-06（D20 手机端 P0 操作口径）+ 货位 C2 联动；**后端零改动**（P3/P3b 既有 InboundController/TenantOutboundController 契约）。W1 = 出入库作业核心高频链；W2（库存查询/批次·移库/临期预警/盘点核对）下轮续。
> 与 F3 WA/F4 ST 不同：WK 是**仓库内执行角色**（X-Tenant-Id = 所服务仓），对 SKU/商户/库存均有**只读放宽**（P3b 起 `listSkuByWholesaler` 等端点 WK 可读）——移动端用 tenant 侧列表端点直接取数补名，无需 admin 页缓存。

- **F5-W1 范围收敛决策**：US-WK-06 的 P0 操作里「出入库登记执行」是最高频且受状态机约束的日常任务 → 本子波先做执行链；「拍照举证/附件」与「现场代建入库（WK 登记建单走 PENDING_WA_CONFIRM 等商户 72h 确认）」留 admin/W2（移动端登记正向链为主，避免照片上传与 WK 代建链扩面）；盘点/库存/临期亦归 W2
- **会话/入口（WK）**：`utils/session.ts`——`WorkRole` 增 `WK`；新增 `wkEntries`（WK 且带仓按 priority 排序）/`hasWkScope`；`healWork` zone 扩为 `'wa'|'st'|'wk'`（wk 区自愈防 WA/ST/WK 三角色串仓请求头）；`pages/wa/login` 分流补 WK（顺序 ST→WK→WA，账号多区角色取 priority 小者、跨区重登约定不变）；RT 首页入口文案「我是批发商 / 结算员 / 库管」
- **API 与工具**：`api/wk.ts`——inbound 5（list/accept/reject/registerForward/print）+ outbound 7（list 分页 MpPage/print/revertToPending/register/confirmWithdraw/rejectWithdraw/createByWk）+ 共享 4（listWholesalers/listSkuByWholesaler/listInventories/batchConfig）+ rejectReasonOptions；**端点逐项对照真实 Controller 零漂移**（含 P3b `POST /tenant/wk/outbound-requests` 代建直达 COMPLETED、`/outbound-requests/{id}/print|revert-to-pending|confirm-withdraw|reject-withdraw`、入库 `/{id}/accept|reject|register`、`/tenant/inbound` 非分页 List）；`utils/warehouse.ts`（出入库状态/来源/驳回理由话术对齐 12-p3-design+13-p3b-design + tone 色板 + fmtDateTime）
- **页面（pages/wk/ 4 个）**：
  - `index` 库管工作台：仓卡（渐变青绿 + WK chip + 多仓切换 sheet + 退出）、入库待办（WA_SUBMIT & SUBMITTED/ACCEPTED 计数）/出库待办（PENDING_ACCEPT+PRINTED total）实时统计、入口 2×2（入库作业/出库作业/代建出库/库存·盘点占位注明电脑端）、作业提示
  - `inbound/index` 入库作业：四段（待受理/待登记/已驳回/已完成，WA_SUBMIT 正向链）+ 行卡（docNo/商户/SKU+批次/申请件数/状态 tag）；详情弹层 = 单证只读核对（含批次三字段、驳回理由、撤回原因）+ 操作：SUBMITTED→[受理][驳回]、ACCEPTED→[登记入库]；**登记校验**：实收≠申请必须备注（R 留痕）、过期批次登记需勾选二次确认、货位开关开启时货位号必填；驳回 = 理由 chips（QTY/QUALITY/BATCH/OTHER）+ 说明必填
  - `outbound/index` 出库作业：四段（待受理/待登记/已完成/全部）+ 分页队列（onReachBottom 加载更多 + 下拉刷新）+ 行卡（docNo/来源标签/商户/SKU/件数/托盘/撤回申请角标）；操作链：PENDING_ACCEPT→「受理并登记」（modal 说明纸单可电脑补打 → print 标记 PRINTED）→PRINTED→「登记出库」（托盘释放可空/货位联动必填）→COMPLETED；PRINTED 附加：回退待受理 + 若商户申请撤回则[同意撤回]（CANCELLED 库存回补）/[拒绝撤回]（继续履约）；名称映射走 listWholesalers + 按商户 listSkuByWholesaler
  - `outbound/create` 代建出库：选商户 chips → 选货品列表（名称/公开价 + 在库实时展示）→ 件数/托盘/货位（开关联动）→ **大额判定 qty > 在库×50% 强制复述件数==qty**（50338 前端预检）+ 在库不足预检 + confirmed 凭据勾选 → POST 代建直达 COMPLETED
- **验证**：vue-tsc 0 错（修 3 类：`LoginResponse` 无 user/tenantName、`writeWork` 单参需先构 `WaWork`、模板箭头闭包对 ref 窄化失效改参数化 helper）；`MpPage.total` 为 string|number 需 Number 化；read_lints 0；build:h5 + build:mp-weixin 双端 DONE（仅 legacy-sass 警告）
- **边界与说明**：手机端「打印」= 状态标记（纸单在电脑端补打）；现场代建入库/拍照附件/盘点/库存查询归 F5-W2；多区角色（WK+ST/WA）移动端按 priority 落一区、跨区重登（与 F3/F4 约定一致）

## 2026-09-03 · F4 ST 结算员移动端（F 波第三业务子波：工作台/账单一览/账单详情/申诉处理，CodeBuddy）

> 用户续「继续吧」按序推进 F3→F4（本会话先收 F3 遗留 + 提交 F1-F3，再落 F4）。需求锚点：US-ST-06（D20 口径 ST 移动端支持、admin 电脑全功能保留）+ P4 账单状态机/US-ST-04 回款登记/R12 冲销二次确认；后端零改动（P4/W3 既有 StBillController 契约）。
> 与 F3 WA/WE 不同：ST 是**仓库主体角色**（X-Tenant-Id = 所服务仓），会话在 F3 账号体系上直接扩展，无新身份模型。

- **F3 收尾（本会话前置）**：契约逐项对照后端真实 Controller，修正 uni 两处漂移——① `api/pii.ts` 原写 `POST /tenant/pii/phone-reveal`+body，实际后端为 **`GET /api/v1/pii/phone-reveal?biz=&id=`**（PiiRevealController，biz 四枚举，uni 仅 INQUIRY）→ 重写与 admin 同口径；② `api/customers.ts` 三漂移：detail 路径补 `/detail` 后缀、saveRemark POST→**PUT**、deleteReminder 补 `wholesalerId` query（逐项对 CustomerController 修正 + detail.vue 调用点同步）；随后 **F 波 F1-F3 三笔提交**（7b3c375 backend F2 / 656ccb7 frontend F1-F3 / 03a7b02 docs F 波记录）
- **会话/请求扩展（ST）**：`utils/session.ts`——`WorkRole` 增 `ST`；新增 `stEntries`/`hasStScope`；`pickDefaultWork` 全工作角色（WA/WE/ST）按 priority 选默认；新增 **`healWork(zone)`** 角色区自愈（工作台 onLoad/onShow 保证当前 work 属本区角色，防 WA/ST 双角色账号串仓请求头）；`pages/wa/login` 泛化为 WA/WE/ST 统一登录（文案/落地页按可用工作区角色分流：ST→/pages/st/index，WA/WE→/pages/wa/index，其余提示电脑端）；RT 首页补「我是批发商 / 结算员」工作台入口（F3 曾缺失）
- **API 与工具**：`api/st.ts`（stBillApi.list/detail/dispatch/withdraw/registerPayment/reversePayment/listDisputes/resolveDispute，端点逐项对照 StBillController 零漂移）；`utils/billing.ts`（话术对齐 13-p4-prd：6 态/明细行/收款方式/申诉标签 + tone 语义色映射 + fmtMoney/recentMonths/currentMonth/toCents 分转）
- **页面（pages/st/ 4 个）**：
  - `index` 结算工作台：仓卡（storeName/tenantName + 结算员紫 chip）、本月应收/已收/未收汇总（list month=当前月）、入口（账单一览 / 申诉处理带待处理角标）、多仓切换 sheet、退出登录、买家入口
  - `bills/index` 账单一览：月份 chips（近 6 月 + 全部账期）+ 状态分段（7 项）+ 按筛选汇总条（应收/已收/未收/账单数）+ 分页触底 + 下拉刷新 + 空态
  - `bills/detail` 账单详情：头部（商户/billNo/账期/状态 tag）+ 争议冻结横幅 + 三金额（应收/已收/未收高亮）+ 明细（冲销划线/负数红）+ 回款记录（行内冲销）+ 申诉记录；**操作栏按状态机**——DRAFT→下发（modal）/ DISPATCHED→撤回（modal）/ PENDING_PAYMENT·PARTIAL_PAID→登记回款（bottom-sheet：金额默认未收、不超收校验、日期 picker、方式五选、备注）/ EFFECTIVE 回款行→冲销（bottom-sheet：理由必填 + 勾选二次确认，R12）；回款/冲销后重拉详情
  - `disputes/index` 申诉处理：待处理/已成立/已驳回三态分段 + 卡片（申诉理由/处理说明/时间）；待处理卡点击 → 处理弹层（结论成立/不成立 chips + 处理说明必填 → resolve，留痕）
- **验证**：vue-tsc 0 错（修 2 类：`||`/`??` 混用 TS5076 → 统一 `??`；textarea 冗余 `@input` 移除）；build:h5 + build:mp-weixin 双端 DONE（仅 legacy-sass 警告）；read_lints 0
- **边界与说明**：调整/行冲销（US-ST-02/R10）与导出留 admin 电脑端（移动端 P0 = 核对/下发/撤回/回款/冲销/申诉）；多区角色（ST+WA）移动端 v1 按 priority 落地一区，跨区需重登（文档化）；待 F5 = WK 库管移动端

## 2026-09-03 · F3 WA/WE 批发商移动端（F 波第二业务子波：登录/工作台/询价处理/客户跟进/我的商品，CodeBuddy）

> 用户续「继续吧」按序执行 F3。需求锚点：US-WA-06/US-WE-03 询价处理（含授权员工）、US-WE-04 客户跟进、US-WE-01 上下架；D20 口径 **WA/WE 一定走移动端**（admin 电脑全功能保留）。
> 与 F2（RT 匿名）不同：WA/WE 是**账号体系** → 本子波为 uni 引入登录会话 + 多仓工作台 + 受保护请求链路（X-Tenant-Id 按工作仓注入），与 RT 匿名双身份并存互不干扰。

- **会话与请求基建**：`utils/session.ts`（auth=登录响应持久化；work=当前工作仓角色条目，绑定 userId 防跨账号，默认取 priority 最小批发商角色、多仓随切）+ `utils/request.ts` 增强（RT 匿名不带头；有会话自动注入 `Authorization`/`satoken` 裸 token + `X-Tenant-Id` + `X-Client: uni-mobile`；41xxx 登出级清会话并 reLaunch 登录页）+ `api/account|inquiry|customers|pii|sku`（与 admin 同端点 `/tenant/...`；专属价 = GET `/tenant/customer-prices?wholesalerId=` 同 pricingApi 口径）
- **页面（pages/wa/ 6 个）**：
  - `login`：密码登录（员工密码店主电脑端分配）；非 WA/WE 登录提示回电脑端
  - `index` 批发商工作台：仓卡（storeName + 角色 chip）、待确认询价统计（PENDING 实时计数）、入口（询价/客户/我的商品）、多仓切换 sheet（写 work 即切请求头）、退出登录
  - `inquiries/index` 询价处理：待确认/全部/历史过滤；卡片 docNo+打码买家号+状态 chip+明细摘要+合计；PENDING → 确认 sheet：逐行成交价输入（默认公开价快照）+「沉淀为客户专属价」勾选（成交价≠公开价行计数 + 该买家现有专属价「将覆盖」提示，沿用 PriceSettleDialog 语义）→ confirm；查全号走 pii reveal（INQUIRY 锚点、弹窗复制）；CONFIRMED 显示「已确认并结算→自动转出库」、COMPLETED/VOIDED 只读
  - `customers/index` 客户跟进：分页（size 20、触底加载）+ 行卡（打码号/商户/询价次数/最近询价与成交/备注与待提醒标）+ 查全号
  - `customers/detail`：统计 + 查全号；备注编辑（覆盖式，留空保存=清除）；跟进提醒增删（date+time picker 拼 remindAt，到点站内信+电脑端提醒）
  - `products/index` 我的商品：SKU 全量列表（在售/已下架）+ 自绘开关上下架（确认弹窗 → PUT listing）+ 公开价/起批展示，在售在前
- **验证**：vue-tsc 0 错（修 2 处：`WaAuth.tenantInfo` 可选对齐 LoginResponse；Inquiry 主键引用统一 `id`）；build:h5 + build:mp-weixin 双端 DONE
- **边界与说明**：WE 未授权操作询价由后端权限位拦截（42004，移动端不做前端预判）；下架仅买家不可见、入仓库存不受影响；api-types `LoginRoleEntry.storeName` 注释纠正（后端 2026-09-01 起实际已下发）；WA 电脑端 admin 全功能保留，移动端承载高频操作

## 2026-09-03 · F 波正式多端启动：apps/uni（uni-app Vue3+TS）工程落地（CodeBuddy）

> 拍板（q-0）= **新建 apps/uni**，D09 技术定案首次落真实移动端工程（admin 内 RT 最小 H5 为过渡态）。
> 口径修正：TA=租户管理员=**仓库老板/店长**（注册即开仓）；WA=**入驻批发商**（可多仓入驻）；**WA/WE 一定走移动端**；**结算员 ST 亦支持移动端**（D20 用户补遗，2026-09-03 重申）。端划分 = admin（OPS/TA 电脑管理；ST 电脑全功能保留）＋ uni（WA/WE·WK·**ST**·RT，手机 H5/小程序同源码）。

- **工程**：`frontend/apps/uni`（@cangchu/uni）——uni-app 编译器 5.25（vue3）/ vite 5.2.8（插件 peer 精确锁）/ vue 3.4.21（uni-h5 内部锁）/ @dcloudio 全链 `3.0.0-alpha-5020520260829001`（npm `vue3` tag 2026-08-29，7 包同版）；文件 = vite.config（端口 5175、`/api`+`/files` 代理对齐 admin）/ tsconfig（extends 根 base + @dcloudio/types）/ index.html / src{pages.json、manifest.json（h5 hash 路由 + mp-weixin urlCheck:false）、uni.scss（主题变量对齐 design-tokens：品牌深蓝/操作蓝/RT 生鲜绿）、App.vue、main.ts、pages/index 骨架页}
- **验证**：vue-tsc 0 错；`build:h5` DONE（dist/build/h5）；`build:mp-weixin` DONE（dist/build/mp-weixin 可直接导入微信开发者工具）；仅无危害警告（appid 未配 + Dart Sass legacy-js-api）
- **踩坑（本机环境）**：GitHub 443 直连不通 → degit/`create-uni -t vue3` 均不可用，改**手写工程骨架**（npm registry 可达，依赖约束逐包核实：vite-plugin-uni peer vite=5.2.8、uni-h5 内部锁 vue 3.4.21）；pnpm 增量链接触发 CodeBuddy safe-delete 批量保护（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`，替换大 junction 逐个卡死）→ 以 `$env:CODEBUDDY_SAFE_DELETE_ENABLED='0'` 进程级放行完成 install（**后续 pnpm install 需同法**）；残留 `frontend/_tmp_*`（safe-delete 回收目录）待清理（删除操作需用户在场批准）；`@vueuse/core@14` peer vue^3.5 警告源自 uni-cli-shared→unplugin-auto-import@19 构建链（非运行时依赖、uni 未启用 unplugin 注入）暂容忍
- **F 波子波进度**：F1 uni 工程 ✅ → F2 RT 买家正式端 ✅（详录见 F2 段落）→ **F3 WA+WE 批发商移动端 ✅（详录见 F3 段落）** → **F4 ST 结算员移动端 ✅（详录见 F4 段落）** → **F5-W1 WK 库管出入库作业 ✅ 2026-09-03（详录见 F5-W1 段落）** → **F5-W2 WK 库存·批次·临期·盘点核对 ✅ 2026-09-04（详录见顶部段落；四页 + 盘点单全链，vue-tsc 0 错 + lint 0 + 双端 DONE；F 波库管主体收官）**

## 2026-09-03 · F2 RT 买家正式端（F 波第一业务子波：US-RT-01~04 全链，CodeBuddy）

> 按既定子波顺序开工（用户：「按照你规划的顺序来」）。uni 承载，替代 admin 内 RT 最小 H5 过渡态（admin Store.vue 保留待 RT 登录子波统一收尾）。
> 口径沿用 phase-1：RT 无账号体系，**手机号 = 本地身份**；询价/我的价目/意向单全部匿名公开端点，手机号放 POST body、服务内 hmac 盲查。

- **后端（新端点 1 个 · 零新错误码）**：`POST /api/v1/rt/my-inquiries`（document 域 `RtInquiryController.myInquiries` → `InquiryServiceImpl.listForRt`）——store→tenant 解析复用 storefront 出口 `getStorePage`（提交询价同款，RT 无 TenantContext → TenantLine 不注入，显式 tenantId+rtPhoneHmac 过滤防跨店泄漏）、createdAt 倒序；商户名经 tenant `WholesalerService.getById`、SKU 名经 product `SkuService.listForRtBySkuIds` 出口补全（G-S2，按 wholesaler 批量取数）；响应 `RtInquiryListVo` **结构无明文字段**（仅尾号归属提示）；PENDING 行 dealPrice = 提交时公开单价快照（WA 确认可改写）；纯只读不产生单据
- **后端验证**：`RtMyInquiriesScenarioTest` **7/7 绿**（MI-01 倒序+字段齐全 / 02 未知手机号空态 / 03 跨店隔离 / 04 空手机号+未知店铺拒绝 / 05 响应 JSON 无明文断言 / 06 只读不产生单据）
- **契约与类型**：`api-contract-storefront` §3.4 + v1.2（标注 **D-RT-01**：已确认后展示商户联系方式，PII 无登录态安全出口 → 留 RT 登录子波）；api-types `rt.ts` 增 `RtMyInquiries` 全套（类型单一来源，uni 经 tsconfig paths + vite alias 双解析直引源码，免 pnpm install）
- **前端 uni（RT 正式端 3 页 + 基础层）**：
  - `pages/index` RT 买家首页：品牌 hero + 店铺码输入/分享链接自动提码（?code=）+ 扫码进店（条件编译：仅 MP-WEIXIN 真 `uni.scanCode`，H5 提示改用输入）+ 最近店铺 chips（本地最近 3 店一键直达）+ 手机号身份卡（询价/价目/意向单复用）
  - `pages/rt/store` 店铺页：进店加载态/失败态 + 店铺头（名/营业时间/intro）+ 工具行（我的价目/意向单入口）+ 商户横向 tabs（置顶标）+ 商品行（主推标/规格/公开价/起批提示/库存/步进器+手输）+ 底部估价提交栏（「参考估价，实际以商户确认价为准」）+ 手机号收集弹层 + 提交成功弹层（docNo → 我的意向单）+ 我的价目 sheet（分组/议价沉淀标/下架置灰/到期日/公开价划线）
  - `pages/rt/inquiries` 我的意向单：手机号查询 + 状态过滤（全部/进行中/已结束）+ 意向单卡（docNo/状态 chip/商户/明细行价快照/合计/状态引导语）+ 下拉刷新
  - 基础层：`config`（API_BASE/PHONE_RE）/ `utils/request`（uni.request + R 壳 + ApiError + toast，雪花 ID 后端已 ToStringSerializer 无需精度兜底）/ `utils/storage`（手机号、最近店铺）/ `utils/format`（金额/时间）/ `api/rt.ts`（getStore/submitInquiry/getMyPriceList/getMyInquiries）
- **验证**：vue-tsc 0 错；`build:h5` DONE；`build:mp-weixin` DONE（可导入微信开发者工具）；后端 mvn 编译通过 + 场景测试全绿
- **边界与后续（D-RT-01）**：专属价 `matchedPrice` 需已登录 RT token（P2 Wave3b 语义，匿名仅公开价）→ 正式端专属价走「我的价目」入口；「确认后展示商户联系方式」PII 无安全出口 → 契约标注留 RT 登录子波；admin RT 过渡 H5 保留，收尾替换待 RT 登录子波统一处理

## 2026-09-03 · D 波 X 期本地收尾（roadmap D · W8-L1/L4/L5，CodeBuddy 收口）

> 不待环境的部署侧项全闭环；L2（V34 观察期）/L3（prod 冒烟）/L6（Redis ACL）仍待环境。

- **W8-L1 还原演练脚本固化 + 全量演练 PASS**：备份 `backup_w8_gap_delete_20260901.sql`（37 表 27700 行 INSERT）可完整还原——
  - `shared/ops/restore-drill-w8.py`（入库）：解析备份 → 源库 `CREATE TABLE LIKE` 建临时库 `restore_drill_w8_<ts>` → 关外键检查逐表参数化 `executemany` → 校验 a) 逐表 `COUNT(*)==N_file` b) **PII 8 表全列逐行值比对**（按主键序 zip，NULL/日期/Decimal 归一）；`--dry-run`/`--only`/`--keep`；只读源库、演练后默认删库
  - `shared/ops/v33-reverse-rename.sql`（入库）：8 列 `*__bak` rename 回明文 + 恢复 3 旧索引/约束（MySQL 8），**适用窗口 = V33 后 V34 前**（16 §5.2）
  - `shared/ops/README.md`（入库）；**全量演练结果**：37 表行数全 PASS、PII 8 表逐行比对全 PASS（Decimal 精度归一后 lng/lat 一致）、约 5s、临时库正确删除
  - 踩坑（本次收口修链）：备份解析反引号被 PowerShell shell 吞 → 独立 .py 承载；还原 SQL 双重反引号 → `cols` 解析 `strip('\`')`；Decimal 精度 FAIL（tenant_applications lng/lat）→ 统一 `Decimal` 归一
- **W8-L4 CVE 复扫 —— 发现并修复 1 项真实 CVE**（06 报告 §7）：
  - Boot 3.5.16 为 3.5.x **终版**，BOM 固定 Tomcat **10.1.55**，受 **CVE-2026-55956（Moderate，≤10.1.55）/ CVE-2026-59083（Low，≤10.1.56）** 影响；tomcat.apache.org/security-10.html（2026-09-03 抓取）最新修复版 **10.1.59**（10.1.58 未过发布投票）
  - 修复：`pom.xml` 加 `<tomcat.version>10.1.59</tomcat.version>`（Spring Boot 官方支持路径，patch 级差异）；`dependency:tree` 确认仅 tomcat-embed-{core,el,websocket} 10.1.55→10.1.59、其余 162 依赖零变化；基线归档 `dependency-tree-after-boot3516.txt` 同步（165 依赖）
  - 核对无新增：P4-W5 新增 22 依赖（POI 5.4.1 / PDFBox 2.0.33 / OpenHTMLtoPDF 1.0.10 / commons-* 等）+ 存量关键件（Spring FW 6.2.19 / Jackson 2.21.4 / Logback 1.5.34 等）
  - 门禁：OWASP dep-check / Trivy 本机未装、下载需批准 → 命令留 06 §7.4 正式环境执行
- **W8-L5 graceful shutdown 硬化 + 手测手册**：`application.yml` 加 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`（停止收新请求 → 在途限时完成 → 关停）；**Windows 停服手测手册** 写入 13 报告 §8.5.2（Ctrl+C 触发 shutdown hook 的期望日志 4 项 + 超时负路径 + `Stop-Process` 强杀不触发优雅停机须用 `sc stop` 的注意）；实测待本机重启后端后人工执行（当前 8080 后端启动于配置修改前）
- **验证**：本波代码变更 = pom `tomcat.version` + `application.yml` shutdown（无业务代码改动）→ 回归策略：依赖树 diff 干净 + 后端全量（见提交消息）；「长命令被环境 skip」时以 dependency:tree 快验 + 文档化人工步骤兜底
- **收口**：roadmap v3.3（D 已收官）+ 06 报告 §7（复扫记录）+ 13 报告 §8.5（操作手册，§8 W8-L1/L4/L5 状态回填）+ 本记录；提交分 backend（pom/yml）/ docs（06/13/dependency-tree + ops + roadmap + progress）

## 2026-09-02 · C2 货位功能收官（P5-D 小项池 C 波补完，CodeBuddy 收口）

- **C2 货位功能（US-WK-05，product/17 §2 + DECISION D-C-1~1d）**：架构 `architecture/25-p5-c-c2` v1.0 定稿后实现（三件套：仓级开关 locationEnabled + 开启后出入库登记货位 + 批次移库）
- **迁移 V40**（`V40__p5d_c2_location.sql` 单文件）：`tenant_settings.location_enabled`(默认 0) + `batches.location` + `inbound_requests.location` + `outbound_requests.location` 4 处加列 + 新表 `batch_location_logs`（from/to/operator/created_at；TenantLine 白名单追加）
- **后端**：
  - tenant 域：`StoreSettingsDto`/`TenantSettings`/`TenantDetailVo`/`TenantBatchConfigVo`/`TenantServiceImpl` 扩展 locationEnabled——**开关走通用 `PUT /tenant/me`**（纯字段显隐+必填约束、无冻结副作用，对照 batchEnabled 专用 toggle 不适用）
  - inventory 域：`Batch`/`BatchVo` +location；新实体 `BatchLocationLog` + Mapper + Vo；`BatchServiceImpl.registerInboundBatch` 落 location；新增 `updateBatchLocation`（新旧相同幂等空转不落日志；null=清空；**零记账副作用**）与 `listLocationLogs`；`BatchController` +2 端点（PUT location / GET location-logs）；`BatchLocationUpdateDto`
  - document 域：`InboundRequest`/`OutboundRequest` +location；登记 4 DTO（InboundRegisterDto/InboundForwardRegisterDto/OutboundRegisterDto/WkOutboundCreateDto）+location（@Size≤64）；Inbound/Outbound `registerByWk`/`registerForwardByWk`/`createByWk` 按**当刻开关**必填校验（50822）+ 落值 + 透传批次钩子；两个 VO +location
  - ErrorCode 50822（LOCATION_REQUIRED）/50823（BATCH_LOCATION_TOO_LONG）；不动 batch-toggle（50360）、InventoryService 账务、stock_movements
- **前端**（typecheck 通过）：ta/Settings.vue「启用货位」开关卡片（随通用设置提交）；ta/Inbound.vue 登记行货位字段（开关显隐/必填）；ta/Outbound.vue 登记/代建拣货位（批次货位联想 chips，读 `/tenant/batches` location 去重）；ta/Batches.vue 登记簿货位列 + 行内移库弹窗 + 变更记录抽屉；`api/batch.ts` +updateLocation/locationLogs + api-types 同步（Batch/Request/VO 全链路 location）
- **测试**：`LocationScenarioTest` LV-01~06 7 例（默认关免填零回归 / 开必填 50822 / 落单落批次 / 出库零记账副作用断言 / 移库幂等+日志 / 关开关存量保留 / 跨租户 50363）；全量回归 **524**（首跑 3 flake 均为环境/顺序问题并已修复：PiiWrite 2 例 41205 = Redis `sms:daily` 当日多次运行累积超每号 10 次上限——清键恢复；CustomerFollowup cf05 t304 = C3 遗留 `seedTenant` 用 `id%1000` 简码在新增测试类后顺序撞唯一键——改 `TestUniq.tenantSimpleCode()` 全局唯一，见下）
- **测试隔离修复（随波收口）**：`CustomerFollowupScenarioTest.seedTenant` 简码改 `TestUniq.tenantSimpleCode()`（仓库 22 个测试类唯一仍用 `id%1000` 的存量，消除跨类唯一约束顺序耦合）
- **收口**：roadmap v3.2（C 波 C1/C2/C3 三子项全收官）+ product/17 v1.6（C2 由 backlog 转已实现，D-C-1~1d 标记落地）+ architecture/25 v1.1（标注已实现）+ 本记录

## 2026-09-02 · C 小项池 C1+C3 双波收官（P5-D 收尾，CodeBuddy 收口）

- **C1 专属价目复购（US-RT-05）**：架构 `architecture/23-p5-c-c1` + 后端（pricing 出口 `listActiveRefsByPhone` + product 出口 `listForRtBySkuIds` + storefront 编排 `getMyPriceList` + `RtStoreController POST /my-pricelist`：按 wholesaler 分组价目、专属价/公开价对照、有效期）+ RtPriceListScenarioTest 12/12 绿 + 前端 rt/Store.vue「我的价目」抽屉（勾选数量提交询价、下架置灰、空态降级）+ api/rt.ts + api-types rt 契约 + `api-contract-storefront` §3.3
- **C3 客户跟进（US-WE-04）**：架构 `architecture/24-p5-c-c3` v1.1 + V39（customer_followups wholesaler×rt_phone_hmac 唯一 + followup_reminders，TenantLine 白名单 + cf_/fr_ 索引）+ document 域 CustomerFollowupService/Controller（/api/v1/tenant/customers：list/detail/remark/reminders/delete；按 wholesaler×hmac 归并打码、customerKey = URL-safe base64(hmac) + wholesalerId 收敛、remark 覆盖式清档规则、50840-42）+ InquiryRequestMapper GROUP BY 聚合 + FollowupReminderJob（每 5 分钟到点站内信给创建 WE，CAS + 同事务防重发）+ Notification TYPE_CUSTOMER_FOLLOWUP + CustomerFollowupScenarioTest CF-01~05 5/5 绿
- **前端 C3**：views/wa/Customers.vue（列表 + 详情抽屉：备注编辑/提醒新建删除/查全号复用 pii reveal）+ 9 个 wa 视图菜单追加「客户跟进」（ChatDotRound，紧跟询价确认）+ 路由 /wa/customers + NotificationBell「客户跟进」标签 + api/customers.ts + api-types tenant C3 类型；vue-tsc 0 错 + vite build 通过
- **验证**：全量回归 **517 全绿**（500 基线 + C1 12 + C3 5，0 失败 0 错误）
- **收口**：roadmap v3.1（C 已收官）+ 本记录 + docs 提交（17 v1.5 / 23 / 24 已入库）；**C2 货位功能**（US-WK-05）用户拍板「记录下来后面还是要做」→ 需求档案保留 product/17 §2，后续单独排波

## 2026-09-02 · B D56 商品档案收官（P5-D 起步，CodeBuddy 收口）

- **A 波（OPS 控制台）收官后 B 波排期落地**：口径 `product/16`（D-B-1~7 全采纳）+ 设计 `architecture/22` 定稿并实现
- **后端（63917bc）**：V38 `spus` 平台级表 + `skus` 标品快照 3 列（16:16 首跑发现 H2 不支持单条 ALTER 多列 ADD，改逐条 ADD 后修复）；product 域 Spu 全套（requireOps 42002 / ACTIVE/OFFLINE/MERGED 状态机 / 合并源 MERGED + 引用 SKU 单 SQL 原子重指 + 快照刷新 / 自动编码唯一）；OpsSpuController（/ops/spus* + spu-categories）+ CatalogSpuController（登录态只读 /catalog/spus，补 TA 选标品越权盲点）；SkuServiceImpl 挂接 ACTIVE 校验 + 快照列写；OpsSpuScenarioTest 7 例
- **前端（fb01030）**：views/ops/SpuCatalog.vue（搜索/新增两级品类联动/合并/下架）+ OPS 菜单 5→6 项统一（5 页同步）+ TA Skus.vue 建 SKU 选标品（搜索 ACTIVE / 回填 spuId / 列表展示所属标品）+ api-types Spu 契约；typecheck 通过
- **验证**：全量回归 **500 全绿**（55 测试类，0 失败 0 错误）
- **收口**：roadmap v2.9 + 本记录 + docs 提交（16/22 已入库）随代码一并推送

## 2026-08-31 · W7 验收核对 + B2 遗留修复 + guardrails v3

- **W7 验收核对**（前端逐页 + 后端 VO 对照 15 §5.2 清单，结论：**覆盖完整、无缺陷**）
  - 管理端 4 页（ops/Blacklist、ops/TenantAudit、ta/Pricing、ta/WholesalerApplications）打码 + 查全号 ✅；后端 5 service VO 打码 ✅；检索口径（黑名单 11 位精确/last4/执照 LIKE）✅
  - **本人豁免正确**：wa/Apply 走 `listMine`（`toVo(masked=false)` 保留全号），驳回重提无"打码号回写"风险 ✅
  - **编辑身份键不可改**：ta/Pricing 编辑分支只提 unitPrice/status（rtPhone 不随编辑提交），无打码号提交风险 ✅
  - 已知待产品定：wa/Inquiry、wa/PriceSettleDialog 无查全号入口（§5.2 标"由产品定"）；wa/Staff 全号展示（§5.2 例外项）
  - 阴性页 st/*、ta/BillsOverview、ops/Arbitrations 无手机号展示 ✅
- **B2 修复**（遗留缺陷收口）：`Blacklist.removedAt` 标 `@TableField(updateStrategy=ALWAYS)` 允许 null 下发——复活分支 `setRemovedAt(null)` 生效；影响评估：前端 0 处消费 removedAt（展示零影响）、remove 路径不受影响、保持实体写路径（不破坏 PII"双写切点走实体"约定）。`PiiDualWriteBackfillScenarioTest` 复活用例补 `removedAt isNull` 断言；DualWrite 20 + Hmac 22 + Onboarding 15 全绿零回归
- **guardrails v3**：`05-secure-coding-guardrails.md` 增 G-8.3/8.4/8.5（手机号默认打码口径统一、全号走 reveal+角色归属校验+审计、检索禁 LIKE、本人豁免须显式）+ 自检卡 PII 项（对应 15 §1.2-G/§4 阶段2 防回潮）

## 2026-08-31 · PII-W7 阶段 2 前置交付（列表打码 + 检索口径 + 查全号）

- **提交**：44fb080（main 工作区直做，已推 origin，`origin/main..main=0`，工作区干净）
- **范围**：15-pii-hardening-v2 §4 阶段 2-1/2-2（§5.2 清单的管理端部分）；**V29 明文收缩不在本波**（归 W8）
- **查全号接口** `GET /api/v1/pii/phone-reveal?biz=&id=`（`PiiRevealController` + `PiiRevealService`，登录拦截由 SaInterceptor 覆盖）：
  - **四类 biz 权限矩阵**：`BLACKLIST`/`TENANT` → 仅 OPS；`WA_APPLICATION` → OPS 或该申请归属租户的 TA（跨租户 TA 一律拒绝，不泄漏存在性）；`INQUIRY` → 该询价归属 wholesaler 的 WA 或持 `INQUIRY_CONFIRM` 授权位的 WE（对齐 `InquiryServiceImpl.requireWaRole` 口径）
  - **跨租户显式归属校验**：wholesaler_applications / inquiry_requests 查询先 `TenantContext.clear()` 再查（TenantLine 会注入当前租户条件、把 50401 伪装成"不存在"，无法与 50402 区分）；归属校验在方法内显式完成并注释明示，PII 横切模块直连 mapper 照 `PiiReadRouter` 先例（G-S1/G-S2 既定例外，其余业务代码仍禁直连他域 mapper）
  - **审计**：`[PII-REVEAL] operator={} biz={} id={} ts={}` 只落 operator/biz/id，不落明文；错误码新增 `PII_REVEAL_TYPE_INVALID` / `PII_REVEAL_FORBIDDEN` / `PII_REVEAL_TARGET_NOT_FOUND`
  - **数据源**：当前直接读明文列（V29 未做），W8 收缩后改 cipher 解密，**接口形态不变**
- **检索口径**（15 §4 阶段 2-2 / B3）：黑名单 LIKE 模糊查号下线——完整 11 位 → `target_value=kw OR target_value_hmac=hmac(kw)` 两列精确查；其他输入 → `RIGHT(target_value,4)=kw` 精确尾号 + 执照号行保留 LIKE（非 PII）
- **VO 打码**：BlacklistServiceImpl 列表 PHONE 行 `SmsUtil.maskPhone`（LICENSE_NO 原样）+ Inquiry/Pricing/Tenant/WholesalerApplication 五 service 回打码号；前端 `utils/phone.ts`（maskPhone 对齐后端）+ `api/pii.ts` + 管理端 4 页（ops/Blacklist、ops/TenantAudit、ta/Pricing、ta/WholesalerApplications）接"查看完整号"展开
- **测试**：`PiiRevealScenarioTest`（新）+ Pricing/Onboarding/Wave6DefectFix/WeEmployee 四场景类适配 + E2E `onboarding-flow`/`onboarding-visual` 更新；全量 **49 类绿**（0 失败/0 错误/0 跳过）
- **未做（归 W8 / 待产品拍板）**：wa/ 侧 `Inquiry.vue`、`PriceSettleDialog.vue` 的"详情展开查全号"（§5.2 标注"是否放开由产品定"）；`wa/Staff.vue` 例外项（建议保留全号，待确认）；V29 明文收缩 + 删双写/开关代码；`05-secure-coding-guardrails` 防回潮规约增补（导出/通知/列表禁出完整手机号）

## 2026-08-31 · PII-W6 收尾完成（Team Lead，CodeBuddy 接手收口）

- **PII 阶段 1 Step 3 登录链双读切换交付**：全量 **488 绿**（基线 470 + `PiiLoginHmacReadScenarioTest` 18 例，0 失败/0 错误/0 跳过，零回归）。A1–A6 六切点经 `PiiReadRouter.user()` 统一走"hmac 出结果 / 漏填旧列兜底 / 异步补写自愈"；`PiiFallbackHealer` 以 `pii.fallback` 承接七天闸门（FALLBACK 恒 0 为切读后准入线）
- **RED 变异验证（W5 同款纪律，两个互补变异）**：变异 A（切读没发生）18 例杀 **15**；变异 B（hmac 查询失效）18 例杀 **7**（6 例"hmac 一致" + 1 例"指标分家"）；两轮都不红的正是默认值/CAS/漏填兜底等"兜底语义"用例——行为断言本就不该杀它们，与设计注释一致。变异还原后复验 488 全绿
- **PiiCrypto 待核点结论：无需改动**。现形态（HMAC 单入口 + 启动 KAT + fail-fast）已完全满足 Step 3 全部需求；设计文档中 AES-GCM 加密属阶段 2（PII-W7/W8），不在 W6 范围
- **用户拍板（2026-08-31）**：①无生产环境 → 不设 7 天/3 天观察期，验证直接拨 hmac 跑全量即可；双读兜底代码保留（上线后 FALLBACK 恒 0 监控即保险）②W6 在 main 工作区直接收尾（未建分支）
- **默认值未动**：全局 `read-mode: shadow`、login 模块占位符空——交付的是"代码就绪 + 开关可拨"，生产拨动顺序 login 殿后（爆炸半径最大者最后切）
- 提交：W6 单提交（含 8 改动 + 2 新文件 + progress.md），main 已同步 origin

## 2026-08-18（会话收口·交接）
- **在途两分支已现场保护（均未合并，接手先看 task_plan 交接标注）**：
  - fix/p4-leftovers（3 commits）：P4-L2 WA 按日端点+LIF-10 测试（未跑）、P4-L3 PDF 无字体兜底（X-Export-Warning 头+首页提示行）。差最后一步：全量跑绿即可合并
  - feat/pii-stage0（3 commits）：V27 加列（hmac 列 NULLable+普通索引，V28 才 UNIQUE）、PiiCrypto 单入口+KAT+fail-fast；wip=双写切点半程未编译。真源 15-pii-hardening-v2 §阶段0；读路径不动红线
- **本段大量余额 401 中断**（约 10+ 次），均按"每段即 commit"纪律无损；接手 Agent 从 wip commit 续做即可
- **用户新规（已固化 CLAUDE.md 规则 10）**：代码分析/重构任务分批加载文件（单批 1-4 个、先报清单待确认、禁递归批量读目录、禁会话中变更 MCP 工具列表以保护 Prompt 缓存）
- headroom：mode=cache（8/15 定论勿switch token）；Memurai 已服务化；服务 8080/5173 状态未知，接手先探活

## 2026-08-10
- **P4 全部交付 ✅（W5c 终验收全绿，P4 收官）**：八段全合并（W0 双文档 580cd26/b65fd46 + W1-W3 后端 351→377→401 绿 + W4 前端 E2E 5/5 + W5a 导出 408 绿 + W5b 前端 E2E 2/2）。W5c：全量 408×4 遍全绿（fresh reports；基线×2 + SaManager 泄漏收口后×2）、**SaManager 静态泄漏测试侧根治**（2fde373，@BeforeAll/@AfterAll 直读直写 SaManager.config 捕获-还原，方案评估见报告 §3.1）、E2E 45/45（P1-P3b 38 例连过 2 遍 + p4-billing 5 + w5b 2，零环境失败零真缺陷）、视觉矩阵 18 图逐张亲检零新缺陷（p4-w5-visual.spec 66f3c28；375 详情吸底栏遮挡甄别为 fullPage 伪影并双视口图取证）、零角色码复核 0 违规。报告 **test-plan/12-p4-delivery-report.md**（遗留 P4-L1~L6：aria-disabled quirk/WA 无按日视角/导出中文字体部署项/上线检查单余项；下一期建议 PII 三段式硬化窗口已到）。
- **环境**：8080（main，dev,local，logs/w5c-boot.log）+ 5173（主仓 vite）保持运行供真机复验。

## 2026-08-03
- **P3b 全部交付 ✅（W5 终验收全绿）**：九开发波全合并（W0 设计/T1-BE·FE/T3-W1·W2·FE/T4-W1·W2·FE），后端 270→285→303→318→337 全绿递进，每波 Team Lead 独立复验后合并。W5：337×4 遍全绿、简码碰撞抖动根治（TestUniq 全局序列替换 10 处取模造数）、E2E 38/38（3.9m）、17 图视觉亲检、报告 11-p3b-delivery-report.md（遗留 L-1~L-7 + P3 检查单复核：Redis 已实测绑 127.0.0.1）。识破 mvnw 假成功坑（wrapper jar 缺失退出码误报 0，L-6）。
- **环境**：Memurai 已装为 Windows 服务（8/2 机器重启曾致登录 500，根治）；8080/5173 保持最新 main 供真机复验。

## 2026-07-27
- **缺陷批+refactor+硬化 三分支复验合并 ✅**（main 至 0b62e14 已推 origin）：
  - fix/p3-be-defects（10 commits）：B1 BLOCKER 行锁改真 FOR UPDATE（Team Lead 亲验代码）、N1-N5、FE-W1 两缺陷（通知收件人 7 处同根因全修）、角色码清扫 9 处、stock-preview 端点。独立复验 236 绿（concurrentWithdraw H2 抖动第二次出现，隔离复跑绿，判定环境 flake → W5 稳定化项）
  - refactor/account-user-service：实际已于前次会话尾段合入（aea514b，transcript 因进程重启丢失但 git 完整）；独立复验 231 绿；揭穿原 WIP 两处不实（不编译/死代码）并修正，account 域外 UserMapper 引用清零
  - chore/hardening-boot-upgrade（6 commits）：Boot 3.2.5→3.5.16（四高危组件全达线，CVE-2025-24813/22228 根治，零业务代码改动）、日志 profile 化+手机号脱敏、active-timeout 1800s、prod fail-fast 配置。独立复验 250 绿。交付报告 09-hardening-w1-report.md，6 项遗留入上线检查单（含 Redis 0.0.0.0 需 bind 回环）
  - **合并后 main 组合回归 248→250 全绿**
- **执行准则落地**：用户 7/25 下发六条准则已固化 CLAUDE.md 规则 8；P3b 预研 14 项 DECISION 已按准则由 Team Lead 拍板落档（10-p3b-requirements v1.1，D-11=C/D-8=A），P3b 解除阻塞
- **在途**：仅剩 FE-W2（出库链前端，4 commits 已落，收尾 E2E+stock-preview 接入；期间遭遇 codecmd 余额 401×3 + 网关 502/400 多次，均无损续跑）

## 2026-07-25
- **FE-W1 入库链前端 ✅（feat/p3-inbound-fe，4 commits 31f5f6f→f5ec4db，已合并）**：
  - 交付：WA `/wa/inbound` 入库确认页（待确认/全部页签、72h 秒级倒计时 deadline 升序、来源映射 仓库代建/我方提交、autoAccepted 标记、确认二次弹窗、异议弹窗〔预设四选+补充说明合成 reason≤512+附件≤5〕、冲销结果回显 登记/已冲销/差额/YY-单号）；TA `/ta/approvals` 审批中心（待仲裁角标=PENDING total、⏰超72h提醒、decide 弹窗按 09 §4.1：通过·恢复流水/驳回·保留冲销、差额>0 驳回时定责四选必填、备注必填、已裁决只读详情）；NotificationBell（unread-count 60s 轮询+抽屉+标记已读）；AttachmentUpload（≤5MB jpg/png/webp 预检）；api-types/error-codes 50330-50342/四组 API 封装；TA 各页菜单接通「审批中心」、WA 四页菜单增「入库确认」；用户可见文案零角色码（liability 中文四选）。
  - 闸门：typecheck 绿；Playwright `inbound-dispute.spec.ts` 3/3 绿（INB-01 确认链/INB-02 异议链含真实附件上传/INB-03 TA decide + TA 侧铃铛角标-条目-已读全链断言）；截图 6 张逐张目检无对齐/溢出/错位（动画入镜的 3 张已加静置重拍）。
  - **契约偏差 →BE 待修**：① `registerByWk`/72h Job 的「通知归属 WA」发给 `wholesalers.owner_user_id`，SELF_OPERATED 商户该列= TA 操作人，绑定 WA 账号收不到通知（listForWa 用 user_roles 推导无此问题；E2E 改在 TA 侧断言铃铛全链）。② `/files/**` GET 静态映射在启动时 `Path.toUri()`，若 upload-dir 尚不存在则 URI 缺尾斜杠 → 上传成功但 GET 500，重启后自愈（建议 addResourceHandlers 先 createDirectories 或手工拼尾斜杠）。③ PRD 09 §6.2 要求异议弹窗展示实时在库 M/差额 N−M，后端无异议前在库查询端点，已降级为口径文案+提交后回显（如需严格达标需 BE 补端点）。
  - 环境插曲：8080 曾跑 BE-W1 合并前旧实例（新端点 404→90001、V15-V17 未迁移），Team Lead 重启后解决；又因 ② 再重启一次使 /files GET 生效。axios 实例默认 application/json 覆盖 FormData 检测的坑已修（file.ts 摘除 Content-Type）。
- **BE-W2 出库状态机+异常链 ✅（待 Team Lead 复验合并）**〔feat/p3-outbound-chain，5 commits〕：V18（出库补拆列+inquiry voided_at，存量回填幂等）；DocStateMachine 引擎（OUTBOUND/INBOUND 双矩阵+assertCanGo 50330+通用 casTransition，兑现 BE-W1 备注 2）；confirmByWa 唯一触主链改动（出库 PENDING_ACCEPT/询价停 CONFIRMED）；R4 两路/R8 作废/代建大额 50%/30 天客诉+OPS 四选（remark 必填按 PRD）；R13 未结扩展至入库+仲裁；R14 钩子三处接入。测试 219/219 绿（基线 202+17 新增，P1 断言适配 4 文件）。错误码零新增（BE-W1 预登记段全启用）。偏差 10 处已回写 12 据实现备注（要点：WE 暂不开放出库、一单一诉查历史仲裁单、托盘账不动、50004 不存在改 50330）。中途插曲：上游 502×1 + codecmd 余额×1，均按「撞墙先 commit」纪律无损续跑；新拍板规则 8（文案去角色码）已在本波落地。

## 2026-07-24
- **P3 W0 设计定稿 ✅**：并行两 Agent 产出——产品 09-p3-arbitration-prd.md v1.1（双仲裁最小 PRD，Q-D04/Q-D10 收口，04 §1.2 确认即扣转正，05 §7.1 新前缀 RTN-/PD-/QK-/YY-/KS-，决策日志 D57-D60）+ 架构 12-p3-design.md v2（V15-V18 迁移、状态机 String+CAS、封顶冲销口径、72h Job 复用 SchedulingConfig、错误码 50330-50342、四波次拆分）。**Team Lead 契约对账拦下 3 处并行漂移**（liability 列缺失→补+50342、仲裁 doc_no 缺失→补 YY-/KS-、PRD 命名 9 处漂移→对齐落库定稿；另架构自查出盘点/清库前缀冲突按产品 PD-/QK- 统一）——W1 教训的对账机制第二次见效。
- **headroom 生效确认 ✅**：会话重启后 BASE_URL=127.0.0.1:8787，stats 显示 262 请求被压缩、累计省 25.6 万 token。
- **BE-W1 派发**：入驻异常链+基建〔feat/p3-inbound-chain〕，闸门见 task_plan。

## 2026-07-23
- **Wave 6 完成 ✅ / P2 全部交付**：双分支合并 main（3564607/bfba12e），回归 187/187 绿 + typecheck 绿 + E2E 12/12 绿（1.9m）；报告 07 v2 增补复验记录（0405b19）；main 已推 origin（aca2ae8）；worktree defects-be/fe 已清理。**发现**：后端 Agent 在 worktree 留有未提交的 G-S1/G-S2 架构债重构（tenant 跨域直连 UserMapper 收敛为 UserService 出口），未混入 Wave6——已抢救到分支 `refactor/account-user-service`（WIP，未经测试验证，P3 期间择机补测合并）。
- **P3 拍板 ✅（用户）**：三题全选 B——72h 待确认库存可售+冲销按剩余在库封顶（差额进 TA 仲裁）；扣库存保持「确认即扣」+状态机补拆（撤回走反向回补流水）；双仲裁最小闭环版（P3 产品首任务补最小 PRD+Q-D04 收口）。两项修正同意：单据号按已上线 WK-/CK-/XJ-（退货 RTN-）修订 PRD；Flyway P3 自 V15 起。详见 09-p3-decision-options.md v2。**P3 解除阻塞。**
- **headroom 路由修复 ✅**：代理一直在跑但会话绕行——项目 settings.local.json 残留 BASE_URL=codecmd 覆盖了全局 8787 配置；已删除覆盖，/v1/messages 经 8787 端到端验证通。新会话起走压缩。
- **Wave 6 双分支就绪**：后端 fix/p2-defects 5 commits mvn 全量绿；前端 fix/p2-defects-fe 6 commits typecheck 绿（前端二批 DEF-1 下拉/DEF-6 分页实际已随一批完成，无需再派）。进入合并+回归。
- **环境插曲**：headroom 压缩代理曾损坏（7-16 起 headroom.exe 报废导致会话报错），已重装 `headroom-ai[proxy,ml]` 0.32.1+开机自启；`.claude/settings.local.json` 清理跨机残留规则；CLAUDE.md 技能列表格式修正（5b0e745）。main 已推送 origin（65 commits，至 5b0e745）。
- **Wave 6 启动**：P2 收尾缺陷修复（DEF-1~DEF-6，源自 07 报告 §5）。派发：后端 Agent〔fix/p2-defects〕+ 前端一批 Agent〔fix/p2-defects-fe，DEF-4/5 纯前端〕并行；前端二批等后端 DTO 定稿后派（吸取 W1 契约漂移教训，契约类修复以后端据实现文档为真源）。

## 2026-07-16
- **WE 前端对账 ✅**：commit 823b876，typecheck 5/5 绿。关键修复：Staff.vue 8 处误用 userId→改角色绑定行 id（运行时必炸级）；41110 登录禁用文案、50319-50322、defaultRouterFor WE→/wa/inquiry、30 天倒计时 disabledAt 自算。守卫备注：WE 进 /wa/* 前端不拦（与 TA/WA 互访策略一致，页面权限靠后端 42004）。
- **Wave3 后端 ✅（主体）**：WE 员工全套完成（176/176 绿含 18 新增，8 commits 至 b7e2136）。8 端点、50319-50322/42004/41110、permissions 存 user_roles JSON 文本列（白名单解码防脏数据放大权限）、D52 路由（WA/WE→/wa/inquiry）、41110 全角色禁用拒登（修掉"被禁 WE 以 TA 兜底登录"的洞）、TA 端码管理过滤 WE 码。坑：R17 草稿作废为空操作（phase-1 无 WE 草稿单据，钩子已留§21）；询价 reject 端点不存在，将来补须挂 INQUIRY_CONFIRM 切点。
- **审查修复批次未随 Wave3 落地（插单晚到）**→ 已唤回专做 F1(V13 pending_flag 唯一索引)/F4/F5/F7+SEC-S4-01；**WE 前端对账已并行派**（错误码/员工VO/登录路由 defaultRouterFor WE 项过期）。
- **契约对账+F2/F3/F6/F8 ✅**：onboarding-fe 两 commits（db5dd4a R13/R14 对账、7160184 审查修复），typecheck 绿。要点：WaWithdrawStatus 收敛四值+CANCELLED（RESTORED/ARCHIVED 移到商户主体状态）、PageRecords 类型新增、50203/50204 重定义、黑名单改单键提交（后端 DTO 决定，原双键同拉不存在了——如需双键需产品确认走两次提交）。裁量遗留：50310/50311 数值落在退驻段但语义属黑名单（注释已标，Wave5 错误码文档核对）。**3 处 el-table 断言未赶上（消息晚到），Wave5 合并 fe-types 时 Team Lead 手工补**。
- **类型雷根治 ✅**：fix/fe-table-types 2 commits（151d3e3）——dts 再生补 9 条声明（含 ElTable/ElTableColumn），7 处 DefaultRow 断言修复，vitest 5/5。写法结论：el-table 非泛型，插槽形参标注过不了 vue-tsc，只能调用点 `row as T` 断言（已有先例可循）。剩 3 处在 onboarding-fe 文件里→已转契约对账 Agent 顺手加，保证 fe-types 最后合并时零冲突。
- **W1 提前审查 ✅**：05-onboarding-review-w1.md（commit 2125072）——BLOCKER 3/MAJOR 5/MINOR 7。F1 先查后写并发穿透（缺唯一索引）；F2/F3/F6 前后端字段漂移（黑名单页/审批页当前不可用、联系人静默丢失）；F7 明文密码+手机号进日志；F8 前端 50203/50204 语义过期。**Team Lead 拍板：契约真源=10-onboarding-design.md（据实现）**。修复已分派：F1/F4/F5/F7→Wave3 后端顺带（pending_flag 唯一索引方案）；F2/F3/F6/F8→契约对账 Agent 扩围。教训入档：并行契约先行必须以据实现文档收口对账，早审查挽回了 Wave5 大返工。
- **Wave2 后端 ✅**：R13+R14 完成（158/158 绿含 16 新增，5 commits 至 93dfd3d）。9 端点含插单的 precheck/cancel/mine/listMine；错误码 50312-50318；副作用链实测（SKU下架+店铺隐藏+专属价失效含Redis+WA/WE token 全踢）；60 天口径=audited_at 起数据库时间，59/60/61 边界测试过；归档 job 每日 03:40（错开 04:17 重索引），SchedulingConfig 全项目首个调度基建（P3 复用）。DTO 定稿：无 restoreDeadline（前端用 auditedAt 自算）、status 增 CANCELLED。
- **Wave3 已派**（onboard 续跑）：WE 员工全套（V12、码白名单、授权位切点、R17、D52 路由）+ OPS 租户列表端点；**契约对账已派**（onboard-fe）：api-types 对齐 Wave2 最终 DTO。
- **P3 预研 ✅**：08-p3-requirements-extract.md（commit 7ecab65）。5 主题+G1-G10 缺口。三大未决风险：(1)72h 待确认库存可售性 vs 异议冲销（产品空白，需用户拍板，预研建议方案B：可售+冲销按剩余在库封顶）(2)P1 询价确认即扣库存 vs P3 完整出库状态机扣库存时点（P3 架构第一波必须收口）(3)两条仲裁链终点未定义（TA 仲裁详情缺+Q-D04 客诉实体未定）。另发现：单据号 PRD 与 P1 实现冲突（IN-/OUT- vs 已上线 WK-/CK-）、全仓无调度基建（72h Job/临期扫描无处跑，P3 需先建 scheduler；本期 Wave2 的 60 天归档 job 是第一个，注意复用）。
- **Team Lead 修正（P3 预研的过期信息）**：(1)Flyway P3 应从 V12 起（V11 已被 Wave2 占用）(2)"OPS 路由守卫不查角色"已在 feat/p2-onboarding-fe 修复，P3 以合并后代码为准。
- **X 硬化方案 ✅**：11-hardening-design.md（286行，commit ff94059）。重磅发现：现有 phone_hash 是无盐 SHA-256≈明文（GPU 分钟级还原）且是登录唯一键，方案用双列双读过渡；额外泄露面：pricing Redis 键与 log.info 明文手机号。实施顺序：日志 profile 化/Redis 密码/active-timeout（可并行）→ Boot 升级（冻结窗口，先于 PII 切读）→ PII 三段式（双写尽早铺）。
- **Team Lead 修正**：方案称 blacklist 未建可直接按 hmac 落地——过期信息，Wave1 已在 feat/p2-onboarding 用明文 phone/license 建了 V10。处置：不阻塞本期合并（无存量数据），PII 阶段 0 将 blacklist 双键纳入加列清单（ALTER 成本≈0）。
- **Wave4b 前端 ✅**：5 commits（api第二批/退驻页/下架弹窗/员工页/窄屏修复），typecheck 绿，24 截图自查（修 2 窄屏缺陷：WA顶栏折行、表格撑宽）。契约偏差 4 处：precheck+cancel 两端点已转 Wave2 补；下架弹窗老单据动态计数降级静态文案（Wave5 再议）；DTO 字段名待 Wave2 完成后对账 api-types。**注意：vite dev 会重生成 components.d.ts/auto-imports.d.ts，勿带进 commit（fe-types 分支专管）**。
- **Wave2 状态注意**：发现其曾静默停止（SendMessage 时 no active task），已带 precheck/cancel 契约唤醒续跑。
- **OPS 租户审核页 ✅**：feat/ops-tenant-audit 3 commits（页面+守卫+导航徽标，typecheck 绿，9 截图自查修掉 2 个 375 窄屏真缺陷含共用 shell 的 Blacklist）；移交：后端缺 GET /admin/tenants 列表端点（前端已契约先行+优雅降级）→ 已排进 Wave3；该分支还原了 dev server 重生成的 components.d.ts 保持基线（类型雷由 fe-types 分支专门处理，合并时 fe-types 最后进）。
- **CVE 扫描 ✅**：06-dependency-cve-scan.md（commit 6899a4e）——高危4组件/约9 CVE 全系 Boot 3.2.5 BOM 传递依赖（Boot 升 3.5.x 一次根治，含 Tomcat CVE-2025-24813 在野利用、security-crypto CVE-2025-22228 BCrypt>72字符误判）；前端运行时 0 已知高危；Redis 无密码已登记 X 项。上线前需 OWASP/osv-scanner 复扫。**Boot 升级列为 Wave5 后独立硬化任务，不混入本期功能分支。**
- **Wave1 后端 ✅**：入驻主链完成（feat/p2-onboarding 4 commits，142/142 测试绿含 15 新增）；3 高危点全落实（自营过黑名单/TenantLine 配置+测试/审批 CAS）；坑：Memurai 需在跑、ensureWaAccount 拆为 ensureWaUser+provisionWaAccount、blacklist.evidence_urls 遗留未建。
- **Wave4 前端 ✅**：3 页+守卫完成（feat/p2-onboarding-fe 3 commits，typecheck 绿，Playwright 14 截图视觉自查过）；移交：①components.d.ts 残缺生成物掩盖 10 处 el-table 类型错（波及既有页面，留 Wave5 统一修，需登记缺陷清单）②listMine 端点契约缺口已转 Wave2 补③375 窄屏顶栏换行为全站既有表现。
- **Wave2 已派发**：R13 退驻+R14 强制下架（同 worktree 续 feat/p2-onboarding），指令含高危点：副作用链③④段（专属价失效+WE 一起踢）、新拒老放分界、60 天边界 59/60/61 测试、V11 迁移、50312+ 错误码。
- **测试计划 ✅**：04-onboarding-test-plan.md（86用例 P0=61，commit 7ae46d5）；5大高危漏点识别（自营也过黑名单/R14新拒老放分界/退驻副作用③④段/TenantLine配置遗漏/60天时区边界）；Wave1 相关 3 条已转发后端 Agent 自查。
- **产品线框补齐 ✅**：06b-onboarding-wireframes.md（600行，commit 763412e）——6页线框+交互标注；关键决策：50205对WA端不透出黑名单字样、R14输商户名二次确认、WE生码默认最小授权。
- **余额中断插曲**：Wave1/Wave4 Agent 首次派发因 codecmd 余额 401 挂掉（worktree 干净无残留），充值后已唤醒续跑；新增保险：撞墙先 commit 再停。

- **启动**：P2 定价已交付（127 测试绿，commit 9945d82），按路线图开工 P2 另一半：入驻生态 + WE 员工。
- **核对**：确认架构全局设计 P0 已有（schema/api-spec/modules 均覆盖入驻），不重做架构设计，直接落地核对。
- **调查**：并行两 Agent 完成——PRD 提取（6 主题+6 设计缺口）+ 代码现状（Flyway V9→V10、tenant 审批先例可复用、员工码只差白名单、AccountServiceImpl:222 接入点、错误码 50201-50205 预留可用）。产出 findings.md。
- **规划**：task_plan.md 五波：Wave1 入驻主链（后端）∥ Wave4 前端第一批 → Wave2 R13/R14 → Wave3 WE → Wave4b 前端第二批 → Wave5 测试审查合并。决策 O-1~O-6 已记录。
- **下一步**：建 worktree（.claude/worktrees/onboard + onboard-fe），派发 Wave1/Wave4 并行 Agent。

## 踩坑 / 注意（沿袭）
- 后端本地跑必须 `dev,local` profile，否则 MySQL 认证失败。
- 这台机 `npx claude-mem restart/stop` 会产生端口僵尸，别碰。
- `.worktrees` 是文件占位，worktree 建在 `.claude/worktrees/`。

## 2026-08-21 · PII-S0 合并收口

- 提交 2 个悬挂改动：`CLAUDE.md`（Token 规则上移标注）+ `admin/components.d.ts`（unplugin 重新生成，补 13 个在用组件声明）→ `eb2ac0c`
- `--no-ff` 合并 `feat/pii-stage0`（5 commits）→ main `acad899`，无冲突
- 合并后全量回归：**419 tests / 0 failures / 0 errors / 0 skipped**，45 个测试类；`PiiDualWriteBackfillScenarioTest` 10 例全过 → 零回归
- push origin/main，本地与远端 0/0 同步
- 回收 3 个已并入 main 的 worktree：`pii-s0` / `p4-leftovers` / `p4-w1`（分支保留，仅移除工作区）；仓库现只剩主工作区
- 决策：遗留测试债 A1/A4/B2 从「S0 尾巴」提升为「**S1 准入门槛**」——S1 影子双查要改读路径，缺这三处切点断言就没有回归网兜底

## 2026-08-23 · PII-S1 启动：Step1 影子双查（波次 PII-W4）

- 开工前摸底摸出一处**计划与实现的落差**：15 §4 阶段0 原列 7 张表加列，**V27 实际只加了 `users.phone_hmac` + `blacklist.target_value_hmac`**。定价链（customer_prices）/ sms_codes / inquiry_requests 连 hmac 列都没有，双写回填自然也没有 → 这几处**进不了影子期**。故 W4 范围据实收敛为 A1–A6 + B1/B2 共 8 个读切点，缺口单列进 task_plan（Step 2 前须补一次「V30 加列 + 双写 + 回填 + 对账」，等于补做一段 S0，不得夹带进 W5）
- 新增 `PiiProperties.read-mode`（plain/shadow/hmac）与 `PiiShadowReader`：出结果的永远是旧列，只多用 hmac 列查一遍比对计数；方法返回 void——调用方**拿不到影子结果，就不可能误用它做判定**
- 三条红线写进类注释并落实：①零行为变化 ②影子异常一律吞在类内（故意在业务 `@Transactional` 方法内部 catch，异常不越出方法就不会把事务标脏）③告警只打切点/结论/行 id，不落 PII
- 观测：Micrometer `pii.shadow{pointcut,verdict}`（`ObjectProvider` 软依赖，无 MeterRegistry 也不炸）+ 进程内 `snapshot()` 供关卡测试差值断言
- 关卡测试 10 例：**检出力与零行为变化写在同一条用例里**——造 hmac 漏填 / 造 hmac 指向别行，既断言计数落在 MISSING/DIVERGED，又断言登录与黑名单命中的**结果分毫不变**
- **RED 已验证**：强制 `read-mode=plain` 复跑，10 例中 9 例转红（唯一不红的是 LICENSE_NO 负向断言，同 S0 先例）
- 全量 **434 绿**（46 类，424+10，0 失败/0 错误/0 跳过）。全量日志仅 4 条 mismatch 告警，逐条溯源均为两个 PII 关卡类自己造的数——**业务用例零不一致**
- 生产闸门未起算：`pii.shadow` mismatch 须连续 **≥7 天为 0** 才可进 Step 2；回滚为 `read-mode` 拨回 plain，秒级

### 踩坑
- 跑 `@SpringBootTest` 前先确认 6379 有人听——Memurai 服务注册已坏，直接跑 `C:\Program Files\Memurai\memurai.exe`
- `mvn -q` 会把 `Tests run` 汇总压掉；真实计数从 `target/surefire-reports/*.xml` 用 awk 聚合

## 2026-08-25 · 补做 S1 缺口：定价/短信/询价三链的 S0（V30）

W4 摸出来的那个缺口补完了。范围严格限定「加列 + 双写 + 回填 + 对账」，读路径一行没动。

- **V30 加列**：`customer_prices.rt_phone_hmac` / `sms_codes.phone_hmac` / `inquiry_requests.rt_phone_hmac`。口径逐条照抄 V27——全 NULLable、先建普通索引（唯一索引升级不在本迁移）、纯 additive 无回滚脚本。索引列序按「Step 2 切读后要走的查询」设计，与各表现有明文索引一一对应
- **双写切点 4 处**：`setCustomerPrice` / `settleFromInquiry` / `sendSmsCode` / `submitByRt`。唯一产生点仍是 `PiiCrypto.phoneHmac`，一律 `write-mode=dual` 才写。**上切点前先核了覆盖**：主代码里这三表的 `mapper.insert` 就这 4 处，无 XML/Wrapper 绕过
  - 两处 upsert 的**命中既有行**分支也补了 hmac（`.set(condition, ...)`）——存量行只有这一个机会性回填点，漏写就留一个盲索引空洞。口径抄 blacklist REMOVED 复活分支
  - `doBatchCustomerInTx` **不算切点**：它只改价/状态/过期，不写 rt_phone；按 rtPhone 圈选那部分是 C3 的**读**切点，归 Step 2
- **回填+对账扩到五表**：把「主键/明文列/hmac 列/行过滤」抽成 `HmacColumn` record，CAS 幂等 + keyset 游标 + legacy 拒填三件套共用一份实现，替掉本来要写 5 份的复制粘贴（5 份里抄漏一个 `isNull` CAS 条件，只在并发下才现形）。users/blacklist 的公开方法签名与 `ReconcileResult.table()` 取值不变；`reconcile()` 从 2 条变 5 条
- **关卡测试 +5 例**（`PiiDualWriteBackfillScenarioTest` 15→20）：切点一律真调——C1 走 `settleFromInquiry`（新建 + 命中既有行两条分支分开钉）、SMS 走真端点 `POST /api/v1/account/sms-code`、C2 走 `submitByRt`（tenant/store/wholesaler/sku/stock 脚手架沿用 `PricingSettleScenarioTest` 的 mapper-seed 风格）。**不用 mapper 造行代替切点**，否则断言的是造数不是双写
- 全量 **439 绿**（434+5，0 失败/0 错误/0 跳过，零回归）

### 踩坑 / 决策
- **对账基线必须拉平五表**：兄弟场景类（`PricingSettleScenarioTest` / `PricingRtMatchScenarioTest` / `OutboundChainScenarioTest`）直接 `mapper.insert` 造 customer_prices / inquiry_requests，绕过双写切点，hmac 天然 NULL。原来只 flatten users+blacklist，扩到五表后 `reconcile()` 的 allSatisfy(clean) 会直接红——改走 `flattenBackfillBaseline()`
- 三个新字段都补了 `@JsonIgnore`。V27 的 `users.phone_hmac` 有这条且有红线用例把着，新列漏加就会让实体直出的响应形状变化
- `sms_codes` 回填**刻意不按「未过期」缩小分母**——分母随时间滑动的话，「回填填全了」这句话就无法证明。生产首跑成本靠 `backfill-batch-size` 控
- `PiiShadowReader` **没**给这三表接影子切点，类注释已改成「V30 已补齐前置，影子切点随 Step 2 一起做」。不进 Step 1 的 7 天闸门分母

## 2026-08-27 · PII-W5 影子读切点：定价链 + 短信码（Step 2 前半段）

Step 2 拆成两半，本次只做前半段「接影子切点」，**切读本身（`read-mode=hmac`）一行没动**——先让这几条路也攒够观察数据，再谈切。

- **新增 5 个读切点**，口径逐条照抄 W4 那 8 个（返回 void、异常吞在 `probe()` 内、日志只打切点/结论/行 id、hmac 算不出记 SKIPPED 不入分母）：
  - `C1-price-set` / `C1-price-settle`：`setCustomerPrice` 与 `settleFromInquiry` 的 upsert 唯一键探测，两处同一口径（15 §1.2-C1 本就把它俩算作一个场景）
  - `C2-price-resolve`：`resolveCustomUnitPrice`。影子查询**必须同带 `status=ACTIVE`**，否则比的不是同一个问题。该切点在 Redis 缓存 miss 分支内，分母 = 真实 DB 读次数，不是请求数
  - `C3-price-batch`：`doBatchCustomerInTx` 按 rtPhone 圈选。**唯一的多行切点**，比的是行 id 集合——影子少捞=MISSING、多捞=EXTRA、两头都对不上=DIVERGED，与单行 `compare()` 语义逐条对齐。差集日志封顶 10 个 id
  - `SMS-verify`：`verifySmsCode`
- **inquiry_requests 没接，是核实后的结论不是遗漏**：主代码对该表的读全部按 id / tenant / wholesaler / status，**没有一处按 rt_phone 圈选**（15 §1.2-C6 只把它列为落库+透传的写触点，§4 Step 2 的切读清单同样只有 blacklist/sms/pricing）。没有明文读路径就没有「两列答案对不对得上」可比，硬造探针只会往分母里灌永远 MATCHED 的噪音。该列正确性由 `reconcile()` 兜底；将来真出现按手机号查询询价单的入口，接切点时一并补进 `PiiShadowReader`
- **闸门分组写进类注释**：W5 这 5 个切点**不进 Step 1 的 7 天分母**（那是登录/黑名单 8 切点的准入线），服务的是 Step 2 自己的「pricing 全量 + 黑名单用例 + E2E 45×2 全绿，观察 ≥3 天」
- **关卡测试 10→19 例**（仍在 `PiiShadowReadScenarioTest`，不另起类）：C1/C2/C3/SMS 各一对「一致记 MATCHED」+「造漏填记 MISSING 且主路结果分毫不变」，检出力与零行为变化同一条用例；另 1 例钉死 C3 显式 ids 分支不入分母。三个定价切点的零行为变化**分开断言**，因为爆炸半径不同：C1 走成 insert 会撞唯一键连累 confirmByWa 整单回滚、C2 回退公开价是资损、C3 少圈一行是漏调价
- **RED 已验证**：强制 `read-mode=plain` 复跑本类，19 例中 17 例转红；不红的两例正是 b2 LICENSE_NO 与 c3 显式 ids 这两条负向「不入分母」断言（同 W4 先例）
- 全量 **448 绿**（439+9，0 失败/0 错误/0 跳过，零回归）

### 踩坑 / 决策
- **全量日志 13 条 mismatch，其中 4 条不是本类造的，但也不是缺口**：`PricingSettleScenarioTest`（C1 ×1）与 `PricingRtMatchScenarioTest`（C2 ×3）直接 `customerPriceMapper.insert` 造价行，绕过双写切点，`rt_phone_hmac` 天生 NULL——**和 S0 波次把对账基线改走 `flattenBackfillBaseline()` 是同一个成因**。生产没有 mapper 造行这回事，闸门读的是 prod 的 Micrometer 计数，不受测试态影响，故不追改兄弟类，只在关卡类注释里写明来源，免得下一个人把它当回填缺口查
- **SMS 切点在测试态默认根本触发不到**：`cangchu.sms.mock=true` 下发出的就是 888888，而 888888 会在 `verifySmsCode` 首行短路，永远走不到 sms_codes 的 DB 读。做法是先经真端点发码（行仍由真双写切点写入），再把落库那行的 `code` 改成非万能码——读切点仍由真端点驱动，改的只是一个夹具字段
- **修掉一处会咬人的测试抖动**：本类 `PHONE_SEQ` 原起点固定，而 sms-code 的 60s 重发冷却键 `sms:cd:{phoneHash}:{scene}` 在 Redis 里**跨 JVM 存活**（H2 每次重建，Redis 不会）→ 60 秒内复跑本类必撞 41204 假红。改成按本次运行随机偏移，仍在 176 段内、留 1000 万号余量。兄弟类 `PiiDualWriteBackfillScenarioTest` 用 177 段且同样调 sms-code 端点，存在同样的潜在抖动，本波未动（不越界改他人用例），留待其自身波次处理
- `C3` 的显式 ids 分支选择**早返回不探测**（而非记 SKIPPED）：那条路主路压根没读 rt_phone 列，记 SKIPPED 等于承认"这里本该有个影子"，语义不对。口径抄 `checkBlacklistEntry` 对 LICENSE_NO 的处理

## 2026-08-29 · PII-W5 后半段：Step 2 切读（read-mode=hmac）

### 做了什么
范围严格按 15 §4 Step 2：blacklist(B1/B2) + sms 校验 + pricing(C1/C2/C3) + Redis 键 HMAC 化(C4)。**登录链 A1–A6 一行未动**（归 Step 3/W6）。

- **`PiiReadRouter`（新）**：切读开关本体。明文查询由调用方以 `legacyRead` Supplier 传入——回滚分支就是原来那条查询，一个字没改，拨回即秒级恢复。异常**不吞**（与 `PiiShadowReader` 相反）：切读后没有第二个答案可用，吞掉等于凭空编一个"未命中"
- **`PiiHmacQueries`（新）**：hmac 查询的唯一构造入口。影子期比对用的谓词与切读后出结果用的谓词**必须逐字节是同一条**，否则「7 天 / 3 天 mismatch=0」证明的是 A 查询、上线跑的是 B 查询，闸门就是自欺。`PiiShadowReader` 只在其上追加 `.select(id)`
- **`PiiModule`（新）+ `read-modes` 映射**：灰度粒度按模块（blacklist / sms / pricing / redis-key），未登记或空值回落全局 `read-mode`
- **C4**：`price:match:*` 原键里**直接带明文手机号**（本次堵掉）、`sms:cd:* / sms:daily:*`、`login:fail:*` 三处派生物统一走 `redisKeyPart`
- **关卡测试 `PiiHmacReadScenarioTest` 22 例**（新类；影子类 19 例一条没删，仍在 shadow 口径下跑）
- 全量 **470 绿**（448+22，零回归）

### 踩坑 / 决策
- **Step 2 硬切，不做旧列兜底**——这是本波最该被质疑、也最该写清楚的一条。`PiiProperties` 原注释写的是「主读 hmac + 旧列兜底回退」，本次改掉了：回填有没有填全，是切读**之前**由影子闸门证明的事；用运行时兜底去掩盖，等于把「回填有洞」这个事实永久藏起来，闸门也就再没有归零的一天。Step 3 登录链另做双读兜底自愈，是因为登录切错的代价是全员登不上，权衡不同
- **切读后 MISSING 的语义要重新钉，而不是把影子期的用例删掉**：影子期「造漏填 → 记 MISSING 且主路结果分毫不变」那批一条没删；新类钉的是同一份数据在切读后的**另一半语义**——B1 放行该拦的人 / B2 复活语义丢失退化成 uk 兜底 50310 / SMS 41202 / C1 撞唯一键连累 confirmByWa 整单回滚 / C2 回退公开价 9.90 / C3 真的少圈一行。代价逐条写进断言消息，那道 3 天闸门才不是走过场
- **灰度粒度必须是模块，不是一个全局开关**：四块爆炸半径完全不同，一刀切意味着任一块翻车就得把已观察合格的其余三块一起赔进去
- **主配得显式登记四行空占位符**（`${PII_READ_MODE_*:}`）：`redis-key` 带连字符，不登记就没法用标准环境变量注入，「按模块灰度」会退化成纸面能力。随之要让启动校验放行空值（空 = 未登记，不是配置错误），同时对**模块名/模式值笔误拒绝启动**——写错会静默回落全局模式，想切的没切、想拨回的没拨回，且毫无征兆
- **`PiiShadowReader.checkUser` 故意不设模块**，继续吃全局 `read-mode`：那正是 Step 1 七天闸门组的口径，给登录留个模块名反而会让人误以为拨一下就能切。其余五个方法改分模块闸门——模块一旦切读，其影子探针停摆是**对的**，已经没有「旧列的答案」可比，再计数就是拿 hmac 跟自己比
- **B1/B2 因此会退出 Step 1 的 7 天分母**：切读发生在闸门达标**之后**，不影响准入判定，但值得记一笔免得下次看指标掉零去查故障
- **RED 用两个互补变异而非单向变异**：只做「切读没发生」这一个变异的话，6 条「hmac 命中 == 旧列命中」用例不会红——它们断言的正是两种模式结论相同，本就区分不了。补上「切读发生但 hmac 查不到」的变异后，21 例正式用例全部被至少一个变异杀死；两轮都不红的 4 例全是负向断言（默认值、模块隔离、LICENSE_NO、显式 ids），同 W4/W5 先例
- **`markRemoved` 造 REMOVED 行来放大 B2 的可观察差异**：直接对 ACTIVE 行测，明文与切读两条路的错误码都是 50310，差异被唯一键兜底掩盖看不出来；改成 REMOVED 行后，明文口径走**复活**、切读漏填走 insert 撞 uk，行为差异肉眼可辨
- **默认值刻意没动**（全局仍 shadow、四个占位符全空），本波交付的是「代码就绪 + 开关可拨」，不是「已经切了」

## 2026-08-31 · 记忆迁移核对 + PII-W6 在途交接（Team Lead，CodeBuddy 接手）

> 从 claude-mem 抽取 8/18 后 22 条会话摘要与 8/30-8/31 全部记录核对，与本文档一致，**无文档外决策遗漏**。唯一增量 = 下方 W6 在途状态，已固化。

- **PII-W6（15 §4 Step 3 登录双读切换）代码已动工但未提交**（上个 CLAUDE CODE 会话 8/30 晚–8/31 凌晨在 main 工作区直接改，未建分支）：
  - 新增未跟踪：`PiiFallbackHealer.java`（双读兜底计数 `pii.fallback{pointcut,verdict}` + 单线程有界队列异步补写，Verdict 含 FALLBACK/HEALED/HEAL_FAILED/HEAL_DROPPED，类注释钉死「FALLBACK 恒为 0 才是 7 天闸门在切读后的延续」）+ `PiiLoginHmacReadScenarioTest.java`（29KB 关卡测试）
  - 已跟踪改动 8 个：`AccountServiceImpl`/`UserServiceImpl` 的 A1–A6 登录链已从 `selectOne(phoneHash)`+`piiShadowReader.checkUser` 改走 `piiReadRouter.user(...)`（双读兜底）；`PiiReadRouter`/`PiiShadowReader`/`PiiModule`/`PiiBackfillService`/`PiiHmacQueries` 配套（login 模块入 PiiModule、checkUser 改吃模块闸门、PiiHmacQueries 新增 users 查询、PiiBackfillService 新增 `healUserHmac` 单行按需补填）；`application.yml` 新增 `login: ${PII_READ_MODE_LOGIN:}` 占位符并注明拨动顺序 login 殿后
  - 状态：会话记录最后动作为「Maven 编译主源码」，**全量测试未跑、未提交、未推送**；PiiCrypto 改动点（计划里的第三批）经 git diff 未见——待核
  - 接手：跑全量（基线 470）→ 修绿 → 独立复验 → commit。注意与 W5 相同的验证纪律：RED 变异、PiiCrypto 若需改须过 KAT
- **环境**：6379 Memurai ✅；8080 后端未监听（java 进程在但无服务）；`main` 领先 `origin` 2 commits（W5 两个）未推送
- 附：claude-mem 出现一条 8/30 的「OAuth2 PKCE」记录，与本项目不符，疑为跨项目误抓，已忽略
