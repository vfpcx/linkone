# 12 · P4「计费与结算」需求预研（Requirements Extract）

> 项目：仓储云 · P4 计费结算（路线图下一主线）
> 版本：v1 · 2026-08-04
> 编写：产品设计 Agent
> 需求源：`00-roadmap.md` P4 段、`product/02-user-stories.md`（US-TA-04/08、US-ST-01~06、US-WA-08、R10/R11/R12/R20、S1/S3/S7/S8）、`product/04-core-flows.md`（§1.4 Bill 状态机、§2.3 月度账单链路、§3.4 账单异常、§6.2/6.3 一致性）、`product/05-business-rules.md`（§1 计费公式、§5.4、§7.1、§11、§12、§13）、`product/06-page-wireframes.md`（§2.4 R20 确认、§4 ST 端线框）、`product/09-p3-arbitration-prd.md` §2.4（G10）、`product/11-p3b-prd.md`（计费边界）
> 现状快照：**2026-08-04 当日实测代码**（main，Flyway 最高 V23），逐项文件+行号，避免过期结论（沿 08/10 两次预研实测惯例）

---

## 0. 一句话结论

P3/P3b 铺的计费锚点（biz_time / reversal_of_id / pallet_delta / batch_id / 12 类流水）**已全部落库且口径可直接消费**；billing 域后端**零实现**（无模块、无表、无 Job），ST 角色**账号链路通、业务面为占位页**；架构侧 03-database-schema §7 六张表 + 04-api-spec ST/WA 端点已有设计稿可直接细化。**核查中发现一个活缺陷**：TA 店铺设置「计费维度+单价」前后端契约断裂，单价从未落库（见 §2.6），P4 第一波必须连带修复。

---

## 1. P4 范围提取

### 1.1 计费核心（仓储费）

| 项 | 口径 | 出处 |
|---|---|---|
| 计费维度 | **件·天 与 托盘·天**，可任选其一或并存（`件数×在库天数`、`占用托盘数×在库天数`） | 05 §1.1、US-TA-04 |
| 基准日 | 入库/盘盈=**次日 0:00 起算**；出库/退货/盘亏/清库=**当日截止**（表见 05 §1.2）；同日入出=0 件·天不收费 | 05 §1.2/1.6 |
| 粒度 | PRD 原文：批次启用按 (SKU,Batch)、关闭按 SKU——**与批次方案 C 实现冲突，P4 统一按 SKU 粒度出账**（见 ❓D-P4-1） | 05 §1.1 vs 10-p3b:183 |
| 快照 | DailySnapshot 每日 0 点，幂等（同日仅一份） | 00-roadmap、04 §6.3 |
| 月度聚合 | 月 1 日 0:00 自动生成 Bill/BillItem；应收=Σ每日费用+折扣/减免（负值）+冲销（反向项）；幂等键 (租户,批发商,月)；**应收 0 仍生成账单、直接已结清** | 05 §1.4、04 §2.3/§3.4/§6.3 |
| 规则变更 R20 | 只对未来生效，历史账单不重算，当月**分段计费**（变更日按新规则）；变更需 TA 二次确认 + 推送全部入驻 WA | 05 §1.5、02 R20、06 §2.4 |
| 时区 | 全部 UTC+8 自然日/自然月 | 05 §12 |

### 1.2 账单生命周期（ST 工作流）

- 状态机：`待核对 → 已下发 → 待回款 → 部分回款 → 已结清`，+`争议中`（R14 触发）；已下发 0 回款可撤回（R11）；已结清可经 R12 冲销回款回退（04 §1.4 全图）。
- 操作集：核对、调整（折扣/减免，US-ST-02）、冲销（R10：新增反向条目不删原条目）、下发（US-ST-03，站内信+短信）、撤回（R11）、已收款登记（US-ST-04，支持多次部分回款、拍照凭证）、已收款冲销（R12）、导出 PDF/Excel（US-ST-05，含印章位）。
- 一致性约束：已下发不能直接改金额，必须先 R11 撤回（04 §6.2）；已结清冲销必须先 R12 → 状态回退 → 再 R10（04 §3.4）。
- 账单号：`BL-{tenant简码}-{wholesaler简码}-{YYYYMM}`，月粒度、无日序列（05 §7.1，账单是统一单据号格式的唯一例外）。

