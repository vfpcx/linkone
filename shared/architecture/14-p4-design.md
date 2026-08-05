# 14 · P4「计费与结算」架构设计（W0 定稿）

> 项目：仓储云 · 编写：架构师 Agent · 2026-08-05
> 真源：`product/12-p4-requirements.md` v1（**10 项 DECISION 拍板记录全量生效**：D-P4-1=A SKU 粒度 / D-P4-2=A 快照缓存+流水回放为准 / D-P4-3=A 最小规则模型 / D-P4-4 契约修复+不补历史 / D-P4-5=A 托盘基线 / D-P4-6 补 PENDING_PAYMENT / D-P4-7=A WS 简码兜底 / D-P4-8=A 同步流式导出 / D-P4-9=A ST 三核心页响应式 / D-P4-10 联动三项）
> 输入：`product/04-core-flows.md` §1.4/§2.3/§3.4/§4/§5/§6、`product/05-business-rules.md` §1/§5.4/§7.1/§12、`13-p3b-design.md`（DocStateMachine/封顶家族/SchedulingConfig cron 表/据实现备注惯例）、`03-database-schema.sql` §7 蓝图（仅参考，落地以本文档为准）、`10-onboarding-design.md` §13/§14（O-5 占位）
> 风格：据实现编写。**所有「实测」标注均经 2026-08-05 对 main 当日代码核对**。
> 范围：billing 域从 0 到 1（规则/快照/引擎/账单/回款/申诉）+ ST 角色业务面 + W1 契约修复 + R13/R14 联动。**平台不接资金（D03）**：无支付通道，回款=线下转账后 ST 手工登记。

---

## 0. 开工实测快照（2026-08-05，main）

| # | 事实 | 结论 |
|---|---|---|
| B1 | Flyway 最高 **V23**（`V23__p3b_clearance.sql`），无在途分支占用更高号 | **P4 迁移自 V24 起**（V24/V25/V26 归 W1/W2/W3，见 §4） |
| B2 | ErrorCode 枚举实占至 **50367**（`ErrorCode.java:207`）；50357–50359/50368–50369 为 T3/T4 预留不挪用；50343–50349 为 P3 缓冲不挪用；05-error-codes 勘误注明示「P4 账单状态码改用其它空段」（蓝图 STATE_BILL 50301–50304 与实占 50300–50306 重叠作废） | P4 段 = **50323**（O-5 承诺位，R13 账单校验）+ **50370–50389**（新分配段），50324–50329 维持预留缓冲；已登记 05-error-codes.md P4 段 |
| B3 | 全仓 cron 实测（SchedulingConfig 注释占位表 + @Scheduled 扫描）：分钟位实占 {5,15,25,35,45,55}（InboundAutoConfirmJob 全天）、02:00、02:30、03:40 | 新任务错峰选位：**00:10 DailySnapshotJob、00:50 BillAutoConfirmJob、每月 1 日 01:20 MonthlyBillJob**（均为空闲分钟位） |
| B4 | `DocType` 无 BILL 前缀；`DocumentNumberServiceImpl` 仅「前缀+tenant简码+yyyyMMdd+4 位日序列」一种格式（Redis 日计数） | DocType 扩 `BILL("BL")`；DocumentNumberService 扩**月度分支**（无序列无 Redis 计数，§3.4） |
| B5 | `wholesalers` 表**无简码列**（V4 实测无 simple_code）；仲裁单简码兜底先例 `"T"+tenantId`（`ArbitrationServiceImpl.java:86-88`） | D-P4-7=A：账单号 WS 段 = **`W{wholesalerId}`** 兜底，零迁移 |
| B6 | §2.6 活缺陷当日复核属实：`tenant_settings` 仅 `billing_dim VARCHAR(16) DEFAULT 'QTY'`（V2:55）；`StoreSettingsDto.java:37` / `TenantDetailVo.java:40` 仅 billingDim 字符串；`Settings.vue:157-160/242-245/295-298` 发送/回显 `billingByQty/billingByPallet/pricePerQtyDay/pricePerPalletDay` 幽灵字段，单价被静默丢弃、R20 变更检测比对幽灵字段 | W1 以 billing_rules + 新 API 修复（§2.3），`billing_dim` 转只读镜像 |
| B7 | `DocStateMachine` 现 5 张矩阵（OUTBOUND/INBOUND/RETURN/STOCKTAKE/CLEARANCE），50330/50331 引擎语义稳定 | 扩第 6 张 **`DocKind.BILL`** 矩阵（§3.1），不可达/CAS 复用 50330/50331 |
| B8 | notify 模块 REF_* 现 8 类（Notification.java:76-88），type VARCHAR(32) 零 DDL 扩展先例 | 扩 `REF_BILL`/`REF_PAYMENT`/`REF_BILL_DISPUTE` 跳转引用 + 8 类账单通知（§3.6） |
| B9 | 10-onboarding 三处占位实测在位：precheck `billing:{cleared:null}` 灰态、R13 第 3 项「本期不校验」TODO、forceOffline「未结账单转争议中」TODO | 本期全部兑现（D-P4-10 三项，§3.5）；争议账单 OPS 仲裁闭环留 P5 |
| B10 | 后端测试基线 **337 绿**（T4-W2 收口值）；`/st/dashboard` → PlaceholderDashboard 占位（router/index.ts:209-215）；backend 无 billing 模块、无任何账单表 | 影响面清单见 §8；ST 业务面从 0 到 1 |
| B11 | 流水 12 类 type 常量+计费口径注释齐备（StockMovement.java:18-47）；biz_time/reversal_of_id/pallet_delta/batch_id 四锚点列在位 | 计费引擎类型字典直接引用，**零 DDL、零流水侧改动** |

---

## 1. 计费引擎（W2）——D-P4-2=A：快照作缓存，流水回放为准

### 1.1 统一回放公式（引擎核心不变量）

按 (wholesaler, sku) 回放，全部 12 类流水**一条规则通吃**（无逐类型 if 分支）：

