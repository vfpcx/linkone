# 13 · P3b「正向申请链 / 退货·盘点·清库 / 批次临期」架构设计（T1 / T3 / T4）

> 项目：仓储云 · 编写：架构师 Agent · 2026-07-30
> 真源：`product/10-p3b-requirements.md` v1.1（§五 14 项拍板全量生效：D-11=C、D-8=A、D-7=登记时扣、D-3=直落 CONFIRMED、D-4=CORRECTION_IN/OUT、D-10=盘亏封顶、D-13=batch_enabled 禁改、D-14=50350–50369）
> 输入：`12-p3-design.md`（DocStateMachine/封顶冲销/SchedulingConfig/arbitrations 既有引擎与先例，含 BE-W1/BE-W2 据实现备注）、`03-database-schema.sql` 蓝图（仅参考，状态命名以已落地风格为准，10 §0-C14）
> 风格：据实现编写。**所有「实测」标注均经 2026-07-30 对 main@75fe3f1 核对**。
> 范围：T1 正向申请链（R1/R2/R3）、T3 退货/盘点（清库依 D-6 移入 T4）、T4 批次登记+临期+清库。零金额计算（计费全部 P4，只保证流水 type/biz_time/pallet_delta 锚点准确）。

---

## 0. 开工实测快照（main@75fe3f1）

| # | 事实 | 结论 |
|---|---|---|
| A1 | Flyway 最高 **V18**（`V18__p3_outbound_states.sql`），无在途分支占用更高号 | **P3b 迁移自 V19 起**，与 10 §0-C1 预期一致 |
| A2 | ErrorCode 实占至 **50342**（`ErrorCode.java:175`）；50343–50349 预留未用 | 溢出段 **50350–50369** 生效（D-14），50343–50349 维持预留作缓冲 |
| A3 | `DocType` 已预留 `RETURN("RTN")/STOCKTAKE("PD")/CLEARANCE("QK")`（`DocType.java:24-29`） | 单号直接 `DocumentNumberService.generate(...)` 启用，零改造 |
| A4 | `WePermissions.ALLOWED` = 3 枚（PRICE_EDIT/INQUIRY_CONFIRM/INBOUND_CONFIRM），位于 `common/util/WePermissions.java` | T1-BE 扩第 4 枚 `INBOUND_SUBMIT` |
| A5 | 全仓 @Scheduled 仅 2 处：`InboundAutoConfirmJob`（0 5/10 * * * ?）、`WholesalerArchiveJob`（0 40 3 * * ?） | **02:00 / 02:30 分钟位空闲**，按 12 §2.5 预约给 T4 |
| A6 | `InventoryService` 现有 addStock/deductStock/reverseOutbound/reverseInboundForDispute/restoreInboundAfterArbitration + assertStockEnough（均 锁+doXxxInTx 代理先例） | T3/T4 新增 returnStock/gainStock/lossStock/clearStock 同构扩展 |
| A7 | `stock_movements` 有 biz_time/reversal_of_id/remark，**无 pallet 变化列、无 batch_id**；出库链托盘对称传 0（12 BE-W2 备注 5） | V20 加 `pallet_delta`、V22 加 `batch_id` |
| A8 | `tenant_settings` 已有 `batch_enabled`(默认0)/`photo_mode`/`expiry_threshold_days`(默认30)（V2），`TenantServiceImpl:995-998` 可写且零行为 | D-13 禁改防御 T3-W1 落、T4-W1 解除 |
| A9 | SKU 列表 `GET /api/v1/tenant/skus`（SkuController:25）经 `SkuServiceImpl.requireWaOrTa` 闸门，**纯 WK 42xxx 被拒**（上线检查单遗留项 §5-4，`ta/Outbound.vue:450` 已降级注记） | T1-BE 顺手补口：只读列表放行 WK（§5.4） |
| A10 | 后端测试 31 文件 / **252 个 @Test**；V13 有 pending_flag 部分唯一索引先例；`requireWkOrTa` 已有先例（OutboundRequestServiceImpl:619） | 影响面清单见 §7 |

**库存公式（12 §0 不变量的 P3b 终版扩展）**：

```
inventories.qty = ΣINBOUND − ΣOUTBOUND + ΣOUTBOUND_REVERSAL − ΣDISPUTE_REVERSAL + ΣDISPUTE_RESTORE
               + ΣCORRECTION_IN − ΣCORRECTION_OUT − ΣRETURN + ΣGAIN − ΣLOSS − ΣEXPIRY_CLEARANCE
inventories.pallet_qty = Σ(stock_movements.pallet_delta)   （V20 起新流水；存量行 pallet_delta=0，见 §2.4-4 边界）
```

一切变动**新增流水**，永不 update/delete 既有流水；qty、pallet_qty 恒 ≥0（封顶口径保证）；流水 qty 恒正、方向由 type 表达（现约定不变）。

---

## 1. T1 · 正向申请链（WA 提交 → WK 受理 → 登记入库 + R1/R2/R3）

### 1.1 状态机（inbound_requests 扩 4 值，命名 12 §2.1 已冻结）

```
正向链：[*] → SUBMITTED (WA/WE 提交, source=WA_SUBMIT, 不动库存/计费)
        SUBMITTED → WITHDRAWN (R1, withdraw_reason 必填)
                  | REJECTED  (R2, reject_reason 单选 + reject_remark 必填 + 附件可选)
                  | ACCEPTED  (WK 受理, 锁单防撤回)
        ACCEPTED  → CONFIRMED (WK 登记, 此刻才 addStock + INBOUND 流水; D-3 直落 CONFIRMED, 不复活 REGISTERED)
代建链（已上线，一字不动）：[*] → PENDING_WA_CONFIRM → CONFIRMED | DISPUTED → CONFIRMED | REVOKED
```

- `DocStateMachine.INBOUND_TRANSITIONS` 扩 4 行（SUBMITTED→{WITHDRAWN,REJECTED,ACCEPTED}、ACCEPTED→{CONFIRMED}）；不可达统一 50330、CAS 并发 50331（引擎已随 BE-W2 落地，直接复用）。不可达红线：REJECTED→ACCEPTED ❌、CONFIRMED→WITHDRAWN ❌、SUBMITTED→CONFIRMED ❌（必须经受理）。
- **库存时点不对称是有意设计**（10 §1.2-1）：出库=创建即扣，入库=**登记才加**。SUBMITTED/ACCEPTED/REJECTED/WITHDRAWN 全程零库存、零流水、零计费；测试断言与前端文案须明示，禁止拿出库口径套入库。
- 登记事务口径（单事务）：CAS `ACCEPTED→CONFIRMED`（extraSet：qty=实登件数、registered_at=now、attachments）→ `addStock`（INBOUND 流水，biz_time=登记时刻，**pallet_delta=+palletQty**，计费次日起算）→ 通知 WA。`wa_confirm_deadline` 恒 NULL → 72h Job 扫描条件（status=PENDING_WA_CONFIRM）天然不命中，**72h 链零改动**（10 §1.2-7）。
- UI 文案：CONFIRMED 统一「已入库」（source 区分链路展示，正向链不出现「已确认」话术）。

### 1.2 数量差异边界（T1-5）与登记参数

