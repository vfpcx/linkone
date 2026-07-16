# P2 入驻生态 · Wave1 提前代码审查问题清单（05-onboarding-review-w1）v1

> 编写：测试&审查 Agent · 2026-07-16
> 审查对象：
> - 后端 `feat/p2-onboarding` 截至 **c2880e5**（6a8198d 迁移/实体 → 986c4db 服务端点 → dd63d51 测试 → c2880e5 文档），`git diff main...c2880e5 -- backend/`
> - 前端 `feat/p2-onboarding-fe` 截至 **ed32255**（24b1880 api-types/API 层 → 94b931e 三页面 → ed32255 路由+守卫），`git diff main...ed32255 -- frontend/`
> 基线：`../architecture/05-secure-coding-guardrails.md`（G-x/§10/§12）· `04-onboarding-test-plan.md`（用例 ID）· `../architecture/05-error-codes.md`（c2880e5 已更新版）· `../architecture/10-onboarding-design.md`
> 定位：**提前审查**（不等 Wave5），供 Wave2/3（后端）与 Wave4b（前端）在后续波次修复。BLOCKER 项按 guardrails §0.4 阻塞合并。

---

## 0. 结论摘要

| 严重级 | 数量 | 编号 |
|---|---|---|
| BLOCKER | 3 | F1（后端并发红线）、F2/F3（前后端契约断裂，联调必挂） |
| MAJOR | 5 | F4–F8 |
| MINOR | 7 | F9–F15 |

总体评价:后端 Wave1 的**审批 CAS、租户隔离双保险、黑名单三路径拦截、注册接入事务回滚**四个高危点实现质量良好且有测试佐证；主要缺口是「一账号仅一 PENDING 申请」仍是**先查后写**（§10 红线）。前端三页面代码风格与既有一致、错误码分支处理到位，但因与后端**并行开发且各自演绎契约**，字段名/分页结构存在系统性不匹配——OPS 黑名单页与 TA 审批页在当前后端下**完全不可用**，必须在 Wave4b/Wave5 前对齐。

---

## 1. 问题清单

### F1 「一账号仅一 PENDING 申请」用先查后写控并发，违反 §10 红线 — **BLOCKER**

- **位置**：`backend/src/main/java/com/cangchu/tenant/service/impl/WholesalerApplicationServiceImpl.java:305-310`（`doCreatePending`）；`backend/src/main/resources/db/migration/V10__init_onboarding.sql:25`（`idx_wsapp_applicant` 为普通索引）
- **问题**：50201 的重复申请防护是 `selectCount(status='PENDING')` 后再 `insert`，无任何 DB 唯一约束或锁兜底。同一 WA 并发提交（双击/脚本）时两个事务都数到 0，各自落一条 PENDING——CON-S7-01（P0）必穿。guardrails §10 明文：「红线：禁止用先 SELECT 判断、再 INSERT 保证唯一性……发现『先查后写』实现直接登记缺陷」。同理 `doCreatePending:300` 的 ALREADY_ONBOARDED（50204）检查也是先查后写（危害较低，与 F4 合并处理）。
- **建议修法**（二选一，按 §10）：
  1. **DB 唯一索引方案**：加可空列 `pending_flag TINYINT NULL`（PENDING 时=1，审批后置 NULL），建 `UNIQUE KEY uk_wsapp_applicant_pending(applicant_user_id, pending_flag)`；insert 捕获 `DuplicateKeyException` → 50201（P5 模式）。审批 CAS 翻转时同事务置 NULL。
  2. **Redisson 锁方案**：`lock:wsapp:apply:{userId}` 先锁后事务 + self 代理（P1 模式），锁内查+插。
  方案 1 更符合 P5「不靠应用层先查」精神，推荐。
- **对应用例**：CON-S7-01（P0）、ONB-S6-01

### F2 OPS 黑名单页与后端契约全面不匹配，页面功能不可用 — **BLOCKER**

