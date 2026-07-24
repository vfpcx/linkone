# 12 · P3「完整单据与履约异常」架构设计（T5 状态机 / T1 入库异常链 / T2 出库异常链 / 双仲裁）

> 项目：仓储云 · 编写：架构师 Agent · 2026-07-24
> 真源：`09-p3-decision-options.md` v2 拍板记录表（三题全 B + 两修正同意）
> 输入：`product/08-p3-requirements-extract.md`（T1/T2/T5 + G1–G10）、`03-database-schema.sql` 蓝图、`04-api-spec.md`、`05-error-codes.md`
> 风格：据实现编写（参照 10-onboarding-design.md）——所有"现状"均经 2026-07-24 对 main 分支实测核对。
> 范围：本文档覆盖拍板解锁的三条链（T5 出库状态机补拆、T1 代建入库异常链、T2 出库异常链）+ 双仲裁最小闭环 + 支撑基建（流水扩展/通知/附件/调度）。**T1 的 WA 正向申请链（R1/R2/R3）、T3 退货/盘点、T4 批次临期不在本文档内**，属 P3 后续波次另行设计。

---

## 0. 拍板结论 → 架构约束（速览）

| 拍板 | 架构约束 |
|---|---|
| 问题一 B | 待确认库存与正常库存无差别可售；`reverseInboundForDispute` 按剩余在库封顶（§2.4 精确口径）；差额落仲裁单 `shortfall_qty` 交 TA 定责 |
| 问题二 B | **库存扣减时点不动**（询价确认即扣，P1 语义保留）；出库单补拆 待受理→已打印→已出库；R4/R8 走**反向回补流水**（独立流水，禁止 update 原流水，与 D22 同构） |
| 问题三 B | 双仲裁统一落一张 `arbitrations` 表（多态单据引用）；结论枚举双套；仲裁通过恢复流水 `biz_time` 沿用原入库时间戳（G10）；仅站内信通知+附件 URL，调证/多轮沟通 P5 |
| 修正一 | 单据号沿用 `WK-/CK-/XJ-` + 4 位日序（`DocumentNumberServiceImpl` 现状）；新前缀按产品 05 §7.1 修订版：`RTN-`(退货)/`PD-`(盘点)/`QK-`(清库) 预留进 DocType，`YY-`(入库异议仲裁)/`KS-`(出库客诉) 本波启用（仲裁单入统一单据号体系） |
| 修正二 | **Flyway 自 V15 起**。实测（2026-07-24，main@c563332）：`backend/src/main/resources/db/migration/` 现有 V1–V14（V14 `merge_wa_placeholder_role` 已合入 main），无在途分支占用更高版本 → V15 确认可用 |

**全链共同不变量**（05 §3.1 库存公式的 P3 扩展）：

```
inventories.qty = Σ(INBOUND) − Σ(OUTBOUND) + Σ(OUTBOUND_REVERSAL) − Σ(DISPUTE_REVERSAL) + Σ(DISPUTE_RESTORE)
```

任何冲销/回补/恢复都**新增流水**，永不修改或删除既有流水；`qty` 恒 ≥ 0（封顶口径保证）。

---

## 1. 单据状态机引擎与出库单补拆（T5）

### 1.1 引擎形态：转换表 + CAS，不换存储模型

P1 现状：status 为 String 常量 + 条件更新 CAS（`InquiryServiceImpl.confirmByWa` 的 `UPDATE ... WHERE status='PENDING'` 校验 affected==1；P2 入驻/退驻审批同款先例）。**P3 沿用该底座**，引擎只做三件事，落 `com.cangchu.document.statemachine`：

```java
public final class DocStateMachine {
    // 每单据类型一张静态迁移表 Map<String from, Set<String> to>
    static final Map<String, Set<String>> OUTBOUND_TRANSITIONS = Map.of(
        PENDING_ACCEPT, Set.of(PRINTED, WITHDRAWN),
        PRINTED,        Set.of(COMPLETED, CANCELLED, PENDING_ACCEPT),
        COMPLETED,      Set.of(COMPLAINED),
        COMPLAINED,     Set.of(COMPLETED));
    /** 前置校验：不可达迁移统一抛 DOC_STATE_TRANSITION_INVALID(50330) */
    public static void assertCanGo(DocKind kind, String from, String to);
    /** CAS 迁移：UPDATE...WHERE id=? AND status=from，affected!=1 抛 50331（并发被抢占） */
    public static <T> boolean casTransition(BaseMapper<T> mapper, Long id, SFunction<T,String> statusCol,
                                            String from, String to, Consumer<LambdaUpdateWrapper<T>> extraSet);
}
```

- **不引入** Spring StateMachine / 事件总线：P1/P2 全部副作用都是同事务直调（先例），P3 保持；跨域通知 = 同事务写 `notifications` 行（§4.3）。
- 商户状态前置钩子（08 §7.5 R14 提示）：把「wholesaler 必须 ACTIVE」抽成 `DocPreconditions.requireWholesalerActive(wholesalerId)`（内部即现 50313 校验），代建入库/代建出库/WA 手动出库提交三处复用，不再散落 if。

### 1.2 出库单状态枚举与迁移矩阵

存量值 `COMPLETED` 语义不变（=已出库）。新增值均 ≤32 字符（列已是 VARCHAR(32)）：

| 值 | 含义 | 进入方式 |
|---|---|---|
| `PENDING_ACCEPT` | 待受理 | 询价确认生成（改造点 §1.4）；WA 手动出库提交 |
| `PRINTED` | 已打印 | WK 打印（printed_at、print_count++） |
| `COMPLETED` | 已出库 | WK 登记出库（completed_at）；**代建出库直达**（source=WK_CREATED） |
| `WITHDRAWN` | 已撤回 | R4：待受理时 WA 直接撤 |
| `CANCELLED` | 已撤销 | R4：已打印 + WK 二次确认；R8：意向单作废联动 |
| `COMPLAINED` | 客诉中 | WA 对代建出库 30 天内客诉（对齐 03 蓝图命名） |

迁移矩阵（行=from，列=to；✅ 合法，其余全部 50330 不可达）：

