# 12 · P4 交付验收报告（W5c 终验收，P4 收官）

> 测试&审查 Agent · 2026-08-10 · 验收基线 main=5319e44（P4 全部八段合并）；本报告随 W5c 测试侧提交（SaManager 收口 2fde373、视觉矩阵 spec 66f3c28）一并入 main
> 上期基线：11-p3b-delivery-report.md（main=afb9b26，337 测试 / E2E 38 例）+ 收口批（L-1~L-7 清零，341 绿）

## 1. P4 范围

计费与结算全链（真源 product/12-p4-requirements.md 十项 DECISION + 13-p4-prd.md v1 + architecture/14-p4-design.md v1）：

- **计费规则**（W1）：V24 一日一版规则链（件·天 / 托盘·天双维）、R20 变更二次确认+通知、billing_dim 只读镜像、契约断裂修复
- **回放引擎+快照**（W2）：V25 统一回放公式（十二类流水锚点、争议对归一修正——设计 §1.1 数学证伪修订）、00:10 每日快照 Job + 全量不变量对账哨兵
- **账单生命周期**（W3）：V26 账单四表、6 态状态机（DRAFT→已下发→已确认/争议中→部分回款→已结清）+ DISPUTED 冻结、BL- 月度单号、回款登记/冲销（R12 状态回退）、WA 确认/行级申诉闭环、ST 角色首次启用、R13·R14 联动
- **前端**（W4）：TA 规则设置（Settings 计费区块）、ST 三页（工作台/账单列表/详情：按日·按货品下钻+全操作弹窗）+ 回款登记、WA 账单确认+申诉表单；三核心页 375 降档（D-P4-9=A）
- **导出**（W5a）：openhtmltopdf PDF（DRAFT 水印/印章位/中文字体系统 TTF 候选链）+ POI SXSSF Excel 四 Sheet + 按日对账单，同步流式不落存储（D-P4-8=A）、>5000 行按货品聚合降级、RFC 5987 中文文件名、导出审计日志；TA bills-overview 补口
- **导出接入+总览页**（W5b）：blob 下载基建（拦截器 content-type 分流）、ST 详情三导出按钮、TA /ta/bills-overview 独立页（四汇总卡+状态分布+商户行下钻）
- 错误码 50370-50389；Flyway V24-V26

## 2. 八段闸门数据（均 Team Lead 独立复验后合并）

| 段 | 合并/定稿 commit | 闸门 |
|---|---|---|
| W0 产品 PRD | 580cd26 | 13-p4-prd v1（10 线框/6 态中文对照/ST 三页降档标注） |
| W0 架构设计 | b65fd46 | 14-p4-design v1（V24-V26/回放公式五锚点收敛/六波闸门）；词表对账一致 |
| W1 规则后端 | f93370d | **351 绿**（V24/规则 CRUD/R20/billingDim 只读镜像） |
| W2 回放+快照 | 64713e9 | **377 绿**（金账本 26 例/争议对锚点归一/快照 Job+哨兵） |
| W3 账单生命周期 | 0c2daf4 | **401 绿**（6 态矩阵/BL- 单号/回款+申诉闭环/ST 启用） |
| W4 计费前端 | e0293fc | E2E p4-billing 5/5 + 375 降档截图亲检 |
| W5a 导出后端 | 8f0846f | **408 绿**（导出 7 例：Excel 回读/PDF 魔数+水印/5000 行降级/越权矩阵） |
| W5b 导出前端+总览 | 5319e44 | E2E w5b 2/2（download 事件+RFC5987 文件名+魔数；总览一致性+下钻） |

## 3. W5c 后端全量回归（408 例 × 4 遍全绿）

每遍跑前删除 `target/surefire-reports`（fresh reports），44 测试类，聚合自 surefire 报告：

| 轮次 | 代码态 | 结果 |
|---|---|---|
| Run 1 | 修复前基线（mvnw test） | **408 / 0 fail / 0 error / 0 skip** |
| Run 2 | 修复前基线（mvnw clean test） | **408 全绿** |
| Run 3 | SaManager 收口后（2fde373） | **408 全绿** |
| Run 4 | 同上连跑 | **408 全绿** |