- **位置**：`frontend/packages/api-types/src/ops.ts:36-46`（`CreateBlacklistRequest{phone?,license?,reason}`）、`frontend/apps/admin/src/views/ops/Blacklist.vue:93-95`（`data?.list`）与 `:194-198`（提交 payload）、`frontend/apps/admin/src/api/ops.ts:24-25`；后端实际契约 `BlacklistAddDto{targetType,targetValue,reason}`、`GET /ops/blacklist` 返回 `List<Blacklist>`（非分页，query 参数是 `status` 而非 `page/size`）
- **问题**：前端按 task_plan 早期契约开发，后端 Wave1 落地为 `targetType/targetValue` 单键单条模型：
  1. **加黑**：提交 `{phone,license,reason}` → 后端 `targetType` @NotBlank 校验 40001，**永远失败**；
  2. **列表**：后端返回裸数组，页面取 `data?.list`/`data?.total` 均为 undefined → **列表恒空、分页恒 0**；
  3. 前端允许一次同时填手机号+执照号，后端一次只收一键一条。
  （表格渲染字段 `row.targetType/targetValue` 反而与后端实体一致，说明只是 API 层没对齐。）
- **建议修法**：以 `10-onboarding-design.md` §2（据实现编写）为真源改前端：`CreateBlacklistRequest` 改为 `{targetType:'PHONE'|'LICENSE_NO', targetValue, reason}`；双键同填时循环发两次或 UI 限制单选一键；列表改为 `request<BlacklistItem[]>`，`status` 过滤参数，分页前端本地做或推动后端 Wave2 补分页（需契约拍板，G-9.1）。
- **对应用例**：BLK-S1-01（P0）、ONB-E2E-02（P0）

### F3 TA 审批页字段名/分页结构与后端 VO 全面不匹配，列表恒空 — **BLOCKER**

- **位置**：`frontend/apps/admin/src/views/ta/WholesalerApplications.vue:140`（`data?.list`）、`:218-220/:259`（`row.applicationId`）、`:358/:371/:376/:395`（`wholesalerName/licenseNo/appliedAt/remark`）；`frontend/packages/api-types/src/tenant.ts` `WholesalerApplication` 接口；后端实际返回 `{records,total,page,size}` + `WholesalerApplicationVo{id,name,license,createdAt,auditRemark,...}`
- **问题**：分页容器后端用 `records`，前端 `PageData.list` → 列表**恒为空**；即便修好容器，行字段 `id/name/license/createdAt/auditRemark` 与前端 `applicationId/wholesalerName/licenseNo/appliedAt/remark` 五处全不对齐 → 全列渲染 `—`，审批调用传 `String(undefined)`。ONB-E2E-01 第③步必挂。
- **建议修法**：按后端 VO 改 `WholesalerApplication` 类型与页面取值（`id/name/license/createdAt/auditRemark/source/authBasis`），列表解析改 `records`；或由 Team Lead 拍板后端改字段迁就 PageData 通用契约——**两侧必须先在契约文档定稿再改码**（G-9.1，本轮 `roleList→roles` 教训重演）。同时 `listWaApplications` 的 `PageData<T>` 泛型签名与实际不符，改为专用响应类型。
- **对应用例**：ONB-S1-03（P0）、ONB-E2E-01（P0）、V-02

### F4 跨路径重复入驻无兜底：审批通过不复查已入驻，OPS 代建可与 PENDING 申请并存 — **MAJOR**

- **位置**：`WholesalerApplicationServiceImpl.java:171-200`（audit APPROVED 分支未查 `listActiveWholesalerIds`）、`:239`（createByOps 的检查为先查后写，且不处理该用户存量 PENDING 申请）
- **问题**：「一个 WA 只入驻一个仓库」仅在**申请时**校验。时序：WA 向 A 仓申请（PENDING）→ OPS 用同手机号代建入驻 B 仓（成功，代建不查 PENDING）→ A 仓 TA 审批通过 → 该用户获得**两个 ACTIVE WA 绑定**，违反 01 §3.5 业务规则。两个 TA 并发审批同一申请人不同申请（驳回重申+窗口）同理。
- **建议修法**：audit APPROVED 分支在 CAS 成功后、建主体前复查 `listActiveWholesalerIds(applicantUserId,"WA")`，非空则抛 50204（事务回滚，申请留 PENDING 由 TA 驳回）；createByOps 成功后将该用户存量 PENDING 申请置 REJECTED（remark=已由 OPS 代建）或建单前拒绝。根治需 user_roles 层唯一约束，可与 F1 一并设计。
- **对应用例**：ONB-S6-02（P0）、CON-S7 系列

