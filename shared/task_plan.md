# Task Plan · P3b（T1 正向申请链 / T3 退货盘点 / T4 批次临期）

> 真源：`product/10-p3b-requirements.md` v1.1（14 项 DECISION 已拍板，§五）→ W0 产出 `product/11-p3b-prd.md` + `architecture/13-p3b-design.md`。
> P3 计划已归档 `shared/archive/task_plan-p3-documents.md`。上期交付基线：main=75fe3f1，后端 250 测试 / E2E 29 例全绿，Boot 3.5.16。

## 拍板要点（全文见 10 §五）

D-11=C（批次登记+FIFO 离线推算，交易路径零改动）；D-8=A（托盘释放 T3 一次补齐）；D-7 退货登记时扣；D-3 正向链直落 CONFIRMED；D-4 CORRECTION_IN/OUT 配对；D-5 仅加 INBOUND_SUBMIT；D-9 WE 退货不开放；D-10 盘亏封顶；D-13 batch_enabled 禁改；D-14 错误码 50350-50369；Flyway 自 V19（开工前实测复核）。

## 阶段（Waves）

- [完成] **W0 设计定稿（2026-07-30）**：产品 11-p3b-prd v1 + 架构 13-p3b-design v1（V19-V23/50350-50369/FIFO 以 batch_enabled_at 切割/托盘 pallet_delta）；Team Lead 契约词表对账无漂移（产品后写主动对齐）
- [进行中] **T1-BE 正向申请链后端**：4 状态启用/登记加库存/R3 纠错/INBOUND_SUBMIT/附件挂单/WK SKU 名称端点补口（上线检查单项顺手清）
- [待办] **T1-FE ∥ T3-W1**：T1 前端（WA 入库申请/WE 授权）∥ T3 后端一（退货 RTN- 登记时扣）
- [待办] **T3-W2 → T3-FE ∥ T4-W1**：盘点 PD-+盘亏封顶+托盘释放补齐 → T3 前端 ∥ T4 后端一（batches 表+FIFO 推算+batch_enabled 禁改）
- [待办] **T4-W2 → T4-FE**：临期 Job+清库 QK- → T4 前端（临期看板）
- [待办] **W5 验收**：全量回归+E2E+视觉矩阵+交付报告；含 P3 上线检查单复核

## 验证（Verification）

- 每波：mvn 全量绿（基线 250 起）+ typecheck + E2E + 截图目检（含未登录页矩阵沿用）
- BE 波串行（迁移号/流水枚举单线）；FE 与下一 BE 波并行；worktree 隔离，Team Lead 独立复验后合并
- 规范：文案零角色码（规则9）、实体选择弹窗化、生成物不入 commit