### 1.3 对账与争议

- **WA 申诉（BillDispute）**：WA 收账单后对争议条目发起申诉，留痕，ST 基于申诉调整（US-WA-08、04 §2.3）。与 P3 仲裁是两套实体：P3 出库客诉**不改账单**（D43）；P3 入库仲裁的账面影响通过流水锚点在 P4 回溯自动体现（09 §2.4，见 §2.1）。
- **争议中账单（P3 埋的联动）**：R14 强制下架 → 未结账单标「争议中」（P2 只落了 wholesaler 状态位，账单侧 P4 落地）；「已下架→争议中→已退驻」的 OPS 仲裁闭环为 10-onboarding 遗留（建议仍留 P5，见 ❓D-P4-10）。
- **R13 退驻账单结清校验**：`BillingService.assertAllSettled` O-5 占位（错误码 50323 起预留段），P4 落地接上（10-onboarding-design §、05-error-codes:251）。

### 1.4 ST 角色启用

- 端能力：PC 账单列表/详情（按日/按 SKU 下钻）+ H5 核心操作（US-ST-06 要求全部 P0 操作手机可用，见 ❓D-P4-9）；线框已备：06 §4.1~4.3。
- 权限边界：ST **不能看库存明细**，只看聚合到账单的件·天/托盘·天（05 §5.4，账务与库存职责分离）；WE 对账单**整域拒绝**（WEM-S4-03 防回归用例已占位）。一人多岗操作日志 `actor_role=ST` 区分（S4）。
- TA 侧：账单总览（US-TA-08，应收/已收/未收按月汇总，下钻单 WA）。

### 1.5 支付边界（明确不做）

- **平台不接资金**（D03，2026-05 用户拍板；D25 盘亏赔偿线下协商）：无支付通道、无代收代付、无担保；回款=线下转账后 ST **手工登记**（可附凭证照片）。平台撮合费/代收代付明确排除（99 §缓冲区）。

### 1.6 顺带交付（test-plan 已挂账到 P4 的契约补口）

| 项 | 出处 |
|---|---|
| `/tenant/dashboard` 真端点（清 TA 工作台 mock V-2） | test-plan/10:85、11:105 |
| `GET /tenant/batch-config` 读端点（L-1） | test-plan/11:80 |
| SKU 名称展示统一（L-5） | test-plan/11:105 |

---

## 2. 计费锚点现状核查表（2026-08-04 实测）

汇总：五处锚点 **4 处 ✅ 可直接消费、1 处 ⚠️ 需 PRD 口径修订**；另发现 1 个活缺陷（§2.6）。流水类型全集 12 类见 `backend/src/main/java/com/cangchu/inventory/entity/StockMovement.java:18-47`（每类常量注释即计费口径，可作 P4 计费引擎的类型字典直接引用）。

### 2.1 入库流水 biz_time（P3 D39 异议冲销截止 / 仲裁恢复原时间戳）✅

| 证据 | 文件:行 | 实测口径 |
|---|---|---|
| 列定义 | `backend/src/main/resources/db/migration/V15__p3_stock_movement_ext.sql:13-18` | `biz_time` 计费语义时间锚点；**存量已回填 =created_at**（V15 前入库/出库口径不变） |
| 实体 | `StockMovement.java:76`（bizTime）、`:80`（reversalOfId） | 默认=created_at；DISPUTE_RESTORE=原入库时间戳（G10） |
| 异议冲销 | `inventory/service/impl/InventoryServiceImpl.java:295` | `DISPUTE_REVERSAL` biz_time=异议时刻（当日截止，D39）✅ |
| 仲裁恢复 | 同文件 `:361-362` | `DISPUTE_RESTORE` biz_time=`ctx.getOriginalInboundAt()`（原入库时间戳），reversal_of_id 回指配对冲销流水 ✅ → 回溯效果=原入库次日 0:00 连续计费、争议期成对抵消（09 §2.4） |
| 出库回补 | 同文件 `:234-243` | `OUTBOUND_REVERSAL` biz_time=原 OUTBOUND 锚点、reversal_of_id 必填 → P4 配对抵消「视同从未出库」✅ |
| 纠错 | 同文件 `:444/:468` | `CORRECTION_IN/OUT` biz_time=原 INBOUND biz_time（D-4，与 RESTORE 同构） |