| from \ to | PENDING_ACCEPT | PRINTED | COMPLETED | WITHDRAWN | CANCELLED | COMPLAINED |
|---|---|---|---|---|---|---|
| PENDING_ACCEPT | — | ✅打印 | ❌ | ✅R4 直撤 | ✅R8 联动 | ❌ |
| PRINTED | ✅WK 重新核对回退 | —(补打不迁移) | ✅登记出库 | ❌ | ✅R4 二次确认 / R8 联动 | ❌ |
| COMPLETED | ❌ | ❌ | — | ❌ | ❌ | ✅客诉(30d, WK_CREATED) |
| COMPLAINED | ❌ | ❌ | ✅OPS 裁决(任何结论) | ❌ | ❌ | — |
| WITHDRAWN / CANCELLED | 终态，全 ❌ | | | | | |

不可达红线（04 §1.2）：已出库→已撤回/已撤销 ❌；已撤销→已出库 ❌——由矩阵天然覆盖，JUnit 须逐格断言（§7 测试关卡）。

### 1.3 库存语义（不变量，拍板二 B）

**扣减时点唯一且不动**：出库单在「创建成非终撤状态」的瞬间，其数量已从库存扣除。三种来源统一：

| 来源(source) | 扣库存时点 | 状态起点 |
|---|---|---|
| `INQUIRY_AUTO` | 询价确认瞬间（P1 现状，同事务） | PENDING_ACCEPT（改造前 COMPLETED） |
| `WA_SUBMIT` | WA 提交出库申请瞬间（同构，无超卖窗口） | PENDING_ACCEPT |
| `WK_CREATED` | 代建提交瞬间（P1 语义子集） | COMPLETED（直达） |

推论：**任何** WITHDRAWN/CANCELLED 迁移都必须伴随一条 `OUTBOUND_REVERSAL` 回补流水（§1.5）；PRINTED↔PENDING_ACCEPT、PRINTED→COMPLETED、COMPLAINED↔COMPLETED 均为纯作业/争议记录，**不动库存**。中间状态期间账实偏差（货在仓账已减）为拍板接受项，盘点口径说明留 T3。

### 1.4 询价确认改造点（唯一触 P1 主链的改动）

`InquiryServiceImpl.confirmByWa`：生成出库单处 `STATUS_COMPLETED` → `PENDING_ACCEPT`（扣库存、CAS、议价逻辑全部不动）。询价单终态联动调整：确认后停在 `CONFIRMED`，该询价名下**全部**出库单到 `COMPLETED` 时（登记出库的同事务内检查）才迁 `COMPLETED`。

### 1.5 反向回补流水：复用 stock_movements + 类型扩展（不建新表）

流水表已有 `type VARCHAR(16)`/`qty`/`ref_doc_no`，扩展列见 §5 V15。类型枚举（qty 恒正，方向由 type 表达——现约定不变）：

| type | 方向 | 触发 | biz_time（计费锚点，7.6 口径） |
|---|---|---|---|
| `INBOUND` / `OUTBOUND` | +/− | 现状 | 回填=created_at；入库次日起算/出库当日截止 |
| `OUTBOUND_REVERSAL` | + | R4 撤回 / R8 作废联动 | =被回补 OUTBOUND 流水的 biz_time，且 `reversal_of_id` 指向原流水 → P4 配对抵消，计费视同从未出库 |
| `DISPUTE_REVERSAL` | − | WA 异议冲销（封顶口径 §2.4） | =异议时刻（计费截止异议日，D39） |
| `DISPUTE_RESTORE` | + | TA 仲裁通过恢复 | =**原入库单 created_at**（G10：沿用原时间戳；created_at 仍为真实写入时刻，审计/计费双轨清晰） |
| `RETURN` / `GAIN` / `LOSS` / `EXPIRY_CLEARANCE` | 预留 | T3/T4 波 | 本波仅扩枚举宽度，不实现 |

`InventoryService` 新增（全部沿用 Redisson 锁 `lock:inv:{wholesalerId}:{skuId}` + `doXxxInTx` 代理事务先例）：

```java
/** R4/R8 回补：锁内 qty += ctx.qty，写 OUTBOUND_REVERSAL（reversal_of_id=原 OUTBOUND 流水） */
InventoryVo reverseOutbound(OutboundReversalContext ctx);
/** 异议冲销：锁内按剩余在库封顶（§2.4），写 DISPUTE_REVERSAL；返回实际冲销/差额 */
DisputeReversalResult reverseInboundForDispute(InboundDisputeContext ctx);
/** 仲裁通过恢复：锁内 qty += reversedQty，写 DISPUTE_RESTORE（biz_time=原入库时间戳） */
InventoryVo restoreInboundAfterArbitration(DisputeRestoreContext ctx);
```

### 1.6 单据号与打印（T5 收尾项）

- `DocType` 增 `DISPUTE_ARBITRATION("YY")`、`COMPLAINT("KS")`（本波启用，仲裁单号 §4.1）+ `RETURN("RTN")`、`STOCKTAKE("PD")`、`CLEARANCE("QK")`（预留，前缀以产品 05 §7.1 修订版为准——本文档 v1 曾写 CT-/EC-，据 Team Lead 契约对账改为 PD-/QK-）；现有 WK/CK/XJ、4 位日序、Redis 日切 TTL（Q-D10）全部维持。
- 打印（G8 建议采纳）：HTML 打印视图（前端 window.print，模板见 design-system/print-templates），**打印记录落库**——`printed_at` 首打时间 + `print_count` 累计（补打 count++ 不迁移状态）；PDF 生成延至 P4 账单复用。

---

## 2. 入库异常链（T1 · 代建 72h + 异议冲销 + TA 仲裁）

### 2.1 入库单状态枚举（本波子集）

现状：`inbound_requests.status VARCHAR(16)` 仅 `REGISTERED`（P1 WK 代建登记即完成）。V16 扩列至 VARCHAR(32)（对齐 03 蓝图/出库表）并启用：

| 值 | 含义 |
|---|---|
| `PENDING_WA_CONFIRM` | 待 WA 确认（wk_created 登记即生效、库存已加、计费已起算、**可售**） |
| `CONFIRMED` | 已确认（WA 接受 / 72h 自动 auto_accepted=1 / 仲裁通过） |
| `DISPUTED` | 争议中（WA 异议，冲销已执行，等 TA 仲裁） |
| `REVOKED` | 已撤销（仲裁驳回，冲销保留，货线下处理） |