- 单据同时保留 `requested_qty`（申请件数，提交落值后不可变）与 `qty`（实登件数，登记时覆写）。
- 登记 `actualQty`：`|actualQty − requested_qty| / requested_qty ≤ 5%` → 按实登记，差异非 0 时 remark 必填；**>5% → 抛 50351**，提示走 R2 驳回（WA 复制重建）。边界含等于（5.00% 放行）。
- D-2 最小版：登记时可附照片 ≤5 张（`attachments` 列，复用 `POST /api/v1/files`，photo_mode 三档行为本波不做）；R2 驳回举证附件独立 `reject_attachments` 列。
- 打印（T1-7）：`printed_at`/`print_count` 落列，print 端点登记前后均可调（补打 count++），**非状态节点**（与出库 PRINTED 不同）。
- D-5 多 SKU：沿一单一 SKU；WA 提交表单多行 → 后端拆 N 张单，共享 `batch_submit_id`（雪花，同批打印聚合用），无明细表。

### 1.3 R3 登记纠错（inbound_corrections 独立小表 + TA 审批）

适用范围：**source=WA_SUBMIT ∧ status=CONFIRMED** 的正向链单据（代建链错误走异议/盘点，不入本表）；发起=WK，审批=TA（01 §4.3）。

流程：WK 发起（登记后 ≤24h，SQL 内比数据库时间，超窗 50352；同单已有 PENDING 抛 50353）→ TA decide：

- APPROVED（单事务，锁 `lock:inv:{w}:{s}` 内）：
  - `delta = new_qty − old_qty > 0`（改大）：补录 **`CORRECTION_IN`** 流水 qty=delta，qty += delta；
  - `delta < 0`（改小）：**复用 12 §2.4 封顶口径**——`applied = min(|delta|, max(onhand,0))`，写 **`CORRECTION_OUT`** 流水 qty=applied，`shortfall = |delta| − applied` 写纠错单备注（遇已售差额线下定责，禁止打负）；applied=0 时不写流水但纠错单照常 APPROVED 留痕；
  - 两类流水 `biz_time = 原 INBOUND 流水 biz_time`、`reversal_of_id = 原 INBOUND 流水 id`（D-4，与 DISPUTE_RESTORE 同构，P4 按配对重算仓储费——本波仅留锚点，零金额）；托盘 `pallet_delta = ±ceil(原入库 pallet_qty × applied / 原 qty)`（释放侧再对 `inventories.pallet_qty` 封顶）；
  - `inbound_requests.qty` 覆写为 new_qty（单据可见值与库存一致；原值在纠错单 old_qty 留痕）。
- REJECTED：`decide_remark` 必填，零库存影响。

审批弹窗复用仲裁弹窗样式（前端事）；纠错单不入 DocType 单号体系（内部审批件，以 id 引用，避免消耗前缀——与 wholesaler_applications 同类先例）。

### 1.4 钩子与授权

- **R14**：WA 提交接 `DocPreconditions.requireWholesalerActive`（第 4 处复用）；商户非 ACTIVE 时存量 SUBMITTED 单 WK **受理同样拒绝**（50313，货未入仓不属「保护客户权益」，与出库老单放行不同——文案随 50313 开发提示补注）。
- **R13**：未结枚举扩 `SUBMITTED/ACCEPTED`（§7.2 汇总）。
- **WE 授权位**：`WePermissions` 扩第 4 枚 `INBOUND_SUBMIT`（提交/撤回自己提交的申请）；50319 文案同步为「仅允许 PRICE_EDIT/INQUIRY_CONFIRM/INBOUND_CONFIRM/INBOUND_SUBMIT」；未持位调用 → 42004。出库侧 OUTBOUND_SUBMIT/COMPLAIN 维持悬置（D-5/D-9，P5 议）。
- 通知（同事务写 notifications，先例不变）：提交→WK；受理/驳回/登记完成→WA；纠错待审→TA、结论→WK。

---

## 2. T3 · 退货（RTN-）/ 盘点（PD-）+ 托盘账补齐（D-8=A）

### 2.1 return_requests（V20 建表）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| doc_no | VARCHAR(64) NOT NULL, uk_rtn_doc_no | `generate(RETURN)` → `RTN-…`（A3 零改造） |
| tenant_id / wholesaler_id / sku_id | BIGINT NOT NULL | TenantLine 白名单（MybatisPlusConfig 同步） |
| qty | INT NOT NULL | 退货件数（提交落值；登记可按实覆写，remark 留痕） |
| pallet_release | INT NOT NULL DEFAULT 0 | 登记时释放托盘（默认建议值 §2.4-2，WK 可覆盖） |
| status | VARCHAR(32) NOT NULL | `PENDING_ACCEPT` / `WITHDRAWN` / `ACCEPTED` / `COMPLETED`(已退货)（沿已落地命名风格，C14） |
| withdraw_reason | VARCHAR(512) NULL | R1 同构，撤回必填 |
| wa_user_id / wk_user_id | BIGINT | 发起 / 受理·登记人 |
| accepted_at / completed_at | DATETIME NULL | |
| remark | VARCHAR(512) NULL | |
| created_at / updated_at | DATETIME | |

索引：`idx_rtn_ws_status(wholesaler_id, status)`、`idx_rtn_tenant_status(tenant_id, status, created_at)`。

状态机（入 DocStateMachine 新矩阵，无审批）：`PENDING_ACCEPT → WITHDRAWN(WA 撤) | ACCEPTED(WK 受理)`；`ACCEPTED → COMPLETED(WK 现场出货登记)`。

**库存时点 = 登记时扣（D-7 拍板，PRD 原文）**：提交/受理不动库存（退货前货仍可售）；登记单事务锁内 `returnStock`——不足抛 `STOCK_NOT_ENOUGH(50251)` 现有码（WA 改单，与 04 §3.2 拣货不足同构），足则 qty −= n、pallet 释放（§2.4）、写 **RETURN** 流水（`biz_time=登记日`，计费当日截止——锚点，零金额）。无回补流水类别（登记前撤回本就没扣——D-7 的架构红利）。

校验：提交时 `assertStockEnough` 软校验（仅提示，不锁定）；SKU 下拉仅 in_stock>0（前端过滤，14b.10）；退货不影响 RT 意向单流转。权限：发起/撤回=WA（**WE 不开放**，D-9）；受理/登记=WK（requireWkRole 先例）。通知：发起→WK、登记完成→WA。

### 2.2 count_sheets / count_sheet_items（V21 建表）

**count_sheets**：

| 列 | 类型 | 说明 |
|---|---|---|
| id / doc_no | BIGINT / VARCHAR(64) uk | `generate(STOCKTAKE)` → `PD-…` |
| tenant_id / wholesaler_id | BIGINT NOT NULL | 一张盘点单盘一个商户（R13 归属明确） |
| status | VARCHAR(32) NOT NULL | `DRAFT` / `PENDING_APPROVAL` / `REJECTED`(→可改回 DRAFT 重提) / `APPROVED`（已通过不可逆） |
| pending_flag | TINYINT NULL | 部分唯一 `uk_cs_ws_pending(wholesaler_id, pending_flag)`：DRAFT/PENDING_APPROVAL=1、终态 NULL（V13 先例）——同商户在途盘点至多一张，防双重盈亏（违者 50356） |
| wk_user_id / ta_user_id / decided_at | | 操作人 / 审批人留痕（T3-9） |
| reject_remark / remark / attachments | VARCHAR(512)/512/1024 | 驳回理由必填 / 备注 / 现场照片 ≤5（复用 /files） |
| created_at / updated_at | DATETIME | |

**count_sheet_items**（明细，一单多 SKU——盘点天然全仓性质，与单据链一单一 SKU 不同域，不构成先例冲突）：