```
signed(m) = +qty  if type ∈ {INBOUND, GAIN, DISPUTE_RESTORE, CORRECTION_IN, OUTBOUND_REVERSAL}
          = −qty  if type ∈ {OUTBOUND, RETURN, LOSS, DISPUTE_REVERSAL, CORRECTION_OUT, EXPIRY_CLEARANCE}
          =  0    if type = PALLET_RELEASE                      // qty 恒 0，天然无影响

bizDate(m) = biz_time 所在 UTC+8 自然日（05 §12）

计费在库量 billableQty(D)    = max( Σ signed(m)      where bizDate(m) ≤ D−1, 0 )
计费托盘量 billablePallet(D) = max( Σ pallet_delta(m) where bizDate(m) ≤ D−1, 0 )

当日费用(D) = billableQty(D)×件·天单价[规则段(D)]×qtyEnabled
            + billablePallet(D)×托盘·天单价[规则段(D)]×palletEnabled
```

**「≤ D−1」一式实现全部基准日口径**（05 §1.2 逐行验证）：入库/盘盈/仲裁恢复/纠错补录次日 0:00 起算 ✅；出库/退货/盘亏/清库/异议冲销/纠错冲销当日截止（当日仍计、次日不计）✅；同日入出 0 件·天 ✅；DISPUTE_REVERSAL(biz_time=异议日)+DISPUTE_RESTORE(biz_time=原入库时间戳) 配对自动呈现「原入库次日起连续计费、争议期成对抵消」（09 §2.4 语义），**无需按 reversal_of_id 特判**——RESTORE 的 reversal_of_id=null 防御缺口（12-p4 §2.1 提醒 2）在本公式下天然容错（按 biz_time 直接计入，不 crash，测试须有正例）。

实现约束：
- 引擎为**纯函数**（输入=流水列表+规则段，输出=逐日/逐段聚合），供 Job 与测试直驱（BatchService.recalcAll 先例）；月内逐日用前缀和，O(流水数+天数×规则段数)。
- LOSS 按流水 qty（封顶后 applied）计，不按盘点单差异原值（12-p4 §2.2 注意项）。
- EXPIRY_CLEARANCE/PALLET_RELEASE 的 type 特判仅存在于**统计口径**（不计正常出库统计/无件数语义），计费公式本身无特判。
- 跨域访问（G-S1）：billing 域经 inventory 域新增只读出口 `InventoryService.listMovementsForBilling(tenantId, wholesalerId, untilExclusive)` 取锚点字段（type/qty/biz_time/pallet_delta/sku_id），不直连 StockMovementMapper。

### 1.2 daily_snapshots：缓存 / 下钻 / 对账三用途（非记账真源）

- **DailySnapshotJob**（`0 10 0 * * ?`，每日 00:10）：对每个存在流水的 (wholesaler, sku) 以 §1.1 公式算 `bizDate ≤ 昨日` 的净值，落快照行（快照日=昨日；qty 与 pallet 均 0 的不落行，缺行即 0）。**快照=回放的逐日物化**，纯函数天然幂等、漏跑可补算任意历史日（Job 启动时回补缺口 ≤7 日）。
- **不存金额**（蓝图 daily_fee 列裁掉）：金额只在出账时按规则段现算，避免规则当日变更后快照金额失真——快照只存物理量（qty/pallet_qty）。
- 用途：① ST 账单详情「按日下钻」直接读快照（免重放）；② 对账留痕；③ Job 末步**全量不变量对账**：`回放(至今) == inventories.qty` 且 `Σpallet_delta == inventories.pallet_qty`，不一致记 ERROR 日志（含 ws/sku 与两侧值）——流水被绕改/时钟漂移的哨兵。
- 快照与规则解耦：无规则租户照常快照（物理量与价无关），TA 首次设置规则后历史天数**不出账**（D-P4-4），但下钻可见物理量。

### 1.3 粒度与分段（D-P4-1=A / R20）

- **SKU 粒度出账**：账单 STORAGE 明细行 = (SKU × 规则段)；「按批次」下钻不做（06 §4.2 线框第 892 行注记移除，产品 PRD 13-p4 随 W0 修订 05 §1.1）。batch_id 锚点不消费。
- **R20 分段**：月内规则变更 → 同 SKU 拆两行 STORAGE（每段独立 period_start/period_end/单价），变更日按新规则（effective_from 当日起，§2.1）。历史已出账月**永不重算**。
- **计费起点**：账单期间 = [max(月初, 首版规则 effective_from), 月末]；首版前天数不出账、上线前历史月份不补出账（D-P4-4）。

### 1.4 托盘·天基线（D-P4-5=A）

`billablePallet` 直接 Σpallet_delta：V20 前存量流水 pallet_delta 恒 0（13 §2.4-4 明拒回填）⇒ 托盘·天自然以「规则生效时的 Σpallet_delta 现值」为基线。**上线 checklist（W4 交付纪要）**：提示 TA 启用托盘·天计费前先完成一次盘点托盘校准（13 §2.4-4 既定收口手段）；件·天不受影响（qty 全量准确）。

### 1.5 跨月锚点差额 → ADJUSTMENT 入当月账（D-P4-2=A 后半）

仲裁恢复（DISPUTE_RESTORE）/纠错（CORRECTION_IN/OUT）的 biz_time 可落在**已出账历史月**。口径：

