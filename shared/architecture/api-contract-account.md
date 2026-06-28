# 账号模块 · 接口契约（权威 · 以实现为准）

> 项目：仓储云
> 版本：v1 · 2026-06-28
> 编写：架构师 Agent
> 依赖：04-api-spec.md（通用约定）/ 05-error-codes.md（错误码）/ PRD 05 §13、§16
> 状态：**已对齐当前后端 MVP 实现**（单一事实源 / Single Source of Truth）

---

## 0. 文档说明

本文档固化**账号模块**（登录 / 注册 / 改密 / 找回密码 / 换绑手机 / RT 免密登录 / 退出）的请求/响应/错误码契约，**以后端实际代码为准**，覆盖 `04-api-spec.md` §4.1 / §5.1 中已过时的设计稿描述。

权威来源（核对依据）：
- Controller：`backend/.../account/controller/AccountController.java`
- 响应 VO：`backend/.../account/vo/LoginVo.java`
- 请求 DTO：`backend/.../account/dto/*.java`
- 路由映射：`AccountServiceImpl.resolveRouter`

> ⚠️ 与 04-api-spec.md 的差异（设计稿 vs 实现）：
> 1. **路径**：设计稿规划为 `/api/v1/public/account/**` 与 `/api/v1/common/account/**`；**实现统一在 `/api/v1/account/**`**，未做 public/common 拆分。
> 2. **响应字段**：登录/注册返回 `roles`（原文档/旧代码曾用 `roleList`，2026-06-28 已统一为 `roles`）。
> 3. **路由表**：见 §4，已前后端一致。

---

## 1. 通用约定（继承 04-api-spec.md §3）

- 统一响应包装 `R<T>`：`{ code, message, data }`，`code=0` 成功；非 0 见 05-error-codes.md。
- 雪花 ID 字段后端用 `ToStringSerializer` 序列化为 **string**，前端按 string 处理（`userId` / `tenantId` / `wholesalerId`）。
- 时间字段 `expireAt` 为 ISO-8601（`LocalDateTime` 序列化）。
- 所有账号接口 base path：`/api/v1/account`。
- dev/test 环境 mock 短信验证码：**`888888`**（`cangchu.sms.mock-code`，见 `application-dev.yml`）。任意手机号用 `888888` 均可通过短信校验。

---

## 2. 接口清单（实现）

| # | 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|---|
| 1 | POST | `/api/v1/account/sms-code` | 发送短信验证码 | 免登录 |
| 2 | POST | `/api/v1/account/register` | 注册（角色感知入口） | 免登录 |
| 3 | POST | `/api/v1/account/login` | 登录（密码 / 验证码二选一） | 免登录 |
| 4 | PUT  | `/api/v1/account/password` | 修改密码 | 登录态 |
| 5 | POST | `/api/v1/account/password/reset` | 找回密码 | 免登录 |
| 6 | PUT  | `/api/v1/account/phone` | 换绑手机号 | 登录态 |
| 7 | POST | `/api/v1/account/login/rt?phone=&code=` | RT 免密验证码登录（首次自动注册） | 免登录 |
| 8 | POST | `/api/v1/account/logout` | 退出登录 | 登录态 |

> RT 免密登录用 **query 参数**（`phone`、`code`），非 JSON body。

---

## 3. 统一登录响应（LoginVo）