| 列 | 类型 | 说明 |
|---|---|---|
| id / sheet_id / tenant_id / sku_id | BIGINT | `idx_csi_sheet(sheet_id)`；同 sheet 内 sku 去重（应用层校验 50355） |
| system_qty | INT NOT NULL | **提交时刻**账面快照（inventories.qty，已扣后口径） |
| actual_qty | INT NOT NULL | 实物数 ≥0 |
| diff | INT NOT NULL | actual − system（正=盘盈、负=盘亏；系统不做在途还原折算，10 §2.2 口径正文） |
| applied_diff | INT NULL | 审批通过实际生效值（盘亏封顶后，D-10）；盘盈=diff |
| pallet_delta | INT NOT NULL DEFAULT 0 | 盘盈托盘可选填 +M；盘亏默认按比例释放建议值，WK 可覆盖（05 §3.2/§3.3） |
| remark | VARCHAR(512) NULL | 差异理由 |

状态机：`DRAFT → PENDING_APPROVAL(提交, 明细快照定格) `；`PENDING_APPROVAL → REJECTED | APPROVED`；`REJECTED → DRAFT(重新编辑重提)`。TA **全量审批，无自动通过阈值**（D23；60007 蓝图码不启用）。

**审批通过事务（逐 SKU，锁 `lock:inv:{w}:{s}` 内串行，T3-10）**：

- diff>0（盘盈）：`gainStock`——qty += diff，写 **GAIN** 流水（pallet_delta=+M 可选）；盘盈并入 SKU 池（无批次期口径，US-TA-12；D24 批次分支随 P5/方案 A）；
- diff<0（盘亏，**D-10 封顶**）：`lossStock`——`applied = min(|diff|, max(onhand,0))`（onhand=**审批时刻**锁内重读，非提交快照——G9：等待期被出库出完则按剩余在库封顶），写 **LOSS** 流水 qty=applied，`shortfall = |diff| − applied` 写该明细行 remark 并通知 TA/WK（与 12 §2.4 封顶同构，qty 恒 ≥0 不破）；托盘释放同步封顶；
- 两类 `biz_time=审批通过日`（盘亏当日截止、盘盈次日起算视同当日入库，05 §1.2——锚点，零金额；月度账单明细行 P4 标注）。
- 不生成赔偿单（D25），差异线下协商。

**盘点页护栏（口径正文 10 §2.2，数据端点 §5.2）**：详情页必须展示「当前存在 N 张已确认未出库单据（合计 M 件）」提示条（按 wholesaler+sku 聚合 PENDING_ACCEPT/PRINTED 出库单），差异列旁标注「其中 ≤M 件可能为在途出库占用」；同理提示已受理在途退货单（D-7 登记扣→ ACCEPTED 退货单同为「账面含、实物即将出」）。实物>账面是正常现象，不是盘盈。

通知：提交→TA、通过/驳回→WK。权限：全链 WK 建/提，TA 审（TA 兼任 WK 操作按现有 requireWkOrTa 先例放行查看）。

### 2.3 清库单（QK-）：依 D-6 移入 T4-W2，设计见 §3.4

### 2.4 托盘账补齐（D-8=A：出库/退货/盘亏/清库四处一次补齐）

1. **落列**：V20 给 `stock_movements` 加 `pallet_delta INT NOT NULL DEFAULT 0`（+占用 / −释放）。今后 `inventories.pallet_qty ≡ Σ pallet_delta`（05 §3.4 公式）可对账；DISPUTE_REVERSAL/RESTORE 新流水**双写**（列 + 既有 `remark palletReversed=N` 快照保留，P4 兼容两代数据）。
2. **释放规则（05 §3.3 简化版，全域统一）**：默认建议值 `ceil(池 pallet_qty × 本次件数 / 池 qty)`（无批次期按池比例近似——05 §3.3「批次关闭时反算」口径；批次启用后仍按池，方案 C 不拆批次库存），**WK 登记页可手动覆盖（含 0）**；落库前再 `min(·, inventories.pallet_qty)` 双重封顶，pallet_qty 恒 ≥0。
3. **出库托盘释放的时点与载体**：件数=创建即扣（拍板二 B 不动），**托盘=登记出库（COMPLETED）时释放**——托盘腾空的物理时点在货离仓，且 WK 覆盖值登记时才产生。因 OUTBOUND 流水已在创建时写就且**永不 update**（12 §0 红线），登记同事务追加一条独立流水 **`PALLET_RELEASE`**（qty=0 恒定、pallet_delta=−n、ref_doc_no=出库单、biz_time=登记时刻）；qty=0 不进件数公式，P4 托盘·天按 Σ pallet_delta 结算。对称性红利：R4/R8 撤回只发生在 COMPLETED 之前 → 从未释放托盘 → OUTBOUND_REVERSAL 维持 pallet 0（BE-W2 备注 5 行为不变），**无需托盘回补路径**。
4. **存量边界（明示）**：V20 之前的全部流水 pallet_delta=0——存量出库单（COMPLETED）**托盘账不动、不回填**：无 WK 覆盖值无法追溯，回填近似值会污染 P4 计费基线。后果=历史托盘账仍「只增不减」的既成虚高，由 T3 上线后**首次盘点的托盘现值校准**收口（盘点单 pallet_delta 一次性拉平），该操作规程写入 T3-FE 上线说明。
5. 接入点汇总：出库登记（PALLET_RELEASE）、退货登记（RETURN 流水负托盘）、盘亏审批（LOSS 流水负托盘）、清库审批（EXPIRY_CLEARANCE 流水负托盘）+ 纠错（§1.3 比例）。入库侧现状已 +托盘，仅补记列。

### 2.5 交叉口径（无需特判，测试补交叉用例）

RETURN/LOSS 使 onhand 变小 → 在途 DISPUTE 冲销封顶结果受影响——12 §2.4-3「冲销以异议时刻快照一次性执行、不追溯」天然覆盖；反向（异议冲销后退货登记不足）由 STOCK_NOT_ENOUGH 正确拒绝。

---

## 3. T4 · 批次（D-11=C：批次登记 + FIFO 离线推算，交易路径零改动）

### 3.1 batches 登记簿（V22 建表）+ 入库批次列

**batches**：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| tenant_id / wholesaler_id / sku_id | BIGINT NOT NULL | TenantLine 白名单 |
| batch_no | VARCHAR(64) NOT NULL | `uk_bat_ws_sku_no(wholesaler_id, sku_id, batch_no)` 唯一（T4-2，冲突 50362）；默认批次 `DEFAULT-{YYYYMMDD}` |
| production_date / expiry_date | DATE NULL | 到期日 NULL=不参与临期/归零扫描（默认批次可补录） |
| initial_qty | INT NOT NULL | 批次累计入库件数（INBOUND 落 batch_id 时累加；默认批次=启用时刻池 qty 快照） |
| remaining_qty | INT NOT NULL | **FIFO 推算剩余**（02:00 Job 覆写，§3.2；非记账值，UI 标注「推算」） |
| status | VARCHAR(16) NOT NULL | `IN_STOCK` / `SOLD_OUT` / `EXPIRING`(临期) / `PENDING_CLEARANCE`(待清理) / `CLEARED`(已清库) / `CLOSED`(启→关冻结) |
| source | VARCHAR(16) NOT NULL | `INBOUND` / `DEFAULT`(开关生成) |
| expiring_notified_at | DATETIME NULL | D-12 去重锚点：进入 EXPIRING 首次通知落值，状态不变不重发 |
| manual_notified_at | DATETIME NULL | WK 一键通知 24h 限 1 的比对锚点（50367） |
| cleared_at / created_at / updated_at | DATETIME | |