**两条给 P4 的提醒**：
1. 09 PRD §2.4 写的独立标注字段 `billing_anchor=ORIGINAL_INBOUND_AT` / `restored_from_inbound_at` **未按字面落库**——实现收敛为 `biz_time + reversal_of_id` 承载同一语义（12-p3-design §定稿）。P4 PRD/开发引用**以实现为准**，勿按 09 字段名找列。
2. 防御缺口（08-p3-review-w1:99）：`disputeRestore` 找不到配对 DISPUTE_REVERSAL 时 reversal_of_id=null 照常入账（正常流程不可达）→ P4 配对抵消算法需容忍 reversal_of_id=null 的 RESTORE（按 biz_time 直接计入，不 crash）。

### 2.2 盘盈盘亏「当日截止/次日起算」（T3-W2 文案）✅

| 证据 | 文件:行 | 实测口径 |
|---|---|---|
| 流水常量 | `StockMovement.java:39-42` | `GAIN`(+) biz_time=审批通过日，次日起算视同当日入库；`LOSS`(−) biz_time=审批通过日计费当日截止，且 **D-10 封顶** qty=min(\|diff\|, 审批时刻在库) |
| 写入 | `InventoryServiceImpl.java:640`（GAIN）、`:689`（LOSS） | biz_time=审批事务时刻（=审批通过日）✅；LOSS 同时 pallet_delta=−released |
| 用户文案 | `frontend/apps/admin/src/views/ta/Stocktake.vue:1158` | 「盘亏当日截止 / 盘盈次日起算计费」已明示 ✅（同类文案：Inbound.vue:1418「计费自次日 0:00 起算」、Clearance.vue:442「仓储费当日截止、不计正常出库统计」） |
| P4 依赖 | 11-p3b-prd:367 | 月度账单**盘盈/盘亏明细行** P4 交付（02 US-WK-03「月度账单包含本月盘盈/盘亏计费影响明细」） |

**注意 LOSS 封顶副作用**：盘点差异 −N 被在库封顶后 applied<N 时，账面与 PRD「盘亏件按盘点日截止」件数不等——P4 按**流水 qty（applied）**计，不按盘点单差异原值。

### 2.3 批次计费语义字段（09 拍板 G10 / 方案 C）⚠️ 需口径修订

| 证据 | 文件:行 | 实测口径 |
|---|---|---|
| batch_id 列 | `V22__p3b_batches.sql:40-42`、`StockMovement.java:93-98` | **仅 INBOUND / EXPIRY_CLEARANCE / CORRECTION_IN/OUT 落值，出库类流水恒 NULL**（方案 C：出库按 SKU 池扣减不感知批次） |
| 清库流水 | `StockMovement.java:44-47`、`InventoryServiceImpl.java:749-751` | `EXPIRY_CLEARANCE` biz_time=清库日当日截止、batch_id 落值、**不计正常出库统计（P4 按 type 区分）** |
| 批次登记簿 | `V22:12-33` | `batches.remaining_qty` 为 02:00 **离线 FIFO 推算值（非记账值）** |
| 已预标注 | `product/10-p3b-requirements.md:183`、`architecture/13-p3b-design.md:406` | 「批次级计费下钻随方案 A 顺延，**P4 按 SKU 粒度出账**——需在 P4 账单 PRD 标注」 |

**结论**：05 §1.1「批次启用按 (SKU,Batch) 计费、可下钻批次明细」与实现**不可行**（出库不落批次，无法闭合批次级在库天数）。P4 必须按 SKU 粒度出账；账单详情「按批次」下钻（06 §4.2 线框第 892 行）降级为推算展示或移除 → ❓D-P4-1。

### 2.4 托盘 pallet_delta 逐笔账（D-8=A，为托盘·天铺的）✅ 带存量边界

