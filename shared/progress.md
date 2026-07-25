# Progress Log · P3 完整单据与履约异常

> 最新在上。关联 `task_plan.md` / `findings.md`。P2 定价/入驻计划已归档 `shared/archive/`。

## 2026-07-25
- **FE-W1 入库链前端 ✅（feat/p3-inbound-fe，4 commits 31f5f6f→f5ec4db，待 Team Lead 复验合并）**：
  - 交付：WA `/wa/inbound` 入库确认页（待确认/全部页签、72h 秒级倒计时 deadline 升序、来源映射 仓库代建/我方提交、autoAccepted 标记、确认二次弹窗、异议弹窗〔预设四选+补充说明合成 reason≤512+附件≤5〕、冲销结果回显 登记/已冲销/差额/YY-单号）；TA `/ta/approvals` 审批中心（待仲裁角标=PENDING total、⏰超72h提醒、decide 弹窗按 09 §4.1：通过·恢复流水/驳回·保留冲销、差额>0 驳回时定责四选必填、备注必填、已裁决只读详情）；NotificationBell（unread-count 60s 轮询+抽屉+标记已读）；AttachmentUpload（≤5MB jpg/png/webp 预检）；api-types/error-codes 50330-50342/四组 API 封装；TA 各页菜单接通「审批中心」、WA 四页菜单增「入库确认」；用户可见文案零角色码（liability 中文四选）。
  - 闸门：typecheck 绿；Playwright `inbound-dispute.spec.ts` 3/3 绿（INB-01 确认链/INB-02 异议链含真实附件上传/INB-03 TA decide + TA 侧铃铛角标-条目-已读全链断言）；截图 6 张逐张目检无对齐/溢出/错位（动画入镜的 3 张已加静置重拍）。
  - **契约偏差 →BE 待修**：① `registerByWk`/72h Job 的「通知归属 WA」发给 `wholesalers.owner_user_id`，SELF_OPERATED 商户该列= TA 操作人，绑定 WA 账号收不到通知（listForWa 用 user_roles 推导无此问题；E2E 改在 TA 侧断言铃铛全链）。② `/files/**` GET 静态映射在启动时 `Path.toUri()`，若 upload-dir 尚不存在则 URI 缺尾斜杠 → 上传成功但 GET 500，重启后自愈（建议 addResourceHandlers 先 createDirectories 或手工拼尾斜杠）。③ PRD 09 §6.2 要求异议弹窗展示实时在库 M/差额 N−M，后端无异议前在库查询端点，已降级为口径文案+提交后回显（如需严格达标需 BE 补端点）。
  - 环境插曲：8080 曾跑 BE-W1 合并前旧实例（新端点 404→90001、V15-V17 未迁移），Team Lead 重启后解决；又因 ② 再重启一次使 /files GET 生效。axios 实例默认 application/json 覆盖 FormData 检测的坑已修（file.ts 摘除 Content-Type）。

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
