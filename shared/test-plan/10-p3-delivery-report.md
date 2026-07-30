# 10 · P3 交付报告（W5 终验收）

> 项目：仓储云 · 编写：测试&审查 Agent · 2026-07-30
> 验收基线：main=09e5245（P3 三波+UX+缺陷批+refactor+Boot 3.5.16 硬化+FE-W2 全部合入）
> 本报告随 W5 测试侧提交一并入库；W5 提交：7fc7ce2（抖动稳定化）/ dccaec4（E2E 契约升级）/ e6c290a（视觉矩阵 spec）
> 真源：`architecture/12-p3-design.md` v2（据实现）、`product/09-p3-arbitration-prd.md` v1.1、`shared/task_plan.md`、`shared/progress.md`

---

## 1. P3 范围（本期交付面）

依 09-p3-decision-options v2 用户三题拍板（全 B）+ 12-p3-design v2：

1. **T5 出库单状态机补拆**：确认即扣不动；PENDING_ACCEPT→PRINTED→COMPLETED；撤回/作废走 OUTBOUND_REVERSAL 反向回补流水（配对可解析）
2. **T1 代建入库异常链**：72h 待确认可售；异议冲销 `min(登记量,在库)` 封顶；差额入 YY- 仲裁单 TA 定责；72h 自动确认 Job
3. **T2 出库异常链**：R4 撤回两路（待受理直撤 / 已打印 flag→WK 二次确认）、R8 作废联动、WA 手动出库、代建大额 50% 复述、30 天客诉 KS-
4. **双仲裁最小闭环**：arbitrations 多态引用；TA 二选+liability 定责；OPS 四选（不动库存与账单）
5. **支撑基建**：流水类型扩展、notifications 站内信、附件上传、错误码 50330-50342、DocStateMachine CAS 引擎
6. **随波交付**：UX 批（认证页窄屏 P0 根治+实体选择弹窗化+角色码中文化）、缺陷批（B1 行锁 BLOCKER+N1-N5+FE-W1 两契约偏差+stock-preview 端点）、refactor（G-S1/G-S2 UserMapper 收敛）、X 硬化第一批（Boot 3.2.5→3.5.16 CVE 根治+日志脱敏+active-timeout+prod fail-fast）

**出圈**（P3b，10-p3b-requirements v1.1 已拍板）：T1 正向申请链 R1-R3、T3 退货/盘点/清库、T4 批次临期。

## 2. 各波闸门数据（自 progress.md / task_plan.md 提取）

| 波次 | 分支 | 合并点 | 闸门数据 |
| --- | --- | --- | --- |
| W0 设计定稿 | — | d2c34c1 | PRD v1.1 + 架构 v2；Team Lead 契约对账拦下 3 处并行漂移 |
| BE-W1 入库异常链+基建 | feat/p3-inbound-chain | acefaaf | V15-V17；15 例场景测试（封顶 4 边界/Job 幂等竞态/liability 三态/公式不变量）+ mvn 全量绿 |
| BE-W2 出库状态机+异常链 | feat/p3-outbound-chain | 1b7f760 | V18+DocStateMachine；Team Lead 独立复验 **219/219 绿**；矩阵 6×6 逐格断言/回补配对/29d23h·30d1h 边界/虚拟线程 CAS 竞态×2 |
| FE-W1 入库链前端 | feat/p3-inbound-fe | ebf6dc0 | typecheck 绿 + Playwright inbound-dispute **3/3 绿** + 截图 6 张亲检；契约偏差 3 项移交缺陷批 |
| UX 批 | — | 877bd07 | 认证三页收编 AuthShell（窄屏 P0 根治）、EntityPickerDialog 7 处、角色码中文化（规则 8） |
| 缺陷批 | fix/p3-be-defects | 72a5308 | 10 commits；B1 行锁改真 FOR UPDATE（Team Lead 亲验）；独立复验 **236 绿** |
| refactor | refactor/account-user-service | aea514b | 独立复验 **231 绿**；G-S1/G-S2 补测 12 例；account 域外 UserMapper 引用清零 |
| X 硬化第一批 | chore/hardening-boot-upgrade | 0b62e14 | Boot 3.5.16（四高危组件达线，CVE-2025-24813/22228 根治）；独立复验 **250 绿**；报告 09 |
| FE-W2 出库链前端 | feat/p3-outbound-fe | 09e5245 | WK 作业流/WA 出库客诉/OPS 仲裁/stock-preview 接入；outbound-chain E2E 4 例 |
| 合并后组合回归 | main | 0b62e14 时点 | **248→250 全绿** |

## 3. W5 终验收结果

### 3.1 后端全量回归（`mvn clean test`，H2 内存库）

| 轮次 | 结果 | 备注 |
| --- | --- | --- |
| 修前基线 1 遍 | **250/250 绿**（3m25s） | main=09e5245 原样 |
| 稳定化修后连跑 3 遍 | **3 × 250/250 全绿** | 见 §3.2，commit 7fc7ce2 |

### 3.2 concurrentWithdraw H2 抖动稳定化（W5 专项）