迁移矩阵：`PENDING_WA_CONFIRM → CONFIRMED（wa_confirm / auto）| DISPUTED（dispute）`；`DISPUTED → CONFIRMED（仲裁通过）| REVOKED（仲裁驳回）`；其余全不可达。**存量 `REGISTERED` 行一次性回填为 `CONFIRMED`**（V16：confirmed_at=created_at、auto_accepted=0、source='WK_CREATED'——P1 语义即"登记即认"，测试断言小改见 §8.1）。WA 正向申请链的 SUBMITTED/ACCEPTED/… 值留待 R1/R2/R3 波启用，枚举命名此处一并冻结（对齐 03 蓝图 §6.1）。

### 2.2 登记改造与 72h 窗口

`InboundRequestServiceImpl.registerByWk` 改动：状态落 `PENDING_WA_CONFIRM`、`source='WK_CREATED'`、`wa_confirm_deadline = created_at + 72h`（**登记时显式落列**——队列按倒计时升序排序、Job 用 SQL 比较数据库时间，均不依赖应用时钟，BND-S3-01 先例）；同事务写站内信通知归属 WA（§4.3）。库存/流水/计费行为不变（登记即 addStock + INBOUND 流水，拍板一 B：不冻结）。

### 2.3 WA 确认 / 异议端点

- `POST /api/v1/wholesaler/inbound-requests/{id}/confirm`：CAS `PENDING_WA_CONFIRM→CONFIRMED`（wa_confirm_at=now, auto_accepted=0）。归属校验：操作人须为该 wholesaler 的 ACTIVE WA（`requireWaRole` 先例）；WE 需 `INBOUND_CONFIRM` 授权位（G7，`WePermissions` 白名单扩 1 位）。
- `POST /api/v1/wholesaler/inbound-requests/{id}/dispute`，body `{reason(必填≤512), attachments?(URL[]≤5)}`：单事务内——
  1. CAS `PENDING_WA_CONFIRM→DISPUTED`（affected!=1 → 50331，天然防重复异议/与自动确认竞态：Job 也走 CAS，两边只有一个赢）；
  2. `reverseInboundForDispute`（§2.4）拿到 `reversedQty/shortfallQty`；
  3. 建 `arbitrations` 行（biz_type=INBOUND_DISPUTE，PENDING，含 reason/attachments/reversed_qty/shortfall_qty；doc_no=`generate(DISPUTE_ARBITRATION)` → `YY-…`）；
  4. 通知 TA（审批中心角标）+ WK。
- 超时后 confirm/dispute 均因 CAS 失败返回 50332（窗口已关，单据已自动确认）。

### 2.4 异议冲销封顶口径（精确定义）

在 Redisson 锁 `lock:inv:{wholesalerId}:{skuId}` 内原子计算并执行（锁内消灭「计算→扣减」间隙的 TOCTOU，与 deductStock 同级）：

```
Q          = 该入库单登记件数（inbound_requests.qty）
onhand     = 锁内读取 inventories.qty（该 (wholesaler, sku) 池当前在库）
reversedQty  = min(Q, max(onhand, 0))          // 冲销件数，写 DISPUTE_REVERSAL 流水（reversedQty=0 时不写流水但仍立仲裁单）
shortfallQty = Q − reversedQty                  // 已售差额，只записи仲裁单，不动库存
palletReversed = min(ceil(inbound.pallet_qty × reversedQty / Q), inventories.pallet_qty)   // 托盘按比例、双重封顶
```

**边界定义**：

1. **「该单未售余量」的归属口径（多单混库）**：P1/本波无批次，同 (wholesaler, sku) 多笔入库共池、个体不可区分。采用**保守归属**：在库件优先视为被异议单的货（即封顶取 `min(Q, onhand)`，不按比例摊薄）。理由：a) 保证库存永不打负；b) 对异议方最有利（能退的都退），差额只会更小；c) 真实归属争议正是 TA 仲裁的定责内容，系统不裁量。批次启用后（T4）该口径升级为按 batch 精确归属，本口径写明为「无批次池化下的封顶规则」。
2. **部分出库 / FIFO 已拣**：确认即扣语义下不存在「已拣未扣」中间占用——已确认未出库的量已从 `onhand` 扣除，天然不计入「剩余在库」（那部分正是差额，正确）。
3. **异议后的回补不追溯**：冲销以**异议时刻快照**一次性执行；此后发生的 R4 撤回回补（OUTBOUND_REVERSAL）不重算冲销、不追加二次冲销——差额定责时 TA 可见全部流水自行裁量。仲裁单落 `reversed_qty/shortfall_qty` 后不再变更。
4. **onhand=0（已售罄）**：reversedQty=0，不写冲销流水，全额进差额；单据照常入 DISPUTED，仲裁照常。
5. **幂等**：冲销唯一入口是 §2.3 的 CAS 迁移成功分支，单据状态即幂等闸门，无需流水去重。

### 2.5 72h 自动确认 Job（复用 SchedulingConfig 基建）

`InboundAutoConfirmJob`（`com.cangchu.document.job`），完全复刻 `WholesalerArchiveJob` 先例：`@Scheduled` 包一层吞异常记日志、任务体独立在 Service 供测试直驱、时间比较在 SQL 内用数据库时间。

- **cron：`0 5/10 * * * ?`**（每 10 分钟，分钟位 5/15/…/55）——72h 截止精度 ≤10min 足够；避开整点，且与既有 03:40 归档、04:17 全量索引不同分钟位不冲突；**02:00/02:30 分钟位留给 T4 临期扫描/归零标记**（后续波按此预约，不要占用）。
- 任务体 `InboundRequestService.autoConfirmExpired()`：`SELECT id FROM inbound_requests WHERE status='PENDING_WA_CONFIRM' AND wa_confirm_deadline <= NOW()`（V16 建索引 `idx_inb_status_deadline`），逐行 CAS 迁 `CONFIRMED`（auto_accepted=1, wa_confirm_at=NOW()）——逐行 CAS 而非批量 UPDATE，因每行成功后要写 WA 通知（50007 语义：72h 已过自动确认）。与 WA 手动操作竞态由 CAS 决出唯一赢家。单实例部署，无需分布式锁；多副本前置条件记入 §8.5。

