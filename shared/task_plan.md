# Task Plan · P4 计费与结算

> 真源：`product/12-p4-requirements.md`（10 项 DECISION 已拍板，D-P4-9 ST 降档已标注供用户否决）→ W0 产出 `product/13-p4-prd.md` + `architecture/14-p4-design.md`。
> P3b 计划已归档 `shared/archive/task_plan-p3b.md`。基线：main=b65fd46，后端 337 测试/E2E 38 例全绿。

## 阶段（Waves，六波见 14 §波次）

- [完成] **W0 设计定稿（2026-08-04）**：架构 14-p4-design v1（V24-V26/50370-50389/统一回放公式/账单6态/三Job错峰）+ 产品 13-p4-prd v1（10 线框/6 态中文/ST 三页降档）；词表对账一致
- [待办] **P4-W1 规则与契约修复**：billing_rules API+V24；TA 计费设置契约断裂修复（活缺陷）；billing_dim 只读镜像
- [待办] **P4-W2 回放引擎+快照**：统一回放公式实现；每日快照 Job(00:10)；对账下钻端点
- [待办] **P4-W3 账单生命周期**：月账单生成 Job；6 态状态机+PENDING_PAYMENT；BL- 单号；回款登记+ST 角色启用；R13 50323/R14 DISPUTED 联动
- [待办] **P4-W4 前端**：TA 规则页/账单列表详情下钻/WA 确认/ST 三核心页（响应式降档口径）
- [待办] **P4-W5 导出+验收**：流式导出；全量回归+E2E+视觉矩阵+交付报告
- [并行插件] 收口批 BE/FE（P3b 遗留 L 项，在途）

## 验证

- 每波 mvn 全量绿（基线 337 起）+ typecheck + E2E + 截图目检；BE 串行 FE 并行；独立复验后合并
- 规范：零角色码、实体选择弹窗化、生成物不入 commit、迁移开工前实测版本号