环境注记：mvnw wrapper 已被收口批根治（本轮四遍均经 `./mvnw` + JDK 21 真实执行）；简码碰撞（TestUniq）与 H2 并发先例本轮零再现。

### 3.1 SaManager 静态泄漏处置（W5a 移交项 → 已收口，纯测试侧）

- **根因**：`SessionActiveTimeoutTest` 用 `properties = "sa-token.active-timeout=3"` 起第二 Spring 上下文，sa-token starter 注入时调 `SaManager.setConfig(...)` 覆写 **JVM 全局静态单例**；Spring 上下文缓存不隔离静态字段，同 JVM 后续所有测试类被 3 秒冻结语义污染——长静默用例撞 41001（W5a 曾被迫把 BillExportScenarioTest 5000 行造数改 JDBC 批量直插规避）。
- **方案评估**：`@DirtiesContext` 只关闭上下文、不还原静态字段（无效）；surefire 独立 JVM fork 需改全局构建配置且拖慢全量（过重）；**「捕获-还原」最简**——`@BeforeAll`（先于本类上下文加载执行）直读 `SaManager.config` 公共字段捕获主上下文配置（不走 `getConfig()`，避免 null 时惰性默认初始化），`@AfterAll` 直写字段还原（不走 `setConfig()`，避免事件回调副作用）。
- **证明**：修后连跑 2 遍全量 408 全绿；两遍中本类均为第 3 个执行、先于全部 billing 类，还原路径被真实检验（后续 405 例零冻结失败）。修前基线 2 遍亦绿（泄漏属执行序敏感隐患，非显性失败）。业务代码零触碰。
- BillExportScenarioTest 的批量直插保留（本身也是造数提速优化），其注释中的泄漏引用现指向已收口状态。

## 4. E2E 全套（45/45 全绿）

前置：8080 后端自 main 重启（spring-boot:run，dev,local；导出/总览端点通过即 W5a 代码版本的功能性实证）；5173 为主仓 frontend vite dev（实时服务 main 源码）。日志留档 `.e2e-tmp/w5c-e2e-p1-p3b.log`、`.e2e-tmp/w5c-e2e-p4.log`。

| spec | 用例 | 结果 |
|---|---|---|
| auth | E1-E8（注册/登录/找回/工作台/退出/负向×2/幂等） | 8/8 ✅ |
| sell-flow / sell-flow-2 | S1×2/S2×2/S6 + B-RT/B-WA/B-EMP | 10/10 ✅ |
| onboarding-flow | ONB-E2E-01~04 | 4/4 ✅ |
| inbound-dispute / outbound-chain | INB-01~03 / OUT-01~04 | 7/7 ✅ |
| p3b-inbound-forward / t3 / t4 | FWD·RTN·PD·T4 各 3 | 9/9 ✅ |
| **p4-billing** | 规则链/账单全链/申诉链/按日下钻/375 三核心页 | **5/5 ✅** |
| **p4-w5b-export-overview** | 三导出下载+RFC5987+魔数 / 总览一致性+下钻 | **2/2 ✅** |
| **合计** | | **45/45 ✅（P1-P3b 38 例连过 2 遍；3.7m+45.5s）** |

零环境失败、零真缺陷。

## 5. 视觉矩阵（p4-w5-visual.spec.ts，18 图，逐张目检）

产物：`.e2e-tmp/p4-w5-visual/`。16/16 用例绿（另 2 张 375 详情视口取证图）。