### 2.6 TA 仲裁（入库侧）

- `GET /api/v1/tenant/arbitrations?bizType=INBOUND_DISPUTE&status=PENDING`（TA，审批中心列表复用现有弹窗样式——前端事）；详情含单据引用、reason、attachments、reversed/shortfall、全量相关流水（供定责）。
- `POST /api/v1/tenant/arbitrations/{id}/decide`，body `{conclusion: APPROVED|REJECTED, remark(驳回必填), liability?}`，单事务：
  - **liability 校验**（产品刚性规则）：当 `conclusion=REJECTED ∧ shortfall_qty>0` 时 liability 必填（四选枚举 §4.1）；其余场景传入非空、或枚举非法，均抛 50342；
  - 仲裁单 CAS `PENDING→DECIDED`（防并发双裁，affected!=1 → 50334）；
  - `APPROVED`（异议成立不成立？——**语义冻结：APPROVED=异议不成立、恢复流水**，与 04 §1.1「仲裁通过→已确认(恢复流水)」一致）：`restoreInboundAfterArbitration`（qty += reversed_qty，DISPUTE_RESTORE 流水 **biz_time=原入库单 created_at**，G10）+ 入库单 CAS `DISPUTED→CONFIRMED`；
  - `REJECTED`：保留冲销，入库单 CAS `DISPUTED→REVOKED`；
  - 双方站内信（WA+WK），差额部分文案注明「线下定责依据，平台不接资金」。
- 恢复只回 `reversed_qty`（差额那部分货已离仓，不恢复）；`shortfall_qty` 仅作 TA 结论备注的定责输入。计费重算不做（G10：P3 只保证流水类型/biz_time 准确可回溯，账面重算标注给 P4）。

---

## 3. 出库异常链（T2 · R4 撤回 / R8 作废联动 / 代建出库 / 30 天客诉）

### 3.1 R4 出库撤回（WA 发起）

`POST /api/v1/wholesaler/outbound-requests/{id}/withdraw`：

| 当前状态 | 行为 |
|---|---|
| `PENDING_ACCEPT` | 单事务：CAS→`WITHDRAWN` + `reverseOutbound` 回补流水 + 意向单联动检查（下行）+ 通知 WK |
| `PRINTED` | 不迁移状态：置 `withdraw_requested=1, withdraw_requested_at`，通知 WK（纸质单已在现场，需人核）；WK 端 `POST /api/v1/tenant/outbound-requests/{id}/confirm-withdraw` 二次确认 → CAS `PRINTED→CANCELLED` + 回补 + 联动；WK 拒绝则 `POST .../reject-withdraw` 清 flag 通知 WA |
| `COMPLETED` | 50335（不可撤，走 R5 退货——T3 波） |

**意向单联动口径（本设计冻结）**：P1 一张询价单按明细生成 N 张出库单。R4 撤回作用于**单张出库单**；仅当该询价名下**全部**出库单均已 WITHDRAWN/CANCELLED 时，询价单 CAS `CONFIRMED→PENDING` 回滚（RT 通知「已回到待确认」）；部分撤回时询价单停留 CONFIRMED（剩余出库单继续履约）。

### 3.2 R8 已确认意向单作废（WA 发起）

`POST /api/v1/tenant/inquiry/{id}/void`（挂现有 InquiryController）：前置——询价 `CONFIRMED` 且名下出库单**均非** `COMPLETED`（有已出库的抛 50337）。单事务：询价 CAS `CONFIRMED→VOIDED`（新增值，voided_at）→ 遍历名下 PENDING_ACCEPT/PRINTED 出库单逐张 CAS→`CANCELLED` + `reverseOutbound` 回补（每张一条 OUTBOUND_REVERSAL，reversal_of_id 配对）→ 通知 WK + RT。已打印的单在 R8 下**不需** WK 二次确认（作废是整单意思表示，PRD 04 §1.2「意向单来源联动回滚」），但通知 WK 收回纸单。

### 3.3 代建出库（US-WK-02b）

`POST /api/v1/tenant/wk/outbound-requests`，body `{wholesalerId, skuId, qty, palletQty?, receiverPhone?, confirmed: true, restatedQty?}`：

- `DocPreconditions.requireWholesalerActive`（R14）+ requireWkRole；
- **大额校验**：锁外预读 onhand，`qty > onhand × 50%` 时要求 `restatedQty == qty`（复述件数，06 §3.5b 版为准），不满足抛 50338；`confirmed=true` 为显著二次确认弹窗的后端凭据（缺省抛 60301 语义码 50338 同段）；
- 单事务：deductStock（不足即 STOCK_NOT_ENOUGH，现状）+ 建单 `status=COMPLETED, source='WK_CREATED', completed_at=now` + 通知归属 WA（「已确认（代建）」队列=WA 出库单列表 filter source）；
- 留痕：wk_user_id（已有）+ operator IP 走现有日志，原始截图属前端埋点不落库（PRD「留痕强化」最小集）。

### 3.4 30 天客诉 → OPS 仲裁（仅判责）

- `POST /api/v1/wholesaler/outbound-requests/{id}/complain`，body `{reason(必填), attachments?}`：前置 `status=COMPLETED && source='WK_CREATED'`（非代建抛 50004 现有码）且 `completed_at ≥ NOW()−30d`（超窗抛 50339）；单事务：出库单 CAS `COMPLETED→COMPLAINED` + 建 `arbitrations`（biz_type=OUTBOUND_COMPLAINT, PENDING，doc_no=`generate(COMPLAINT)` → `KS-…`）+ 通知 WK/OPS。
- OPS 端：`GET /api/v1/ops/arbitrations?bizType=OUTBOUND_COMPLAINT&status=`（简单列表）；`POST /api/v1/ops/arbitrations/{id}/decide`，body `{conclusion: WK_LIABLE|WA_LIABLE|NEGOTIATED|NO_LIABILITY, remark?}`：仲裁单 CAS `PENDING→DECIDED` + 出库单 CAS `COMPLAINED→COMPLETED`（**库存、流水、账单一概不动**，D43）+ 双方通知（结论仅线下赔偿依据）。
- 路由：`/api/v1/ops/**` 已在 SaTokenConfig checkLogin 段（P2 Wave1），OPS 角色校验走 `authService.hasRole` 平台维度先例；路由守卫角色检查已在 feat/p2-onboarding-fe 修复（09 修正三）。