1. 月度出账时扫描「created_at ∈ 本期 ∧ bizDate < 本期期初」的流水（锚点在过去的新流水）；
2. 对每个受影响历史月 M'：若该 (t,ws,M') **已有账单** → 影子重算 `replay(M', 含新流水) − 原 STORAGE 小计`，差额（可正可负）以 `ADJUSTMENT` 条目入**当月**账，description 注明「{M'} 仲裁恢复/纠错回溯差额」；原历史账单一字不动（R20 严肃性同构）；
3. 若 M' 无账单（规则前/未出账）→ 不补（D-P4-4）。

### 1.6 引擎接口测试金标准（W2 合并闸门，逐例断言）

| # | 用例 | 期望 |
|---|---|---|
| G1–G5 | 05 §1.6 五个边界示例原样造数 | 100 / 0 / 110 / (100+7×N) / 盘盈自次日 件·天逐一相等 |
| G6 | 入库→异议冲销（D+3）→仲裁恢复（RESTORE biz_time=原入库） | 与「从未异议」全月件·天相等（争议期抵消） |
| G7 | RESTORE reversal_of_id=null（防御缺口正例） | 照常计入，不抛异常 |
| G8 | 盘亏封顶 applied<原差异 | 按流水 qty 计（12-p4 §2.2） |
| G9 | R20 月中变更（分段+变更日新价） | 两段金额、变更日归新段 |
| G10 | PALLET_RELEASE / 清库 / 退货托盘链 | 件·天不受 PALLET_RELEASE 影响；托盘·天按 Σdelta；V20 前存量 0 不参与 |
| G11 | 快照幂等重跑 / 漏跑回补 | 行级相等；对账哨兵触发用例（人工造不一致） |
| G12 | 跨月 RESTORE → ADJUSTMENT 差额 | 历史账单不变，当月出现差额条目，金额=影子重算差 |

---

## 2. billing_rules 与 W1 契约修复（D-P4-3=A / D-P4-4）

### 2.1 表与版本化行为（V24，§4 详表）

最小模型（D-P4-3=A）：**租户级单一有效规则 + effective_from 版本链**；两维度可并存（各自开关+单价）。蓝图偏差：蓝图单行 `billing_dim+unit_price` 装不下「并存」语义，改为**双维四列**（qty_enabled/price_per_qty_day/pallet_enabled/price_per_pallet_day）；`wholesaler_id/min_charge/extra_rule_json` 三列**留位不启用**（P4 恒 NULL/0，per-WA 个性价、保底、阶梯 → P5）。

R20 变更事务（单事务）：
1. 校验：至少一维启用且启用维单价非空 ≥0（违者 50379）；真实翻转需 `confirmed=true` 凭据（缺失 40003，batch-toggle 先例）；与当前规则完全相同 → 幂等空转（不计版本、不通知）。
2. 当日已有版本行（effective_from=今日）→ **覆写该行**（同日多次变更最后一次生效，uk 保证一日一版）；否则当前行（effective_to IS NULL）CAS 置 `effective_to=今日−1`，插入新行 `effective_from=今日, version=旧+1`。**变更日按新规则计**（05 §1.5）。
3. 同事务镜像 `tenant_settings.billing_dim`：QTY / PALLET / **BOTH**（新枚举值，VARCHAR(16) 容得下；读侧 storefront/TenantDetailVo 零改动，前端展示映射随 W1 补，§8）。
4. 通知**全部入驻 WA**（ACTIVE 商户，`BILLING_RULE_CHANGED` 站内信；05 §1.5「推送全部入驻 WA」）。
5. 首版（无任何规则行）：effective_from=**首次保存日**、version=1、无需 confirmed（无变更即无 R20）；历史不补出账。

### 2.2 API（W1）

| 方法/路径 | 角色 | 说明 |
|---|---|---|
| GET `/api/v1/tenant/billing-rules` | TA/ST | `{current: RuleVo\|null, history: [RuleVo]}`；RuleVo 含 qtyEnabled/pricePerQtyDay/palletEnabled/pricePerPalletDay/effectiveFrom/effectiveTo/version |
| POST `/api/v1/tenant/billing-rules` | TA | R20 变更：`{billingByQty, pricePerQtyDay?, billingByPallet, pricePerPalletDay?, confirmed?}`（§2.1 事务） |

蓝图偏差：04-api-spec 4.5 将 billing-rules 挂 ST 下——按 US-TA-04/06 §2.4（R20 二次确认为 TA 交互）**归 TA 专属写**；ST 只读复用 GET（核对账单时查版本）。

### 2.3 契约断裂修复（§2.6 活缺陷，W1 强制项）

| 触点 | 改动 |
|---|---|
| `Settings.vue` 计费区块 | 改接 §2.2 新 API：回显读 `current` 规则（无规则 → 空表单引导首次设置）；保存独立提交 POST billing-rules（不再混入 PUT /tenant/me）；R20 变更检测（billingChanged）比对**当前规则真实字段**；二次确认弹窗透传 confirmed=true |
| `frontend/packages/api-types` | tenant.ts 删除 4 个幽灵字段（billingByQty/billingByPallet/pricePerQtyDay/pricePerPalletDay）；新增 billing.ts 规则类型 |
| `StoreSettingsDto.billingDim` | **废弃**：后端收到即忽略并 log warn（不报错，兼容旧客户端）；`tenant_settings.billing_dim` 只由规则保存事务镜像写入 |
| `TenantDetailVo.billingDim` | 保留（读镜像），值域扩 BOTH |
| 测试 | TenantService 设置用例若断言 billingDim 可写 → 改断言忽略；新增规则 CRUD/版本链/镜像/幂等/40003/50379 接口测试 |

缺陷补录：该断裂请测试&审查 Agent 录入 03-defect-findings（12-p4 §2.6 已提）。

### 2.4 权限

写=requireTaRole；读=requireStOrTa（billing 域新 gate，`requireWkOrTa` 同构先例 OutboundRequestServiceImpl:663）。WE/WK/WA 均拒（42004/42001）。

---

## 3. 账单生命周期（W3）

### 3.1 状态机（PRD 6 态 + D-P4-6 补 PENDING_PAYMENT，DocKind.BILL 矩阵）

| 枚举 | 中文（界面唯一话术，07-24 文案铁律） | 说明 |
|---|---|---|
| DRAFT | 待核对 | 月初生成落点；唯一可调整/冲销态 |
| DISPATCHED | 已下发 | dispatch_at 落值；0 回款且未确认可 R11 撤回 |
| PENDING_PAYMENT | 待回款 | WA 确认 或 下发满 1 日自动（confirmed_at 落值） |
| PARTIAL_PAID | 部分回款 | 0 < paid < total |
| PAID | 已结清 | paid=total；应收 0 账单生成即此态 |
| DISPUTED | 争议中 | R14 联动位；P4 冻结全部写操作（50381），OPS 闭环 P5 |

蓝图偏差：`GENERATING`（生成在 Job 单事务内完成，无中间态可见）与 `CANCELLED`（无作废需求）两态**不落地**。

```
DRAFT → DISPATCHED（下发）
DISPATCHED → DRAFT（R11 撤回：paid_amount=0 ∧ confirmed_at IS NULL，否则 50372）
           | PENDING_PAYMENT（WA 确认 / 00:50 Job 满 1 日自动）
           | DISPUTED（R14）
PENDING_PAYMENT → PARTIAL_PAID | PAID | DISPUTED
PARTIAL_PAID → PAID | PENDING_PAYMENT（R12 全额冲回）| DISPUTED
PAID → PARTIAL_PAID | PENDING_PAYMENT（R12：冲后 paid>0 / =0）
DISPUTED → ∅（P4 无出边，P5 OPS 仲裁闭环解冻）
```

- 入 `DocStateMachine.BILL_TRANSITIONS`（第 6 矩阵）；不可达 50330、CAS 并发 50331（引擎复用）。重复回款登记不是状态迁移（PARTIAL_PAID 停留），与出库补打同构。
- 偏差注明：R14 边在 PRD 04 §1.4 仅画「待回款/部分回款→争议中」，本设计**扩 DISPATCHED→DISPUTED**（已下发未确认同属未结，冻结更安全）；DRAFT 不标（未对外），但**生成时商户已 OFFLINE → 生成后直落 DISPUTED**（同一 Job 事务内二迁移）。
- 一致性约束照 PRD：已下发不能改金额，必须先 R11（50371）；已结清冲销必须先 R12 状态回退再 R10（PAID 上 adjust/reverse-item → 50371）。

### 3.2 月度生成 Job（MonthlyBillJob，`0 20 1 1 * ?` 每月 1 日 01:20）

- 出账对象：每租户 × 「上月存在计费流水 ∨ 上月首日 billableQty/Pallet>0 ∨ 存在 §1.5 待结转差额」的 wholesaler——**含 WITHDRAWN/OFFLINE/ARCHIVED**（退驻/下架商户的尾款月照常出账，R13 只保证已生成账单结清，清库当月费用次月才出账）。
- 前置：租户无生效规则 → 该租户跳过并 log WARN（手动触发端点则抛 50380）。
- 单 (t,ws) 单事务：BL- 单据号（§3.4）→ bills 行（幂等键 `bill:{t}:{ws}:{yyyyMM}` uk + 先查后写，重跑/并发天然幂等 90203 语义不占新码）→ bill_items：STORAGE（SKU×规则段，§1.3）+ ADJUSTMENT（§1.5 差额）+ **STOCKTAKE_IMPACT 盘盈亏明细行**（11-p3b-prd:367 / US-WK-03：每条上月 GAIN/LOSS 流水一行，amount=0 纯展示，description=「盘盈 +N 件（M-DD 审批，次日起算）/ 盘亏 −N 件（当日截止）」，费用已含于 STORAGE 行）→ 汇总（subtotal=ΣSTORAGE、adjust=ΣADJUSTMENT、total=subtotal+adjust）。
- **应收 0 仍生成、直接 PAID**（04 §3.4）；商户 OFFLINE → 直落 DISPUTED（§3.1）。
- 通知 ST（BILL_GENERATED，逐租户 ST 全员，user_roles 推导先例）；失败单租户吞异常记日志继续（recalcAll 先例），重试=次日人工触发端点 `POST /api/v1/tenant/st/bills/generate`（requireStOrTa，幂等）。
- 时区：账期为 UTC+8 自然月（05 §12），Job 用 `Asia/Shanghai`（DocumentNumberServiceImpl ZONE 先例）。

### 3.3 操作集（全部经 DocKind.BILL CAS，操作日志 actor_role=ST）

| 操作 | 端点（§6） | 前置与口径 |
|---|---|---|
| 调整（US-ST-02） | adjust | 仅 DRAFT（否则 50371）；`{type: DISCOUNT\|RELIEF, amount>0, remark 必填}` 落 ADJUSTMENT 条目（存负值）；调整后 total<0 → 40204；重算三金额 |
| 冲销（R10） | reverse-item | 仅 DRAFT；仅 STORAGE/ADJUSTMENT 条目、未被冲销过（否则 50383）；新增 REVERSAL 条目 amount=−原值、reverse_of_item_id 回指，**不删原条目**；重算 |
| 下发（US-ST-03） | dispatch | DRAFT→DISPATCHED + dispatch_at/dispatch_user_id；通知 WA（站内信真发+短信 mock） |
| 撤回（R11） | withdraw | DISPATCHED ∧ paid=0 ∧ confirmed_at NULL → DRAFT（否则 50372）；通知 WA |
| WA 确认 | wholesaler confirm | DISPATCHED→PENDING_PAYMENT + confirmed_at=now；**BillAutoConfirmJob**（`0 50 0 * * ?`）扫 `status=DISPATCHED ∧ dispatch_at <= NOW()-INTERVAL '1' DAY`（SQL 数据库时间，双方言先例）批量同迁移 |
| 回款登记（US-ST-04） | payments | 仅 PENDING_PAYMENT/PARTIAL_PAID（PAID → 50374；DRAFT/DISPATCHED → 50330）；`{amount>0, payAt, payMethod: BANK_TRANSFER\|CASH\|WX\|ALIPAY\|OTHER, evidences?≤5, remark?}`；amount ≤ 剩余应收（否则 50373，**不允许超收**）；凭证复用 POST /files + 50340 白名单；paid_amount 累计，=total → PAID；多次部分回款=多条 payment_records；通知 WA |
| 回款冲销（R12） | payments/{id}/reverse | payment 须 EFFECTIVE（否则 50375）；`{reason 必填, confirmed:true}`（二次确认凭据；蓝图 60205「OPS 确认」不启用——R12 归 ST，US-ST 口径）；EFFECTIVE→REVERSED + reverse_* 三列留痕；paid_amount 回减 + 状态回退（§3.1）；通知 WA |
| WA 申诉（US-WA-08） | wholesaler dispute | 窗口=dispatch_at 后 **7 天**（超窗 50378，SQL 数据库时间）；账单须已下发过（DRAFT → 50370 不泄漏）；`{reason 必填, disputedItemIds?, attachments?≤5}`；条目须属本账单（否则 50377）；同账单 PENDING 申诉至多一张（pending_flag 部分唯一 uk_bd_bill_pending，V13 先例，违者 50382）；**申诉不冻结账单**（ST 决定是否 R11 撤回调整）；通知 ST |
| 申诉处理 | st resolve | PENDING（否则 50376）；`{conclusion: RESOLVED\|REJECTED, resolution 必填}`；留痕 resolver/resolved_at；通知 WA |
| 导出（US-ST-05，W5） | export | DRAFT 亦可导（预览稿水印「未下发」）；同步流式（§7 W5） |

与 P3 客诉边界重申：出库客诉不改账单（D43）；BillDispute 与 arbitrations 两套实体，互不落表。

### 3.4 BL- 账单号（D-P4-7=A）

- `DocType` 扩 `BILL("BL")`（前缀不撞角色缩写，05 §7.1 账单行既定）。
- `DocumentNumberService` 扩月度分支：`generateBillNo(String tenantSimpleCode, Long wholesalerId, YearMonth month)` → `BL-{简码归一}-W{wholesalerId}-{yyyyMM}`。**无日序列、无 Redis 计数**——(t,ws,月) 天然唯一，`uk_bill_no` + `uk_idempotent` 双层兜底（G-5.1 同构）；简码归一复用现 normalize()。示例：`BL-T8801-W17-202607`。
- wholesalers 不加简码列（零迁移；P5 若上简码列，仅影响新账单展示，旧单号不回改——05 §7.1 存量不回改先例）。

### 3.5 R13/R14 联动（D-P4-10 三项，全部兑现既有 TODO）

1. **R13 `BillingService.assertAllSettled(wholesalerId)`**：存在 `status != PAID` 账单（含 DISPUTED）→ 抛 **50323**（O-5 承诺位兑现）；接入 WholesalerLifecycleServiceImpl 前置第 3 项（发起与审批**双检**，50312/50314 同构）；precheck `billing` 由 `{cleared:null}` 灰态转 `{cleared:bool, count:int}` 真值（WDR-07 用例适配）。
2. **R14 未结账单标 DISPUTED**：forceOffline TODO 兑现——同事务将该 ws 的 DISPATCHED/PENDING_PAYMENT/PARTIAL_PAID 账单批量 CAS→DISPUTED（§3.1 矩阵内），通知 ST；FOF-S1-04 由状态位断言转真实流转断言。
3. **不做**：「已下架→争议中→已退驻」OPS 仲裁闭环（DISPUTED 无出边）→ P5。

### 3.6 通知与权限边界

- 通知 8 类（04 §5 矩阵 7 类 + R20）：`BILL_GENERATED`(→ST 全员)、`BILL_DISPATCHED`/`BILL_WITHDRAWN`/`PAYMENT_REGISTERED`/`PAYMENT_REVERSED`/`BILL_DISPUTE_RESOLVED`(→WA 管理员全员)、`BILL_DISPUTE_SUBMITTED`(→ST 全员)、`BILLING_RULE_CHANGED`(→全体 ACTIVE WA)。站内信同事务写入（先例）；短信沿 mock；type/REF 零 DDL（B8）。
- 权限切点：ST 端点统一 `requireStOrTa`（TA 兼岗放行，一人多岗并集 05 §5.2；操作日志 actor_role=ST 区分，S4）；**WE 对账单整域拒绝**（WA 端账单端点仅 WA，WE 不放行只读——WEM-S4-03 防回归用例）；WK 全拒；**ST 不可见库存明细**（05 §5.4）：billing 域不提供任何 inventory 实时端点，详情下钻只读 daily_snapshots 聚合；跨租户/跨商户按不存在（50370，不泄漏存在性）。

---

## 4. 数据库迁移（V24 起，每条一文件；H2 双方言沿 V11 先例；TenantLine 白名单逐波同步）

| 版本 | 波次 | 内容 |
|---|---|---|
| **V24__p4_billing_rules.sql** | W1 | 建表 `billing_rules`（下表）；无需动 tenant_settings（billing_dim 列已在，镜像为行为约定非 DDL） |
| **V25__p4_daily_snapshots.sql** | W2 | 建表 `daily_snapshots`（下表） |
| **V26__p4_bills.sql** | W3 | 建表 `bills` / `bill_items` / `payment_records` / `bill_disputes`（下表） |

**billing_rules**（V24）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id | BIGINT NOT NULL | TenantLine 白名单 |
| wholesaler_id | BIGINT NULL | **留位恒 NULL**（P5 per-WA 个性价，D-P4-3） |
| qty_enabled / pallet_enabled | TINYINT NOT NULL | 两维度可并存，至少其一=1（应用层 50379） |
| price_per_qty_day / price_per_pallet_day | DECIMAL(14,4) NULL | 启用维必填 ≥0 |
| min_charge | DECIMAL(14,2) NOT NULL DEFAULT 0 | 留位不启用（P5 保底费） |
| extra_rule_json | TEXT NULL | 留位不启用（P5 阶梯/临期加价） |
| effective_from | DATE NOT NULL | `uk_rule_tenant_from(tenant_id, effective_from)`——一日一版，同日变更覆写 |
| effective_to | DATE NULL | NULL=当前生效；关旧=from−1（§2.1） |
| version | INT NOT NULL | 自 1 递增 |
| created_by / created_at / updated_at | | |

索引：`idx_rule_tenant_to(tenant_id, effective_to)`（当前规则点查）。蓝图偏差（§2.1 已述）：双维四列替代 billing_dim+unit_price；去 deleted_at（规则版本链只追加不删）。

**daily_snapshots**（V25）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id / wholesaler_id / sku_id | BIGINT NOT NULL | **SKU 粒度**（蓝图 ws 粒度不够 D-P4-1 下钻，偏差） |
| snapshot_date | DATE NOT NULL | `uk_snap_ws_sku_date(wholesaler_id, sku_id, snapshot_date)` |
| qty / pallet_qty | INT NOT NULL DEFAULT 0 | §1.1 公式净值（双 0 不落行） |
| created_at | DATETIME | |

索引：`idx_snap_tenant_date(tenant_id, snapshot_date)`、`idx_snap_ws_date(wholesaler_id, snapshot_date)`。蓝图偏差：去 expiry_qty/daily_fee/billing_rule_id（金额不物化，§1.2）。

**bills**（V26）：

| 列 | 类型 | 说明 |
|---|---|---|
| id / bill_no | BIGINT PK / VARCHAR(64) NOT NULL | `uk_bill_no`；§3.4 格式 |
| tenant_id / wholesaler_id | BIGINT NOT NULL | |
| billing_month | VARCHAR(7) NOT NULL | yyyy-MM |
| period_start / period_end | DATE NOT NULL | §1.3 起点截断 |
| subtotal_amount / adjust_amount / total_amount / paid_amount | DECIMAL(14,2) NOT NULL DEFAULT 0 | total=subtotal+adjust ≥0 |
| status | VARCHAR(32) NOT NULL | §3.1 六枚举 |
| dispatch_at / dispatch_user_id / confirmed_at | DATETIME/BIGINT/DATETIME NULL | R11 判据 + 自动确认锚点 |
| idempotent_key | VARCHAR(64) NOT NULL | `uk_bill_idempotent`，`bill:{t}:{ws}:{yyyyMM}` |
| created_at / updated_at | DATETIME | |

索引：`idx_bill_tenant_status(tenant_id, status, billing_month)`、`idx_bill_ws_month(wholesaler_id, billing_month)`。蓝图偏差：去 GENERATING/CANCELLED、due_date、pdf_url/excel_url（D-P4-8 流式不落存储）、deleted_at。

**bill_items**（V26）：

| 列 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / bill_id | BIGINT | `idx_bi_bill(bill_id)` |
| item_type | VARCHAR(32) NOT NULL | `STORAGE` / `ADJUSTMENT` / `REVERSAL` / `STOCKTAKE_IMPACT`（蓝图 MIN_CHARGE/EXPIRY_SURCHARGE 随 P5 留位不启用） |
| sku_id | BIGINT NULL | STORAGE/STOCKTAKE_IMPACT 落值 |
| period_start / period_end | DATE NULL | STORAGE 规则段（R20 分段一段一行） |
| qty_days / pallet_days | INT NULL | 件·天 / 托盘·天 |
| unit_price_qty / unit_price_pallet | DECIMAL(14,4) NULL | 段内单价快照 |
| amount | DECIMAL(14,2) NOT NULL | STOCKTAKE_IMPACT 恒 0 |
| description | VARCHAR(255) NULL | |
| reverse_of_item_id | BIGINT NULL | REVERSAL 回指（R10 冲销链） |
| operator_user_id | BIGINT NULL | ST 调整/冲销留痕 |
| created_at | DATETIME | |

**payment_records**（V26）：

| 列 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / wholesaler_id / bill_id | BIGINT | `idx_pay_bill(bill_id)` |
| amount | DECIMAL(14,2) NOT NULL | >0 且 ≤剩余应收（50373） |
| pay_at | DATETIME NOT NULL | 实付时间（ST 手填） |
| pay_method | VARCHAR(16) NOT NULL | §3.3 五枚举 |
| evidence_urls | VARCHAR(1024) NULL | ≤5 张，/files 白名单 |
| remark | VARCHAR(255) NULL | |
| status | VARCHAR(16) NOT NULL DEFAULT 'EFFECTIVE' | EFFECTIVE/REVERSED |
| reverse_reason / reverse_user_id / reverse_at | | R12 留痕 |
| created_by / created_at / updated_at | | created_by=登记 ST |

蓝图偏差：去 payment_no（内部凭证以 id 引用，不占单号前缀——inbound_corrections 先例）、deleted_at。

**bill_disputes**（V26）：

| 列 | 类型 | 说明 |
|---|---|---|
| id / tenant_id / wholesaler_id / bill_id | BIGINT | `idx_bd_tenant_status(tenant_id, status, created_at)` |
| submit_user_id | BIGINT NOT NULL | WA 提交人 |
| reason | VARCHAR(512) NOT NULL | |
| disputed_item_ids | VARCHAR(1024) NULL | JSON 数组（字符串 id 防 JS 精度） |
| attachments | VARCHAR(1024) NULL | ≤5 |
| status | VARCHAR(16) NOT NULL DEFAULT 'PENDING' | PENDING/RESOLVED/REJECTED |
| pending_flag | TINYINT NULL | 部分唯一 `uk_bd_bill_pending(bill_id, pending_flag)`（50382，V13 先例；终态 NULL） |
| resolution / resolver_user_id / resolved_at | | ST 处理留痕 |
| created_at / updated_at | | |

---

## 5. 错误码分配（50323 + 50370–50389；已同步登记 05-error-codes.md P4 段）

| code | errorCode | 用户提示 | 场景 | 启用波 |
|---|---|---|---|---|
| 50323 | `WITHDRAW_BILL_NOT_SETTLED` | 退驻前须结清账单（存在未结清账单） | R13 前置第 3 项（O-5 承诺位兑现，发起/审批双检） | W3 |
| 50370 | `BILL_NOT_FOUND` | 账单不存在 | 不存在/跨租户/跨商户/未下发对 WA 均按不存在（不泄漏存在性） | W3 |
| 50371 | `BILL_NOT_ADJUSTABLE` | 当前状态不可调整，请先撤回下发 | 调整/冲销仅待核对（04 §6.2；已结清先 R12 再 R10） | W3 |
| 50372 | `BILL_NOT_WITHDRAWABLE` | 账单已有回款或已被确认，无法撤回 | R11：仅 DISPATCHED ∧ paid=0 ∧ 未确认 | W3 |
| 50373 | `BILL_PAYMENT_EXCEEDS` | 登记金额超出剩余应收 | 蓝图 60201 语义移段（勘误注口径） | W3 |
| 50374 | `BILL_ALREADY_SETTLED` | 账单已结清，无需重复登记 | 蓝图 60202 语义移段 | W3 |
| 50375 | `BILL_PAYMENT_NOT_REVERSIBLE` | 该回款记录已冲销或不存在 | R12 重复冲销/不存在按不存在 | W3 |
| 50376 | `BILL_DISPUTE_NOT_PENDING` | 该申诉已处理 | resolve 时非 PENDING/不存在 | W3 |
| 50377 | `BILL_DISPUTE_INVALID` | 申诉条目无效 | disputedItemIds 非本账单条目 | W3 |
| 50378 | `BILL_DISPUTE_WINDOW_CLOSED` | 申诉期已过（账单下发后 7 天内可提） | 蓝图 60204 语义移段；SQL 数据库时间 | W3 |
| 50379 | `BILLING_RULE_INVALID` | 计费规则无效（至少启用一种维度并填写单价） | W1 规则校验 | W1 |
| 50380 | `BILLING_RULE_MISSING` | 计费规则未设置，无法生成账单 | 手动出账触发时无生效规则（Job 侧静默跳过） | W3 |
| 50381 | `BILL_DISPUTED_LOCKED` | 账单争议中，操作受限 | R14 DISPUTED 位冻结全部写操作（OPS 闭环 P5） | W3 |
| 50382 | `BILL_DISPUTE_PENDING_EXISTS` | 该账单已有待处理申诉 | pending_flag 部分唯一兜底 | W3 |
| 50383 | `BILL_ITEM_NOT_REVERSIBLE` | 该条目不可冲销或已被冲销 | R10：仅 STORAGE/ADJUSTMENT 且未被冲销 | W3 |
| 50384–50389 | 预留 | — | P4 后续增补 | — |

复用不新占：`50330/50331`（BILL 矩阵不可达/CAS）、`50340`（回款/申诉附件校验）、`40003`（confirmed 凭据缺失）、`40103`（金额格式）、`40204`（调整超小计）、`42001/42004/42101`（越权/WE 拒绝/跨租户）、幂等重复=先查后写返回既有单（不抛码）。50324–50329、50343–50349、50357–50359、50368–50369 各段维持原预留归属不挪用。

---

## 6. API 端点清单（风格=现状 `POST /resources/{id}/{action}`；全部界面中文文案）

### 6.1 W1 规则（见 §2.2，2 端点）

### 6.2 ST（`/api/v1/tenant/st/**`，requireStOrTa）

| 方法/路径 | 波次 | 说明 |
|---|---|---|
| GET `/st/bills?month=&wholesalerId=&status=&page=&size=` | W3 | 列表+汇总卡（应收/已收/未收合计）；倒序 |
| GET `/st/bills/{id}` | W3 | 详情：三金额+状态+items 全量+payments+disputes；skuName 冗余带出（L-5 口径） |
| GET `/st/bills/{id}/daily-breakdown` | W3 | 按日下钻（读 daily_snapshots 聚合：date→qty/pallet/当日金额按段现算）；**不暴露实时库存**（05 §5.4） |
| POST `/st/bills/generate` | W3 | `{month}` 手动补跑（幂等；无规则 50380） |
| POST `/st/bills/{id}/adjust` | W3 | §3.3 调整 |
| POST `/st/bills/{id}/reverse-item` | W3 | §3.3 R10 |
| POST `/st/bills/{id}/dispatch` | W3 | §3.3 下发 |
| POST `/st/bills/{id}/withdraw` | W3 | §3.3 R11 |
| POST `/st/bills/{id}/payments` | W3 | §3.3 回款登记 |
| POST `/st/payments/{id}/reverse` | W3 | §3.3 R12 |
| GET `/st/bill-disputes?status=` | W3 | 申诉队列（PENDING 升序先到先处理，先例） |
| POST `/st/bill-disputes/{id}/resolve` | W3 | §3.3 处理 |
| GET `/st/bills/{id}/export?format=pdf\|excel` | W5 | 同步流式下载（D-P4-8=A） |

### 6.3 TA / WA

| 方法/路径 | 角色 | 波次 | 说明 |
|---|---|---|---|
| GET `/api/v1/tenant/bills-overview?month=` | TA | W4 | US-TA-08：应收/已收/未收按月汇总+逐 WA 行（下钻单 WA） |
| GET `/api/v1/wholesaler/bills?month=&status=` / GET `/{id}` | WA（**WE 拒**） | W3 | 收到的账单（仅已下发过的可见；DRAFT 对 WA 50370 不泄漏） |
| POST `/api/v1/wholesaler/bills/{id}/confirm` | WA | W3 | 对账确认 → 待回款（D-P4-6；蓝图未列，本设计补） |
| POST `/api/v1/wholesaler/bills/{id}/dispute` | WA | W3 | §3.3 申诉（7 天窗） |

### 6.4 W5 契约补口（test-plan 挂账三项）

| 方法/路径 | 说明 |
|---|---|
| GET `/api/v1/tenant/dashboard` | TA 工作台真端点（清 V-2 mock）：待审批数/在库汇总/本月账单三卡聚合（Controller 编排既有 Service 出口，G-S1） |
| GET `/api/v1/tenant/batch-config` | L-1 读端点（batch_enabled/batch_enabled_at/expiry_threshold_days 只读） |
| SKU 名称展示统一（L-5） | 无新端点：账单/下钻 VO 一律冗余 skuName（盘点详情带名先例），前端删自行映射残留 |

Job（无端点）汇总：`DailySnapshotJob` 00:10（§1.2）、`BillAutoConfirmJob` 00:50（§3.3）、`MonthlyBillJob` 每月 1 日 01:20（§3.2）——SchedulingConfig 注释占位表同步登记；单实例假设沿 12 §8.4，多副本前 ShedLock 统一评估。

---

## 7. 六波拆分与测试关卡（波序沿 12-p4 §4 拍板；BE 波串行，迁移号/枚举/矩阵单线；每波开工再核 main 迁移最高号）

```
W0 定稿 ──▶ W1(V24 规则+契约修复) ──▶ W2(V25 引擎+快照) ──▶ W3(V26 生命周期) ──▶ W4(前端) ──▶ W5(导出+补口+回归)
                                                                                └── W5 可与 W4 部分并行
```

| 波 | 内容速览 | 合并闸门（JUnit+E2E 必过） |
|---|---|---|
| **W0** | 本文档 + 产品 13-p4-billing-prd（05 §1.1 粒度修订、状态中文文案表、06 §4.2 批次下钻注记移除） | 文档评审；错误码/迁移号登记完成 |
| **W1** | V24；billing_rules 实体/API 2 端点/版本链事务/镜像 billing_dim(含 BOTH)/R20 通知全 WA；**§2.3 契约修复全量**（Settings.vue+api-types+StoreSettingsDto 忽略）；requireStOrTa gate | JUnit：版本链（首版免 confirmed/变更需 confirmed 40003/同日覆写/关旧开新 from−1/幂等空转）；50379 四路；镜像三值；越权矩阵（TA 写/ST 读/WE·WK·WA 拒）；通知仅 ACTIVE WA。E2E：Settings 计费区块保存→回显→R20 弹窗真实触发；**单价落库断言**（缺陷收口证据） |
| **W2** | V25；回放引擎纯函数 + InventoryService 只读出口 + DailySnapshotJob（幂等/回补/对账哨兵）+ 分段/跨月差额算法 | §1.6 金标准 G1–G12 逐例；性能护栏（单 ws 万级流水回放 <1s）；快照与 inventories 全量对账绿 |
| **W3** | V26 四表；DocKind.BILL 矩阵；MonthlyBillJob/BillAutoConfirmJob；§3.3 全操作集；BL- 单号（DocType.BILL+月度分支）；R13 assertAllSettled(50323)+precheck 真值；R14 DISPUTED 联动；8 类通知；50370–50383 | JUnit：BILL 矩阵**逐格**（6×6 含不可达）；幂等键并发双跑恰一单；应收 0 直落 PAID；R11 三前置；回款四态（部分/结清/超收 50373/结清后 50374）；R12 回退两分支；申诉窗口±7 天边界/条目校验/pending 唯一并发；R13 双检+precheck（WDR-07 适配）；R14 三态标 DISPUTED+FOF-S1-04 真实流转；OFFLINE 生成直落 DISPUTED；DISPUTED 全写操作 50381；WE 整域拒绝/ST 无库存端点（WEM-S4-03/05-secure-coding 自检）；虚拟线程并发：回款登记×R12、下发×R11 同单 CAS 决出 |
| **W4** | ST 三页替换占位（/st/dashboard 工作台、/st/bills 列表、/st/bills/:id 详情：按日/按 SKU 下钻+全操作弹窗）+ 回款登记页；**三核心页（列表/详情/回款登记）响应式**（D-P4-9=A 降档口径，US-ST-06 验收=此三操作移动可用，交付纪要向用户显著标注）；TA bills-overview 页；WA 账单页+confirm+申诉表单；界面全中文 | Playwright：生成→核对→调整→下发→WA 确认→回款→结清主链；R11/R12/申诉支线；移动视口三核心页截图；**视觉 QA 截图目检**（test-plan 00-overview §3.5/§3.6） |
| **W5** | 导出：Excel=Apache POI、PDF=HTML 模板+印章位（06 §4.2/US-ST-05 明细行模板，建议 openhtmltopdf 内嵌中文字体，后端可换同类库）**同步流式不落存储**（D-P4-8=A；明细超 5000 行降级按 SKU 聚合护栏）；§6.4 契约补口三项；全量回归 | 导出内容断言（三金额/明细行数/印章位）；V-2/L-1/L-5 三项验收；**全量回归绿（基线 337+新增零回归）**；交付报告（含托盘校准 checklist、D-P4-9 降档标注） |

---

## 8. 风险与既有代码影响面

| 触点 | 波次 | 口径 |
|---|---|---|
| 既有 337 测试 | W1 起 | TenantService 设置用例：billingDim 写路径断言改「忽略不落库」；其余零改动预期（billing 全新域不触旧签名） |
| storefront/前端读 billingDim | W1 | 新值 **BOTH** 出现：前端展示映射补三值（件·天/托盘·天/两者），读侧接口零改动 |
| PUT /tenant/me 旧客户端 | W1 | billingDim 忽略不报错（兼容）；api-types 幽灵字段删除为 breaking，仅 admin 单端同步改 |
| DISPUTE_RESTORE reversal_of_id=null | W2 | §1.1 公式天然容错，金标准 G7 断言（12-p4 §2.1 提醒 2 收口） |
| PALLET_RELEASE qty=0 / EXPIRY_CLEARANCE 不计出库统计 | W2 | 仅统计口径特判，计费公式零特判（§1.1）；对账脚本已知晓（13 §7.3） |
| 托盘历史虚高（V20 前基线） | W2/W4 | D-P4-5=A：Σpallet_delta 现值起算；上线 checklist 提示先盘点校准（13 §2.4-4 既定） |
| 已出账月不可变 | W2 | 跨月差额一律 ADJUSTMENT 入当月（§1.5）；测试断言历史 bills 行 updated_at 不变 |
| 退驻/归档商户尾款月 | W3 | 出账对象含 WITHDRAWN/OFFLINE/ARCHIVED（§3.2）；R13 只校验已生成账单——尾款账单在退驻后次月出现属预期，写入 ST 操作说明 |
| DISPUTED 冻结回款 | W3 | 争议中线下收到款项暂不可登记（50381），P5 OPS 闭环解冻——W0 已知取舍（D-P4-10），交付纪要标注 |
| Job 单实例假设 | W2/W3 | 沿 12 §8.4：SchedulingConfig 注释登记三新任务；多副本前 ShedLock 统一上 |
| 幂等与重试 | W3 | 生成失败单租户吞异常次日手动补跑（50380/幂等键保护）；蓝图 60203「生成失败联系 OPS」不占码（日志+告警渠道） |
| 精度 | 全程 | 单价 DECIMAL(14,4)、金额 DECIMAL(14,2)；逐段金额先算后舍（HALF_UP 到分），Σ条目=账单三金额恒等式入测试不变量 |

---

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-08-05 | 首版（W0 定稿）：五锚点消费统一回放公式（≤D−1 一式）+ 快照缓存三用途 + billing_rules 版本链与 §2.6 契约修复方案 + 账单 6 态状态机（补 PENDING_PAYMENT/砍 GENERATING·CANCELLED）+ BL- 月度单号（W{id} 兜底）+ R13/R14 联动三项 + V24–V26 迁移 + 错误码 50323/50370–50389 + 六波拆分与闸门 |
