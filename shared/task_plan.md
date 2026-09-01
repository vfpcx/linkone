# Task Plan · PII 三段式硬化 + P4 遗留收口

> 真源：`architecture/15-pii-hardening-v2.md`（V26 基线实测+八波次）。P4 计划已归档 `shared/archive/task_plan-p4.md`。
> 基线：main=acad899，后端 **419 测试全绿**（45 个测试类，0 失败/0 错误/0 跳过），V1-V27。
> 当前：main=**72c5597**（PII-W8 收口 8954049 + G-8.6 保全 691908d + 团队文档 fd3cc97 + F1 前端 72c5597），后端 **451 绿**（47 测试类，0 失败/0 错误/0 跳过，SKIP=0），工作区干净。

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
- [完成] **遗留缺陷：B2 复活 `removed_at` 残留**（2026-08-31 修复）：`Blacklist.removedAt` 标 `@TableField(updateStrategy = ALWAYS)` 允许 null 下发——复活分支 `setRemovedAt(null)` 生效，行恢复干净 ACTIVE。影响评估：前端 0 处消费 removedAt（展示影响为零）；remove 路径 removedAt=now 不受影响（注解只放开空值下发）；保持实体 updateById 路径（不破坏 PII"双写切点走实体"约定）。测试：`PiiDualWriteBackfillScenarioTest` 复活用例补断言 `removedAt isNull`；DualWrite 20 + Hmac 22 + Onboarding 15 全绿零回归
- [进行中] **PII-S1 影子灰度**（15 §4 阶段1，三步走）
  - **Step 1 / 波次 PII-W4 影子双查 ✅ 代码就位**：新增 `PiiProperties.read-mode: plain|shadow|hmac` + `PiiShadowReader`（出结果的仍是旧列，只多用 hmac 列查一遍比对计数；异常一律吞在类内不外抛，故意在业务事务方法内 catch，不把事务标脏；告警只打切点/结论/行 id，不落 PII）。指标 `pii.shadow{pointcut,verdict}`（Micrometer 软依赖，缺 MeterRegistry 也不炸）+ 进程内 `snapshot()` 供测试与无监控环境读数。结论分 MATCHED/MISSING/EXTRA/DIVERGED/ERROR/SKIPPED——除 MATCHED/SKIPPED 外都算 mismatch
    - 覆盖 8 个读切点：A1 注册查重 / A2 密码登录 / A3 找回密码 / A4 换绑查重 / A5 RT 免密 / A6 代建开号 / B1 入驻命中 / B2 加黑查重。LICENSE_NO 行 hmac 恒 NULL 主动跳过（15 §2-1，不是缺口，不稀释闸门分母）
    - 关卡测试 `PiiShadowReadScenarioTest` 10 例（差值断言，计数器 JVM 内跨类共享故不断言绝对值）：一致路径 4 例 + **检出力 3 例**（造 hmac 漏填→MISSING、造 hmac 指向别行→DIVERGED，同时断言登录/黑名单命中**结果不变**——零行为变化与检出力同一条用例钉死）+ 开关自身 1 例 + LICENSE_NO 不入分母 2 例
    - **RED 已验证**：`SPRING_APPLICATION_JSON` 强制 `read-mode=plain` 复跑本类，10 例中 9 例转红（唯一不红的是 LICENSE_NO 负向断言，同 S0 类先例）
    - 2026-08-23 全量 **434 绿**（46 类，424+10，0 失败/0 错误/0 跳过，零回归）。全量日志仅 4 条 mismatch 告警，逐条溯源均为两个 PII 关卡类自己造的数（S0 类抹 blacklist hmac 测回填 1 条 + 本类故意造的 3 条）——**业务用例零不一致**，这也是"影子期零行为变化"的回归网本身（测试态 `read-mode=shadow`，全量都走了一遍影子）
    - **闸门（待生产）**：`pii.shadow` 的 mismatch 连续 **≥7 天为 0** 才可进 Step 2。回滚：`read-mode` 拨回 plain，秒级
  - [进行中] Step 2 / PII-W5 非命门切读（blacklist/sms/pricing + Redis 键 HMAC 化）。前置已就绪：V30 三表加列+双写+回填+对账已完成（见下方缺口条目）
    - **影子切点已接** ✅（2026-08-27）：新增 5 个读切点，口径逐条照抄 W4 的 8 个（返回 void、异常吞在 `probe()` 内、日志只打切点/结论/行 id、hmac 算不出记 SKIPPED 不入分母）——`C1-price-set`（`setCustomerPrice` upsert 探测）/ `C1-price-settle`（`settleFromInquiry` 同）/ `C2-price-resolve`（`resolveCustomUnitPrice`，须同带 `status=ACTIVE` 才是同一个问题；分母 = Redis 缓存 miss 的 DB 实读次数）/ `C3-price-batch`（`doBatchCustomerInTx` 按 rtPhone 圈选，唯一的**多行**切点，比的是行 id 集合：影子少捞=MISSING、多捞=EXTRA、两头对不上=DIVERGED；显式 ids 分支没读明文列，直接不探测、不入分母）/ `SMS-verify`（`verifySmsCode`）
    - **inquiry_requests 没接，且不是遗漏**：主代码对该表的读全部按 id / tenant / wholesaler / status，**没有一处按 rt_phone 圈选**（15 §1.2-C6 只把它列为落库+透传的写触点，§4 Step 2 的切读清单也只有 blacklist/sms/pricing）。没有明文读路径就没有「两列答案对不对得上」可比，硬造探针只会往分母里灌永远 MATCHED 的噪音；该列正确性由 `reconcile()` 对账兜底。已写进 `PiiShadowReader` 类注释
    - **闸门分组**：W5 这 5 个切点**不进 Step 1 的 7 天分母**（那是登录/黑名单 8 切点的准入线），服务的是 Step 2 自己的「pricing 全量 + 黑名单用例 + E2E 45×2 全绿，观察 ≥3 天」
    - 关卡测试 `PiiShadowReadScenarioTest` 10→19 例：C1/C2/C3/SMS 各「一致记 MATCHED」+「造漏填记 MISSING 且主路结果分毫不变」成对，检出力与零行为变化同一条用例；另 1 例钉死 C3 显式 ids 分支不入分母。三个定价切点的零行为变化各自分开断言（C1 走成 insert 会撞唯一键连累 confirmByWa 整单回滚、C2 回退公开价是资损、C3 少圈一行是漏调价）
    - **RED 已验证**：`SPRING_APPLICATION_JSON` 强制 `read-mode=plain` 复跑本类，19 例中 17 例转红（不红的两例是 b2 LICENSE_NO 与 c3 显式 ids 两条负向「不入分母」断言，同 W4 先例）
    - 2026-08-27 全量 **448 绿**（439+9，0 失败/0 错误/0 跳过，零回归）。全量日志 13 条 mismatch 告警：9 条是本关卡类自己造的数，另 4 条（C1 ×1 来自 `PricingSettleScenarioTest`、C2 ×3 来自 `PricingRtMatchScenarioTest`）是这两个兄弟类直接 `customerPriceMapper.insert` 造价行绕过双写切点所致，hmac 天生 NULL——**夹具噪音，非回填缺口**（同 S0 波次把对账基线改走 `flattenBackfillBaseline()` 的成因）；生产没有 mapper 造行，闸门读 prod Micrometer，不受影响，故不追改兄弟类
    - 顺手修掉一处测试抖动：本类 `PHONE_SEQ` 原起点固定，而 sms-code 的 60s 重发冷却键在 Redis 里跨 JVM 存活 → 60 秒内复跑必撞 41204 假红；改为按本次运行随机偏移（仍在 176 段内，留 1000 万号余量）
    - **切读本身 ✅ 代码就位（2026-08-29）**：新增 `PiiReadRouter`（切读开关本体，明文查询以 `legacyRead` Supplier 传入 = 回滚分支原样保留）+ `PiiHmacQueries`（hmac 查询**唯一构造入口**，影子期比对与切读后出结果共用同一条谓词——否则闸门证明的是 A 查询、上线跑的是 B 查询）+ `PiiModule`（灰度模块划分）
      - **分模块灰度**：`cangchu.pii.read-modes.{blacklist|sms|pricing|redis-key}`，未登记/空值回落全局 `read-mode`。四块爆炸半径不同（放进一个该拦的人 / 全员注册失败 / 资损 / 缓存重算），一刀切意味着任一块翻车就得把已观察合格的其余三块一起赔进去。主配已按四行空占位符登记（`${PII_READ_MODE_*:}`），否则 `redis-key` 带连字符没法用标准环境变量注入，「按模块灰度」就是纸面能力。模块名/模式值写错**拒绝启动**（静默回落＝想切的没切且毫无征兆）
      - **硬切，无旧列兜底**：hmac 未命中 = 真未命中。回填填没填全由切读**之前**的影子闸门证明，用运行时兜底掩盖等于把「回填有洞」永久藏起来，闸门再没有归零的一天。（Step 3 登录链的双读兜底自愈是另一套权衡：登录切错的代价是全员登不上）
      - **切点**：B1 命中检查 / B2 加黑查重（LICENSE_NO 行 hmac 恒 NULL，切读期照走明文，不被误伤）、SMS-verify、C1-price-set / C1-price-settle / C2-price-resolve / C3-price-batch（显式 ids 分支没读明文列，无可切之读）。**登录链 A1–A6 一行未动**（归 Step 3/W6），继续吃全局 `read-mode`——那也正是 Step 1 七天闸门组的口径；故 `PiiShadowReader.checkUser` 不设模块，其余五个方法改为分模块闸门（模块一旦切读，其影子探针停摆——已无「旧列的答案」可比）
      - **C4 Redis 键 HMAC 化**：`price:match:*`（原键里**直接带明文手机号**，本次堵掉）、`sms:cd:* / sms:daily:*`、`login:fail:*` 三处的手机号派生物统一经 `redisKeyPart` 派生。旧键不清洗，靠各自 TTL 自然消亡（60s / 当日 / 15min）；代价是拨动开关等于把这几个窗口重开一次（冷却中可再发码、锁定中即解锁、专属价缓存重算）——有界且自愈，但别在遭爆破时拨
      - 关卡测试 `PiiHmacReadScenarioTest` 22 例（新类，影子类 19 例一条没删）：每个切点「hmac 命中 == 旧列命中」+「hmac 未命中即真未命中」成对。后者把切读后的代价逐条钉死并写进断言消息——B1 放行该拦的人 / B2 复活语义丢失退化成 uk 兜底 50310 / SMS 41202 全员注册受阻 / C1 撞唯一键连累 confirmByWa 整单回滚 / C2 回退公开价 9.90 是资损 / C3 真的少圈一行。另有拨回秒级恢复（含影子探针复活）、模块隔离、启动校验拒绝配置笔误、默认值仍是 shadow 各一例
      - **RED 已验证（两个互补变异，21 例正式用例全被杀死）**：变异 A「模块覆写失效＝切读没发生」→ 11 例转红（6 条真未命中 + 3 条 C4 + 模块粒度 + 拨回）；变异 B「切读发生但 hmac 查不到」→ 10 例转红（6 条一致用例 + 3 条 C4 + C3 未命中）。两轮均不红的 4 例全是负向断言（默认值、模块隔离、LICENSE_NO、显式 ids），同 W4/W5 先例
      - 2026-08-29 全量 **470 绿**（448+22，0 失败/0 错误/0 跳过，零回归）。全量日志 16 条 mismatch = 基线 13 + 本类自造 3 条 B1-MISSING；C1 那 3 条逐条溯源为 S0 类 ×1 / 影子类 ×1 / `PricingSettleScenarioTest` ×1（已知夹具噪音），无新增无解释条目
      - **默认值刻意没动**：全局仍 `read-mode: shadow`，四个模块占位符全空。本波交付的是「代码就绪 + 开关可拨」，生产闸门（pricing 全量 + 入驻黑名单用例 + E2E 45×2 全绿，观察 ≥3 天）过了才逐块拨 hmac
    - [待办] **生产切读执行**：闸门达标后按 `redis-key → blacklist → sms → pricing` 顺序逐块拨（先拨代价最轻、可自愈的），每块观察 ≥3 天再拨下一块；出事只拨回那一块
  - [完成] **Step 3 / PII-W6 登录双读切换（2026-08-31，61df04d）**：A1–A6 登录链改走 `PiiReadRouter.user()`（主路 hmac + 旧列兜底放行 + 异步补写自愈，`PiiFallbackHealer` 承接七天闸门——`pii.fallback` FALLBACK 恒 0 为切读后准入线）+ `PiiHmacQueries` 增 users 查询 + login 模块入 `PiiModule`。用户拍板：无生产环境 → 不设 7 天/3 天观察期，验证直接拨 hmac 跑全量；双读兜底代码保留。默认值未动（login 殿后）。全量 **488 绿** + RED 双变异验证（杀 15/7）
  - [完成] **阶段 2 前置 / PII-W7（2026-08-31，44fb080）**：管理端列表打码 + 检索口径切换 + 查全号接口 `GET /api/v1/pii/phone-reveal`（四类 biz 权限矩阵：BLACKLIST/TENANT→OPS、WA_APPLICATION→OPS|归属TA、INQUIRY→归属WA|INQUIRY_CONFIRM 的 WE；跨租户 TenantLine 显式绕过+归属校验；审计只落 operator/biz/id）+ 前端 `maskPhone`/`pii.ts` + 管理端 4 页接查全号。检索口径：黑名单 LIKE 下线改"11 位精确(h明文双列 or) / RIGHT last4 尾号 / 执照号保留 LIKE"。全量 **49 类绿**