### F5 入驻申请不校验目标租户状态，PENDING/REJECTED 租户可被申请并审批 — **MAJOR**

- **位置**：`WholesalerApplicationServiceImpl.java:333-337`（`requireTenantExists` 只查存在性）；三个入口 `:68/:94/:230` 均只调它
- **问题**：租户有 PENDING（注册壳/待 OPS 审）状态（`TenantServiceImpl:106`）。WA 可向未过审的仓库提交入驻申请，该仓 TA（若已绑定角色）甚至可审批通过、建 ACTIVE wholesaler——业务上「仓库还没开张就入驻了商户」。测试计划 ONB-S1-01 前置明确要求「目标租户 ACTIVE」。
- **建议修法**：`requireTenantExists` 升级为 `requireTenantActive`：`!"ACTIVE".equals(tenant.getStatus())` 时抛 TENANT_NOT_FOUND 同款提示（不泄漏租户审核状态）或专用语义码（50310 段），三路径共用。
- **对应用例**：ONB-S1-01 前置、ONB-S2 补充用例（将登记入 04 计划 v1.1）

### F6 WA 申请页提交字段 contact/phone 与后端 contactName/contactPhone 不一致，联系人信息静默丢失 — **MAJOR**

- **位置**：`frontend/packages/api-types/src/tenant.ts`（`SubmitWaApplicationRequest.contact/phone`）、`frontend/apps/admin/src/views/wa/Apply.vue:252-256`；后端 `WholesalerApplyDto.contactName/contactPhone`
- **问题**：Jackson 默认忽略未知字段——提交**不报错**，但 `contactName/contactPhone` 落库为 null（联系电话回退账号手机号）。用户填的联系人/联系电话**静默丢失**，TA 审批列表联系人列恒空；且表单联系电话作为黑名单键之一（selfApply 会检 contactPhone）实际未参与命中检查。比 400 更隐蔽。
- **建议修法**：`SubmitWaApplicationRequest` 字段改 `contactName/contactPhone`，Apply.vue payload 同步；api-types 注释中的契约段落以 `10-onboarding-design.md` §2 为准重写。
- **对应用例**：ONB-S1-01（数据断言）、BLK-S1-02 变体

### F7 明文临时密码 + 手机号写 INFO 日志（G-8.2） — **MAJOR**

- **位置**：`backend/src/main/java/com/cangchu/tenant/service/impl/WholesalerServiceImpl.java:194`（`log.info("[A1][WA开通] 新建 WA 用户 phone={} 临时密码={}", phone, tempPwd)`）
- **问题**：原 `ensureWaAccount` 私有实现的遗留，本次重构为 `ensureWaUser` 公开复用出口时**原样保留并扩大了调用面**（OPS 代建路径也走它）。明文凭据入日志=凭据泄露面（日志采集/聚合链路全暴露），叠加完整手机号违反 G-8.2「手机号/参数不入日志」。
- **建议修法**：删除 tempPwd 日志（或仅 DEBUG + 打码）；手机号打码（`138****1234`）。短信下发落地前若运营需要临时密码，走带权限的一次性查询接口而非日志。既有缺陷但本波扩面，建议 Wave2 顺手修并全仓 grep 同类（03 §3.6 根治原则）。
- **对应用例**：无直接用例，登记 `03-defect-findings.md`

### F8 前端 error-codes 包 50203/50204 语义仍是旧契约，50310/50311 缺失 — **MAJOR**

