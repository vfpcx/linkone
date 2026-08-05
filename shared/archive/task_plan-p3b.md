# Task Plan · P3b（T1 正向申请链 / T3 退货盘点 / T4 批次临期）

> 真源：`product/10-p3b-requirements.md` v1.1（14 项 DECISION 已拍板，§五）→ W0 产出 `product/11-p3b-prd.md` + `architecture/13-p3b-design.md`。
> P3 计划已归档 `shared/archive/task_plan-p3-documents.md`。上期交付基线：main=75fe3f1，后端 250 测试 / E2E 29 例全绿，Boot 3.5.16。

## 拍板要点（全文见 10 §五）

D-11=C（批次登记+FIFO 离线推算，交易路径零改动）；D-8=A（托盘释放 T3 一次补齐）；D-7 退货登记时扣；D-3 正向链直落 CONFIRMED；D-4 CORRECTION_IN/OUT 配对；D-5 仅加 INBOUND_SUBMIT；D-9 WE 退货不开放；D-10 盘亏封顶；D-13 batch_enabled 禁改；D-14 错误码 50350-50369；Flyway 自 V19（开工前实测复核）。

## 阶段（Waves）

- [完成] **W0 设计定稿（2026-07-30）**：产品 11-p3b-prd v1 + 架构 13-p3b-design v1；契约词表对账无漂移
- [完成] **T1-BE（合并 4d93c5c）**：V19/九端点/5%边界/R3 纠错/INBOUND_SUBMIT/WK SKU 补口；270 绿
- [完成] **T1-FE（合并 95cf91d）**：WA 申请双视图/WK 工作台/纠错弹窗；E2E 3/3+12 截图亲检
- [完成] **T3-W1（合并 321ef14）**：V20/RTN- 登记时扣/pallet_delta+五类双写/batch 禁改；285 绿
- [完成] **T3-W2（合并 75cc6c6）**：V21/PD- 全链/D-10 盘亏封顶/代建托盘收口；303 绿
- [完成] **T3-FE（合并 32ea629）**：退货两端/盘点草稿+封顶审批/托盘输入；E2E 3/3+回归 4/4+10 截图亲检
- [完成] **T4-W1（合并 e594369）**：V22/批次登记簿零侵入+FIFO 推算/默认批吸收/T1 两小项收口；318 绿
- [完成] **T4-W2（合并 80e5dd9）**：V23/02:00·02:30 双 Job/D-12 去重/QK- 全链/看板端点；337 绿（含 V22 列宽蓝图缺陷就地修）
- [进行中] **T4-FE**〔feat/p3b-expiry-fe〕：批次管理/临期看板/清库前端/入库批次三字段（最后开发波）
- [待办] **W5 验收**：全量回归+E2E 全套+视觉矩阵+两抖动稳定化（H2 连接/简码碰撞）+交付报告；含 P3 上线检查单复核

## 验证（Verification）

- 每波：mvn 全量绿（基线 250 起）+ typecheck + E2E + 截图目检（含未登录页矩阵沿用）
- BE 波串行（迁移号/流水枚举单线）；FE 与下一 BE 波并行；worktree 隔离，Team Lead 独立复验后合并
- 规范：文案零角色码（规则9）、实体选择弹窗化、生成物不入 commit