---

## 4. 仲裁表 / 通知 / 附件（最小闭环基建）

> 与产品 Agent 在写的 `09-p3-arbitration-prd.md` 同源（均以 09 拍板为真源）；**落库字段以本节为准**。03 蓝图的 `complaints` 通用客诉表（category/priority/assignee/多轮 resolution）属 P5 完整版，本表是其 P3 最小前身，P5 扩展而非推倒。

### 4.1 arbitrations（V17，TenantLine 隔离白名单）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 |
| doc_no | VARCHAR(64) NOT NULL UNIQUE | 仲裁单号，入统一单据号体系（`DocumentNumberService.generate`，已上线格式 `前缀-{tenant简码}-{YYYYMMDD}-{4位日序}`）：INBOUND_DISPUTE→`YY-`、OUTBOUND_COMPLAINT→`KS-`；创建时生成（§2.3 步骤 3 / §3.4），`uk_arb_doc_no` 唯一索引兜底（G-5.1 双保险） |
| tenant_id | BIGINT NOT NULL | 自动填充（INBOUND_DISPUTE 由 TA 裁；OUTBOUND_COMPLAINT 由 OPS 裁但仍记涉事租户，OPS 查询绕 TenantLine 走 IGNORE 注解先例） |
| biz_type | VARCHAR(24) NOT NULL | `INBOUND_DISPUTE` / `OUTBOUND_COMPLAINT`（结论枚举合法性由 biz_type 决定，错配抛 50333） |
| ref_doc_type | VARCHAR(16) NOT NULL | 多态引用①：`INBOUND` / `OUTBOUND`（DocType 名对齐） |
| ref_doc_id | BIGINT NOT NULL | 多态引用②：单据主键 |
| ref_doc_no | VARCHAR(64) NOT NULL | 冗余单据号（列表展示免 join，创建时快照） |
| wholesaler_id | BIGINT NOT NULL | 涉事商户（列表过滤 + WA 端「看本商户」权限行） |
| initiator_user_id / initiator_role | BIGINT / VARCHAR(8) | 发起方（WA 或被授权 WE） |
| reason | VARCHAR(512) NOT NULL | 发起理由（必填） |
| attachments | VARCHAR(1024) NULL | JSON 数组，附件 URL ≤5 个（§4.4 上传） |
| reversed_qty / shortfall_qty | INT NULL | 仅 INBOUND_DISPUTE：实际冲销件数 / 已售差额（定责输入，落单后不可变） |
| status | VARCHAR(16) NOT NULL | `PENDING` / `DECIDED`（唯一性闸门=单据状态：单据只能进 DISPUTED/COMPLAINED 一次，天然一单一裁，无需部分唯一索引） |
| conclusion | VARCHAR(24) NULL | 入库：`APPROVED`(恢复流水)/`REJECTED`(保留冲销)；出库：`WK_LIABLE`/`WA_LIABLE`/`NEGOTIATED`/`NO_LIABILITY` |
| liability | VARCHAR(16) NULL | **差额定责**枚举 `WK_LIABLE`/`WA_LIABLE`/`NEGOTIATED`/`NO_LIABILITY`（产品刚性规则）：仅 `biz_type=INBOUND_DISPUTE ∧ conclusion=REJECTED ∧ shortfall_qty>0` 时**必填**（已售差额定责，供线下赔偿依据）；其余场景必须为 NULL（服务端双向校验，违规抛 50342） |
| conclusion_remark | VARCHAR(512) NULL | 结论备注（REJECTED 必填） |
| arbitrator_user_id / decided_at | BIGINT / DATETIME | 裁决人（TA 或 OPS）/ 时刻 |
| created_at / updated_at | DATETIME | 通用 |

索引：`idx_arb_tenant_type_status(tenant_id, biz_type, status, created_at)`、`idx_arb_ref(ref_doc_type, ref_doc_id)`、`idx_arb_wholesaler(wholesaler_id)`。

### 4.2 结论枚举与副作用绑定（引擎化校验）

`ArbitrationService.decide` 内以 biz_type 为键查「合法结论集 + 副作用函数」注册表——新增仲裁类型（P5 账单申诉等）只加注册项不改流程。

### 4.3 notifications（V17，站内信最小版）

| 列 | 类型 | 说明 |
|---|---|---|
| id / tenant_id | BIGINT | 雪花 / 自动填充 |
| recipient_user_id | BIGINT NOT NULL | 收件人（索引 `idx_ntf_recipient(recipient_user_id, read_at, created_at)`） |
| type | VARCHAR(32) NOT NULL | `INBOUND_PENDING_CONFIRM` / `INBOUND_AUTO_CONFIRMED` / `DISPUTE_CREATED` / `ARBITRATION_DECIDED` / `OUTBOUND_WITHDRAW_REQUESTED` / `OUTBOUND_PROXY_CREATED` / `COMPLAINT_CREATED` … |
| title / content | VARCHAR(128) / VARCHAR(512) | 模板由后端拼（产品文案表在 PRD） |
| ref_type / ref_id | VARCHAR(16) / BIGINT | 跳转引用（单据或仲裁单） |
| read_at / created_at | DATETIME | 已读 / 创建 |

`NotificationService.send(...)` 同事务写入（与业务同回滚，不引入 MQ）。端点：`GET /api/v1/notifications?page&size&unreadOnly`、`GET /api/v1/notifications/unread-count`、`POST /api/v1/notifications/{id}/read`（本人校验）。**短信**：US-WK-01b 的「站内信+短信」中短信一档降级为可开关 TODO（现仓仅有验证码 SmsUtil，业务短信模板/签名未备案，Q-D06 频次未定）——站内信为 P3 验收口径，短信留接口位。

### 4.4 附件上传（新最小基建，异议/客诉共用，后续入库照片可复用）

