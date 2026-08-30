# Progress Log · P3 完整单据与履约异常

> 最新在上。关联 `task_plan.md` / `findings.md`。P2 定价/入驻计划已归档 `shared/archive/`。

## 2026-08-18（会话收口·交接）
- **在途两分支已现场保护（均未合并，接手先看 task_plan 交接标注）**：
  - fix/p4-leftovers（3 commits）：P4-L2 WA 按日端点+LIF-10 测试（未跑）、P4-L3 PDF 无字体兜底（X-Export-Warning 头+首页提示行）。差最后一步：全量跑绿即可合并
  - feat/pii-stage0（3 commits）：V27 加列（hmac 列 NULLable+普通索引，V28 才 UNIQUE）、PiiCrypto 单入口+KAT+fail-fast；wip=双写切点半程未编译。真源 15-pii-hardening-v2 §阶段0；读路径不动红线
- **本段大量余额 401 中断**（约 10+ 次），均按"每段即 commit"纪律无损；接手 Agent 从 wip commit 续做即可
- **用户新规（已固化 CLAUDE.md 规则 10）**：代码分析/重构任务分批加载文件（单批 1-4 个、先报清单待确认、禁递归批量读目录、禁会话中变更 MCP 工具列表以保护 Prompt 缓存）
- headroom：mode=cache（8/15 定论勿switch token）；Memurai 已服务化；服务 8080/5173 状态未知，接手先探活

## 2026-08-10
- **P4 全部交付 ✅（W5c 终验收全绿，P4 收官）**：八段全合并（W0 双文档 580cd26/b65fd46 + W1-W3 后端 351→377→401 绿 + W4 前端 E2E 5/5 + W5a 导出 408 绿 + W5b 前端 E2E 2/2）。W5c：全量 408×4 遍全绿（fresh reports；基线×2 + SaManager 泄漏收口后×2）、**SaManager 静态泄漏测试侧根治**（2fde373，@BeforeAll/@AfterAll 直读直写 SaManager.config 捕获-还原，方案评估见报告 §3.1）、E2E 45/45（P1-P3b 38 例连过 2 遍 + p4-billing 5 + w5b 2，零环境失败零真缺陷）、视觉矩阵 18 图逐张亲检零新缺陷（p4-w5-visual.spec 66f3c28；375 详情吸底栏遮挡甄别为 fullPage 伪影并双视口图取证）、零角色码复核 0 违规。报告 **test-plan/12-p4-delivery-report.md**（遗留 P4-L1~L6：aria-disabled quirk/WA 无按日视角/导出中文字体部署项/上线检查单余项；下一期建议 PII 三段式硬化窗口已到）。
- **环境**：8080（main，dev,local，logs/w5c-boot.log）+ 5173（主仓 vite）保持运行供真机复验。

## 2026-08-03
- **P3b 全部交付 ✅（W5 终验收全绿）**：九开发波全合并（W0 设计/T1-BE·FE/T3-W1·W2·FE/T4-W1·W2·FE），后端 270→285→303→318→337 全绿递进，每波 Team Lead 独立复验后合并。W5：337×4 遍全绿、简码碰撞抖动根治（TestUniq 全局序列替换 10 处取模造数）、E2E 38/38（3.9m）、17 图视觉亲检、报告 11-p3b-delivery-report.md（遗留 L-1~L-7 + P3 检查单复核：Redis 已实测绑 127.0.0.1）。识破 mvnw 假成功坑（wrapper jar 缺失退出码误报 0，L-6）。
- **环境**：Memurai 已装为 Windows 服务（8/2 机器重启曾致登录 500，根治）；8080/5173 保持最新 main 供真机复验。

## 2026-07-27
- **缺陷批+refactor+硬化 三分支复验合并 ✅**（main 至 0b62e14 已推 origin）：
  - fix/p3-be-defects（10 commits）：B1 BLOCKER 行锁改真 FOR UPDATE（Team Lead 亲验代码）、N1-N5、FE-W1 两缺陷（通知收件人 7 处同根因全修）、角色码清扫 9 处、stock-preview 端点。独立复验 236 绿（concurrentWithdraw H2 抖动第二次出现，隔离复跑绿，判定环境 flake → W5 稳定化项）
  - refactor/account-user-service：实际已于前次会话尾段合入（aea514b，transcript 因进程重启丢失但 git 完整）；独立复验 231 绿；揭穿原 WIP 两处不实（不编译/死代码）并修正，account 域外 UserMapper 引用清零
  - chore/hardening-boot-upgrade（6 commits）：Boot 3.2.5→3.5.16（四高危组件全达线，CVE-2025-24813/22228 根治，零业务代码改动）、日志 profile 化+手机号脱敏、active-timeout 1800s、prod fail-fast 配置。独立复验 250 绿。交付报告 09-hardening-w1-report.md，6 项遗留入上线检查单（含 Redis 0.0.0.0 需 bind 回环）
  - **合并后 main 组合回归 248→250 全绿**
