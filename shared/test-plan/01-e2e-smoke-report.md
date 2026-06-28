# 仓储云 admin · E2E 冒烟测试报告 v1

> 执行：Team Lead · 2026-06-28
> 方式：Playwright 真实浏览器驱动 + 后端 API 旁路校验
> 环境：前端 http://localhost:5173（vite dev）· 后端 http://localhost:8080（dev profile，MySQL+Memurai）
> 脚本：`.e2e-tmp/smoke.py`（临时，未入库）

---

## 0. 修复结论（2026-06-28 同日闭环 · 全部缺陷已修并复测）

**初测 3 PASS / 2 FAIL → 修复后复测 E2E 8 PASS / 0 FAIL（5 回归 + 3 新增场景，全绿）。**

- 🔴 Bug B1（路由契约）：**已修复并复测** — 后端 `resolveRouter()` 对齐前端路由，TA 登录/注册落地 `/ta/dashboard`。
- 🔴 Bug B2（字段 roleList→roles）：**已修复并复测** — 后端 `LoginVo` 字段重命名为 `roles`（含 builder/getter/测试同步）；前端加 `roles?.length` 守卫 + Login/Register catch 非 ApiError 兜底。
- 🟠 Bug A（Redisson↔Memurai 连接不稳定）：**已修复并复测** — `RedisConfig` 新增 Redisson `pingConnectionInterval=30s` 探活 + 死连接剔除 + 池/重试/超时收紧。修复后 15 连发注册 **15/15 成功，0 个 90001**。
- ⚪ RT 路由错位：**已修复** — 后端 RT→`/ta/dashboard`，消除潜在 404（admin 不独立承载 RT）。

复测证据：重启加载新代码后，API 注册响应 `code:0` + `primaryRouter=/ta/dashboard` + 字段 `roles`（无 `roleList`）；UI E1/E2 落地 `/ta/dashboard`；E6 工作台全区块渲染；Bug A 压测 15/15。
后端编译 `BUILD SUCCESS`（JDK21）、前端 `pnpm typecheck` 零错误。

---

## 1. 结果总览

| 用例 | 场景 | 初测 | 复测 |
|---|---|---|---|
| E1 | TA 注册 → 工作台 | ❌ FAIL（Bug B1 落 404） | ✅ PASS |
| E2 | 已注册账号密码登录 | ❌ FAIL（Bug B2 + B1） | ✅ PASS |
| E3 | 负向·手机号格式校验 | ✅ PASS | ✅ PASS |
| E4 | 负向·密码错误告警 | ✅ PASS | ✅ PASS |
| E5 | 找回密码两步重置 | ✅ PASS | ✅ PASS |
| E6 | 工作台渲染校验（新增） | — | ✅ PASS |
| E7 | 重复注册→引导登录（新增·S6） | — | ✅ PASS |
| E8 | 退出登录（新增） | — | ✅ PASS |

**两个 FAIL 均为真实产品缺陷（前后端契约错位），非测试脚本问题，现已修复并复测转绿；新增 3 条场景（`.e2e-tmp/extra.py`）一并通过。**

> 复测一处插曲：用 `curl` 直发含中文的注册体曾返回 `90001`，定位为 **Windows Git Bash 把中文按 GBK 发出 → 后端 `JsonParseException: Invalid UTF-8`**，属测试命令编码问题，非后端缺陷；Playwright/Python(UTF-8) 与 ASCII curl 均正常。

---

## 2. 缺陷清单（按优先级）

### 🔴 Bug B1 · 路由契约错位（确定性，阻塞所有角色进工作台）

- **现象**：TA 注册/登录成功后，浏览器跳转到 `/tenant/dashboard`，前端无此路由 → 渲染 404。
- **根因**：后端 `AccountServiceImpl.routerFor()` 与前端路由表 / `auth.defaultRouterFor()` 两套不一致：

  | 角色 | 后端 `primaryRouter` | 前端实际路由 | 是否匹配 |
  |---|---|---|---|
  | OPS | `/ops/dashboard` | `/ops/dashboard` | ✅ |
  | TA | `/tenant/dashboard` | `/ta/dashboard` | ❌ |
  | ST | `/tenant/st/dashboard` | `/st/dashboard` | ❌ |
  | WK | `/tenant/wk/dashboard` | 前端缺 | ❌ |
  | WA | `/wholesaler/dashboard` | 前端缺 | ❌ |
  | WE | `/wholesaler/we/dashboard` | 前端缺 | ❌ |

- **放大点**：前端 `stores/auth.ts:42` 写的是 `payload.primaryRouter || defaultRouterFor(role)`——前端本来有正确映射，但后端传了 truthy 错值，`||` 短路用了后端错值。
- **修复方向**（需架构师定契约，三选一）：
  1. 后端 `routerFor()` 改为与前端一致（`/ta/dashboard` 等）；**推荐**——路由归前端定义；
  2. 前端忽略后端 `primaryRouter`，一律用 `defaultRouterFor(primaryRole)`；
  3. 接口规范统一一套路由常量，前后端共同引用，并补齐前端 WK/WA/WE 缺失路由。