**症状**（历史两次全量跑复现，隔离复跑即绿）：`OutboundChainScenarioTest.concurrentWithdraw`（虚拟线程并发双撤回）偶发 "JDBC rollback failed" / H2 连接关闭。

**根因研判**：并发 `SELECT ... FOR UPDATE`（B1 修复引入的真行锁）在慢机高负载下行锁等待超过 H2 默认 LOCK_TIMEOUT，超时路径偶发升级为连接级故障；属 H2 内存库基建抖动，非业务缺陷（业务断言从未失败过）。

**处置（只改测试侧，commit 7fc7ce2）**：
1. 测试数据源 URL 追加 `LOCK_TIMEOUT=10000`；
2. 测试 Hikari 池 10→20 + connection-timeout 显式 30s（多 Spring 上下文缓存共享同一内存库，防高峰借连接饥饿）；
3. `concurrentWithdraw` 加 `retryOnH2InfraFlake(3)` 受控重试：**只认连接级/事务基建故障签名**（JDBC rollback failed / has been closed / is already closed / Connection is broken 及对应 Spring 异常型），**AssertionError（业务断言失败）与业务异常一律立即抛出，绝不重试洗绿**；每次重试 seedAll 生成全新雪花 ID 数据，尝试间无状态污染。

**验证**：修后连跑 3 遍全量 250/250 全绿；3 遍日志均未出现 `[W5-flake-retry]` 重试打点（即修复 1/2 的配置项已消除触发条件，重试仅作最后保险）。

### 3.3 E2E 全套（前端 5173 + 后端 8080 dev,local，main 最新构建）

首跑 25/29，4 失败经逐一定性**均为用例期望停留在 phase-1/P2 契约，非业务缺陷**（P3 BE-W2 有意变更：12 §1.4 确认后询价停 CONFIRMED、出库单 PENDING_ACCEPT；R13 未结单据扩展至入库/仲裁）。测试侧升级断言后复跑（commit dccaec4）：

| Spec | 用例 | 结果 |
| --- | --- | --- |
| auth.spec.ts | E1-E8（8 例） | ✅ 8/8 |
| sell-flow.spec.ts | SELL-S1×2 / S2×2 / S6（5 例） | ✅ 5/5（S1-02/S6-01 断言升级至 P3 契约，S1-02 补「WK 打印+登记→询价终态联动 COMPLETED」三段式） |
| sell-flow-2.spec.ts | B-RT-02/03/07、B-WA-04、B-EMP-02（5 例） | ✅ 5/5（B-RT-02 卖光造数断言升级） |
| onboarding-flow.spec.ts | ONB-E2E-01~04（4 例） | ✅ 4/4（E2E-03 退驻链补 WA 入库确认+WK 出库作业闭环收尾，适配 R13 P3 扩展） |
| inbound-dispute.spec.ts | INB-01~03（3 例） | ✅ 3/3 |
| outbound-chain.spec.ts | OUT-01~04（4 例） | ✅ 4/4 |
| **合计** | **29 例** | **✅ 29/29 全绿（2.3m）** |

环境说明：验收时发现 8080 上跑的是 Boot 3.2.5 旧实例（合并前构建），已按流程杀旧进程后以 main 最新代码重启（启动日志确认 Spring Boot v3.5.16）——**E2E 结果以新实例为准**。

### 3.4 视觉矩阵（14 图，`.e2e-tmp/w5-visual/`，供 Team Lead 亲检）

覆盖：未登录三页（login/register/forgot）× 390×844 + 375×667 共 6 图；角色页 1280×800 共 8 图（ta-home-dashboard / ta-outbound / ta-approvals / wa-home-inquiry / wa-inbound / wa-outbound / wk-outbound-workbench / ops-home-dashboard + ops-arbitrations）。spec 已入库（w5-visual.spec.ts，e6c290a），可复拍。

**逐图目检结论**：无对齐/溢出/错位类 P0-P1 缺陷；未登录三页双窄屏布局均完好（UX 批 AuthShell 根治成立）。低危发现 4 处（如实登记，均不阻塞交付）：

| # | 位置 | 现象 | 定级 |
| --- | --- | --- | --- |
| V-1 | OPS 首页 /ops/dashboard（占位页 PlaceholderDashboard.vue:13,15） | 文案「留给后续 Agent 开发」+ `{{ auth.primaryRole }}` 直出角色码 **OPS**——全前端唯一命中规则 8 的用户可见角色码 | 低（占位页，OPS 内部角色） |
| V-2 | TA 工作台 /ta/dashboard | 仍为前端 mock 数据：页脚「当前数据为前端 mock，待后端 GET /api/v1/tenant/dashboard 联调」+ 假身份「XX 海鲜库」与顶栏真实店名并存 | 低（已知遗留，待 P4 联调） |
| V-3 | WA 入库确认 / 出库单 / WK 代建选货 | SKU 列展示裸雪花 ID（无名称）——同源于「WK 无 SKU 名称端点」契约缺口（见 §5-4） | 低 |
| V-4 | register/forgot @375×667 | 「请输入短信验证码」placeholder 末字被截；390 宽度下完整 | 极低（cosmetic） |