索引：`idx_bat_ws_sku(wholesaler_id, sku_id)`、`idx_bat_tenant_status_expiry(tenant_id, status, expiry_date)`（预警列表按剩余天数排序）。

**同版本 DDL**：`inbound_requests` 加 `batch_no VARCHAR(64) NULL / production_date DATE NULL / expiry_date DATE NULL`（批次启用时提交/登记必填，04 §3.9；校验 40205/40206 现有码；过期批次强警告需 `expiredConfirmed=true` 二次确认凭据，缺失抛 50364；临期警告放行）；`stock_movements` 加 `batch_id BIGINT NULL`（**仅 INBOUND / EXPIRY_CLEARANCE / CORRECTION_IN/OUT 落值，出库流水不落**——方案 C 定义）；`tenant_settings` 加 `batch_enabled_at DATETIME NULL`（推算的流水切割时点，§3.2）。

### 3.2 FIFO 离线推算算法（不进交易路径、不加锁、纯读流水、幂等）

每日 **02:00**（`0 0 2 * * ?`，`BatchRecalcJob`，复刻 WholesalerArchiveJob 先例：@Scheduled 吞异常记日志、任务体在 `BatchService.recalcAll()` 供测试直驱）。对每个存在批次行的 (wholesaler, sku)：

```
1  batches ← 该键全部非 CLEARED/CLOSED 批次，按 FIFO 序排序：
     expiry_date ASC NULLS LAST → 首次入库时间 ASC → batch_id ASC   （05 §2.1 三键，无到期日的垫底）
2  对每批 b：
     direct[b] = Σqty(CORRECTION_OUT|EXPIRY_CLEARANCE, batch_id=b) − Σqty(CORRECTION_IN, batch_id=b)
     base[b]   = initial_qty[b] − direct[b]                    // 带 batch_id 流水直扣，不进分摊
3  poolNetOut = Σqty(OUTBOUND + RETURN + LOSS + DISPUTE_REVERSAL)
             − Σqty(OUTBOUND_REVERSAL + DISPUTE_RESTORE + GAIN)
     —— 仅统计 batch_id IS NULL 且 created_at > tenant_settings.batch_enabled_at 的流水
        （启用前历史已被默认批次 initial_qty 快照吸收，不重复吃）
     poolNetOut = max(poolNetOut, 0)                           // GAIN 无批次入量先行抵扣净出
4  依 FIFO 序逐批：alloc = min(poolNetOut, max(base[b],0))
                  remaining_qty[b] = max(base[b],0) − alloc；poolNetOut −= alloc
5  状态联动（不触已终态）：remaining=0 → SOLD_OUT；
     remaining>0 ∧ expiry_date − today ≤ expiry_threshold_days → EXPIRING（新进入者记入当日预警集，§3.3）
6  Σremaining 与 inventories.qty 的差 =「无批次在池量」（盘盈未摊完/回补时序），批次下钻页展示为独立行，不报警
```

- 全量重算=流水的纯函数，天然幂等；误差窗口 ≤1 日（Job 日频），清库/盘点现场核数覆盖（10 §3.1-C 已声明）。
- **不支持项（产品已拍板降级，US-TA-12/05 §2 修订版由产品在 T4-W1 前出）**：指定批次出库（05 §2.4 下拉隐藏）、批次级精确库存/盘点（盘点仍按 SKU）、混合 FIFO 扣减、批次级计费下钻——全部随方案 A 顺延 P5；本表结构（batches/batch_id）即 A 的子集，不返工。

### 3.3 临期扫描（02:00）与归零标记（02:30）+ 通知去重（D-12）

- `BatchRecalcJob`（02:00）末步产出「**新进入** EXPIRING 集合」（expiring_notified_at IS NULL 者）：每批次发一次站内信（WK+WA 各一条，通知类型 `BATCH_EXPIRING`），落 expiring_notified_at；状态不变不重发。
- `BatchExpiryMarkJob`（**02:30**，`0 30 2 * * ?`）：`expiry_date < CURDATE() ∧ status ∈ (IN_STOCK, EXPIRING) ∧ remaining_qty > 0` → 标 `PENDING_CLEARANCE`（SQL 内比数据库时间；remaining=0 者直接 SOLD_OUT 不清库），通知 WK（`BATCH_EXPIRED`）。
- WK 预警列表按剩余天数升序（§5.3）；**手动一键通知**同批次 24h 限 1 次（manual_notified_at 比对，超限 50367）；短信不发（沿站内信最小口径）。
- 两 Job 与既有 03:40/分钟位 5 的任务错开（A5 实测空闲）；单实例假设沿 12 §8.4（多副本前 ShedLock，SchedulingConfig 注释已标注）。

### 3.4 清库单 QK-（clearance_requests，V23 建表；状态机套盘点）

| 列 | 类型 | 说明 |
|---|---|---|
| id / doc_no | BIGINT / VARCHAR(64) uk | `generate(CLEARANCE)` → `QK-…` |
| tenant_id / wholesaler_id / sku_id / batch_id | BIGINT NOT NULL | 一单一批次 |
| qty | INT NOT NULL | 发起时默认=批次推算剩余，**WK 现场核数可覆盖**（推算误差的人工收口） |
| pallet_release | INT NOT NULL DEFAULT 0 | §2.4-2 规则 |
| reason | VARCHAR(32) NOT NULL + reason_remark VARCHAR(512) | 预设单选（EXPIRED/DAMAGED/OTHER，OTHER 时备注必填） |
| attachments | VARCHAR(1024) NOT NULL | **实物照片必填 ≥1**（50366），不受 photo_mode 开关影响（R19 刚性） |
| status | VARCHAR(32) | `DRAFT / PENDING_APPROVAL / REJECTED / APPROVED`（DocStateMachine 矩阵与盘点同构） |
| wk_user_id / ta_user_id / decided_at / reject_remark | | |
| created_at / updated_at | DATETIME | |

前置：batch.status=`PENDING_CLEARANCE` 且推算剩余>0（违者 50365）；同批次在途 QK 至多一张（pending_flag 部分唯一，V13 先例）。审批通过事务（锁内）：`clearStock`——`applied = min(单据 qty, max(onhand,0))` 封顶，写 **EXPIRY_CLEARANCE** 流水（qty=applied、**batch_id 落值**、biz_time=清库日=计费当日截止、pallet_delta=−release 封顶、不计正常出库统计——P4 按 type 区分）；批次 `remaining_qty=0, status=CLEARED, cleared_at`；WA 站内信含凭证 URL。差额（现场核数<单据值）写单据 remark。

### 3.5 开关行为（T4-1，方案 C 裁剪版）与 D-13 防御