| 证据 | 文件:行 | 实测口径 |
|---|---|---|
| 列+口径 | `StockMovement.java:85-90`、V20 | `inventories.pallet_qty ≡ Σ pallet_delta`（05 §3.4 可对账）；DISPUTE_*/CORRECTION_* 列+remark 双写，读侧列优先 |
| 入库 | `InventoryServiceImpl.java:107/:129` | INBOUND pallet_delta=+palletQty |
| 出库释放 | 同文件 `:592-593` | `PALLET_RELEASE` **qty=0 恒定**（不进件数公式）、pallet_delta=−n、登记出库时写 → P4 托盘·天=Σpallet_delta，对账脚本须知晓该 type 无件数语义（13 §402 已标注） |
| 退货/盘亏/清库 | `:532 / :690 / :750` | 均 −released（比例+在库封顶） |
| **存量边界** | 13-p3b-design §2.4-4 | **V20 之前流水 pallet_delta 恒 0、不回填**——历史托盘账「只增不减」既成虚高，约定由 T3 上线后**首次盘点校准**收口 → P4 托盘·天基线起点须拍板（❓D-P4-5） |

### 2.5 ST 结算员角色 ⚠️ 账号链路通、业务面零实现

| 证据 | 文件:行 | 实测现状 |
|---|---|---|
| 注册白名单 | `account/service/impl/AccountServiceImpl.java:75` | ST 在合法角色集 |
| TA 生码 | `tenant/service/impl/TenantServiceImpl.java:381` | 员工注册码角色白名单 WK/ST ✅（US-TA-03 链路可用） |
| 登录落点 | `AccountServiceImpl.java:694` | home=`/st/dashboard` |
| 前端 | `frontend/apps/admin/src/router/index.ts:209-215` | `/st/dashboard` → **PlaceholderDashboard.vue 占位**（注释「后续 Agent 实现」），无任何 ST 业务页 |
| 后端 | `backend/src/main/java/com/cangchu/` 模块清单 | account/common/document/inventory/notify/pricing/product/storefront/tenant——**无 billing 模块、无 bills/bill_items/daily_snapshots/billing_rules 任何表**（Flyway V1–V23 无一涉账单） |

即：ST 可注册、可登录、落在占位工作台；P4 是其业务面从 0 到 1。

### 2.6 【核查新发现·活缺陷】US-TA-04 计费规则设置前后端契约断裂

| 证据 | 文件:行 | 问题 |
|---|---|---|
| 后端存储 | `V2__init_tenant.sql:55`、`TenantSettings.java:27` | 仅 `billing_dim VARCHAR(16) DEFAULT 'QTY'`——**无单价列、无变更历史** |
| 后端契约 | `tenant/dto/StoreSettingsDto.java:37`、`tenant/vo/TenantDetailVo.java:40` | PUT/GET `/api/v1/tenant/me` 只有 `billingDim` 字符串 |
| 前端契约 | `frontend/packages/api-types/src/tenant.ts:71-74`、`views/ta/Settings.vue:157-160/242-245/295-298` | 发送/读取 `billingByQty/billingByPallet/pricePerQtyDay/pricePerPalletDay`——**后端全无这些字段**：单价被静默丢弃；回显读不到 billingDim → 计费区块回显恒 false，R20 变更检测（Settings.vue:266-275）比对的是幽灵字段 |

**后果**：US-TA-04（P0）实际未闭环——TA 从未真正保存过单价；P4 计费引擎无价可用。**必须在 P4 第一波以 billing_rules 版本表 + Settings 契约重构一并修复**（❓D-P4-4）。该缺陷此前未见于 test-plan 缺陷清单（03-defect-findings 无记录），建议测试&审查 Agent 补录。

### 2.7 周边基建现状（P4 直接复用/对接）

| 基建 | 现状 | 证据 |
|---|---|---|
| 调度 | `SchedulingConfig` 已建，4 job 错峰（00:05/10min 72h 确认、02:00 FIFO 推算、02:30 临期、03:40 归档）；**每日 0 点/月 1 日 0 点档期空闲**；单实例假设，多副本前统一上 ShedLock（12 §8.4） | `common/config/SchedulingConfig.java:10-18` |
| 单据号 | `DocumentNumberService.generate`（前缀+tenant简码+日序列）；账单 BL- 为月粒度**需新格式分支**；wholesaler **无简码字段**（仲裁单曾用 `"T"+id` 兜底先例，`ArbitrationServiceImpl.java:86-92`） | ❓D-P4-7 |
| 站内信 | notify 模块 + V17 notifications 已上线（仲裁双向通知在用）；04 §4 通知矩阵已列全账单 7 类触发 | `com/cangchu/notify` |
| 错误码 | **50323 起整段预留 P4 billing**（Wave3 占掉 50319-50322 后顺延） | `architecture/05-error-codes.md:238/251` |
| 架构设计稿 | §7 计费域 6 表全套：billing_rules（版本化 effective_from/to、含 per-WA 与保底费扩展位）、daily_snapshots（uk 租户+WA+日）、bills（幂等键+状态枚举）、bill_items（reverse_of_item_id 冲销链）、payment_records（EFFECTIVE/REVERSED）、bill_disputes | `architecture/03-database-schema.sql:896-1041` |
| API 设计稿 | ST 端 12 个端点 + WA 端 2 个 + TA bills-overview | `architecture/04-api-spec.md:388-400/435-436/363` |
| R13/R14 占位 | precheck 返回 `billing:{cleared:null}` 灰态、退驻自查第 3 行「本期不校验」、forceOffline TODO | `architecture/10-onboarding-design.md:177/194/219` |