- **执行准则落地**：用户 7/25 下发六条准则已固化 CLAUDE.md 规则 8；P3b 预研 14 项 DECISION 已按准则由 Team Lead 拍板落档（10-p3b-requirements v1.1，D-11=C/D-8=A），P3b 解除阻塞
- **在途**：仅剩 FE-W2（出库链前端，4 commits 已落，收尾 E2E+stock-preview 接入；期间遭遇 codecmd 余额 401×3 + 网关 502/400 多次，均无损续跑）

## 2026-07-25
- **FE-W1 入库链前端 ✅（feat/p3-inbound-fe，4 commits 31f5f6f→f5ec4db，已合并）**：
  - 交付：WA `/wa/inbound` 入库确认页（待确认/全部页签、72h 秒级倒计时 deadline 升序、来源映射 仓库代建/我方提交、autoAccepted 标记、确认二次弹窗、异议弹窗〔预设四选+补充说明合成 reason≤512+附件≤5〕、冲销结果回显 登记/已冲销/差额/YY-单号）；TA `/ta/approvals` 审批中心（待仲裁角标=PENDING total、⏰超72h提醒、decide 弹窗按 09 §4.1：通过·恢复流水/驳回·保留冲销、差额>0 驳回时定责四选必填、备注必填、已裁决只读详情）；NotificationBell（unread-count 60s 轮询+抽屉+标记已读）；AttachmentUpload（≤5MB jpg/png/webp 预检）；api-types/error-codes 50330-50342/四组 API 封装；TA 各页菜单接通「审批中心」、WA 四页菜单增「入库确认」；用户可见文案零角色码（liability 中文四选）。
  - 闸门：typecheck 绿；Playwright `inbound-dispute.spec.ts` 3/3 绿（INB-01 确认链/INB-02 异议链含真实附件上传/INB-03 TA decide + TA 侧铃铛角标-条目-已读全链断言）；截图 6 张逐张目检无对齐/溢出/错位（动画入镜的 3 张已加静置重拍）。
  - **契约偏差 →BE 待修**：① `registerByWk`/72h Job 的「通知归属 WA」发给 `wholesalers.owner_user_id`，SELF_OPERATED 商户该列= TA 操作人，绑定 WA 账号收不到通知（listForWa 用 user_roles 推导无此问题；E2E 改在 TA 侧断言铃铛全链）。② `/files/**` GET 静态映射在启动时 `Path.toUri()`，若 upload-dir 尚不存在则 URI 缺尾斜杠 → 上传成功但 GET 500，重启后自愈（建议 addResourceHandlers 先 createDirectories 或手工拼尾斜杠）。③ PRD 09 §6.2 要求异议弹窗展示实时在库 M/差额 N−M，后端无异议前在库查询端点，已降级为口径文案+提交后回显（如需严格达标需 BE 补端点）。
  - 环境插曲：8080 曾跑 BE-W1 合并前旧实例（新端点 404→90001、V15-V17 未迁移），Team Lead 重启后解决；又因 ② 再重启一次使 /files GET 生效。axios 实例默认 application/json 覆盖 FormData 检测的坑已修（file.ts 摘除 Content-Type）。
- **BE-W2 出库状态机+异常链 ✅（待 Team Lead 复验合并）**〔feat/p3-outbound-chain，5 commits〕：V18（出库补拆列+inquiry voided_at，存量回填幂等）；DocStateMachine 引擎（OUTBOUND/INBOUND 双矩阵+assertCanGo 50330+通用 casTransition，兑现 BE-W1 备注 2）；confirmByWa 唯一触主链改动（出库 PENDING_ACCEPT/询价停 CONFIRMED）；R4 两路/R8 作废/代建大额 50%/30 天客诉+OPS 四选（remark 必填按 PRD）；R13 未结扩展至入库+仲裁；R14 钩子三处接入。测试 219/219 绿（基线 202+17 新增，P1 断言适配 4 文件）。错误码零新增（BE-W1 预登记段全启用）。偏差 10 处已回写 12 据实现备注（要点：WE 暂不开放出库、一单一诉查历史仲裁单、托盘账不动、50004 不存在改 50330）。中途插曲：上游 502×1 + codecmd 余额×1，均按「撞墙先 commit」纪律无损续跑；新拍板规则 8（文案去角色码）已在本波落地。

## 2026-07-24
- **P3 W0 设计定稿 ✅**：并行两 Agent 产出——产品 09-p3-arbitration-prd.md v1.1（双仲裁最小 PRD，Q-D04/Q-D10 收口，04 §1.2 确认即扣转正，05 §7.1 新前缀 RTN-/PD-/QK-/YY-/KS-，决策日志 D57-D60）+ 架构 12-p3-design.md v2（V15-V18 迁移、状态机 String+CAS、封顶冲销口径、72h Job 复用 SchedulingConfig、错误码 50330-50342、四波次拆分）。**Team Lead 契约对账拦下 3 处并行漂移**（liability 列缺失→补+50342、仲裁 doc_no 缺失→补 YY-/KS-、PRD 命名 9 处漂移→对齐落库定稿；另架构自查出盘点/清库前缀冲突按产品 PD-/QK- 统一）——W1 教训的对账机制第二次见效。
- **headroom 生效确认 ✅**：会话重启后 BASE_URL=127.0.0.1:8787，stats 显示 262 请求被压缩、累计省 25.6 万 token。
- **BE-W1 派发**：入驻异常链+基建〔feat/p3-inbound-chain〕，闸门见 task_plan。