现仓**无任何文件上传/OSS 基建**（实测）。P3 最小方案：`com.cangchu.common.file`——

- `POST /api/v1/files`（登录用户，multipart 单文件）：≤5MB；魔数校验仅 jpg/png/webp；落盘 `${app.upload-dir:./data/uploads}/{yyyyMM}/{uuid}.{ext}`；返回 `{url:"/files/{yyyyMM}/{uuid}.{ext}"}`。
- WebMvc 静态映射 `/files/** → upload-dir`；SaTokenConfig 放行 GET `/files/**`（URL 含 UUID 不可枚举，试点可接受；P5 换 OSS 签名 URL 时前端字段结构不变）。

---

## 5. 数据库迁移（V15 起，已实测 main 最高 V14）

> 开工前再核一次 main + 在途分支（09 修正二要求）；以下每条=一个文件，与波次对齐：V15–V17 归 BE-W1，V18 归 BE-W2。

**V15__p3_stock_movement_ext.sql**（流水扩展，全链地基）
```sql
ALTER TABLE `stock_movements`
    MODIFY COLUMN `type` VARCHAR(32) NOT NULL,                -- DISPUTE_REVERSAL 等超原 16 位
    ADD COLUMN `biz_time` DATETIME NULL COMMENT '计费语义时间锚点(7.6)：默认=created_at；DISPUTE_RESTORE=原入库时间戳(G10)',
    ADD COLUMN `reversal_of_id` BIGINT NULL COMMENT '反向流水指向的原流水id（OUTBOUND_REVERSAL 必填，P4 配对抵消）',
    ADD COLUMN `remark` VARCHAR(255) NULL;
UPDATE `stock_movements` SET `biz_time` = `created_at` WHERE `biz_time` IS NULL;   -- 存量回填
-- 加索引 idx_mv_wholesaler_sku_type(wholesaler_id, sku_id, type)（对账/仲裁详情查询）
```

**V16__p3_inbound_confirm_chain.sql**（入库 72h 链）
```sql
ALTER TABLE `inbound_requests`
    MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_WA_CONFIRM',
    ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'WK_CREATED' COMMENT 'WA_SUBMIT/WK_CREATED（R1-R3 波启用前者）',
    ADD COLUMN `wa_confirm_deadline` DATETIME NULL COMMENT '代建 72h 确认截止（登记时=created_at+72h）',
    ADD COLUMN `wa_confirm_at` DATETIME NULL,
    ADD COLUMN `auto_accepted` TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN `disputed_at` DATETIME NULL;
-- 存量回填：P1 REGISTERED 语义=登记即认 → CONFIRMED
UPDATE `inbound_requests` SET `status`='CONFIRMED', `wa_confirm_at`=`created_at` WHERE `status`='REGISTERED';
-- 加索引 idx_inb_status_deadline(status, wa_confirm_deadline)（Job 扫描）、idx_inb_ws_status(wholesaler_id, status)（WA 队列）
```

**V17__p3_arbitrations_notifications.sql**（§4.1 arbitrations + §4.3 notifications 两表全量建表——arbitrations 含 `doc_no VARCHAR(64) NOT NULL` + `uk_arb_doc_no` 唯一、`liability VARCHAR(16) NULL`；索引如上；均入 TenantLine 白名单——代码侧 MybatisPlusConfig 同步）

**V18__p3_outbound_states.sql**（出库状态补拆，BE-W2）
```sql
ALTER TABLE `outbound_requests`
    ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'INQUIRY_AUTO' COMMENT 'INQUIRY_AUTO/WA_SUBMIT/WK_CREATED',
    ADD COLUMN `printed_at` DATETIME NULL, ADD COLUMN `print_count` INT NOT NULL DEFAULT 0,
    ADD COLUMN `completed_at` DATETIME NULL,
    ADD COLUMN `withdraw_requested` TINYINT NOT NULL DEFAULT 0, ADD COLUMN `withdraw_requested_at` DATETIME NULL,
    ADD COLUMN `pallet_qty` INT NOT NULL DEFAULT 0 COMMENT '出库托盘（代建/登记录入，回补按此还原）';
-- 存量回填：全部 COMPLETED；来源按 inquiry_id 判定；completed_at=created_at
UPDATE `outbound_requests` SET `source`= CASE WHEN `inquiry_id` IS NULL THEN 'WK_CREATED' ELSE 'INQUIRY_AUTO' END,
    `completed_at`=`created_at`;
ALTER TABLE `inquiry_requests` ADD COLUMN `voided_at` DATETIME NULL;   -- 状态值 VOIDED 复用现列
-- 加索引 idx_outb_status(tenant_id, status, created_at)
```

> H2 兼容注意（A1/A2/B1 踩坑先例）：索引内联/命名前缀规则沿用（mv_/inb_/outb_/arb_/ntf_）；`MODIFY COLUMN` 在 H2 用 `ALTER COLUMN ... SET DATA TYPE`——按仓内既有双方言写法处理（V11 有先例）。

---

## 6. API 增补与错误码

### 6.1 端点清单（归属 document 域，除标注外；风格=现状 `POST /resources/{id}/{action}`）