- **禁改防御（D-13，T3-W1 顺手落，T4-W1 解除）**：店铺设置接口拒绝 `batchEnabled` 字段变更（抛 50360「批次功能开发中」）；随 V20 迁移 `UPDATE tenant_settings SET batch_enabled=0`（存量校准归 0，C8 风险即刻设防）。
- T4-W1 起改走**专用端点**（副作用大，不混在通用设置里）：`POST /api/v1/tenant/settings/batch-toggle {enable, confirmed:true}`——24h ≤2 次（Redis 计数 key `batch:toggle:{tenantId}` TTL 24h，超限 50361）；
  - **关→启**：弹窗确认凭据 confirmed=true → 落 `batch_enabled=1, batch_enabled_at=now` + 同事务为全部 in_stock>0 的 (w,sku) 生成默认批次 `DEFAULT-{YYYYMMDD}`（initial_qty=当刻池 qty 快照、expiry NULL 可补录）；再启用时生成**新**默认批次，不复活 CLOSED 批次；
  - **启→关**：停预警/归零两 Job 对该租户生效（Job 内按 batch_enabled=1 过滤）+ 全部非终态批次标 `CLOSED`（批次簿冻结；方案 C 下无「老批次独立库存出完为止」问题——库存本就没拆）；进行中 QK 单按提交时策略走完；
  - 入库表单批次三字段随开关显隐（后端按提交时刻开关状态校验必填）。
- 展示图「入库实拍」联动依赖 T1 拍照全链（D-2 后置），本波不做。

### 3.6 R13/R14 衔接

待清理批次存在**不阻**退驻（退驻前置库存=0 ⇒ 推算剩余必为 0）；QK/盘点在途单计入未结枚举（§7.2）；批次登记/清库不接 requireWholesalerActive（对象是存量库存治理，非新业务受理）。

---

## 4. 数据库迁移（V19 起）与错误码 50350–50369

### 4.1 迁移清单（每条=一个文件；H2 双方言写法沿 V11 先例，索引前缀续用规则：inb_/rtn_/cs_/csi_/bat_/qk_/mv_）

| 版本 | 波次 | 内容 |
|---|---|---|
| **V19__p3b_inbound_forward_chain.sql** | T1-BE | `inbound_requests` 加：`requested_qty INT NULL`（正向链必填；代建链 NULL）、`reject_reason VARCHAR(32)`、`reject_remark VARCHAR(512)`、`reject_attachments VARCHAR(1024)`、`withdraw_reason VARCHAR(512)`、`attachments VARCHAR(1024)`（登记照片）、`printed_at DATETIME`、`print_count INT NOT NULL DEFAULT 0`、`registered_at DATETIME NULL`（R3 24h 窗口锚点）、`batch_submit_id BIGINT NULL`、`wa_user_id BIGINT NULL`（提交人）；加索引 `idx_inb_tenant_status(tenant_id, status, created_at)`（WK 待受理队列）。建表 `inbound_corrections`（§1.3 字段 + `idx_corr_tenant_status`、`idx_corr_request(inbound_request_id)`、pending_flag 部分唯一 `uk_corr_req_pending(inbound_request_id, pending_flag)`——V13 先例）；TenantLine 白名单同步 |
| **V20__p3b_return_pallet.sql** | T3-W1 | `stock_movements` 加 `pallet_delta INT NOT NULL DEFAULT 0`（§2.4，存量恒 0）；建表 `return_requests`（§2.1）；`UPDATE tenant_settings SET batch_enabled=0`（D-13 存量校准）；TenantLine 白名单同步 |
| **V21__p3b_stocktake.sql** | T3-W2 | 建表 `count_sheets` + `count_sheet_items`（§2.2，含 pending_flag 部分唯一）；TenantLine 白名单同步 |
| **V22__p3b_batches.sql** | T4-W1 | 建表 `batches`（§3.1）；`inbound_requests` 加 `batch_no/production_date/expiry_date`；`stock_movements` 加 `batch_id BIGINT NULL` + 索引 `idx_mv_batch(batch_id)`；`tenant_settings` 加 `batch_enabled_at DATETIME NULL`；TenantLine 白名单同步 |
| **V23__p3b_clearance.sql** | T4-W2 | 建表 `clearance_requests`（§3.4，含 pending_flag 部分唯一 `uk_qk_batch_pending(batch_id, pending_flag)`）；TenantLine 白名单同步 |

> 每波开工前照例再核 main 迁移最高号（12 修正二流程）；BE 各波串行（§6），迁移号单线无冲突。流水类型 CORRECTION_IN/CORRECTION_OUT/RETURN/GAIN/LOSS/EXPIRY_CLEARANCE/PALLET_RELEASE 均为 VARCHAR(32) 枚举值，**零 DDL**（V15 已扩宽）——PALLET_RELEASE 为本设计新增第 12 类（§2.4-3）。

### 4.2 错误码分配（50350–50369，D-14 溢出段；50343–50349 维持预留作后续缓冲）

| code | errorCode | 用户提示 | 场景 | 启用波 |
|---|---|---|---|---|
| 50350 | `INBOUND_NOT_WITHDRAWABLE` | 申请已受理，无法撤回 | R1 仅 SUBMITTED 可撤（受理锁单后须走 WK 流转） | T1-BE |
| 50351 | `INBOUND_QTY_DIFF_EXCEEDED` | 实收与申请件数差异超 5%，请驳回后重新申请 | T1-5 登记差异边界（≤5% 按实登记+备注） | T1-BE |
| 50352 | `INBOUND_CORRECTION_WINDOW_CLOSED` | 登记已超 24 小时，请通过盘点调整 | R3 超窗（数据库时间比对） | T1-BE |
| 50353 | `INBOUND_CORRECTION_PENDING_EXISTS` | 该单已有待审批的纠错申请 | R3 防重（pending_flag 唯一兜底） | T1-BE |
| 50354 | `INBOUND_CORRECTION_INVALID` | 纠错件数无效 | new_qty<0 / 与实登相同 / 非正向链或非已入库单据 | T1-BE |
| 50355 | `STOCKTAKE_ITEMS_INVALID` | 盘点明细为空或存在重复商品 | items 空 / 同单 SKU 重复 / 实物数<0 | T3-W2 |
| 50356 | `STOCKTAKE_OPEN_EXISTS` | 该商户已有进行中的盘点单 | 同商户 DRAFT/PENDING_APPROVAL 在途（防双重盈亏） | T3-W2 |
| 50357–50359 | 预留 | — | T3 后续增补 | — |
| 50360 | `BATCH_FEATURE_NOT_READY` | 批次功能开发中，暂不可开启 | D-13 禁改防御（T4-W1 解除后该码转「专用端点外禁改」语义保留） | T3-W1 |
| 50361 | `BATCH_TOGGLE_RATE_LIMITED` | 批次开关 24 小时内最多操作 2 次 | T4-1（Redis 计数） | T4-W1 |
| 50362 | `BATCH_NO_DUPLICATE` | 该批次号已存在 | uk(wholesaler,sku,batch_no) 冲突转译 | T4-W1 |
| 50363 | `BATCH_NOT_FOUND` | 批次不存在 | 不存在/跨商户按不存在（不泄漏存在性） | T4-W1 |
| 50364 | `BATCH_EXPIRED_CONFIRM_REQUIRED` | 该批次已过期，入库需二次确认 | 04 §3.1 强警告凭据缺失（临期仅警告放行） | T4-W1 |
| 50365 | `CLEARANCE_BATCH_NOT_CLEARABLE` | 该批次无需清库 | 非待清理状态 / 推算剩余为 0 / 在途 QK 已存在 | T4-W2 |
| 50366 | `CLEARANCE_PHOTO_REQUIRED` | 清库须上传实物照片 | R19 刚性，不受拍照开关影响 | T4-W2 |
| 50367 | `EXPIRY_NOTIFY_RATE_LIMITED` | 24 小时内已通知过该批次 | D-12 手动一键通知限频 | T4-W2 |
| 50368–50369 | 预留 | — | T4 后续增补 | — |