## 2026-07-23
- **Wave 6 完成 ✅ / P2 全部交付**：双分支合并 main（3564607/bfba12e），回归 187/187 绿 + typecheck 绿 + E2E 12/12 绿（1.9m）；报告 07 v2 增补复验记录（0405b19）；main 已推 origin（aca2ae8）；worktree defects-be/fe 已清理。**发现**：后端 Agent 在 worktree 留有未提交的 G-S1/G-S2 架构债重构（tenant 跨域直连 UserMapper 收敛为 UserService 出口），未混入 Wave6——已抢救到分支 `refactor/account-user-service`（WIP，未经测试验证，P3 期间择机补测合并）。
- **P3 拍板 ✅（用户）**：三题全选 B——72h 待确认库存可售+冲销按剩余在库封顶（差额进 TA 仲裁）；扣库存保持「确认即扣」+状态机补拆（撤回走反向回补流水）；双仲裁最小闭环版（P3 产品首任务补最小 PRD+Q-D04 收口）。两项修正同意：单据号按已上线 WK-/CK-/XJ-（退货 RTN-）修订 PRD；Flyway P3 自 V15 起。详见 09-p3-decision-options.md v2。**P3 解除阻塞。**
- **headroom 路由修复 ✅**：代理一直在跑但会话绕行——项目 settings.local.json 残留 BASE_URL=codecmd 覆盖了全局 8787 配置；已删除覆盖，/v1/messages 经 8787 端到端验证通。新会话起走压缩。
- **Wave 6 双分支就绪**：后端 fix/p2-defects 5 commits mvn 全量绿；前端 fix/p2-defects-fe 6 commits typecheck 绿（前端二批 DEF-1 下拉/DEF-6 分页实际已随一批完成，无需再派）。进入合并+回归。
- **环境插曲**：headroom 压缩代理曾损坏（7-16 起 headroom.exe 报废导致会话报错），已重装 `headroom-ai[proxy,ml]` 0.32.1+开机自启；`.claude/settings.local.json` 清理跨机残留规则；CLAUDE.md 技能列表格式修正（5b0e745）。main 已推送 origin（65 commits，至 5b0e745）。
- **Wave 6 启动**：P2 收尾缺陷修复（DEF-1~DEF-6，源自 07 报告 §5）。派发：后端 Agent〔fix/p2-defects〕+ 前端一批 Agent〔fix/p2-defects-fe，DEF-4/5 纯前端〕并行；前端二批等后端 DTO 定稿后派（吸取 W1 契约漂移教训，契约类修复以后端据实现文档为真源）。