- **位置**：`frontend/packages/error-codes/src/codes.ts:104-108`（`STATE_WA_HAS_UNPAID=50203`、`STATE_WA_HAS_STOCK=50204`）
- **问题**：c2880e5 已按 Team Lead 拍板把 50203 重定义为「申请不存在/不可审核」、50204 为「已入驻」（R13 退驻前置改用 50312+），并更新 `05-error-codes.md`。前端枚举与文案仍是旧语义——TA 并发审批被抢占（50203）时用户会看到「退驻前需结清账单」类完全跑偏的 toast；50310/50311（黑名单管理）在包中不存在。**04-onboarding-test-plan.md §0 错误码基线同样按旧语义编写，由本 Agent 在 v1.1 回填修正。**
- **建议修法**：Wave4b 同步 error-codes 包：50203→`WHOLESALER_APPLICATION_NOT_AUDITABLE`、50204→`WHOLESALER_ALREADY_ONBOARDED`，补 50310/50311 及 messages-zh 文案；Apply.vue:284 的 50204 手写文案「您已入驻该仓库」顺带校正为「一个账号仅可入驻一个仓库」语义。
- **对应用例**：ONB-E2E-01「全链无 90001/文案正确」、V-02

### F9 黑名单键无归一化规则（大小写/全半角/空格），仅 trim — **MINOR**

- **位置**：`BlacklistServiceImpl.java` `add()`（`value=trim()`）与 `isBlacklisted()`（查询侧 trim）
- **问题**：执照号大小写混写（`91330100ma` vs `91330100MA`）或全角字符即绕过精确匹配。存/查两侧目前对称（都只 trim），满足计划「至少精确键必拦」的下限，但「变体行为有明确定义并测两侧」未做。
- **建议修法**：定义归一化函数（trim + 执照号 toUpperCase + 全角转半角），add 与 isBlacklisted 共用；契约写入 10-onboarding-design.md。
- **对应用例**：SEC-S4-07（P1）、BND-S3-03（P1）

### F10 WholesalerApplyDto.contactPhone 无格式校验 — **MINOR**

- **位置**：`backend/.../tenant/dto/WholesalerApplyDto.java:24`（仅 `@Size(max=20)`）
- **问题**：任意 ≤20 字符串可作联系电话入库并参与黑名单键比对（G-3.1 要求格式/范围校验）。对比 `OpsWholesalerCreateDto.waPhone` 有 `@Pattern`。危害有限（账号手机号始终另行必检）。
- **建议修法**：加 `@Pattern(regexp="^1[3-9]\\d{9}$")`（允空），与前端 Apply.vue 校验规则三处对齐（G-9.2）。
- **对应用例**：ONB-S2-03（P1）

### F11 OpsBlacklistController 直接返回实体 Blacklist，未走 VO — **MINOR**

- **位置**：`backend/.../tenant/controller/OpsBlacklistController.java:28,36`
- **问题**：仓内惯例是实体不出 Controller（Wholesaler→WholesalerVo、申请→WholesalerApplicationVo）。Blacklist 实体直出虽当前无敏感外字段（OPS 专属可见），但后续加列即默认泄出，且 `deletedAt` 等内部列随序列化暴露。
- **建议修法**：补 BlacklistVo（id/targetType/targetValue/reason/status/operatorUserId/createdAt/removedAt）。
- **对应用例**：规范项，无用例

### F12 注册直申兜底名称「商户-尾4」易撞租户内唯一索引，申请卡死在名称冲突 — **MINOR**

- **位置**：`WholesalerApplicationServiceImpl.java:100-102`（name 兜底）+ `:184-189`（approve 撞 `uk_wholesaler_tenant_id_name` → 50231 回滚）
- **问题**:手机号尾 4 位重复概率不低；申请单无改名入口，TA 每次 approve 都 50231，只能驳回让用户换名重申，体验差且 TA 无从知道根因（50231 文案指向「名称重复」但 TA 不能替申请人改）。
- **建议修法**：兜底名称加随机后缀或用完整手机号打码形式；或 approve 撞唯一约束时错误文案明示「商户名与本仓已有商户重复，请驳回并告知申请人更换名称」。
- **对应用例**：ONB-S1-02 变体

