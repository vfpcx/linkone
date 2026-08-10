# Task Plan · P4 计费与结算

> 真源：`product/12-p4-requirements.md`（10 项 DECISION 已拍板，D-P4-9 ST 降档已标注供用户否决）→ W0 产出 `product/13-p4-prd.md` + `architecture/14-p4-design.md`。
> P3b 计划已归档 `shared/archive/task_plan-p3b.md`。基线：main=b65fd46，后端 337 测试/E2E 38 例全绿。

## 阶段（Waves，六波见 14 §波次）

- [完成] **W0 设计定稿（2026-08-04）**：架构 14-p4-design v1（V24-V26/50370-50389/统一回放公式/账单6态/三Job错峰）+ 产品 13-p4-prd v1（10 线框/6 态中文/ST 三页降档）；词表对账一致
- [完成] **P4-W1（合并，347→351 绿）**：V24 一日一版规则链/R20/契约断裂收口/billingDim 只读镜像+BOTH
- [完成] **P4-W2（合并，377 绿）**：V25/回放引擎金账本 26 例/争议对锚点归一修正（设计 §1.1 数学证伪）/00:10 快照 Job+哨兵
- [完成] **P4-W3（合并，401 绿）**：V26/6 态+DISPUTED 冻结/BL- 单号/回款与申诉闭环/ST 首次启用/R13·R14 联动 TODO 清零
- [完成] **P4-W4（合并 e0293fc）**：TA 规则页契约切换/ST 四页(375 降档亲检)/WA 确认申诉/precheck 真值；E2E 5/5；TA bills-overview 缺口转 W5a
- [完成] **P4-W5**：W5a 导出+overview 补口（合并 8f0846f，408 绿）→ W5b 前端接入+总览页（合并 5319e44，E2E 2/2）→ **W5c 终验收完成（2026-08-10）**：全量 408×4 遍全绿（基线×2+SaManager 收口后×2，2fde373）、E2E 45/45（P1-P3b 38 连过 2 遍 + P4 7）、视觉矩阵 18 图零新缺陷（66f3c28）、交付报告 test-plan/12-p4-delivery-report.md——**P4 收官**
- [完成·插件] 收口批 BE/FE（L-1~L-7 清零，341 绿）

## 验证

- 每波 mvn 全量绿（基线 337 起）+ typecheck + E2E + 截图目检；BE 串行 FE 并行；独立复验后合并
- 规范：零角色码、实体选择弹窗化、生成物不入 commit、迁移开工前实测版本号