| 方法/路径 | 角色 | 说明 | 波次 |
|---|---|---|---|
| POST `/api/v1/wholesaler/inbound-requests/{id}/confirm` | WA/WE(INBOUND_CONFIRM) | 代建确认 | BE-W1 |
| POST `/api/v1/wholesaler/inbound-requests/{id}/dispute` | WA/WE(同上) | 异议：`{reason, attachments?}` → 冲销+立仲裁单 | BE-W1 |
| GET `/api/v1/wholesaler/inbound-requests?status=&page=` | WA | 待确认队列（PENDING_WA_CONFIRM 按 deadline 升序） | BE-W1 |
| GET `/api/v1/tenant/arbitrations?bizType=&status=` | TA | 仲裁列表（审批中心） | BE-W1 |
| POST `/api/v1/tenant/arbitrations/{id}/decide` | TA | `{conclusion: APPROVED\|REJECTED, remark, liability?(REJECTED∧差额>0 必填)}` | BE-W1 |
| GET/POST `/api/v1/notifications...`（3 个，§4.3） | 登录用户 | 站内信（新 notify 包） | BE-W1 |
| POST `/api/v1/files` | 登录用户 | 附件上传（common/file） | BE-W1 |
| POST `/api/v1/wholesaler/outbound-requests` | WA/WE | 手动出库申请（提交即扣，PENDING_ACCEPT） | BE-W2 |
| GET `/api/v1/wholesaler/outbound-requests?status=&source=` | WA | 出库单列表（含「已确认（代建）」队列=source 过滤） | BE-W2 |
| POST `/api/v1/wholesaler/outbound-requests/{id}/withdraw` | WA | R4（分状态行为 §3.1） | BE-W2 |
| POST `/api/v1/wholesaler/outbound-requests/{id}/complain` | WA/WE | 30 天客诉 `{reason, attachments?}` | BE-W2 |
| GET `/api/v1/tenant/outbound-requests?status=` | WK/TA | 作业列表 | BE-W2 |
| POST `/api/v1/tenant/outbound-requests/{id}/print` | WK | →PRINTED / 补打 count++ | BE-W2 |
| POST `/api/v1/tenant/outbound-requests/{id}/revert-to-pending` | WK | PRINTED→PENDING_ACCEPT（重新核对） | BE-W2 |
| POST `/api/v1/tenant/outbound-requests/{id}/register` | WK | 登记出库 →COMPLETED（+询价终态联动） | BE-W2 |
| POST `/api/v1/tenant/outbound-requests/{id}/confirm-withdraw` / `reject-withdraw` | WK | R4 已打印二次确认 | BE-W2 |
| POST `/api/v1/tenant/wk/outbound-requests` | WK | 代建出库（直达 COMPLETED + 大额校验） | BE-W2 |
| POST `/api/v1/tenant/inquiry/{id}/void` | WA | R8 作废联动（挂现有 InquiryController） | BE-W2 |
| GET `/api/v1/ops/arbitrations?bizType=&status=` | OPS | 客诉仲裁列表 | BE-W2 |
| POST `/api/v1/ops/arbitrations/{id}/decide` | OPS | `{conclusion: 四选, remark?}`（不动库存/账单） | BE-W2 |

> 路径注：现有入库控制器是 `/api/v1/tenant/inbound`（与 04-api-spec 蓝图 `/tenant/wk/inbound-requests` 有偏差）——**沿用现状路径**，04-api-spec 修订随本文档提交后由架构侧同步。`/api/v1/wholesaler/**` 已在 SaTokenConfig checkLogin 段（P2 Wave1）。

### 6.2 错误码分配（50330–50349，P3 单据异常链段）

实测占用：代码 ErrorCode 枚举已用 50201–50205、50280–50287、50290–50292、50300–50306、50310–50322；50323 起预留 P4 billing（O-5 占位）。**P3 取 50330–50349**，避开 50310–50329 全段：

| code | errorCode | 用户提示 | 场景 |
|---|---|---|---|
| 50330 | `DOC_STATE_TRANSITION_INVALID` | 当前状态不允许此操作 | 状态机不可达兜底（引擎统一抛） |
| 50331 | `DOC_STATE_CAS_CONFLICT` | 单据状态已变更，请刷新后重试 | CAS affected!=1（并发被抢占/重复提交） |
| 50332 | `INBOUND_CONFIRM_WINDOW_CLOSED` | 72 小时确认期已过，单据已自动确认 | 超窗 confirm/dispute |
| 50333 | `ARBITRATION_CONCLUSION_INVALID` | 结论选项与仲裁类型不符 | biz_type × conclusion 错配 / REJECTED 缺 remark |
| 50334 | `ARBITRATION_NOT_PENDING` | 该仲裁已有结论 | 仲裁单不存在/已裁决/跨租户按不存在 |
| 50335 | `OUTBOUND_NOT_WITHDRAWABLE` | 当前状态不可撤回（已出库请走退货） | R4 状态不符 |
| 50336 | `OUTBOUND_NO_WITHDRAW_REQUEST` | 该单无待确认的撤回申请 | WK confirm-withdraw 无 flag |
| 50337 | `INQUIRY_NOT_VOIDABLE` | 意向单当前不可作废（存在已出库单据） | R8 前置不满足 |
| 50338 | `OUTBOUND_LARGE_CONFIRM_REQUIRED` | 大额出库需复述件数确认 | 代建 >50% 未复述/未二次确认 |
| 50339 | `OUTBOUND_COMPLAINT_WINDOW_CLOSED` | 客诉期已过（出库后 30 天内可提） | 超窗客诉 |
| 50340 | `FILE_UPLOAD_INVALID` | 文件格式或大小不符合要求 | 上传魔数/尺寸校验 |
| 50341 | `NOTIFICATION_NOT_FOUND` | 消息不存在 | 已读非本人/不存在 |
| 50342 | `ARBITRATION_LIABILITY_INVALID` | 差额定责选项缺失或不适用 | liability 校验：REJECTED∧shortfall_qty>0 必填；其余场景必须为空；枚举非法（§4.1 产品刚性规则） |
| 50343–50349 | 预留 | — | T3 退货/盘点波顺延使用 |

复用现有：50004（代建出库不可异议）、50007 语义并入 50332、STOCK_NOT_ENOUGH、50313（R14）、42004/WE 授权位。05-error-codes.md 同步登记（含把代码枚举 50280–50306 实占段补录进文档的勘误注）。

---

## 7. 波次拆分建议（供 Team Lead 派发）

```
BE-W1 (V15–V17)  ──合并──▶  BE-W2 (V18)  ──合并──▶  FE-W2
   │                            ▲
   └────合并后────▶ FE-W1 ──（与 BE-W2 并行）──┘
```