### F13 测试缺口与断言质量（dd63d51） — **MINOR**

- **位置**：`backend/src/test/java/com/cangchu/tenant/OnboardingScenarioTest.java`
- **问题**：13 个测试整体质量良好（错误码断言具体、含并发 CAS/跨租户/伪造头），但对照 04 计划仍缺 P0 项：
  1. **SEC-S4-01**：无 token 裸调 6 组新端点 41001 全扫——未写；
  2. **CON-S7-01**：并发重复提交申请——未写（会直接暴露 F1）；
  3. **ONB-S2-03**：缺必填/名称超长 40001——未写；
  4. **BLK-S1-03**：执照键拦**自助申请**路径（现只测了执照键拦代建、手机键拦自助，双键×三路径矩阵不全）；
  5. audit `action` 传枚举外值（如 `FOO`）→ 40001——未写；
  6. `s5_crossTenantIsolation:` WA 调审批仅断言 `isNotEqualTo(0)`，应断言具体 42xxx（G-1.3「各断言被拒(41001/42xxx)」）。
- **建议修法**：Wave2 补齐上述 6 项（CON-S7-01 待 F1 修复后作为回归锚点）。
- **对应用例**：如上编号

### F14 TA 侧边栏「入驻审批」角标误用入库待审数 — **MINOR**

- **位置**：`frontend/apps/admin/src/views/ta/Dashboard.vue:115-120`（`badge: dashboard.value.kpi.pendingInbound`）
- **问题**：入驻审批菜单的红点数用的是**入库单待审数**，与页面内 `pendingTotal`（真实 PENDING 申请数，WholesalerApplications.vue:156-157 已正确单独拉取）不一致，误导 TA。
- **建议修法**：Dashboard 单独拉 `listWaApplications({status:'PENDING',page:1,size:1})` 取 total，或后端 dashboard KPI 补 `pendingWaApplications` 字段（需契约同步）。
- **对应用例**：V-02 视觉验收附带检查

### F15 waApplicationApi.listMine 调用后端不存在的端点（已做降级） — **MINOR**

- **位置**：`frontend/apps/admin/src/api/wholesaler.ts:48-57`（`GET /wholesaler/applications`）；后端该路径仅有 POST
- **问题**：前端已在注释与 Apply.vue 声明「契约微调位 + localStorage 降级」，行为可控。但降级后果是：换设备/清缓存的 WA **看不到自己的申请状态与驳回理由**（驳回理由只有 TA 列表可见），ONB-E2E-01 第②步「登录提示审核中」依赖本地缓存不可靠。
- **建议修法**：Wave2 后端补 `GET /api/v1/wholesaler/applications`（返回本人申请列表，含 auditRemark），契约先入 10-onboarding-design.md；前端删除 localStorage 主路径、降级仅兜底。
- **对应用例**：ONB-S1-04（驳回理由可查）、ONB-E2E-01

---

## 2. 各维度核查结论（含无发现项）