复用现有码（不新占）：`STOCK_NOT_ENOUGH(50251)` 退货/纠错/盘亏登记不足语义、`50330/50331` 全部新状态机不可达/CAS、`50313` R14、`42004` WE 无授权位、`50340` 附件校验、`40205/40206` 批次日期校验。已同步登记 `05-error-codes.md`（P3b 段）。

---

## 5. API 端点清单（角色 / 波次归属；风格=现状 `POST /resources/{id}/{action}`）

### 5.1 T1 正向申请链（T1-BE）

| 方法/路径 | 角色 | 说明 |
|---|---|---|
| POST `/api/v1/wholesaler/inbound-requests` | WA/WE(INBOUND_SUBMIT) | 提交申请：`{items:[{skuId, qty, palletQty?, remark?}]}` 多行拆 N 单共享 batch_submit_id；requireWholesalerActive；零库存 |
| GET `/api/v1/wholesaler/inbound-requests?status=&source=` | WA/WE | 我的申请列表（现有列表扩过滤；WE 只读先例） |
| POST `/api/v1/wholesaler/inbound-requests/{id}/withdraw` | WA/WE(INBOUND_SUBMIT) | R1：`{reason 必填}`，仅 SUBMITTED（50350） |
| GET `/api/v1/tenant/inbound?status=SUBMITTED` | WK/TA | 待受理队列（现有控制器 `/api/v1/tenant/inbound` 沿用，12 §6.1 路径注） |
| POST `/api/v1/tenant/inbound/{id}/accept` | WK | 受理锁单（CAS SUBMITTED→ACCEPTED） |
| POST `/api/v1/tenant/inbound/{id}/reject` | WK | R2：`{reason: QTY\|QUALITY\|BATCH\|OTHER, remark 必填, attachments?≤5}` |
| POST `/api/v1/tenant/inbound/{id}/register` | WK | 登记：`{actualQty, palletQty, remark?(差异非0必填), attachments?≤5}`；5% 边界 50351；此刻 addStock |
| POST `/api/v1/tenant/inbound/{id}/print` | WK | printed_at/print_count++，非状态节点，登记前后均可 |
| POST `/api/v1/tenant/inbound/{id}/corrections` | WK | R3 发起：`{newQty, reason 必填}`；24h/防重/合法性 50352-50354 |
| GET `/api/v1/tenant/inbound/corrections?status=` | TA/WK | 纠错列表（TA 审批中心） |
| POST `/api/v1/tenant/inbound/corrections/{id}/decide` | TA | `{conclusion: APPROVED\|REJECTED, remark(REJECTED 必填)}`；APPROVED 走 §1.3 封顶事务 |

**WK 缺 SKU 名称端点补口（上线检查单 §5-4 遗留，T1-BE 顺手）**：`SkuServiceImpl` 的**只读列表路径**（GET `/api/v1/tenant/skus`、`/listed`）闸门由 requireWaOrTa 放宽为 **requireWkOrWaOrTa**（写路径维持 WA/TA 不变；requireWkOrTa 写法先例见 OutboundRequestServiceImpl:619）。前端随 T1-FE 将 `ta/Outbound.vue:448` 代建选货换回名称展示，交付报告 V-3 缺陷一并消除。

### 5.2 T3 退货 / 盘点

| 方法/路径 | 角色 | 波次 | 说明 |
|---|---|---|---|
| POST `/api/v1/wholesaler/return-requests` | WA | T3-W1 | `{skuId, qty, remark?}`；软校验在库；WE 不开放（D-9） |
| GET `/api/v1/wholesaler/return-requests?status=` | WA/WE | T3-W1 | WE 只读 |
| POST `/api/v1/wholesaler/return-requests/{id}/withdraw` | WA | T3-W1 | 仅 PENDING_ACCEPT，reason 必填 |
| GET `/api/v1/tenant/return-requests?status=` | WK/TA | T3-W1 | 受理队列 |
| POST `/api/v1/tenant/return-requests/{id}/accept` | WK | T3-W1 | CAS 受理 |
| POST `/api/v1/tenant/return-requests/{id}/register` | WK | T3-W1 | `{actualQty?, palletRelease?}` 登记时扣（D-7）：returnStock + RETURN 流水（负托盘） |
| POST `/api/v1/tenant/outbound-requests/{id}/register` **改造** | WK | T3-W1 | 出库登记加 `{palletRelease?}` 入参（默认建议值 §2.4-2）→ 追加 PALLET_RELEASE 流水（D-8=A 出库处补齐） |
| POST `/api/v1/tenant/count-sheets` | WK | T3-W2 | 建草稿 `{wholesalerId, items[{skuId, actualQty, palletDelta?, remark?}]}` |
| PUT `/api/v1/tenant/count-sheets/{id}` | WK | T3-W2 | 草稿/被驳回编辑重提 |
| POST `/api/v1/tenant/count-sheets/{id}/submit` | WK | T3-W2 | 提交定格 system_qty 快照 → PENDING_APPROVAL |
| GET `/api/v1/tenant/count-sheets?status=` / `/{id}` | WK/TA | T3-W2 | 详情含在途提示条数据 |
| GET `/api/v1/tenant/count-sheets/in-transit-hint?wholesalerId=` | WK/TA | T3-W2 | 「已确认未出库 N 张 M 件 + 在途已受理退货」聚合（盘点页护栏，§2.2） |
| POST `/api/v1/tenant/count-sheets/{id}/decide` | TA | T3-W2 | `{conclusion, remark(REJECTED 必填)}`；APPROVED 逐 SKU 锁内 GAIN/LOSS（D-10 封顶） |

### 5.3 T4 批次 / 临期 / 清库

| 方法/路径 | 角色 | 波次 | 说明 |
|---|---|---|---|
| POST `/api/v1/tenant/settings/batch-toggle` | TA | T4-W1 | `{enable, confirmed:true}`；24h≤2（50361）；关→启生成默认批次（§3.5） |
| GET `/api/v1/tenant/batches?wholesalerId=&skuId=&status=` | WK/TA | T4-W1 | 批次列表/下钻（含「无批次在池量」行） |
| PUT `/api/v1/tenant/batches/{id}` | WK/TA | T4-W1 | 默认批次补录 production/expiry（仅 source=DEFAULT 且未终态） |
| GET `/api/v1/wholesaler/batches?skuId=&status=` | WA/WE | T4-W1 | WA 侧下钻/临期卡（只读） |
| GET `/api/v1/tenant/batches/expiring` | WK/TA | T4-W2 | 预警列表，剩余天数升序（EXPIRING∪PENDING_CLEARANCE） |
| POST `/api/v1/tenant/batches/{id}/notify-wholesaler` | WK | T4-W2 | 一键站内信，24h 限 1（50367） |
| POST `/api/v1/tenant/clearance-requests` | WK | T4-W2 | 建 QK 草稿 `{batchId, qty(现场核数), reason, reasonRemark?, palletRelease?, attachments 必填}` |
| POST `/api/v1/tenant/clearance-requests/{id}/submit` / PUT `/{id}` | WK | T4-W2 | 提交 / 驳回后重提（与盘点同构） |
| GET `/api/v1/tenant/clearance-requests?status=` | WK/TA | T4-W2 | |
| POST `/api/v1/tenant/clearance-requests/{id}/decide` | TA | T4-W2 | APPROVED → clearStock 封顶 + EXPIRY_CLEARANCE 流水 + 批次 CLEARED + WA 凭证通知 |

