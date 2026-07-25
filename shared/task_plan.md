# Task Plan · P3 完整单据与履约异常（第一期：三链+双仲裁）

> 规则依据：CLAUDE.md 全局协作规则 §6（编码阶段自主执行）§7（planning-with-files）。
> 真源：`architecture/09-p3-decision-options.md` v2（用户拍板）→ `architecture/12-p3-design.md` v2（据设计）+ `product/09-p3-arbitration-prd.md` v1.1（命名已对齐落库定稿）。
> P2 计划已归档 `shared/archive/task_plan-p2-onboarding.md`。

## 目标与范围

**做**（12-p3-design.md 覆盖面）：
1. T5 出库单状态机补拆（确认即扣不动；待受理→已打印→已出库；撤回/作废走 OUTBOUND_REVERSAL 反向回补流水）
2. T1 代建入库异常链（72h 待确认可售；异议冲销 `min(登记量,在库)` 封顶；差额入仲裁单定责；72h 自动确认 Job 复用 SchedulingConfig）
3. T2 出库异常链（R4 撤回两路、R8 作废联动、WA 手动出库、代建出库、30 天客诉）
4. 双仲裁最小闭环（arbitrations 表多态引用，YY-/KS- 单号；TA 二选+liability 定责；OPS 四选不动库存账单）
5. 支撑基建：库存流水类型扩展、notifications 站内信、附件上传、错误码 50330-50342

**不做**（出圈）：T1 正向申请链 R1/R2/R3、T3 退货/盘点、T4 批次临期（P3 后续波次另行设计）；调证/多轮沟通/赔偿追踪（P5）；账单联动（P4）。

## 用户拍板（2026-07-23，见 09 v2）

问题一 B（72h 可售+封顶冲销）、问题二 B（确认即扣+状态补拆）、问题三 B（双仲裁最小闭环）；修正一同意（单据号按已上线，RTN-/PD-/QK-/YY-/KS- 新前缀）、修正二同意（Flyway 自 V15 起，每波开工前复核）。

## 阶段（Waves，依赖图见 12 §7）

- [完成] **W0 设计定稿（2026-07-24）**：产品 09-p3-arbitration-prd v1.1 + 架构 12-p3-design v2；并行漂移 3 处已对账收口（liability 列+50342、doc_no YY-/KS-、PRD 命名 9 处对齐；盘点/清库前缀按产品 PD-/QK- 统一）
- [完成] **BE-W1 入库异常链+基建（2026-07-24 合并 main=acefaaf）**〔branch feat/p3-inbound-chain〕：V15-V17；流水扩展+InventoryService 三新方法；registerByWk 改造；confirm/dispute；72h Job；TA 仲裁 decide；notifications+files；WePermissions 扩 INBOUND_CONFIRM。闸门：JUnit 封顶 4 边界/Job 幂等竞态/biz_time 断言/liability 三态/公式不变量 + mvn 全量绿
- [完成·待复验] **BE-W2 出库状态机+异常链（2026-07-25）**〔branch feat/p3-outbound-chain，未合并待 Team Lead 复验〕：V18；DocStateMachine CAS 引擎落地；confirmByWa 产 PENDING_ACCEPT（询价停 CONFIRMED，登记出库联动终态）；print/revert/register；R4 两路+R8 作废联动（回补配对）；WA 手动出库+代建大额 50%；30 天客诉+OPS 四选 decide；R13 未结扩展（出库/入库/仲裁）。闸门已过：矩阵 6×6 逐格断言/每张撤销单配对 OUTBOUND_REVERSAL/29d23h·30d1h 窗口边界/大额边界/虚拟线程 CAS 竞态×2/公式不变量对账；据实现备注（含 10 处偏差记录）见 12-p3-design.md
- [待办] **FE-W1 入库链前端**〔依赖 BE-W1 合并，与 BE-W2 并行〕：WA 待确认队列+异议弹窗；TA 仲裁弹窗（复用审批中心）；站内信铃铛。闸门：E2E 双路+截图目检（§3.5/§3.6）
- [待办] **FE-W2 出库链前端**〔依赖 BE-W2+FE-W1〕：WK 作业流（打印/登记/撤回确认）；WA 出库列表（撤回/客诉）；OPS 仲裁列表。闸门：E2E 主链+R4/R8/客诉+截图目检
- [待办] **W5 测试审查合并**：全量回归+E2E+视觉验收报告；code-review

## 验证（Verification）

- 后端 `mvn test` 全量绿（dev,local profile 勿忘，Memurai 需在跑）；每波迁移开工前核 main 最高版本号
- 前端 `pnpm -r typecheck` + Playwright E2E + 截图目检
- 合并顺序：BE-W1 → BE-W2 → FE 波次；fe-types 类生成物勿入 commit

## 未做 / 后续
- WIP 分支 `refactor/account-user-service`（G-S1/G-S2 架构债，未经测试）——P3 期间择机补测合并
- Boot 3.2.5→3.5.x 硬化升级（chore/hardening-boot-upgrade 空分支待动工，独立于 P3 功能波次）
- P3 后续期：T1 正向申请链、T3 退货/盘点、T4 批次临期