- **涉及**：后端 `account/service/impl/AccountServiceImpl.java:506-513`；前端 `apps/admin/src/router/index.ts`、`stores/auth.ts:42`。

### 🔴 Bug B2 · 字段命名错位 `roleList` vs `roles`（确定性，阻塞登录跳转）

- **现象**：登录接口返回 `code:0` + token（后端成功，前端弹「登录成功」toast），但页面停在登录页，未进入工作台。
- **根因**：
  - 后端 `vo/LoginVo.java:27` 返回 `roleList`；
  - 前端 `packages/api-types/src/account.ts:82` 期望 `roles`，且 `api/account.ts` 未做字段重映射；
  - `auth.setLoginPayload` 取 `payload.roles` → `undefined` → getter `hasMultipleRoles: s.roles.length>1` 触发 `undefined.length` **抛 TypeError**；
  - `Login.vue.onSubmit` 的 catch 只处理 `ApiError`，该异常被静默吞掉 → `router.replace()` 未执行 → 停在登录页。
- **修复方向**：前后端统一字段名（后端 `roleList`→`roles`，或前端类型/映射改 `roleList`，或 http 层做一次 map）。**推荐**后端对齐 `roles`，与 PRD/接口规范一致。
- **涉及**：后端 `account/vo/LoginVo.java:27`；前端 `packages/api-types/src/account.ts:82`、`stores/auth.ts:30,37`、`views/Login.vue`（建议补 catch 兜底，非 ApiError 也提示而非静默）。

### 🟢 Bug A · Redisson↔Memurai 连接不稳定（已修复并复测）

- **现象**：注册/登录偶发返回 `90001 系统繁忙`，日志见：
  ```
  RedisConnectionFailureException: Unable to write command into connection!
    ... StacklessClosedChannelException after 3 retry attempts
    at AccountServiceImpl.register(AccountServiceImpl.java:135)
  ```
- **根因**：Memurai(Windows) 空闲一段后主动关闭连接；Redisson 默认 `pingConnectionInterval=0`（不探活），死连接留在池里，下次 Sa-Token 写 session 命中 → `StacklessClosedChannelException` → 90001。
- **修复**：`common/config/RedisConfig.java` 新增 `RedissonAutoConfigurationCustomizer`：
  - 核心 `pingConnectionInterval=30000`（定期 PING 保活 + 剔除死连接）；
  - `idleConnectionTimeout=60000`、`keepAlive=true`、`tcpNoDelay=true`；
  - `retryAttempts=3`/`retryInterval=1500`/`timeout=3000`、`nettyThreads=32`、池 `8~32`。
- **复测证据**：重启加载后 **15 连发注册 15/15 成功，0 个 90001**；E2E 8 条不再需要旁路重试即稳定通过。
- **残留说明**：本机 Memurai 的真实「长时间空闲后首请求」场景未做分钟级 idle 专测，但探活机制已对症；建议后续在集成层补 S8 故障注入用例（见 `02-scenario-test-plan.md` §7）。

---

## 3. 通过项确认

- **E3** 手机号格式校验：填 `12345` → 字段下方报「手机号格式不正确」✅
- **E4** 密码错误：错误密码登录 → 停留登录页 + 顶部告警「账号或密码错误」✅（剩余尝试次数提示正常）
- **E5** 找回密码：两步走（手机号+验证码 → 新密码×2）→ 成功 toast + 回登录页 ✅
- **404 兜底页**：设计良好（大号 404 + 「返回登录」），错路由有优雅降级 ✅

---

## 4. 截图证据（`.e2e-tmp/`）

- `e1-landing.png` — TA 注册后落地 404
- `e2-login.png` — 登录成功 toast 但停在登录页
- `e4-wrong-pwd.png` — 密码错误告警
- `e5-reset.png` — 找回密码完成

---

## 5. 建议下一步（待确认）

1. ✅ ~~Bug B1/B2 修复~~ — 已闭环（后端契约对齐 + 前端加固）。
2. ✅ ~~Bug A Redisson 排查~~ — 已闭环（`pingConnectionInterval` 探活）。
3. **代码提交**：本轮改动（后端 4 文件 + 前端 3 文件）尚未 commit，待 Team Lead 决定是否走 worktree 合并流程入库。
4. **E2E 固化**：把 `.e2e-tmp/smoke.py` + `extra.py` 固化进 `frontend/e2e/`，接入 CI 每晚跑。
5. **场景测试补全**：按 `02-scenario-test-plan.md` §4 覆盖矩阵，优先补 Account/Tenant 的 S2/S4/S5/S6 接口测试，及 Inventory/Document 的 S7 并发（Java21 虚拟线程）。