## 2026-07-16
- **WE 前端对账 ✅**：commit 823b876，typecheck 5/5 绿。关键修复：Staff.vue 8 处误用 userId→改角色绑定行 id（运行时必炸级）；41110 登录禁用文案、50319-50322、defaultRouterFor WE→/wa/inquiry、30 天倒计时 disabledAt 自算。守卫备注：WE 进 /wa/* 前端不拦（与 TA/WA 互访策略一致，页面权限靠后端 42004）。
- **Wave3 后端 ✅（主体）**：WE 员工全套完成（176/176 绿含 18 新增，8 commits 至 b7e2136）。8 端点、50319-50322/42004/41110、permissions 存 user_roles JSON 文本列（白名单解码防脏数据放大权限）、D52 路由（WA/WE→/wa/inquiry）、41110 全角色禁用拒登（修掉"被禁 WE 以 TA 兜底登录"的洞）、TA 端码管理过滤 WE 码。坑：R17 草稿作废为空操作（phase-1 无 WE 草稿单据，钩子已留§21）；询价 reject 端点不存在，将来补须挂 INQUIRY_CONFIRM 切点。
- **审查修复批次未随 Wave3 落地（插单晚到）**→ 已唤回专做 F1(V13 pending_flag 唯一索引)/F4/F5/F7+SEC-S4-01；**WE 前端对账已并行派**（错误码/员工VO/登录路由 defaultRouterFor WE 项过期）。
- **契约对账+F2/F3/F6/F8 ✅**：onboarding-fe 两 commits（db5dd4a R13/R14 对账、7160184 审查修复），typecheck 绿。要点：WaWithdrawStatus 收敛四值+CANCELLED（RESTORED/ARCHIVED 移到商户主体状态）、PageRecords 类型新增、50203/50204 重定义、黑名单改单键提交（后端 DTO 决定，原双键同拉不存在了——如需双键需产品确认走两次提交）。裁量遗留：50310/50311 数值落在退驻段但语义属黑名单（注释已标，Wave5 错误码文档核对）。**3 处 el-table 断言未赶上（消息晚到），Wave5 合并 fe-types 时 Team Lead 手工补**。
- **类型雷根治 ✅**：fix/fe-table-types 2 commits（151d3e3）——dts 再生补 9 条声明（含 ElTable/ElTableColumn），7 处 DefaultRow 断言修复，vitest 5/5。写法结论：el-table 非泛型，插槽形参标注过不了 vue-tsc，只能调用点 `row as T` 断言（已有先例可循）。剩 3 处在 onboarding-fe 文件里→已转契约对账 Agent 顺手加，保证 fe-types 最后合并时零冲突。
- **W1 提前审查 ✅**：05-onboarding-review-w1.md（commit 2125072）——BLOCKER 3/MAJOR 5/MINOR 7。F1 先查后写并发穿透（缺唯一索引）；F2/F3/F6 前后端字段漂移（黑名单页/审批页当前不可用、联系人静默丢失）；F7 明文密码+手机号进日志；F8 前端 50203/50204 语义过期。**Team Lead 拍板：契约真源=10-onboarding-design.md（据实现）**。修复已分派：F1/F4/F5/F7→Wave3 后端顺带（pending_flag 唯一索引方案）；F2/F3/F6/F8→契约对账 Agent 扩围。教训入档：并行契约先行必须以据实现文档收口对账，早审查挽回了 Wave5 大返工。
- **Wave2 后端 ✅**：R13+R14 完成（158/158 绿含 16 新增，5 commits 至 93dfd3d）。9 端点含插单的 precheck/cancel/mine/listMine；错误码 50312-50318；副作用链实测（SKU下架+店铺隐藏+专属价失效含Redis+WA/WE token 全踢）；60 天口径=audited_at 起数据库时间，59/60/61 边界测试过；归档 job 每日 03:40（错开 04:17 重索引），SchedulingConfig 全项目首个调度基建（P3 复用）。DTO 定稿：无 restoreDeadline（前端用 auditedAt 自算）、status 增 CANCELLED。
- **Wave3 已派**（onboard 续跑）：WE 员工全套（V12、码白名单、授权位切点、R17、D52 路由）+ OPS 租户列表端点；**契约对账已派**（onboard-fe）：api-types 对齐 Wave2 最终 DTO。
- **P3 预研 ✅**：08-p3-requirements-extract.md（commit 7ecab65）。5 主题+G1-G10 缺口。三大未决风险：(1)72h 待确认库存可售性 vs 异议冲销（产品空白，需用户拍板，预研建议方案B：可售+冲销按剩余在库封顶）(2)P1 询价确认即扣库存 vs P3 完整出库状态机扣库存时点（P3 架构第一波必须收口）(3)两条仲裁链终点未定义（TA 仲裁详情缺+Q-D04 客诉实体未定）。另发现：单据号 PRD 与 P1 实现冲突（IN-/OUT- vs 已上线 WK-/CK-）、全仓无调度基建（72h Job/临期扫描无处跑，P3 需先建 scheduler；本期 Wave2 的 60 天归档 job 是第一个，注意复用）。
- **Team Lead 修正（P3 预研的过期信息）**：(1)Flyway P3 应从 V12 起（V11 已被 Wave2 占用）(2)"OPS 路由守卫不查角色"已在 feat/p2-onboarding-fe 修复，P3 以合并后代码为准。
- **X 硬化方案 ✅**：11-hardening-design.md（286行，commit ff94059）。重磅发现：现有 phone_hash 是无盐 SHA-256≈明文（GPU 分钟级还原）且是登录唯一键，方案用双列双读过渡；额外泄露面：pricing Redis 键与 log.info 明文手机号。实施顺序：日志 profile 化/Redis 密码/active-timeout（可并行）→ Boot 升级（冻结窗口，先于 PII 切读）→ PII 三段式（双写尽早铺）。
- **Team Lead 修正**：方案称 blacklist 未建可直接按 hmac 落地——过期信息，Wave1 已在 feat/p2-onboarding 用明文 phone/license 建了 V10。处置：不阻塞本期合并（无存量数据），PII 阶段 0 将 blacklist 双键纳入加列清单（ALTER 成本≈0）。
- **Wave4b 前端 ✅**：5 commits（api第二批/退驻页/下架弹窗/员工页/窄屏修复），typecheck 绿，24 截图自查（修 2 窄屏缺陷：WA顶栏折行、表格撑宽）。契约偏差 4 处：precheck+cancel 两端点已转 Wave2 补；下架弹窗老单据动态计数降级静态文案（Wave5 再议）；DTO 字段名待 Wave2 完成后对账 api-types。**注意：vite dev 会重生成 components.d.ts/auto-imports.d.ts，勿带进 commit（fe-types 分支专管）**。
- **Wave2 状态注意**：发现其曾静默停止（SendMessage 时 no active task），已带 precheck/cancel 契约唤醒续跑。
- **OPS 租户审核页 ✅**：feat/ops-tenant-audit 3 commits（页面+守卫+导航徽标，typecheck 绿，9 截图自查修掉 2 个 375 窄屏真缺陷含共用 shell 的 Blacklist）；移交：后端缺 GET /admin/tenants 列表端点（前端已契约先行+优雅降级）→ 已排进 Wave3；该分支还原了 dev server 重生成的 components.d.ts 保持基线（类型雷由 fe-types 分支专门处理，合并时 fe-types 最后进）。
- **CVE 扫描 ✅**：06-dependency-cve-scan.md（commit 6899a4e）——高危4组件/约9 CVE 全系 Boot 3.2.5 BOM 传递依赖（Boot 升 3.5.x 一次根治，含 Tomcat CVE-2025-24813 在野利用、security-crypto CVE-2025-22228 BCrypt>72字符误判）；前端运行时 0 已知高危；Redis 无密码已登记 X 项。上线前需 OWASP/osv-scanner 复扫。**Boot 升级列为 Wave5 后独立硬化任务，不混入本期功能分支。**
- **Wave1 后端 ✅**：入驻主链完成（feat/p2-onboarding 4 commits，142/142 测试绿含 15 新增）；3 高危点全落实（自营过黑名单/TenantLine 配置+测试/审批 CAS）；坑：Memurai 需在跑、ensureWaAccount 拆为 ensureWaUser+provisionWaAccount、blacklist.evidence_urls 遗留未建。
- **Wave4 前端 ✅**：3 页+守卫完成（feat/p2-onboarding-fe 3 commits，typecheck 绿，Playwright 14 截图视觉自查过）；移交：①components.d.ts 残缺生成物掩盖 10 处 el-table 类型错（波及既有页面，留 Wave5 统一修，需登记缺陷清单）②listMine 端点契约缺口已转 Wave2 补③375 窄屏顶栏换行为全站既有表现。
- **Wave2 已派发**：R13 退驻+R14 强制下架（同 worktree 续 feat/p2-onboarding），指令含高危点：副作用链③④段（专属价失效+WE 一起踢）、新拒老放分界、60 天边界 59/60/61 测试、V11 迁移、50312+ 错误码。
- **测试计划 ✅**：04-onboarding-test-plan.md（86用例 P0=61，commit 7ae46d5）；5大高危漏点识别（自营也过黑名单/R14新拒老放分界/退驻副作用③④段/TenantLine配置遗漏/60天时区边界）；Wave1 相关 3 条已转发后端 Agent 自查。
- **产品线框补齐 ✅**：06b-onboarding-wireframes.md（600行，commit 763412e）——6页线框+交互标注；关键决策：50205对WA端不透出黑名单字样、R14输商户名二次确认、WE生码默认最小授权。
- **余额中断插曲**：Wave1/Wave4 Agent 首次派发因 codecmd 余额 401 挂掉（worktree 干净无残留），充值后已唤醒续跑；新增保险：撞墙先 commit 再停。

- **启动**：P2 定价已交付（127 测试绿，commit 9945d82），按路线图开工 P2 另一半：入驻生态 + WE 员工。
- **核对**：确认架构全局设计 P0 已有（schema/api-spec/modules 均覆盖入驻），不重做架构设计，直接落地核对。
- **调查**：并行两 Agent 完成——PRD 提取（6 主题+6 设计缺口）+ 代码现状（Flyway V9→V10、tenant 审批先例可复用、员工码只差白名单、AccountServiceImpl:222 接入点、错误码 50201-50205 预留可用）。产出 findings.md。
- **规划**：task_plan.md 五波：Wave1 入驻主链（后端）∥ Wave4 前端第一批 → Wave2 R13/R14 → Wave3 WE → Wave4b 前端第二批 → Wave5 测试审查合并。决策 O-1~O-6 已记录。
- **下一步**：建 worktree（.claude/worktrees/onboard + onboard-fe），派发 Wave1/Wave4 并行 Agent。

## 踩坑 / 注意（沿袭）
- 后端本地跑必须 `dev,local` profile，否则 MySQL 认证失败。
- 这台机 `npx claude-mem restart/stop` 会产生端口僵尸，别碰。
- `.worktrees` 是文件占位，worktree 建在 `.claude/worktrees/`。

## 2026-08-21 · PII-S0 合并收口

- 提交 2 个悬挂改动：`CLAUDE.md`（Token 规则上移标注）+ `admin/components.d.ts`（unplugin 重新生成，补 13 个在用组件声明）→ `eb2ac0c`
- `--no-ff` 合并 `feat/pii-stage0`（5 commits）→ main `acad899`，无冲突
- 合并后全量回归：**419 tests / 0 failures / 0 errors / 0 skipped**，45 个测试类；`PiiDualWriteBackfillScenarioTest` 10 例全过 → 零回归
- push origin/main，本地与远端 0/0 同步
- 回收 3 个已并入 main 的 worktree：`pii-s0` / `p4-leftovers` / `p4-w1`（分支保留，仅移除工作区）；仓库现只剩主工作区
- 决策：遗留测试债 A1/A4/B2 从「S0 尾巴」提升为「**S1 准入门槛**」——S1 影子双查要改读路径，缺这三处切点断言就没有回归网兜底

## 2026-08-23 · PII-S1 启动：Step1 影子双查（波次 PII-W4）

- 开工前摸底摸出一处**计划与实现的落差**：15 §4 阶段0 原列 7 张表加列，**V27 实际只加了 `users.phone_hmac` + `blacklist.target_value_hmac`**。定价链（customer_prices）/ sms_codes / inquiry_requests 连 hmac 列都没有，双写回填自然也没有 → 这几处**进不了影子期**。故 W4 范围据实收敛为 A1–A6 + B1/B2 共 8 个读切点，缺口单列进 task_plan（Step 2 前须补一次「V30 加列 + 双写 + 回填 + 对账」，等于补做一段 S0，不得夹带进 W5）
- 新增 `PiiProperties.read-mode`（plain/shadow/hmac）与 `PiiShadowReader`：出结果的永远是旧列，只多用 hmac 列查一遍比对计数；方法返回 void——调用方**拿不到影子结果，就不可能误用它做判定**
- 三条红线写进类注释并落实：①零行为变化 ②影子异常一律吞在类内（故意在业务 `@Transactional` 方法内部 catch，异常不越出方法就不会把事务标脏）③告警只打切点/结论/行 id，不落 PII
- 观测：Micrometer `pii.shadow{pointcut,verdict}`（`ObjectProvider` 软依赖，无 MeterRegistry 也不炸）+ 进程内 `snapshot()` 供关卡测试差值断言
- 关卡测试 10 例：**检出力与零行为变化写在同一条用例里**——造 hmac 漏填 / 造 hmac 指向别行，既断言计数落在 MISSING/DIVERGED，又断言登录与黑名单命中的**结果分毫不变**
- **RED 已验证**：强制 `read-mode=plain` 复跑，10 例中 9 例转红（唯一不红的是 LICENSE_NO 负向断言，同 S0 先例）
- 全量 **434 绿**（46 类，424+10，0 失败/0 错误/0 跳过）。全量日志仅 4 条 mismatch 告警，逐条溯源均为两个 PII 关卡类自己造的数——**业务用例零不一致**
- 生产闸门未起算：`pii.shadow` mismatch 须连续 **≥7 天为 0** 才可进 Step 2；回滚为 `read-mode` 拨回 plain，秒级

### 踩坑
- 跑 `@SpringBootTest` 前先确认 6379 有人听——Memurai 服务注册已坏，直接跑 `C:\Program Files\Memurai\memurai.exe`
- `mvn -q` 会把 `Tests run` 汇总压掉；真实计数从 `target/surefire-reports/*.xml` 用 awk 聚合

## 2026-08-25 · 补做 S1 缺口：定价/短信/询价三链的 S0（V30）

W4 摸出来的那个缺口补完了。范围严格限定「加列 + 双写 + 回填 + 对账」，读路径一行没动。

- **V30 加列**：`customer_prices.rt_phone_hmac` / `sms_codes.phone_hmac` / `inquiry_requests.rt_phone_hmac`。口径逐条照抄 V27——全 NULLable、先建普通索引（唯一索引升级不在本迁移）、纯 additive 无回滚脚本。索引列序按「Step 2 切读后要走的查询」设计，与各表现有明文索引一一对应
- **双写切点 4 处**：`setCustomerPrice` / `settleFromInquiry` / `sendSmsCode` / `submitByRt`。唯一产生点仍是 `PiiCrypto.phoneHmac`，一律 `write-mode=dual` 才写。**上切点前先核了覆盖**：主代码里这三表的 `mapper.insert` 就这 4 处，无 XML/Wrapper 绕过
  - 两处 upsert 的**命中既有行**分支也补了 hmac（`.set(condition, ...)`）——存量行只有这一个机会性回填点，漏写就留一个盲索引空洞。口径抄 blacklist REMOVED 复活分支
  - `doBatchCustomerInTx` **不算切点**：它只改价/状态/过期，不写 rt_phone；按 rtPhone 圈选那部分是 C3 的**读**切点，归 Step 2
- **回填+对账扩到五表**：把「主键/明文列/hmac 列/行过滤」抽成 `HmacColumn` record，CAS 幂等 + keyset 游标 + legacy 拒填三件套共用一份实现，替掉本来要写 5 份的复制粘贴（5 份里抄漏一个 `isNull` CAS 条件，只在并发下才现形）。users/blacklist 的公开方法签名与 `ReconcileResult.table()` 取值不变；`reconcile()` 从 2 条变 5 条
- **关卡测试 +5 例**（`PiiDualWriteBackfillScenarioTest` 15→20）：切点一律真调——C1 走 `settleFromInquiry`（新建 + 命中既有行两条分支分开钉）、SMS 走真端点 `POST /api/v1/account/sms-code`、C2 走 `submitByRt`（tenant/store/wholesaler/sku/stock 脚手架沿用 `PricingSettleScenarioTest` 的 mapper-seed 风格）。**不用 mapper 造行代替切点**，否则断言的是造数不是双写
- 全量 **439 绿**（434+5，0 失败/0 错误/0 跳过，零回归）

### 踩坑 / 决策
- **对账基线必须拉平五表**：兄弟场景类（`PricingSettleScenarioTest` / `PricingRtMatchScenarioTest` / `OutboundChainScenarioTest`）直接 `mapper.insert` 造 customer_prices / inquiry_requests，绕过双写切点，hmac 天然 NULL。原来只 flatten users+blacklist，扩到五表后 `reconcile()` 的 allSatisfy(clean) 会直接红——改走 `flattenBackfillBaseline()`
- 三个新字段都补了 `@JsonIgnore`。V27 的 `users.phone_hmac` 有这条且有红线用例把着，新列漏加就会让实体直出的响应形状变化
- `sms_codes` 回填**刻意不按「未过期」缩小分母**——分母随时间滑动的话，「回填填全了」这句话就无法证明。生产首跑成本靠 `backfill-batch-size` 控
- `PiiShadowReader` **没**给这三表接影子切点，类注释已改成「V30 已补齐前置，影子切点随 Step 2 一起做」。不进 Step 1 的 7 天闸门分母

## 2026-08-27 · PII-W5 影子读切点：定价链 + 短信码（Step 2 前半段）

Step 2 拆成两半，本次只做前半段「接影子切点」，**切读本身（`read-mode=hmac`）一行没动**——先让这几条路也攒够观察数据，再谈切。

- **新增 5 个读切点**，口径逐条照抄 W4 那 8 个（返回 void、异常吞在 `probe()` 内、日志只打切点/结论/行 id、hmac 算不出记 SKIPPED 不入分母）：
  - `C1-price-set` / `C1-price-settle`：`setCustomerPrice` 与 `settleFromInquiry` 的 upsert 唯一键探测，两处同一口径（15 §1.2-C1 本就把它俩算作一个场景）
  - `C2-price-resolve`：`resolveCustomUnitPrice`。影子查询**必须同带 `status=ACTIVE`**，否则比的不是同一个问题。该切点在 Redis 缓存 miss 分支内，分母 = 真实 DB 读次数，不是请求数
  - `C3-price-batch`：`doBatchCustomerInTx` 按 rtPhone 圈选。**唯一的多行切点**，比的是行 id 集合——影子少捞=MISSING、多捞=EXTRA、两头都对不上=DIVERGED，与单行 `compare()` 语义逐条对齐。差集日志封顶 10 个 id
  - `SMS-verify`：`verifySmsCode`
- **inquiry_requests 没接，是核实后的结论不是遗漏**：主代码对该表的读全部按 id / tenant / wholesaler / status，**没有一处按 rt_phone 圈选**（15 §1.2-C6 只把它列为落库+透传的写触点，§4 Step 2 的切读清单同样只有 blacklist/sms/pricing）。没有明文读路径就没有「两列答案对不对得上」可比，硬造探针只会往分母里灌永远 MATCHED 的噪音。该列正确性由 `reconcile()` 兜底；将来真出现按手机号查询询价单的入口，接切点时一并补进 `PiiShadowReader`
- **闸门分组写进类注释**：W5 这 5 个切点**不进 Step 1 的 7 天分母**（那是登录/黑名单 8 切点的准入线），服务的是 Step 2 自己的「pricing 全量 + 黑名单用例 + E2E 45×2 全绿，观察 ≥3 天」
- **关卡测试 10→19 例**（仍在 `PiiShadowReadScenarioTest`，不另起类）：C1/C2/C3/SMS 各一对「一致记 MATCHED」+「造漏填记 MISSING 且主路结果分毫不变」，检出力与零行为变化同一条用例；另 1 例钉死 C3 显式 ids 分支不入分母。三个定价切点的零行为变化**分开断言**，因为爆炸半径不同：C1 走成 insert 会撞唯一键连累 confirmByWa 整单回滚、C2 回退公开价是资损、C3 少圈一行是漏调价
- **RED 已验证**：强制 `read-mode=plain` 复跑本类，19 例中 17 例转红；不红的两例正是 b2 LICENSE_NO 与 c3 显式 ids 这两条负向「不入分母」断言（同 W4 先例）
- 全量 **448 绿**（439+9，0 失败/0 错误/0 跳过，零回归）

### 踩坑 / 决策
- **全量日志 13 条 mismatch，其中 4 条不是本类造的，但也不是缺口**：`PricingSettleScenarioTest`（C1 ×1）与 `PricingRtMatchScenarioTest`（C2 ×3）直接 `customerPriceMapper.insert` 造价行，绕过双写切点，`rt_phone_hmac` 天生 NULL——**和 S0 波次把对账基线改走 `flattenBackfillBaseline()` 是同一个成因**。生产没有 mapper 造行这回事，闸门读的是 prod 的 Micrometer 计数，不受测试态影响，故不追改兄弟类，只在关卡类注释里写明来源，免得下一个人把它当回填缺口查
- **SMS 切点在测试态默认根本触发不到**：`cangchu.sms.mock=true` 下发出的就是 888888，而 888888 会在 `verifySmsCode` 首行短路，永远走不到 sms_codes 的 DB 读。做法是先经真端点发码（行仍由真双写切点写入），再把落库那行的 `code` 改成非万能码——读切点仍由真端点驱动，改的只是一个夹具字段
- **修掉一处会咬人的测试抖动**：本类 `PHONE_SEQ` 原起点固定，而 sms-code 的 60s 重发冷却键 `sms:cd:{phoneHash}:{scene}` 在 Redis 里**跨 JVM 存活**（H2 每次重建，Redis 不会）→ 60 秒内复跑本类必撞 41204 假红。改成按本次运行随机偏移，仍在 176 段内、留 1000 万号余量。兄弟类 `PiiDualWriteBackfillScenarioTest` 用 177 段且同样调 sms-code 端点，存在同样的潜在抖动，本波未动（不越界改他人用例），留待其自身波次处理
- `C3` 的显式 ids 分支选择**早返回不探测**（而非记 SKIPPED）：那条路主路压根没读 rt_phone 列，记 SKIPPED 等于承认"这里本该有个影子"，语义不对。口径抄 `checkBlacklistEntry` 对 LICENSE_NO 的处理

## 2026-08-29 · PII-W5 后半段：Step 2 切读（read-mode=hmac）

### 做了什么
范围严格按 15 §4 Step 2：blacklist(B1/B2) + sms 校验 + pricing(C1/C2/C3) + Redis 键 HMAC 化(C4)。**登录链 A1–A6 一行未动**（归 Step 3/W6）。

- **`PiiReadRouter`（新）**：切读开关本体。明文查询由调用方以 `legacyRead` Supplier 传入——回滚分支就是原来那条查询，一个字没改，拨回即秒级恢复。异常**不吞**（与 `PiiShadowReader` 相反）：切读后没有第二个答案可用，吞掉等于凭空编一个"未命中"
- **`PiiHmacQueries`（新）**：hmac 查询的唯一构造入口。影子期比对用的谓词与切读后出结果用的谓词**必须逐字节是同一条**，否则「7 天 / 3 天 mismatch=0」证明的是 A 查询、上线跑的是 B 查询，闸门就是自欺。`PiiShadowReader` 只在其上追加 `.select(id)`
- **`PiiModule`（新）+ `read-modes` 映射**：灰度粒度按模块（blacklist / sms / pricing / redis-key），未登记或空值回落全局 `read-mode`
- **C4**：`price:match:*` 原键里**直接带明文手机号**（本次堵掉）、`sms:cd:* / sms:daily:*`、`login:fail:*` 三处派生物统一走 `redisKeyPart`
- **关卡测试 `PiiHmacReadScenarioTest` 22 例**（新类；影子类 19 例一条没删，仍在 shadow 口径下跑）
- 全量 **470 绿**（448+22，零回归）

### 踩坑 / 决策
- **Step 2 硬切，不做旧列兜底**——这是本波最该被质疑、也最该写清楚的一条。`PiiProperties` 原注释写的是「主读 hmac + 旧列兜底回退」，本次改掉了：回填有没有填全，是切读**之前**由影子闸门证明的事；用运行时兜底去掩盖，等于把「回填有洞」这个事实永久藏起来，闸门也就再没有归零的一天。Step 3 登录链另做双读兜底自愈，是因为登录切错的代价是全员登不上，权衡不同
- **切读后 MISSING 的语义要重新钉，而不是把影子期的用例删掉**：影子期「造漏填 → 记 MISSING 且主路结果分毫不变」那批一条没删；新类钉的是同一份数据在切读后的**另一半语义**——B1 放行该拦的人 / B2 复活语义丢失退化成 uk 兜底 50310 / SMS 41202 / C1 撞唯一键连累 confirmByWa 整单回滚 / C2 回退公开价 9.90 / C3 真的少圈一行。代价逐条写进断言消息，那道 3 天闸门才不是走过场
- **灰度粒度必须是模块，不是一个全局开关**：四块爆炸半径完全不同，一刀切意味着任一块翻车就得把已观察合格的其余三块一起赔进去
- **主配得显式登记四行空占位符**（`${PII_READ_MODE_*:}`）：`redis-key` 带连字符，不登记就没法用标准环境变量注入，「按模块灰度」会退化成纸面能力。随之要让启动校验放行空值（空 = 未登记，不是配置错误），同时对**模块名/模式值笔误拒绝启动**——写错会静默回落全局模式，想切的没切、想拨回的没拨回，且毫无征兆
- **`PiiShadowReader.checkUser` 故意不设模块**，继续吃全局 `read-mode`：那正是 Step 1 七天闸门组的口径，给登录留个模块名反而会让人误以为拨一下就能切。其余五个方法改分模块闸门——模块一旦切读，其影子探针停摆是**对的**，已经没有「旧列的答案」可比，再计数就是拿 hmac 跟自己比
- **B1/B2 因此会退出 Step 1 的 7 天分母**：切读发生在闸门达标**之后**，不影响准入判定，但值得记一笔免得下次看指标掉零去查故障
- **RED 用两个互补变异而非单向变异**：只做「切读没发生」这一个变异的话，6 条「hmac 命中 == 旧列命中」用例不会红——它们断言的正是两种模式结论相同，本就区分不了。补上「切读发生但 hmac 查不到」的变异后，21 例正式用例全部被至少一个变异杀死；两轮都不红的 4 例全是负向断言（默认值、模块隔离、LICENSE_NO、显式 ids），同 W4/W5 先例
- **`markRemoved` 造 REMOVED 行来放大 B2 的可观察差异**：直接对 ACTIVE 行测，明文与切读两条路的错误码都是 50310，差异被唯一键兜底掩盖看不出来；改成 REMOVED 行后，明文口径走**复活**、切读漏填走 insert 撞 uk，行为差异肉眼可辨
- **默认值刻意没动**（全局仍 shadow、四个占位符全空），本波交付的是「代码就绪 + 开关可拨」，不是「已经切了」
