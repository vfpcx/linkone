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
- [进行中] **PII-S1 影子灰度**（15 §4 阶段1，三步走）
  - **Step 1 / 波次 PII-W4 影子双查 ✅ 代码就位**：新增 `PiiProperties.read-mode: plain|shadow|hmac` + `PiiShadowReader`（出结果的仍是旧列，只多用 hmac 列查一遍比对计数；异常一律吞在类内不外抛，故意在业务事务方法内 catch，不把事务标脏；告警只打切点/结论/行 id，不落 PII）。指标 `pii.shadow{pointcut,verdict}`（Micrometer 软依赖，缺 MeterRegistry 也不炸）+ 进程内 `snapshot()` 供测试与无监控环境读数。结论分 MATCHED/MISSING/EXTRA/DIVERGED/ERROR/SKIPPED——除 MATCHED/SKIPPED 外都算 mismatch
    - 覆盖 8 个读切点：A1 注册查重 / A2 密码登录 / A3 找回密码 / A4 换绑查重 / A5 RT 免密 / A6 代建开号 / B1 入驻命中 / B2 加黑查重。LICENSE_NO 行 hmac 恒 NULL 主动跳过（15 §2-1，不是缺口，不稀释闸门分母）
    - 关卡测试 `PiiShadowReadScenarioTest` 10 例（差值断言，计数器 JVM 内跨类共享故不断言绝对值）：一致路径 4 例 + **检出力 3 例**（造 hmac 漏填→MISSING、造 hmac 指向别行→DIVERGED，同时断言登录/黑名单命中**结果不变**——零行为变化与检出力同一条用例钉死）+ 开关自身 1 例 + LICENSE_NO 不入分母 2 例
    - **RED 已验证**：`SPRING_APPLICATION_JSON` 强制 `read-mode=plain` 复跑本类，10 例中 9 例转红（唯一不红的是 LICENSE_NO 负向断言，同 S0 类先例）
    - 2026-08-23 全量 **434 绿**（46 类，424+10，0 失败/0 错误/0 跳过，零回归）。全量日志仅 4 条 mismatch 告警，逐条溯源均为两个 PII 关卡类自己造的数（S0 类抹 blacklist hmac 测回填 1 条 + 本类故意造的 3 条）——**业务用例零不一致**，这也是"影子期零行为变化"的回归网本身（测试态 `read-mode=shadow`，全量都走了一遍影子）
    - **闸门（待生产）**：`pii.shadow` 的 mismatch 连续 **≥7 天为 0** 才可进 Step 2。回滚：`read-mode` 拨回 plain，秒级
  - [待办] Step 2 / PII-W5 非命门切读（blacklist/sms/pricing + Redis 键 HMAC 化）。前置已就绪：V30 三表加列+双写+回填+对账已完成（见下方缺口条目），W5 只需接影子切点与切读本身
  - [待办] Step 3 / PII-W6 登录双读切换（冻结窗口 0.5d，双读兜底自愈 + 异步补写）
- [完成] **S1 缺口：定价/短信链补做 S0**（W4 摸出来的实测差异）：15 §4 阶段0 原列了 7 张表，但 V27 实际只加了 `users.phone_hmac` 与 `blacklist.target_value_hmac`。2026-08-25 补齐余下三表，口径逐条照抄 V27 那套：
  - **V30 加列** ✅ `customer_prices.rt_phone_hmac` / `sms_codes.phone_hmac` / `inquiry_requests.rt_phone_hmac`，全部 NULLable + 普通索引，索引列序对齐各自现有明文索引（customer_prices 用 `(wholesaler_id, rt_phone_hmac, sku_id)` 对齐物理唯一键）。纯 additive 无回滚脚本；三个新字段均带 `@JsonIgnore`，实体直出响应形状零变化
  - **双写切点** ✅ 唯一产生点仍是 `PiiCrypto.phoneHmac`，一律 `write-mode=dual` 才写：`PricingServiceImpl.setCustomerPrice` / `settleFromInquiry`（insert 分支写入，命中既有行分支做机会性回填——同 blacklist REMOVED 复活口径）、`AccountServiceImpl.sendSmsCode`、`InquiryServiceImpl.submitByRt`。已核：主代码里这三表的 insert 只有这 4 处，无遗漏。`doBatchCustomerInTx` 不写 rt_phone，属 C3 **读**切点，归 Step 2
  - **回填+对账** ✅ `PiiBackfillService` 扩到五张表；把「主键/明文列/hmac 列/行过滤」抽成 `HmacColumn` record，CAS 幂等、keyset 游标、legacy 拒填三件套共用一份实现（原 users/blacklist 公开方法签名与 `ReconcileResult.table()` 取值不变）。`reconcile()`/`unreadyTables()` 现覆盖五表，`PiiBackfillRunner` 一次重启跑完五表
  - **关卡测试** ✅ `PiiDualWriteBackfillScenarioTest` +5 例（15→20）：C1 新建 / C1 命中既有行机会性回填 / SMS 真端点 `POST /api/v1/account/sms-code` / C2 `submitByRt` / V30 三表回填幂等。切点一律真调，不用 mapper 造行代替。JSON 红线用例扩到三表；对账基线拉平改走 `flattenBackfillBaseline()` 覆盖五表（多个兄弟场景类直接 mapper 造 customer_prices / inquiry_requests，绕过双写切点，hmac 天然 NULL）
  - 2026-08-25 全量 **439 绿**（434+5，0 失败/0 错误/0 跳过，零回归）
  - 遗留：`PiiShadowReader` **未**给这三表接影子切点——那是读路径改造，随 Step 2 / PII-W5 一起做，不进 Step 1 闸门分母（类注释已同步）。生产首跑注意 `sms_codes` 行数随发码量线性增长，回填全表成本由 `backfill-batch-size` 控制（刻意不按「未过期」缩小分母，否则闸门口径随时间漂移）
- [待办] **PII-S2 收缩+打码**：V29 明文列处置+Redis 键改造+限流键加盐+前端 9 处打码（逐页清单）+E2E 断言更新
- [待办] **验收**：全量+E2E45×2+报告 13；上线检查单余项复核（prod 冒烟/CVE 复扫/graceful shutdown 属部署侧待环境）

## 验证

- 每波 mvn 全量绿（基线 419；补完切点测试债后 424；W4 影子双查后 434；V30 补做 S0 后为 **439**）+ E2E 全套；登录命门波次须含回滚演练证据；独立复验后合并
- 测试态常开 `write-mode=dual` + `read-mode=shadow`——「阶段 0 双写不改行为」「阶段 1 影子期零行为变化」这两句话，靠的就是全量在这两个开关下仍全绿