登录（#3）、注册（#2）、RT 登录（#7）均返回同一结构 `LoginVo`，包装在 `R.data` 内。`@JsonInclude(NON_NULL)` — 空字段不下发。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "satoken-xxxxx",
    "userId": "184237892374823400",
    "primaryRole": "TA",
    "roles": [
      {
        "role": "TA",
        "tenantId": "184237892374820000",
        "wholesalerId": null,
        "priority": 10
      }
    ],
    "primaryRouter": "/ta/dashboard",
    "expireAt": "2026-07-02T10:30:00",
    "tenantInfo": {
      "tenantId": "184237892374820000",
      "tenantName": "杭州西湖仓",
      "tenantSimpleCode": "CC01"
    },
    "isNew": false
  }
}
```

### 3.1 字段定义

| 字段 | 类型 | 必返回 | 说明 |
|---|---|---|---|
| `token` | string | 是 | Sa-Token token 值，前端存本地并放入 `Authorization` |
| `userId` | string(雪花) | 是 | 用户 ID |
| `primaryRole` | string | 是 | 主角色（按 priority 升序取第一个 ACTIVE 角色；无角色兜底 `TA`） |
| `roles` | RoleInfo[] | 是 | 该账号下所有 ACTIVE 角色（**原 `roleList`，2026-06-28 更名为 `roles`**） |
| `primaryRouter` | string | 是 | 登录后落地路由，由 `primaryRole` 映射（见 §4） |
| `expireAt` | string(ISO) | 是 | token 过期时间 |
| `tenantInfo` | object \| null | 否 | 租户信息；NON_NULL，无租户上下文时不下发 |
| `isNew` | boolean \| null | 否 | 是否本次新注册（注册、RT 首次自动注册时为 `true`）；NON_NULL |

### 3.2 RoleInfo（roles 数组元素）

| 字段 | 类型 | 说明 |
|---|---|---|
| `role` | string | 角色码：OPS/TA/ST/WK/WA/WE/RT |
| `tenantId` | string(雪花) \| null | 所属租户；RT 等无租户角色为 null |
| `wholesalerId` | string(雪花) \| null | 所属批发商；非 WA/WE 为 null |
| `priority` | int | 角色优先级，数字小优先级高：TA=10 / ST=20 / WK=30 / WA=40 / WE=50 / RT=60 |
| `storeName` | string | **前端可选扩展**：店铺名（后端 MVP 当前未下发，前端切换器用，留作扩展） |
| `pendingCount` | int | **前端可选扩展**：待办数（同上，留作扩展） |

> 多角色判定：`roles.length > 1` ⇒ 前端可弹角色切换器。前端取字段务必空值守卫（`roles?.length`）。

---

## 4. primaryRouter 路由映射（前后端一致）

后端 `AccountServiceImpl.resolveRouter(primaryRole)`：

| primaryRole | primaryRouter |
|---|---|
| OPS | `/ops/dashboard` |
| TA | `/ta/dashboard` |
| ST | `/st/dashboard` |
| WK | `/ta/dashboard` |
| WA | `/ta/dashboard` |
| WE | `/ta/dashboard` |
| RT | `/ta/dashboard` |
| 默认 / 未知 | `/ta/dashboard` |

> 说明：RT 是 H5/小程序买家，admin 后台无 `/rt/*` 路由，故 RT/WK/WA/WE 统一兜底 `/ta/dashboard`，与前端 `stores/auth.ts` 的 `defaultRouterFor` 对齐，避免 admin 内 404。

---

## 5. 各接口请求契约（DTO 实现）

### 5.1 发送短信验证码 `POST /api/v1/account/sms-code`

```json
{ "phone": "13800138000", "scene": "REGISTER" }
```

| 字段 | 必填 | 校验 | 说明 |
|---|---|---|---|
| `phone` | 是 | `^1[3-9]\d{9}$` | 手机号 |
| `scene` | 是 | 非空 | 场景：REGISTER / LOGIN / RESET_PASSWORD / CHANGE_PHONE / CHANGE_PASSWORD |

响应：`R<Void>`（`data:null`）。dev/test 实际不发短信，固定 mock 码 `888888`。

### 5.2 注册 `POST /api/v1/account/register`

```json
{
  "phone": "13800138000",
  "password": "abc123",
  "smsCode": "888888",
  "role": "TA",
  "inviteCode": null,
  "nickname": "西湖仓老板"
}
```

| 字段 | 必填 | 校验 | 说明 |
|---|---|---|---|
| `phone` | 是 | `^1[3-9]\d{9}$` | 手机号 |
| `password` | 是 | `^(?=.*[a-zA-Z])(?=.*\d).{6,20}$` | 6–20 位含字母数字 |
| `smsCode` | 是 | 非空 | 短信验证码（dev=888888） |
| `role` | 否 | — | 注册入口角色，默认 `TA`；可选 TA/WK/ST/WA/WE/RT |
| `inviteCode` | 否 | — | 员工注册（WK/ST/WE）场景的邀请码 |
| `nickname` | 否 | — | 昵称 |

响应：`R<LoginVo>`，`isNew=true`。

### 5.3 登录 `POST /api/v1/account/login`

密码登录与验证码登录二选一（`password` 与 `smsCode` 至少一个）。

```json
{
  "phone": "13800138000",
  "password": "abc123",
  "smsCode": null,
  "device": "PC",
  "deviceInfo": "Chrome on Win10"
}
```

| 字段 | 必填 | 校验 | 说明 |
|---|---|---|---|
| `phone` | 是 | `^1[3-9]\d{9}$` | 手机号 |
| `password` | 条件 | — | 密码登录时必填 |
| `smsCode` | 条件 | — | 验证码登录时必填 |
| `device` | 否 | — | 默认 `PC`：PC/H5/MP/APP |
| `deviceInfo` | 否 | — | 设备描述 |

响应：`R<LoginVo>`。

### 5.4 修改密码 `PUT /api/v1/account/password`（登录态）

```json
{ "oldPassword": "abc123", "newPassword": "xyz789" }
```

| 字段 | 必填 | 校验 |
|---|---|---|
| `oldPassword` | 是 | 非空 |
| `newPassword` | 是 | `^(?=.*[a-zA-Z])(?=.*\d).{6,20}$` |

`userId` 取自登录态（`StpUtil`）。改密成功后全端 token 失效（踢下线）。响应 `R<Void>`。

### 5.5 找回密码 `POST /api/v1/account/password/reset`

```json
{ "phone": "13800138000", "smsCode": "888888", "newPassword": "xyz789" }
```

| 字段 | 必填 | 校验 |
|---|---|---|
| `phone` | 是 | `^1[3-9]\d{9}$` |
| `smsCode` | 是 | 非空 |
| `newPassword` | 是 | `^(?=.*[a-zA-Z])(?=.*\d).{6,20}$` |

响应 `R<Void>`。

### 5.6 换绑手机号 `PUT /api/v1/account/phone`（登录态）

```json
{
  "oldSmsCode": "888888",
  "newPhone": "13900139000",
  "newSmsCode": "888888",
  "password": "abc123"
}
```

| 字段 | 必填 | 校验 |
|---|---|---|
| `oldSmsCode` | 是 | 非空（原手机号验证码） |
| `newPhone` | 是 | `^1[3-9]\d{9}$` |
| `newSmsCode` | 是 | 非空（新手机号验证码） |
| `password` | 是 | 非空 |

`userId` 取自登录态。换绑成功后全端 token 失效。响应 `R<Void>`。

### 5.7 RT 免密验证码登录 `POST /api/v1/account/login/rt`

Query 参数（非 body）：`?phone=13900139000&code=888888`

| 参数 | 必填 | 说明 |
|---|---|---|
| `phone` | 是 | 手机号 |
| `code` | 是 | 短信验证码（dev=888888） |

首次登录自动创建 RT 账号 + RT 角色（priority=60），`isNew=true`。响应 `R<LoginVo>`。

### 5.8 退出登录 `POST /api/v1/account/logout`（登录态）

无 body。`userId` 取自登录态，注销 token。响应 `R<Void>`。

---

## 6. 错误码（账号相关，详见 05-error-codes.md）

| 场景 | code | errorCode |
|---|---|---|
| 账号或密码错误 | 41101 | AUTH_ACCOUNT_001 |
| 账号锁定 | 41102 | AUTH_ACCOUNT_002 |
| 手机号未注册 | 41103 | AUTH_ACCOUNT_003 |
| 手机号已注册 | 41104 | AUTH_ACCOUNT_004 |
| 新旧密码相同 | 41105 | AUTH_ACCOUNT_005 |
| 旧密码错误 | 41107 | AUTH_ACCOUNT_007 |
| 验证码过期 | 41201 | AUTH_SMS_001 |
| 验证码错误 | 41202 | AUTH_SMS_002 |
| 验证码发送过频（60s） | 41204 | AUTH_SMS_004 |
| 验证码当日上限 | 41205 | AUTH_SMS_005 |
| 账号已冻结 | 41004 | AUTH_BASIC_004 |
| 账号已注销 | 41103（带消息「账号已注销」） | AUTH_ACCOUNT_003 |
| 参数校验失败 | 40001 | VALIDATION_BASIC_001 |
| 手机号格式错误 | 40101 | VALIDATION_FORMAT_001 |
| 密码强度不足 | 40102 | VALIDATION_FORMAT_002 |

> 注：当前后端 MVP 部分错误（如账号注销）复用 `AUTH_ACCOUNT_003` 带自定义消息；与 05-error-codes.md 设计稿存在轻微偏差，已在 §7 列为待对齐项。

---

## 7. 已知契约不一致清单（提请 Team Lead / 各角色对齐）

> 本文档以**后端实现**固化为权威；以下为发现的跨文档/跨端不一致，建议后续统一：

1. **路径前缀**：04-api-spec.md §4.1 规划 `/api/v1/public/account/**`、`/api/v1/common/account/**`；实现统一为 `/api/v1/account/**`。前端 `packages/api-types/src/account.ts` 顶部注释仍写 public/common 路径，**前端类型注释待更新**。
2. **前端 api-types 字段与后端 DTO 不一致**（`frontend/packages/api-types/src/account.ts`，归前端角色处理）：
   - `RegisterRequest` 含 `realName/tenantName/wholesalerName/targetTenantId/agreedTerms`，后端 `RegisterDto` 实际只有 `phone/password/smsCode/role/inviteCode/nickname`。
   - `ChangePasswordRequest` 含 `smsCode`，后端 `ChangePasswordDto` 无（仅 oldPassword/newPassword）。
   - `ChangePhoneRequest` 用 `oldPhoneSmsCode/newPhoneSmsCode`，后端 `ChangePhoneDto` 用 `oldSmsCode/newSmsCode`。
   - `ResetPasswordRequest` 含 `confirmPassword`，后端无。
   - 登录响应多角色标记：前端 `multiRole?`，后端**不下发**，应由前端按 `roles.length>1` 推导。
   - `SendSmsCodeResponse` 设计为含 `cooldownSec/registered`，后端返回 `R<Void>`（data:null）。
   - RT 登录：前端 `RtSmsLoginRequest`（JSON body + agreedTerms），后端用 **query 参数 phone/code**。
3. ~~**密码强度规则不一致**：05-error-codes.md `VALIDATION_FORMAT_002` 文案写「8–32 位」；后端实际校验 `6–20 位`（RegisterDto/ResetPasswordDto/ChangePasswordDto 正则）。建议二者统一（以实现 6–20 为准或同时修正实现）。~~ **【D-11 已解决，2026-06-28】** 三处（后端正则/前端 zod/文档错误码）统一为后端权威值 **6–20 位**：05-error-codes.md `VALIDATION_FORMAT_002` 文案改为 6–20；前端三处 zod（Register.vue / ForgotPassword.vue / Login.vue）`max(32)→max(20)`；前端 `packages/error-codes/messages-zh.ts` 文案改 6–20。
4. **expireAt 时区**：04-api-spec.md 约定 ISO-8601 含时区（`+08:00`）；后端 `LocalDateTime` 序列化**不含时区偏移**。建议后端改 `OffsetDateTime`/统一 Jackson 配置。
5. **错误码复用**：账号注销场景复用 `AUTH_ACCOUNT_003`（手机号未注册）带自定义消息，语义略偏；可考虑新增专用码（如 `AUTH_ACCOUNT_008 账号已注销`）。

---

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-28 | 首版：固化账号模块以实现为准的请求/响应/错误码契约。本轮对齐：登录/注册响应 **`roleList` → `roles`**；primaryRouter 路由表前后端统一（OPS→/ops/dashboard，TA/ST 对应 dashboard，WK/WA/WE/RT 兜底 /ta/dashboard）；补充 `isNew`、`tenantInfo`、RoleInfo 字段与前端可选扩展 `storeName/pendingCount`；标注实际路径 `/api/v1/account/**` 与 dev mock 短信码 `888888`；§7 列出 5 项待对齐不一致项。 |
| v1.1 | 2026-06-28 | **D-11 密码强度规则三处统一为 6–20 位**（以后端正则为权威）：修正 §7.3 不一致项为已解决；前端 3 处 zod 校验 `max(32)→max(20)`（Register/ForgotPassword/Login），前端 `error-codes` 文案与 `05-error-codes.md` `VALIDATION_FORMAT_002` 文案由 8–32 改为 6–20。 |