**文案角色码残留 grep（全前端 src）**：模板/提示串逐类扫描（含中文邻接角色码模式），映射表（码→中文）与代码注释除外，用户可见文案命中 **1 处 = V-1 占位页**；业务页面零残留（Register 角色下拉、Employees 单选、liability 四选等均为中文文案）。另记：ta/Dashboard.vue:357 身份栏兜底分支 `?? auth.primaryRole` 理论可直出角色码，但该页仅 TA/WK 可达且映射表已覆盖两者，实际不可触发（随 V-2 mock 移除时一并清理）。

## 4. 上线检查单（6 项，继承 09 硬化报告 §4 + 本期新增）

1. **Redis 6379 监听 0.0.0.0**（netstat 实测）：上线前必须 bind 127.0.0.1 + protected-mode + requirepass（11 §2.2）；配好密码后按 09 §4-1 跑 prod profile 三链路冒烟（会话/RLock+Lua/RAtomicLong）与 fail-fast 行为。
2. **WK 无 SKU 名称端点**：`/tenant/skus` 列表仅放行 WA/TA（requireWaOrTa），纯 WK 账号 42xxx 拿不到 SKU 名称——WK 代建选货已降级「在库 SKU 编号+件数」（ta/Outbound.vue:448 已注记）；BE 放行 WK（或提供轻量名称端点）后前端换回名称展示，V-3 一并消除。
3. **打印视图 ID 展示**：出库票面（ta/Outbound.vue 打印区）商品行输出裸 `skuId`（商户名有 name 兜底而 SKU 无）——纸质单据交接场景人工核对不友好，依赖同上契约补口。
4. **H2 抖动处置已闭环，机制留档**：concurrentWithdraw 稳定化三件套见 §3.2；若 CI 换机后再现，先查 `[W5-flake-retry]` 打点判定是否基建签名，**禁止**将重试面扩大到业务断言。
5. **Boot 3.4+ graceful shutdown 默认开启**：Windows 服务停止行为未实测（09 §4-3）。
6. **CVE 复扫门禁未执行**：上线前以 `dependency-tree-after-boot3516.txt` 为基线跑 OWASP dependency-check / Trivy / osv-scanner 复扫（09 §4-4）；另 sa-token redis 集成中期迁移 sa-token-redis-template 系（09 §4-6）。

## 5. 遗留清单（非上线阻塞）

- **TA 工作台 mock**（V-2）：待后端 `/tenant/dashboard` 端点（P4 账单波可顺带）。
- **OPS 首页占位页**（V-1）：/ops/dashboard 尚未实现；OPS 实际工作入口为租户审核/黑名单/客诉仲裁三页，占位页文案与角色码直出待该页实装时清理。
- **窄屏 placeholder 截字**（V-4）：375 宽度验证码输入框 placeholder 末字被截，cosmetic。
- **托盘账只增不减**：出库链不扣托盘（BE-W2 备注 5 有意遗留）→ P3b D-8=A 已拍板随 T3 波统一补齐。
- **WE 出库侧未开放**（BE-W2 备注 1）：等产品定位，P3b C11 已登记。
- **onboarding-visual.spec.ts**（P2 期视觉 spec）未纳入本轮功能回归（非功能断言型），视觉验收以本期 w5-visual 矩阵为准。

## 6. P3b 衔接

- `product/10-p3b-requirements.md` **v1.1 已拍板**（2026-07-25 Team Lead 依用户执行准则裁决，14 项 DECISION 全按建议采纳，**D-11=C**〔批次最小方案：批次登记+临期治理，库存仍按 SKU 池〕/ **D-8=A**〔T3 波一次补齐出库/退货/盘亏/清库托盘释放〕）——P3b 无阻塞，可直接进架构设计。
- 硬事实提醒（该文档 §0 实测快照）：Flyway 自 **V19** 起；错误码 50343-50349 仅 7 枚需架构划溢出段；R13 未结单据枚举在 T3/T4 新单据类型落地时必须同步扩（C12）；batch_enabled 禁改防御（D-13）建议随 T3 首波顺手落。
- 本期 E2E 契约升级（dccaec4）已为 P3b 留好钩子：`completeOutboundChain` / `confirmPendingInbound` 两个 helper 即 T3 退货链造数的现成前置。

## 7. 验收结论

| 项 | 结论 |
| --- | --- |
| 后端全量回归 | ✅ 4 遍 × 250/250 全绿（修前 1 + 修后 3） |
| concurrentWithdraw 抖动 | ✅ 稳定化闭环（配置根治 + 窄面受控重试保险），3 遍验证零触发 |
| E2E 全套 | ✅ 29/29 绿；4 处首跑失败均定性为用例契约过期并已升级（无业务缺陷掩盖） |
| 视觉矩阵 | ✅ 无 P0/P1；低危 4 处如实登记（§3.4） |
| 文案角色码 | ✅ 业务页零残留；唯一命中为 OPS 占位页（V-1） |
| **P3 第一期** | **✅ 验收通过，可交付**；上线前按 §4 检查单 6 项执行 |