- [完成] **S1 缺口：定价/短信链补做 S0**（W4 摸出来的实测差异）：15 §4 阶段0 原列了 7 张表，但 V27 实际只加了 `users.phone_hmac` 与 `blacklist.target_value_hmac`。2026-08-25 补齐余下三表，口径逐条照抄 V27 那套：
  - **V30 加列** ✅ `customer_prices.rt_phone_hmac` / `sms_codes.phone_hmac` / `inquiry_requests.rt_phone_hmac`，全部 NULLable + 普通索引，索引列序对齐各自现有明文索引（customer_prices 用 `(wholesaler_id, rt_phone_hmac, sku_id)` 对齐物理唯一键）。纯 additive 无回滚脚本；三个新字段均带 `@JsonIgnore`，实体直出响应形状零变化
  - **双写切点** ✅ 唯一产生点仍是 `PiiCrypto.phoneHmac`，一律 `write-mode=dual` 才写：`PricingServiceImpl.setCustomerPrice` / `settleFromInquiry`（insert 分支写入，命中既有行分支做机会性回填——同 blacklist REMOVED 复活口径）、`AccountServiceImpl.sendSmsCode`、`InquiryServiceImpl.submitByRt`。已核：主代码里这三表的 insert 只有这 4 处，无遗漏。`doBatchCustomerInTx` 不写 rt_phone，属 C3 **读**切点，归 Step 2
  - **回填+对账** ✅ `PiiBackfillService` 扩到五张表；把「主键/明文列/hmac 列/行过滤」抽成 `HmacColumn` record，CAS 幂等、keyset 游标、legacy 拒填三件套共用一份实现（原 users/blacklist 公开方法签名与 `ReconcileResult.table()` 取值不变）。`reconcile()`/`unreadyTables()` 现覆盖五表，`PiiBackfillRunner` 一次重启跑完五表
  - **关卡测试** ✅ `PiiDualWriteBackfillScenarioTest` +5 例（15→20）：C1 新建 / C1 命中既有行机会性回填 / SMS 真端点 `POST /api/v1/account/sms-code` / C2 `submitByRt` / V30 三表回填幂等。切点一律真调，不用 mapper 造行代替。JSON 红线用例扩到三表；对账基线拉平改走 `flattenBackfillBaseline()` 覆盖五表（多个兄弟场景类直接 mapper 造 customer_prices / inquiry_requests，绕过双写切点，hmac 天然 NULL）
  - 2026-08-25 全量 **439 绿**（434+5，0 失败/0 错误/0 跳过，零回归）
  - 遗留：~~`PiiShadowReader` **未**给这三表接影子切点~~ → **2026-08-27 W5 已收口**：customer_prices / sms_codes 各自的读切点已接（见上方 W5 条目），inquiry_requests 经核实主代码无按 rt_phone 的读路径、无切点可接。生产首跑注意 `sms_codes` 行数随发码量线性增长，回填全表成本由 `backfill-batch-size` 控制（刻意不按「未过期」缩小分母，否则闸门口径随时间漂移）
