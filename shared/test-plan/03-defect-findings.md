# 仓储云 · 缺陷与加固清单 v1（三方交叉印证）

> 汇总：Team Lead · 2026-06-28
> 来源：代码审查 + 安全审查 + 契约同步 + 24 条场景接口测试（AccountScenarioTest 13 / TenantScenarioTest 11）
> 场景测试实跑结果：**24 用例 → 14 PASS / 7 FAIL(真实缺陷) / 3 ERROR(测试 harness 待修)**

---

## 🟢 修复进展（2026-06-28 批次一+二，已验证）

并行派 3 个 Agent 修复 + Team Lead 集成验证：**后端 42 测试全绿（AccountControllerTest 10 / AccountScenarioTest 13 / TenantControllerTest 8 / TenantScenarioTest 11），无回归；前端 typecheck + Playwright 8 条全绿。**

| 缺陷 | 状态 | 验证 |
|---|---|---|
| D-01 鉴权 path | ✅ 已修 | 拦截器覆盖 `/account/**`，exclude 用真实公开路径 |
| D-02 租户隔离+OPS鉴权 | ✅ 已修 | TN-S4-01 转 PASS；加 `TenantLineInnerInterceptor`(白名单 stores/tenant_settings)+`requireOpsRole` |
| D-03 mock 验证码隔离 | ✅ 已修 | 改为配置驱动 + **prod 强制禁用**（修正了"仅 dev"导致 test 全挂的集成回归）|
| D-04 登录锁定限流 | ✅ 已修 | Redisson 计数+TTL，手机号 hash 做 key，真实剩余次数 |
| D-05 状态机 | ✅ 已修 | TN-S5-01/02/03 转 PASS |
| D-06 唯一性 | ✅ 已修 | TN-S6-01 转 PASS（应用层 applicant+name 查重）|
| D-07 角色枚举 | ✅ 已修 | AC-S2-05 转 PASS（40001）|
| D-08 范围校验 | ✅ 已修 | TN-S2-02 转 PASS（lng/lat @DecimalMin/Max）|
| D-10 防账号枚举 | ✅ 已修 | 密码登录路径统一"账号或密码错误"|
| D-12 前端契约对齐 | ✅ 已修 | api-types 9 处对齐 + Playwright 全绿 |
| D-15 yml/注释清理 | ✅ 已修 | REDIS_PASSWORD 对称 + 删 lettuce.pool + roleList 注释 |
| T-01 测试 harness streaming | ✅ 已修 | 加 httpclient5(test) 换工厂，AC-S4/S6 转 PASS |
| **D-16（新）** | 🟠 待修 | 见下 |
| D-11 密码规则统一 | ✅ 已修 | 三处统一 6–20（前端 3 处 zod + 前端/架构错误码文案）；详见 api-contract-account.md §7.3 + v1.1 |
| D-09/D-13/D-14 | ⏳ 待办 | 批次三/四（竞态/时区/生产硬化）|

> **集成教训**：D-03 首版用"active profile==dev"判 mock，导致 `@ActiveProfiles("test")` 的整套测试 41202 全挂（级联到 register→login）。改为**配置开关驱动 + prod 强制禁用**后恢复。已写入 G-7.1 的修订意图。

### 🟠 D-16 注册业务字段丢失（Agent C 发现）
后端 `RegisterDto` 不接收 `realName/tenantName/wholesalerName/targetTenantId/营业资质`，前端入驻表单这些字段被 Jackson 静默忽略——**TA/WA 注册建仓信息实际未落库**（目前 realName 临时塞 nickname）。需后端补 DTO 字段 + 落库逻辑。归属：后端。场景：S1/数据完整性。

---

## 0. 一句话结论

E2E 的 4 个阻塞缺陷已修复闭环；但**深入接口/安全/契约层后，发现一批 MVP 阶段的逻辑与安全缺口**——其中鉴权边界、跨租户隔离、验证码绕过、登录爆破四项为**阻断级**。本清单按优先级排列，并已转化为 `../architecture/05-secure-coding-guardrails.md` 的编码自检规约，供后续开发提前规避。

---

## 1. 优先级总表

