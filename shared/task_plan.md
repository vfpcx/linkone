# Task Plan · PII 三段式硬化 + P4 遗留收口

> 真源：`architecture/15-pii-hardening-v2.md`（V26 基线实测+八波次）。P4 计划已归档 `shared/archive/task_plan-p4.md`。
> 基线：main=acad899，后端 **419 测试全绿**（45 个测试类，0 失败/0 错误/0 跳过），V1-V27。

## 阶段

- [完成] **设计定稿**：15-pii-hardening-v2（登录链 6 处 eq/黑名单明文+3 处日志漏网/pricing 明文 Redis 键/sms·login 限流无盐键；密钥 fail-fast+启动 KAT）
- [完成] **P4 遗留收口**〔fix/p4-leftovers〕：P4-L2 WA 按日端点 ✅ + P4-L3 字体兜底 ✅ + LIF-10 场景测试 ✅。2026-08-19 跑通 409 全绿，`--no-ff` 合入 main=ce8ce0e 并 push。2026-08-21 worktree 已回收（分支保留）
- [完成] **PII-S0 加列双写**〔feat/pii-stage0，5 commits〕：V27 加列 ✅ + PiiCrypto/KAT/fail-fast ✅ + 双写切点 ✅（2026-08-19 实测：编译通过、408 全绿零回归；覆盖已核——`phoneHash` 3 处写入点/`targetValue` 1 处新增+1 处复活全部带 hmac 双写，且无 LambdaUpdateWrapper/XML 绕过实体写这两表）+ B4 三处日志脱敏 ✅。存量回填幂等+对账 ✅（`PiiBackfillService`：CAS 幂等 / keyset 对账 / legacy 拒填；`PiiBackfillRunner` 开关默认关）+ 关卡测试 ✅（`PiiDualWriteBackfillScenarioTest` 10 例，期望值独立实现不复用 PiiCrypto）。2026-08-19 全量 **419 绿**（含随 main 并入的 LIF-10）。**2026-08-21 `--no-ff` 合入 main=acad899，合并后全量复跑 419 绿零回归，已 push**。
  - 红线：读路径一律不动 ✅（回填/对账只写 hmac 列、只读明文做校验，不参与任何登录/命中判定）
- [完成] **S1 前置：补切点测试债**（S1 要动读路径，无这三处断言即无回归网，故列为 S1 的准入门槛而非 S0 尾巴）：新增 5 例并入 `PiiDualWriteBackfillScenarioTest`（不另起类，切点断言与双写/回填同属一处关卡）
  - A1 注册 / A4 换绑 ✅：经 HTTP 全流程驱动。**两块脚手架实际都已存在，无需新搭**——短信码走测试态 mock 短路（`cangchu.sms.mock=true` / `888888`，`verifySmsCode` 首行即短路），不必造 `sms_codes` 行；A4 另断言 hmac 跟着新号重算且 `isNotEqualTo(旧号 hmac)`，钉住影子列不脱节
  - B2 ✅：改走 `BlacklistService.add` 真调，OPS 身份由真注册产生 `user_roles` 行喂给 `requireOpsRole`（`authService.hasRole` 只查库，无需 Sa-Token 脚手架），不再 mapper 造行绕过。覆盖加黑 / LICENSE_NO 不入手机号盲索引 / REMOVED 复活的机会性回填三例——复活那条分支此前零覆盖
  - **RED 已验证**：`SPRING_APPLICATION_JSON` 强制 `write-mode=legacy` 复跑本类，A1/A4/B2加黑/A6 四处 hmac 断言全部 `expected <hmac> but was null`，证明断言确实压在切点上（LICENSE_NO 那例是负向断言，此变异下不失败，其反向语义已由 `backfill_touchesPhoneRowsOnlyAndLeavesLicenseNull` 把住）
  - 2026-08-22 全量 **424 绿**（45 类，419+5，0 失败/0 错误/0 跳过，零回归）
- [待办] **遗留缺陷：B2 复活 `removed_at` 残留**（非 PII，补测时撞见）：`BlacklistServiceImpl.add` 复活分支的 `existing.setRemovedAt(null)` 被 MyBatis-Plus 默认「null 字段不下发」策略吞掉，`updateById` 根本不带该列 → 复活后条目 `status=ACTIVE` 却仍留着旧的 `removed_at`。已在测试里显式注明不断言此项。修法待定（`UpdateWrapper.set` 显式置空，或字段标 `@TableField(updateStrategy = ALWAYS)`），需先评估对 OPS 列表展示与审计追溯口径的影响，不随本波次夹带
- [待办] **PII-S1 影子灰度**：影子双查 7 天对账 Job+登录双读兜底自愈；对账零差异后切主读
- [待办] **PII-S2 收缩+打码**：V29 明文列处置+Redis 键改造+限流键加盐+前端 9 处打码（逐页清单）+E2E 断言更新
- [待办] **验收**：全量+E2E45×2+报告 13；上线检查单余项复核（prod 冒烟/CVE 复扫/graceful shutdown 属部署侧待环境）

## 验证

- 每波 mvn 全量绿（基线 419；补完切点测试债后为 **424**）+ E2E 全套；登录命门波次须含回滚演练证据；独立复验后合并
