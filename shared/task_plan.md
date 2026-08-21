# Task Plan · PII 三段式硬化 + P4 遗留收口

> 真源：`architecture/15-pii-hardening-v2.md`（V26 基线实测+八波次）。P4 计划已归档 `shared/archive/task_plan-p4.md`。
> 基线：main=acad899，后端 **419 测试全绿**（45 个测试类，0 失败/0 错误/0 跳过），V1-V27。

## 阶段

- [完成] **设计定稿**：15-pii-hardening-v2（登录链 6 处 eq/黑名单明文+3 处日志漏网/pricing 明文 Redis 键/sms·login 限流无盐键；密钥 fail-fast+启动 KAT）
- [完成] **P4 遗留收口**〔fix/p4-leftovers〕：P4-L2 WA 按日端点 ✅ + P4-L3 字体兜底 ✅ + LIF-10 场景测试 ✅。2026-08-19 跑通 409 全绿，`--no-ff` 合入 main=ce8ce0e 并 push。2026-08-21 worktree 已回收（分支保留）
- [完成] **PII-S0 加列双写**〔feat/pii-stage0，5 commits〕：V27 加列 ✅ + PiiCrypto/KAT/fail-fast ✅ + 双写切点 ✅（2026-08-19 实测：编译通过、408 全绿零回归；覆盖已核——`phoneHash` 3 处写入点/`targetValue` 1 处新增+1 处复活全部带 hmac 双写，且无 LambdaUpdateWrapper/XML 绕过实体写这两表）+ B4 三处日志脱敏 ✅。存量回填幂等+对账 ✅（`PiiBackfillService`：CAS 幂等 / keyset 对账 / legacy 拒填；`PiiBackfillRunner` 开关默认关）+ 关卡测试 ✅（`PiiDualWriteBackfillScenarioTest` 10 例，期望值独立实现不复用 PiiCrypto）。2026-08-19 全量 **419 绿**（含随 main 并入的 LIF-10）。**2026-08-21 `--no-ff` 合入 main=acad899，合并后全量复跑 419 绿零回归，已 push**。
  - 红线：读路径一律不动 ✅（回填/对账只写 hmac 列、只读明文做校验，不参与任何登录/命中判定）
- [待办] **S1 前置：补切点测试债**（S1 要动读路径，无这三处断言即无回归网，故列为 S1 的准入门槛而非 S0 尾巴）
  - A1 注册 / A4 换绑：切点断言未覆盖，需驱动短信码流程脚手架
  - B2 走 `BlacklistService.add`：断言未覆盖，需 OPS 角色脚手架；现以 mapper 造行覆盖 PII 语义
- [待办] **PII-S1 影子灰度**：影子双查 7 天对账 Job+登录双读兜底自愈；对账零差异后切主读
- [待办] **PII-S2 收缩+打码**：V29 明文列处置+Redis 键改造+限流键加盐+前端 9 处打码（逐页清单）+E2E 断言更新
- [待办] **验收**：全量+E2E45×2+报告 13；上线检查单余项复核（prod 冒烟/CVE 复扫/graceful shutdown 属部署侧待环境）

## 验证

- 每波 mvn 全量绿（基线 419）+ E2E 全套；登录命门波次须含回滚演练证据；独立复验后合并