| 维度 | 结论 |
|---|---|
| **鉴权遗漏** | 已核，无发现。`/api/v1/wholesaler/**`、`/api/v1/ops/**` 已入 SaInterceptor include（SaTokenConfig，G-1.1/G-1.2），exclude 未误放行新前缀；与既有 `/api/v1/rt`（RT 公开）无前缀冲突；6 个新端点服务层均显式 `hasRole`（TA 带租户维度、OPS 平台维度）。缺口仅测试侧 41001 全扫未写（见 F13-1）。 |
| **租户隔离** | 已核，无发现。`wholesaler_applications` 已入 `TENANT_FILTER_TABLES`；TA 列表/审批 TenantLine 兜底 + 显式 `eq(tenant_id)` 双保险；跨租户审批统一 50203「视同不存在」不泄漏存在性；`blacklist` 按 O-6 排除白名单（平台级）；WA 申请人无租户上下文时 TenantLine 正确不注入（MybatisPlusConfig.ignoreTable 已核）；伪造 X-Tenant-Id 有 42101 测试。 |
| **黑名单三路径拦截** | 已核，路径完整。自助申请（含账号手机号+表单电话+执照三键）、OPS 代建（O-2）、TA 自营（WholesalerServiceImpl.create 同检）、注册接入四条路径均调 `isBlacklisted`，且各有测试。遗留：键归一化 F9、双键×路径测试矩阵不全 F13-4。 |
| **注册接入事务回滚** | 已核，无发现。`AccountServiceImpl.register` 确认 `@Transactional`（:161），黑名单命中抛 50205 使注册整体回滚，无半截账号；有 BLK-04 测试佐证。 |
| **信息泄露** | 基本无发现。跨租户 50203 不区分不存在/不可见；50205 不回显命中的是哪个键；黑名单值仅 OPS 可见。例外：临时密码/手机号入日志 F7、实体直出 F11。 |
| **审批 CAS 原子性** | 已核，真正原子。状态翻转为条件 UPDATE（`WHERE id AND tenant_id AND status='PENDING'`）校验 affected，符合 §10 P2；approve 副作用（建主体/绑角色/回填）与 CAS 同事务，失败整体回滚；撞 `uk_wholesaler_tenant_id_name` 转 50231（P5）；有 CON-01 并发测试。遗留：approve 缺已入驻复查 F4。 |
| **状态机完整性** | 已核，无发现。仅 PENDING 可审、action 白名单校验、驳回 remark 必填先于翻转、REJECTED 后可重申，与 TenantServiceImpl.audit 先例一致。 |
| **幂等** | 重复审批（50203）、重复加黑（先查+唯一索引+DuplicateKey 双层，P5 合规）、WA 账号开通（ensureWholesalerRole 幂等复用）均达标；**重复提交申请不达标 → F1**。 |
| **V10 迁移与实体一致性** | 已核，无发现。两新表+补列与实体逐字段对齐；索引内联 KEY、表前缀命名、无 `CREATE INDEX IF NOT EXISTS`（§11 合规）；软删/填充列齐全。 |
| **错误码与文档一致** | 后端与 c2880e5 更新后的 05-error-codes.md 一致（50201-50205 + 50310/50311）；**前端 error-codes 包与 04 测试计划仍是旧语义 → F8**（测试计划 v1.1 由本 Agent 回填）。 |
| **代码风格** | 已核，无发现。DTO 校验注解、雪花 ID ToStringSerializer、Service 注释安全自检块、日志格式均与既有代码一致。 |
| **前端 API 错误处理/表单校验/权限守卫** | 错误处理：http.ts 全局分类 toast + 页面级 50201/50204/50205 分支，达标；表单校验：Apply.vue/Blacklist.vue 规则完整（手机号正则、必填、长度、双键至少一键联动校验），无绕过点（后端有兜底）；OPS 路由守卫：`meta.role==='OPS'` + roles 校验已补（findings §四闭环，SEC-S4-04 前端侧达标），非 OPS 弹回主路由实现正确。**契约不匹配另列 F2/F3/F6**。 |
| **前端类型安全** | api-types 全量类型化、SnowflakeId 统一 string、safeJsonParse 防精度丢失，达标；但类型与后端实际不符时类型安全反成假保障（F2/F3/F6 根因）——**建议 Wave5 起以 10-onboarding-design.md 为唯一契约真源生成/校对 api-types**（G-9.1）。 |

---

## 3. 修复归属建议

| 编号 | 建议归属 |
|---|---|
| F1、F4、F5、F7、F10、F11、F12、F13、F15(后端侧) | 后端 Wave2 Agent（feat/p2-onboarding 追加） |
| F2、F3、F6、F8、F14、F15(前端侧) | 前端 Wave4b Agent（feat/p2-onboarding-fe 追加） |
| F9 | 后端 Wave2（归一化函数）+ 契约文档 |
| 契约真源对齐（F2/F3/F6/F8 根因） | Team Lead 拍板：以 10-onboarding-design.md §2 为准，前端全量对齐 |

## 4. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-16 | 首版：Wave1 后端(至 c2880e5)+前端(至 ed32255)提前审查，3 BLOCKER / 5 MAJOR / 7 MINOR |