Job（无端点）：`BatchRecalcJob` 02:00（FIFO 推算 + 临期扫描 + D-12 首发通知）、`BatchExpiryMarkJob` 02:30（归零标 PENDING_CLEARANCE）——均按租户 batch_enabled=1 过滤。通知类型扩：`INBOUND_SUBMITTED/ACCEPTED/REJECTED/REGISTERED`、`CORRECTION_PENDING/DECIDED`、`RETURN_CREATED/COMPLETED`、`STOCKTAKE_PENDING/DECIDED`、`BATCH_EXPIRING/BATCH_EXPIRED`、`CLEARANCE_PENDING/DECIDED`（notifications.type VARCHAR(32)，零 DDL）。

---

## 6. 波次拆分与测试关卡（供 Team Lead 派发；总波序沿 10 §四拍板）

```
T1-BE(V19) ──合并──▶ T1-FE ∥ T3-W1(V20) ──合并──▶ T3-W2(V21) ──合并──▶ T3-FE ∥ T4-W1(V22) ──▶ T4-W2(V23) ──▶ T4-FE
```

BE 五波**串行**（迁移号+流水枚举+DocStateMachine 矩阵单线）；「FE 与下一 BE 波并行」为常态并行点（12 §7 先例）。每波开工前再核 main 迁移最高号。

| 波 | 内容速览 | 依赖 | 测试关卡（合并闸门，JUnit+E2E 两类必过） |
|---|---|---|---|
| **T1-BE** | V19；4 状态启用+source=WA_SUBMIT；submit/withdraw/accept/reject/register/print 六端点；inbound_corrections+CORRECTION 流水（封顶）；INBOUND_SUBMIT 授权位；R13/R14 钩子；SKU 列表放行 WK；50350–50354 | FE-W2 等在途分支全合并 | JUnit：入库矩阵**逐格**断言（8 状态含代建 4 值交叉不可达）；提交/撤回/驳回/受理全程**库存零变化**断言；5% 边界（4.99/5.00/5.01）；纠错封顶三态（全在库/部分售出/售罄 applied=0）+ biz_time=原 INBOUND 锚点断言；R13 计数扩展回归；WE 授权三态（有位/无位/WA）；WK 拉 SKU 列表 200 回归 + WA/TA 写路径不放宽回归；mvn 全量绿（§7.1 适配后） |
| **T1-FE** | WA 提交表单（多行）/我的申请（撤回）；WK 待受理/受理/驳回弹窗/登记页（5% 提示）/打印视图；R3 表单+TA 审批弹窗（复用仲裁样式）；Outbound.vue 换回 SKU 名称；线框缺口（10 §1.3）先补 | T1-BE 合并 | Playwright：提交→受理→登记主链、撤回、驳回→一键复制重建；截图目检（test-plan 00-overview §3.5/§3.6） |
| **T3-W1** | V20；return_requests+RTN 状态机+5 端点；returnStock+RETURN 流水；**pallet_delta 列+出库登记 PALLET_RELEASE 补齐**（D-8=A）；batch_enabled 禁改+存量归 0（D-13，50360）；R13 扩 | T1-BE 合并 | JUnit：退货矩阵逐格；登记时扣+不足拒绝（STOCK_NOT_ENOUGH）；提交/受理/撤回零库存；托盘比例+WK 覆盖+双重封顶（含池 pallet=0）；出库登记后 Σpallet_delta 对账；存量流水 pallet_delta=0 不回填断言；店铺设置改 batchEnabled → 50360；虚拟线程并发：退货登记 × 出库确认同锁串行 |
| **T3-W2** | V21；count_sheets(+items)+PD 状态机；GAIN/LOSS+盘亏封顶（D-10）；in-transit-hint 端点；50355/50356 | T3-W1 合并 | JUnit：盘点矩阵逐格+REJECTED→DRAFT 重提；G9 封顶（审批期被出完→applied 按剩余+差额通知）；system_qty 快照时点（提交后出库不影响 diff）；在途提示聚合正确性；pending 唯一并发双建 50356；DISPUTE 冲销 × RETURN/LOSS 交叉用例（§2.5） |
| **T3-FE** | WA 退货表单/列表（三页线框 G4 先补）；WK 受理/登记页（托盘覆盖输入）；WK 盘点单（06 §3.6 去批次分支）+TA 审批弹窗（06 §2.3 样式）；托盘校准操作规程说明 | T3-W2 合并 | Playwright：退货全链、盘点建→提→批双路；截图目检 |
| **T4-W1** | V22；batches 表+入库批次列+流水 batch_id；batch-toggle 端点（默认批次/冻结/24h 限 2/禁改解除）；入库表单批次分支校验（50362/50364）；BatchRecalcJob 推算体 | T3-W2 合并 | JUnit：FIFO 推算纯函数矩阵（多批次分摊/带 batch_id 直扣/GAIN 抵扣/回补/启用时点切割/NULLS LAST 排序/幂等重跑）；开关双向+24h 限 2+再启用新默认批次；批次号唯一/过期二次确认；交易路径（deductStock 等）**零改动回归** |
| **T4-W2** | V23；02:00/02:30 双 Job；预警列表+一键通知（D-12 去重）；QK 全链+EXPIRY_CLEARANCE 流水+TA 审批；50365–50367 | T4-W1 合并 | JUnit：两 Job Service 直驱（新进入 EXPIRING 只发一次/状态不变不重发/手动 24h 限 1）；归零标记边界（当日到期不标、昨日标）；清库封顶（现场核数 vs 推算剩余 vs onhand 三值）；批次 CLEARED 后不复算；R13 含 QK 在途 |
| **T4-FE** | TA 开关双确认（06 §2.2/§10）；WK 临期列表+清库表单（06 §3.4c/§3.7 合并版）+TA 清库审批弹窗（套盘点样式）；批次下钻页+WA 临期卡+默认批次补录（G6 线框先补） | T4-W2 合并 | Playwright：开关启用→入库带批次→临期（造数）→清库全链；截图目检 |

---

## 7. 风险与既有代码影响面

### 7.1 现有 252 测试的适配清单（实测 31 文件/252 @Test）

| 触点 | 波次 | 改动 |
|---|---|---|
| SkuService/Pricing 相关权限测试（若断言纯 WK 42xxx 被拒） | T1-BE | 只读列表断言改 200；写路径 42xxx 断言**保留**（放宽仅限读） |
| TenantService 店铺设置测试（写 batchEnabled 成功路径） | T3-W1 | 改断言 50360；其余设置字段不受影响 |
| InventoryService 既有 5 方法测试 | 全程 | **零改动**（新增方法不触旧签名；deductStock 不动）；V20 后 movement 断言若整行比对需容忍新列默认 0 |
| 出库登记（register）测试 | T3-W1 | palletRelease 入参可选、默认建议值——旧用例不传参行为兼容，仅新增 PALLET_RELEASE 流水断言用例 |
| R13 precheck 计数测试 | T1-BE/T3/T4 各波 | 未结枚举每扩一类补一条正例（阻退驻）+ 存量用例不受影响（新表空） |
| 入库链 BE-W1 测试（confirm/dispute/72h Job） | T1-BE | **零改动预期**：正向链不产 PENDING_WA_CONFIRM、deadline 恒 NULL，Job 扫描集不变；回归跑绿即可 |
| V16 status 列默认值 | T1-BE | 正向链 insert 显式落 SUBMITTED（不依赖列默认 PENDING_WA_CONFIRM），无迁移改动 |