---

## 3. 产品缺口与 ❓DECISION 清单

> 按 2026-07-25 用户执行准则：均给推荐方案，Team Lead 可按推荐代拍并记录；仅 D-P4-9 涉及需求优先级取舍，建议汇总确认时点一下。

| # | 决策点 | 选项 | 推荐（含依据） |
|---|---|---|---|
| ❓D-P4-1 | **计费粒度**：05 §1.1 批次级 vs 方案 C 出库不落批次 | A. 按 SKU 粒度出账，账单「按批次」下钻移除/降级为推算展示；B. 强行批次级（需推翻方案 C） | **A**。10-p3b:183 已预标注；B 违背 D-11=C 拍板。随本决策修订 05 §1.1 与 06 §4.2 线框注 |
| ❓D-P4-2 | **计费引擎口径**：快照驱动 vs 流水回放 | A. DailySnapshot 仅作缓存/对账留痕，月度账单生成时**按流水 biz_time 回放为准**；B. 纯快照累加 | **A**。仲裁恢复/纠错流水锚点在过去（biz_time<快照日），纯快照无法回溯；已出账历史月不重算（R20 严肃性同构），跨月差额以 ADJUSTMENT 条目入当月账 |
| ❓D-P4-3 | **规则模型范围** | A. 最小版：租户级规则+版本化（effective_from），件·天/托盘·天两单价；B. 启用架构表全部能力（per-WA 个性价、保底费、阶梯 JSON） | **A**。US-TA-04 只要求租户级；per-WA/保底/阶梯架构表留位不启用 → P5 |
| ❓D-P4-4 | **billing_dim 迁移 + §2.6 缺陷修复** | — | billing_rules 上线后 `tenant_settings.billing_dim` 转只读镜像（storefront 等读侧不动）；Settings.vue 计费区块改接规则 API（补 US-TA-04 闭环 + R20 二次确认真实生效）；首版规则 effective_from=TA 首次保存日，**上线前历史月份不补出账** |
| ❓D-P4-5 | **托盘·天基线**：V20 前 pallet_delta=0 虚高 | A. 托盘·天自规则 effective_from 起按 Σpallet_delta 现值计，上线 checklist 提示 TA 先完成一次盘点校准；B. 回填历史 | **A**。13 §2.4-4 明拒回填（污染基线）；件·天不受影响（qty 全量准确） |
| ❓D-P4-6 | **账单状态机命名对齐**：PRD 6 中文态 vs 架构枚举缺「待回款」 | — | 架构补 `PENDING_PAYMENT`（或定稿映射表），「已下发→待回款：WA 确认或 1 日后自动」用 confirmed_at+job 实现；PRD 6 态语义不变，界面全中文（07-24 文案铁律） |
| ❓D-P4-7 | **账单号 wholesaler 简码**：BL-{tenant}-{WS}-{YYYYMM}，wholesalers 无简码列 | A. 兜底 `W{wholesalerId}`；B. 新增简码列 | **A**（与仲裁单 `"T"+id` 兜底同构，零迁移）；月粒度无日序列，DocumentNumberService 加月度分支 + uk_bill_no 兜底 |
| ❓D-P4-8 | **导出 PDF/Excel 落地形态**：OSS 是 P5 | A. 同步生成流式下载（不落存储，天然无 7 天清理问题）；B. 先建文件存储 | **A**。US-ST-05 的印章位/明细行模板照做；H5「保存/分享微信」= 浏览器下载即可 |
| ❓D-P4-9 | **ST H5（US-ST-06 全 P0 操作手机可用）** | A. P4 做 PC 全功能 + 账单列表/详情/回款登记三页响应式适配；B. 独立 H5 端 | **A**。admin 为 PC Element Plus 栈，独立 H5 与 P5「正式多端」合并更经济；US-ST-06 验收降档为「核心三操作移动可用」——此项动了 P0 验收口径，**建议汇总确认** |
| ❓D-P4-10 | **R13/R14 联动补齐范围** | — | 本期做：assertAllSettled 接入（50323 段）+ R14 未结账单标 DISPUTED + FOF-S1-04 由状态位转真实流转；不做：争议中账单 OPS 仲裁闭环（10-onboarding 遗留 → P5，属客诉流程非计费核心） |