| ID | 级别 | 缺陷 | 印证来源 | 归属 | 场景码 |
|---|---|---|---|---|---|
| **D-01** | 🔴 P0 | `/api/v1/account/**` 未挂登录拦截器；exclude 写成不存在的 `/api/v1/public/account/*` | 安全 C-1 + 契约(路径前缀) | 后端 | S4 |
| **D-02** | 🔴 P0 | 跨租户隔离仅靠请求头 `X-Tenant-Id`，无 `TenantLineInnerInterceptor` 全局过滤 | 安全 C-2 + **测试 TN-S4-01 FAIL** | 后端 | S4 |
| **D-03** | 🔴 P0 | mock 验证码 `888888` 在主配 `application.yml` 也默认开启 → 配合找回密码可接管账号 | 安全 H-2 | 后端/配置 | S4 |
| **D-04** | 🟠 P1 | 登录失败锁定/限流未实现（`MAX_LOGIN_FAILURES`/`LOCKOUT_MINUTES` 定义未用，"剩余4次"写死） | 安全 H-1 | 后端 | S3 |
| **D-05** | 🟠 P1 | 租户状态机非法转移不拒绝：重复审核已通过 / 已驳回再操作 / 未审核改店铺设置 | **测试 TN-S5-01/02/03 FAIL** | 后端 | S5 |
| **D-06** | 🟠 P1 | 仓库同名重复注册无唯一性校验 | **测试 TN-S6-01 FAIL** | 后端 | S6 |
| **D-07** | 🟠 P1 | 注册角色枚举外值不校验（返回 0） | **测试 AC-S2-05 FAIL** | 后端 | S2 |
| **D-08** | 🟠 P1 | 租户申请经纬度越界不校验 | **测试 TN-S2-02 FAIL** | 后端 | S2 |
| **D-09** | 🟡 P2 | 短信防刷基于 DB 计数有 TOCTOU 竞态、无 IP 维度 | 安全 M-1 | 后端 | S6/S7 |
| **D-10** | 🟡 P2 | 用户枚举：登录区分"手机号未注册"vs"密码错误" | 安全 L-2 | 后端 | S2 |
| **D-11** | 🟡 P2 | 密码强度规则冲突：文档 8–32 位 vs 实际 6–20 位 | 契约(§7.3) | 架构+后端 | S3 |
| **D-12** | 🟡 P2 | 前端 api-types 多字段领先/偏离后端 DTO（register/changePhone/resetPassword/RT 登录传参） | 契约(§7.2) | 前端 | — |
| **D-13** | 🟡 P2 | `expireAt` 时区缺失（`LocalDateTime` 无偏移） | 契约(§7.4) | 后端 | — |
| **D-14** | 🟢 P3 | 生产硬化：Redis 无密码、手机号明文存储、`active-timeout:-1`、SQL stdout 全打印 | 安全 M-3/M-4/L-3 | 运维+后端 | S8 |
| **D-15** | 🟢 P3 | yml 漏 `password: ${REDIS_PASSWORD:}`；残留失效的 `lettuce.pool` 块；`account.ts:114` 注释仍写 roleList | 代码审查 🟠1/2/3 | 后端/前端 | — |
| **T-01** | ⚙️ | 测试 harness：AC-S4-01/02、AC-S6-02 改密 PUT 在 401 下 `cannot retry...streaming mode` 报 ERROR（非后端缺陷，待改测试 HTTP 写法） | 本轮实跑 | 测试 | — |

---

## 2. 阻断级详情（P0，dev 也不可接受）

### D-01 鉴权边界错位
- `SaTokenConfig` 的 `SaInterceptor(checkLogin)` 只 `addPathPatterns` 了 `common/**`、`tenant/**`、`admin/**`，**漏了 `/api/v1/account/**`**；exclude 还写成不存在的 `/api/v1/public/account/*`（实际路由无 `public` 段）。
- 现状：改密/换绑/登出靠 Controller 内 `StpUtil.getLoginIdAsLong()` 后置抛异常兜底——"靠抛异常救命"，一旦有人改成从 body 取 userId 即变任意用户改密。
- 修复：拦截器 include 增 `/api/v1/account/**`，excludePathPatterns 用**真实路径**列出 register/login/login/rt/password/reset/sms-code；统一全仓 path 前缀约定。

### D-02 跨租户隔离缺失（测试已证实 FAIL）
- `tenantId` 取自客户端可控的 `X-Tenant-Id`；MyBatis-Plus 仅注册 `PaginationInnerInterceptor`，**无 `TenantLineInnerInterceptor`**，查询/更新无租户过滤，靠各 service 手写 `eq(tenantId)`，漏一处即越权。
- TN-S4-01 实测：非 OPS 角色调 `/admin/tenant/*/audit` **返回 0（成功）**，应被拒。
- 修复：①`X-Tenant-Id` 必须与登录用户角色绑定租户一致才放行；②引入 `TenantLineInnerInterceptor`；③tenantId 唯一可信来源=登录态推导。

### D-03 验证码可绕过 → 账号接管
- `verifySmsCode` 首行 `if (mockCode.equals(code)) return;`，且 `cangchu.sms.mock:true` 写进了**主配 `application.yml`**（默认 active=dev）。
- 链路：找回密码仅需 手机号+验证码+新密码，`888888` 万能 → 接管任意账号。
- 修复：mock 短路用 `@Profile("dev")` 隔离；prod 强制 `mock:false` 且无 mock-code；最好把 mock 移出 service、仅 dev 测试桩注入。

---

## 3. 建议处置节奏

| 批次 | 内容 | 触发 |
|---|---|---|
| **批次一（先修）** | D-01/02/03 鉴权+租户隔离+验证码隔离（阻断级安全） | 立即，进入下一功能开发前 |
| **批次二** | D-04~D-08 锁定限流 + 状态机 + 唯一性 + 输入校验（测试已红） | 紧接批次一 |
| **批次三** | D-09~D-13 契约统一 + 防枚举 + 竞态 | 功能迭代中顺带 |
| **批次四（上线前）** | D-14 生产硬化 + D-15 清理 + T-01 测试 harness | 部署前必过 |

> 修复任一批次后，**重跑 `AccountScenarioTest` + `TenantScenarioTest`**，对应用例应由 FAIL 转 PASS——本套件即回归基线。

---

## 4. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-28 | 首版：归并三方审查 + 24 场景测试，15 缺陷 + 1 测试 harness 项 |