| 波次 | 内容 | 依赖 | 测试关卡（合并闸门） |
|---|---|---|---|
| **BE-W1 入库异常链+基建** | V15–V17；流水类型扩展 + InventoryService 三新方法；registerByWk 改造；confirm/dispute；72h Job；TA 仲裁 decide；notifications + files；WePermissions 扩 INBOUND_CONFIRM | 无（先行） | JUnit 场景：封顶口径 4 边界（全在库/部分售出/售罄 0 冲销/冲销与出库并发抢锁）、Job 幂等+与手动确认竞态 CAS、仲裁通过恢复流水 biz_time=原入库时间戳断言、REJECTED 保留冲销、liability 三态校验（必填/必空/枚举非法 → 50342）、库存公式不变量对账；mvn 全量绿（P1 入库测试适配 §8.1） |
| **BE-W2 出库状态机+异常链** | V18；confirmByWa 产 PENDING_ACCEPT；print/revert/register；R4 两路+意向单联动；R8 void；WA 手动出库；代建出库大额；客诉+OPS decide；R13 assertNoOpenDocs 扩展 | BE-W1 合并（V15 流水/仲裁表/通知） | JUnit：迁移矩阵**逐格**断言（含全部不可达 50330）、每条 WITHDRAWN/CANCELLED 必有配对 OUTBOUND_REVERSAL（reversal_of_id 非空）、R8 联动整单、30 天窗口边界（29d23h/30d1h）、大额 50% 边界；P1 卖货 E2E 断言更新 |
| **FE-W1 入库链前端** | WA 待确认队列（倒计时升序+异议弹窗，按 06 §5.3c 合并版）；TA 审批中心仲裁弹窗（复用现有审批弹窗样式）；站内信铃铛+列表 | BE-W1 合并 | Playwright E2E：代建登记→WA 异议→TA 通过/驳回双路；**截图目检**（对齐 test-plan 00-overview §3.5/§3.6 视觉验收） |
| **FE-W2 出库链前端** | WK 出库作业流（列表/打印视图 window.print/登记/撤回二次确认弹窗）；WA 出库列表（撤回/客诉入口）；OPS 仲裁列表+结论弹窗 | BE-W2 + FE-W1（铃铛组件复用） | Playwright E2E：询价确认→打印→登记主链；R4 两路；R8；客诉→OPS 裁决；截图目检 |

- BE 两波**串行**（V15 是 W2 地基，避免 worktree 间迁移号/流水枚举冲突）；FE-W1 与 BE-W2 并行是关键并行点。
- 每波开工前按修正二再核 main 迁移最高号；worktree 隔离、Team Lead 统一合并（协作规则）。

---

## 8. 风险与既有代码影响面

### 8.1 P1 测试小改清单（确认即扣保留，改动最小化）

| 触点 | 改动 |
|---|---|
| `InquiryServiceImpl.confirmByWa` 相关单测/场景测试 | 断言出库单 `COMPLETED` → `PENDING_ACCEPT`；询价确认后状态断言 `CONFIRMED`（原若断言 COMPLETED 需改）；扣库存断言**不变** |
| 入库登记测试 | 断言 `REGISTERED` → `PENDING_WA_CONFIRM`，新增 deadline 非空断言 |
| 卖货闭环 E2E | 主链补两步：WK 打印 → WK 登记出库，末态才 COMPLETED |
| 存量数据 | V16/V18 回填幂等（WHERE 限定旧值），P1 历史单据语义不漂移（REGISTERED→CONFIRMED、COMPLETED 补 completed_at） |

### 8.2 R13 退驻前置 ×「争议中/客诉中」交互（明确口径）

50314 `WITHDRAW_OPEN_DOCS_EXIST` 的「未结单据」枚举**必须扩展**（P2 落地时已按可扩展枚举预留，08 §7.5）：

- 未结 = 询价 `PENDING/CONFIRMED` ∪ 出库 `PENDING_ACCEPT/PRINTED/COMPLAINED` ∪ 入库 `PENDING_WA_CONFIRM/DISPUTED` ∪ 仲裁 `PENDING`；
- 即：**争议中/客诉中商户不能退驻**，须等仲裁 DECIDED——这是有意设计（防"提诉即跑路"），文案在 50314 开发提示中注明；
- `WITHDRAWN/CANCELLED/COMPLETED/CONFIRMED/REVOKED/VOIDED` 均视为已结。

### 8.3 R14 强制下架分界

代建入库、代建出库、WA 手动出库提交统一走 `requireWholesalerActive`（50313）；已进入 PENDING_ACCEPT/PRINTED 的单在商户下架后允许走完（老放新拒，Wave2 口径不变）；DISPUTED/COMPLAINED 的仲裁流程不受下架阻断（仲裁必须能出结论）。

### 8.4 数据与并发风险

- **冲销封顶的保守归属**（§2.4-1）在多单混库下可能"多退"了实际属他单的在库件——商业上由差额定责兜底，属拍板一 B 的已接受代价；批次上线（T4）后自动收敛，无需迁移期数据修复。
- 异议冲销 vs 出库并发：同锁 `lock:inv:{w}:{s}` 串行化，先到先得；冲销后 onhand 减小可能令在途询价确认失败（STOCK_NOT_ENOUGH）——正确行为，无需特殊处理。
- 72h Job 单实例假设：现部署单副本；多副本前须加 ShedLock（P4 账单 Job 一并评估，本波不做，SchedulingConfig 注释标注）。

### 8.5 基建最小版的演进债（显式登记）

- 附件本地盘存储（§4.4）：无副本、无签名 URL；P5 换 OSS，URL 字段结构已兼容。
- 站内信无推送（拉取式 + 轮询 unread-count）；短信通道 TODO（Q-D06 收口后接）。
- 仲裁单轮结论、无调证/沟通记录（拍板三 B 范围），P5 在 arbitrations 上加子表扩展，不动本表字段。
- G10 计费：本设计只保证 `type/biz_time/reversal_of_id` 可回溯，P4 账单按流水配对与锚点重算，P3 零金额逻辑。

---

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-24 | 首版：拍板三 B 落地设计——出库状态机补拆/入库 72h 异常链/双仲裁最小闭环/V15–V18 迁移/50330 段错误码/四波拆分 |
| v2 | 2026-07-24 | Team Lead 契约对账（与 09-p3-arbitration-prd.md 对齐）：arbitrations 补 `doc_no`（YY-/KS- 前缀入统一单据号体系）与 `liability`（差额定责，REJECTED∧shortfall>0 必填，50342 校验）；DocType 预留前缀按产品 05 §7.1 修订版改 PD-(盘点)/QK-(清库)（原 CT-/EC- 作废） |