未结枚举终版（50314 文案同步）：询价 PENDING/CONFIRMED ∪ 出库 PENDING_ACCEPT/PRINTED/COMPLAINED ∪ 入库 PENDING_WA_CONFIRM/DISPUTED/**SUBMITTED/ACCEPTED** ∪ 仲裁 PENDING ∪ **退货 PENDING_ACCEPT/ACCEPTED ∪ 盘点 DRAFT/PENDING_APPROVAL ∪ 清库 DRAFT/PENDING_APPROVAL ∪ 纠错 PENDING**（盘点/清库/纠错虽 WK 发起，对象是该商户库存，同样阻退驻，10 §2.4-1）。

### 7.2 与 P3 异常链的衔接点

- **72h 确认/异议在正向链上的复用边界**：正向链登记直落 CONFIRMED、不经 PENDING_WA_CONFIRM ⇒ 72h Job/confirm/dispute 端点对 source=WA_SUBMIT 单**天然不可达**（状态永不满足）；WA 对自己提交的申请**无异议入口**（登记错误走 R3 纠错，24h 后走盘点）——文案在 WA 详情页明示。DocPreconditions/DocStateMachine/notifications/files 四件基建全量复用，零新基建。
- **封顶口径家族**（同构复用 12 §2.4，测试可共享脚手架）：异议冲销（已落地）→ R3 纠错改小（§1.3）→ 盘亏 LOSS（§2.2/D-10）→ 清库 EXPIRY_CLEARANCE（§3.4）。四处全部 `min(目标量, max(onhand,0))` + 差额备注定责 + 锁内原子。
- **仲裁表不扩**：R3 纠错/盘点/清库审批均为**单级 TA 审批**，走各自单据状态（与退驻审批同构），不落 arbitrations（该表语义=争议裁决，扩类型留 P5 注册表机制）。

### 7.3 数据与并发风险

| 风险 | 口径 |
|---|---|
| 盘点 system_qty 快照与审批时点漂移 | 有意设计：diff 以提交快照为准（WK 所见即所盘），生效量以审批时刻 onhand 封顶（D-10）——两时点语义分离，测试断言分开 |
| FIFO 推算 ≤1 日误差 | 已拍板接受（D-11=C）；清库 qty 以 WK 现场核数为准、盘点按 SKU 池，误差不进账 |
| PALLET_RELEASE qty=0 新形态 | 不进件数公式（Σ按 type，qty=0 自然无影响）；P4 托盘·天=Σpallet_delta；对账脚本需知晓该 type 无件数语义（P4 账单 PRD 标注） |
| 历史托盘账虚高（存量不回填） | §2.4-4 边界：首次盘点托盘校准收口，写入 T3-FE 上线操作规程 |
| Job 单实例假设 | 沿 12 §8.4：现单副本；多副本前 ShedLock 一并评估（SchedulingConfig 注释追加 02:00/02:30 两条） |
| 批次开关与在途入库单竞态 | 进行中单据按**提交时策略**走完（T4-1）：登记时按单据自身 batch_no 是否有值处理，不重校验当刻开关 |
| P4 计费锚点交付边界 | RETURN/GAIN/LOSS/EXPIRY_CLEARANCE/CORRECTION_* /PALLET_RELEASE 六类只保证 type/biz_time/pallet_delta/reversal_of_id 准确，**P3b 零金额计算**（08 §7.6 重申）；批次级计费下钻随方案 A 顺延，P4 按 SKU 粒度出账 |

---

## 附：T1-BE 据实现备注（2026-07-31，branch feat/p3b-inbound-apply，据实现编写）

> 实现与本设计一致处不重复；以下为落地时的补充口径与轻微偏差，T1-FE / 后续波次以此为准。

1. **端点路径全部按 §5.1 落地**；docNo 沿用 DocType.INBOUND 前缀 **`WK-`**（A3 零改造，正向链不启新前缀）。WK 待受理队列（`GET /tenant/inbound?status=SUBMITTED`）按创建时间**升序**（先到先受理），其余列表倒序。
2. **提交护栏补充**：单次 items ≤50 行（40001）；提交通知库管为**整批 1 条**（含张数/合计件数/首单号），收件人=新增 `AuthService.listActiveWkUserIdsOfTenant`（user_roles 推导先例，多 WK 全发，文案零角色码）。
3. **CAS 败方语义化**：撤回失败重读——已非 SUBMITTED → 50350、其余 50331；并发受理败方为 50331 或 50330（重读已 ACCEPTED 撞矩阵，时序两态）——前端两码均按「刷新重试」处理。
4. **登记端点防绕行**：`/{id}/register` 仅 source=WA_SUBMIT（代建链走原 POST /tenant/inbound），否则 50330；5% 边界为整型算式 `|actual−requested|×100 > requested×5`（无浮点误差，含等于放行实测 950/1000 过、949/1000 拒）。打印端点未加 source 限制（代建链补打无害，非状态节点）。
5. **R3 纠错托盘**：pallet_delta 列随 V20/T3-W1 才落——本波 CORRECTION_IN/OUT 流水以 remark `palletAdjusted=±N` 快照留痕（DISPUTE_REVERSAL remark 先例），V20 后新流水双写；比例=±ceil(原入库 pallet×applied/原 qty)，释放侧对在库托盘二次封顶。
6. **24h 窗口 SQL**：`registered_at > NOW() - INTERVAL '24' HOUR`（MySQL/H2 双方言实测通过）；防重=先查后写 + `uk_corr_req_pending` 部分唯一兜底（DuplicateKey→50353）；并发双裁由 status=PENDING 条件 CAS 决出（败方 50331）。
7. **纠错通知**：待审→租户联系人（getContactUserId，审批中心角标先例）；结论→发起 WK 单人（corr.wk_user_id）。
8. **R13 未结计数**：`countOpenForWholesaler` = 单据 (PENDING_WA_CONFIRM/DISPUTED/SUBMITTED/ACCEPTED) + 纠错 PENDING（同 document 域直连 InboundCorrectionMapper，合规）。
9. **SKU 放宽**：仅 `listByWholesaler`（GET /tenant/skus）闸门 requireWaOrTa→requireWkOrWaOrTa；`/listed` 本就无角色闸（登录+租户上下文）；写路径三处不动（测试断言 WK 写→42101 保留）。
10. **测试**：新增 `InboundForwardChainScenarioTest` 20 用例（矩阵 8×8 逐格/零库存断言/5% 三点/封顶三态+D-4 锚点/虚拟线程并发受理/WE 三态/越权矩阵/R13/SKU 回归/不变量扩 CORRECTION_±）；全量 270 绿（基线 250 零回归；§0-A10 的「252 @Test」实为 250 可执行+2 处注释字样）。

---

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-30 | 首版：P3b 三主题落地设计——正向申请链 4 状态+R1/R2/R3（纠错封顶）/退货登记时扣/盘点封顶/托盘账 D-8=A 补齐（pallet_delta+PALLET_RELEASE）/批次方案 C（batches+FIFO 离线推算+02:00/02:30 双 Job）/V19–V23 迁移/50350–50369 错误码/八波拆分与测试关卡 |
| v1.1 | 2026-07-31 | 附「T1-BE 据实现备注」10 条（docNo 前缀 WK-/整批通知/CAS 败方两态/防绕行/纠错托盘 remark 快照过渡/24h SQL 方言/R13 口径/SKU 放宽范围/测试 270 绿） |