**其余已明确、无需拍板的执行要点**：账单 7 类通知按 04 §4 矩阵接 notify（站内信真发、短信沿 mock）；WE 账单整域拒绝 + ST 不可见库存明细（05 §5.4）进权限测试用例；操作日志 actor_role=ST；应收 0 账单直接已结清；LOSS 封顶后按流水 qty 计（§2.2 注意项）；DISPUTE_RESTORE reversal_of_id=null 容错（§2.1 提醒 2）；EXPIRY_CLEARANCE/PALLET_RELEASE 的 type 特判（不计出库统计 / 无件数语义）。

---

## 4. 建议波次切分（P4 · 预计 5 波 + 定稿波）

| 波 | 内容 | 关键依赖/备注 |
|---|---|---|
| **W0 定稿** | 本文档 DECISION 拍板 → P4 账单 PRD（13-p4-billing-prd，含 05 §1.1 粒度修订、账单状态中文文案表）→ 架构 14-p4-design（V24 起迁移、50323 错误码段、快照/回放口径、状态枚举定稿） | 产品+架构，无编码 |
| **W1 规则与契约修复** | billing_rules 表+版本化 API（R20 变更=关旧版开新版+二次确认+通知全 WA）；**修复 §2.6 活缺陷**：Settings.vue 计费区块接新 API、单价落库、billing_dim 迁移 | 先修地基——后续引擎有价可用；含 Java 接口测试 |
| **W2 计费引擎+快照** | DailySnapshotJob（每日 0 点档，ShedLock 评估随 12 §8.4 一并）；流水回放引擎：12 类 type×biz_time×pallet_delta 消费、配对抵消（reversal_of_id）、分段计费（R20）、SKU 粒度聚合 | 纯后端；以 05 §1.6 五个边界示例 + 仲裁恢复连续计费 + 盘盈亏明细行作为接口测试金标准 |
| **W3 账单生命周期** | 月 1 日生成 Job（幂等键）+ BL- 单据号 + 状态机 + 调整/冲销(R10)/下发/撤回(R11)/回款登记/回款冲销(R12) + WA 申诉(BillDispute) + R13 assertAllSettled/R14 DISPUTED 联动 + 7 类通知 | 依赖 W2；含 WE 整域拒绝/ST 库存隔离安全用例（05-secure-coding 自检） |
| **W4 前端** | ST 工作台（列表/详情下钻按日/按 SKU/调整/回款/导出入口）替换占位页 + TA 账单总览(US-TA-08) + WA 账单页与申诉 + 三核心页响应式(D-P4-9A)；界面全中文 | Playwright E2E 随本波（含视觉 QA 截图自检）；线框直接复用 06 §4 |
| **W5 导出+补口+回归** | PDF/Excel 流式导出（印章位模板）+ `/tenant/dashboard` 真端点(清 V-2 mock) + `GET /tenant/batch-config`(L-1) + SKU 名称统一(L-5) + 全量回归绿 + 交付报告 | test-plan/11:105「一次契约补口清三项」按计划兑现 |

依赖链：W1 → W2 → W3 → W4；W5 与 W4 可部分并行。每波按惯例：单切片 Agent、接口测试当波绿、合并 main 重跑回归。

---

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-08-04 | 首版：P4 范围提取 + 五锚点当日实测核查（全通过，1 处口径修订）+ 发现 US-TA-04 契约断裂活缺陷 + 10 项 DECISION 推荐 + 6 波切分 |