- **未登录三页 × 390/844、375/667**（6 图）：布局无溢出错位。已知 V-4 残留不变——375 宽下 register/forgot「请输入短信验证码」placeholder 末字截断（cosmetic，390 宽完整）；register@375《隐私政策》换行属正常流式换行。
- **P4 新页 @1280**（7 图）：ta-settings 计费区块（双维开关/单价步进/生效口径文案/历史版本留痕）、ta-bills-overview（四汇总卡+状态分布+商户行，数值与 ST 列表一致：150/1/149/1）、st-dashboard（本月三卡+待处理三行，上月账单不计入本月应收——口径正确）、st-bills（三筛选+汇总卡+合计行）、st-bill-detail（三金额卡/按货品·按日切换/回款记录含冲销/申诉记录/三导出按钮）、st-disputes（待处理/全部页签+处理入口）、wa-bills（下发核对引导文案+汇总行）——全部对齐良好、空态规范、金额等宽数字字体、全中文。
- **ST 三页 @375**（3+2 图）：卡片流/筛选纵排/CTA 全宽，无横向滚动。**甄别记录**：详情页 fullPage 截图中吸底操作栏（三导出+登记回款）画在滚动位、看似盖住回款行——追加两张视口截图（滚至回款记录/页底）证实为 **fullPage+fixed 定位的截图伪影**：真实视野中固定吸底栏+足额页底留白，回款行/申诉/导出全部可达，**非缺陷**。同理 ta-settings fullPage 图中部的顶栏暗带亦为 sticky 伪影。
- **观察（极低，非缺陷）**：ta-settings 计费规则卡内「取消/保存」与页面通用「取消/保存」两对按钮相邻出现——源码注释明示双数据源双保存作用域（668/761/790 行），设计使然；如用户反馈混淆可考虑文案区分（「保存计费规则」）。

**零角色码 grep 复核（全前端 src）**：模板可见文本/label/placeholder/title 扫描，用户可见裸角色码直出 **0 处**；唯二命中为 ta/Employees.vue 两个 `el-radio-button` 的 label **值绑定**（显示文本为「库管员/结算员」中文），与 P3/P3b 验收口径一致不计违规。P4 七新页零命中。

## 6. 遗留清单

| # | 项 | 说明 | 级别 |
|---|---|---|---|
| P4-L1 | Element aria-disabled quirk | el-input-number 启用后 `aria-disabled` 属性残留（原生 disabled 已移除），Playwright actionability 需 `{ force: true }` 绕过（p4-billing.spec 105 行注释）；真实用户可正常输入。Element Plus 上游行为，测试侧已固化规避 | 低（测试基建） |
| P4-L2 | WA 无按日视角 | WA 账单详情仅账单/明细两级，无 ST 侧的按日快照下钻——05 §5.4 既定口径（不向 WA 暴露库存明细），产品口径而非缺陷；WA 侧核对颗粒度依赖导出对账单（按日），下一期可评估 WA 只读按日汇总 | 低（口径） |
| P4-L3 | 导出中文字体部署项 | PDF 中文渲染依赖运行机系统 TTF（黑体候选链），缺失时降级并记 WARN——**上线部署 checklist 必项**：目标机预装 simhei/思源黑体任一 | 中（部署） |
| P4-L4 | SaManager 静态泄漏 | **已收口**（§3.1，2fde373，捕获-还原，修后 2 遍 408 绿）。后续新增「properties 起第二上下文」的测试类须比照处理（或抽公共 JUnit Extension） | 已关闭 |
| P4-L5 | 上线检查单余项（09 报告复核） | ① prod profile 实机冒烟未做（Memurai requirepass + 三链路 + fail-fast）② Redis 6379 仍 0.0.0.0（部署配置：bind 回环+protected-mode）③ graceful shutdown Windows 停服行为未实测 ④ CVE 复扫门禁（OWASP dep-check/Trivy）未执行，以 06 报告+Boot 3.5.16 归档树为基线 | 高（上线前） |
| P4-L6 | sa-token redis 集成 | 1.42+ 官方推荐迁 sa-token-redis-template 系（现 redis-jackson 正常），中期择机 | 低 |

## 7. 下一期建议

1. **PII 三段式硬化（窗口已到）**：手机号明文加密为 X 项挂账最久的安全债——建议独立硬化波按「新写加密 → 存量回填 → 读路径切换+脱敏兜底」三段式落地（含索引策略：确定性加密或影子哈希列支持登录/查询），并同批完成 P4-L5 的 prod 冒烟+CVE 复扫+graceful shutdown 实测，一次性清掉上线检查单。
2. WA 按日只读汇总评估（P4-L2）+ 导出字体部署项写入运维手册（P4-L3）。
3. 测试基建：把 SaManager 捕获-还原抽成 JUnit Extension 供后续 properties 上下文类复用（P4-L4 尾巴）。

## 8. 服务保持

- 后端：http://localhost:8080（spring-boot:run，profiles=dev,local，自 main 启动，日志 backend/logs/w5c-boot.log）
- 前端：http://localhost:5173（主仓 frontend vite dev，@cangchu/admin）