- [完成] **PII-S2 收口（PII-W8，2026-09-01，8954049 + 72c5597）**：V31 补 cipher/last4 列（含 `customer_prices.rt_phone_last4`，决策 v3）→ V32 hmac 唯一索引（`uk_phone_hmac`/`uk_blacklist_type_hmac`/`uk_custprice_wh_hmac_sku`）→ V33 明文列 RENAME `*__bak`（8 列，Flyway H2/MySQL 双变体）→ V34 DROP + blacklist PHONE 行改 `PHONE_****{last4}` 摘要（含 hmac 尾 4 消歧）。删双写/开关代码：6 类整删（PiiShadowReader/PiiReadRouter/PiiModule/PiiFallbackHealer/PiiBackfillService/PiiBackfillRunner）+ `isDualWrite()`/read-mode/read-modes/write-mode 全清；Account/User/Blacklist/Inquiry 4 Service 直连 `PiiHmacQueries`；`PiiRevealService` 改 cipher 解密 + D1 接线 `selectInquiryIgnoreTenant`；`PiiCrypto` AES-GCM + 确定性 cipher KAT（`AAAAAAAAAAAAAAAA/0CZM...` 三源闭环）。前端 D1/D4 落地（72c5597）。全量 **451 绿**（455−4 占位，SKIP=0 无占位残留）。决策 D1-D4 全部定稿落地（G-8.6 登记 691908d）。架构师 §8.2 终验三项 + 复核三点全部闭环。**真实 MySQL 执行完成（9/1）**：V31-V34 已执行；§8.1 真实库核对——恢复残留缺口链（users 577、tenants 194、inquiry 47、customer_prices 2、sms_codes 15、wholesaler_applications 89 等）按 Team Lead 决策整链清除（删除前全库备份 `backup_w8_gap_delete_20260901.sql`），7/8 表 100% 覆盖，wholesaler_applications 97.4%（12 行正常用户 APPROVED 申请单，contact_phone 明文本为 NULL，保留）；§8.4 F1 联调 D1/D3/D4 全过（真实库新造数据链，测试账号 TA 13800002001/OPS 15800002001/WA 15900002003/WE 13600002005）。**剩余发布窗口项**：§8.5 V34 观察期闸门（还原演练/观察）+ prod 冒烟/CVE/graceful shutdown/Redis ACL 属部署侧待环境
- [进行中] **验收**：全量 ✅（W8 收口后 main 72c5597 跑 451 绿）；**F1 联调 ✅（2026-09-01 真实 MySQL 全链路：D1 查全号 / D3 员工全号 / D4 打码展示全过，§8.4 G-8.6 解密供给验证闭环）；E2E 45×2 自动化、PII 交付报告（test-plan/13-，未产出）、上线检查单余项（prod 冒烟 / CVE 复扫 / graceful shutdown / Redis ACL·密码 属部署侧待环境）待执行**

## 验证

- 每波 mvn 全量绿（基线 419 → 424 → 434 → 439 → 448 → **470**（W5 切读）→ **488**（W6 登录双读）→ **49 类**（W7））+ E2E 全套；登录命门波次须含回滚演练证据；独立复验后合并
- 测试态常开 `write-mode=dual` + `read-mode=shadow`——「阶段 0 双写不改行为」「阶段 1 影子期零行为变化」这两句话，靠的就是全量在这两个开关下仍全绿
