# 05 · 错误码字典（v1）

> 项目：仓储云
> 版本：v1 · 2026-06-02
> 编写：架构师 Agent
> 依赖：99-arch-decisions.md / 04-api-spec.md / PRD 05 §13
> 状态：草案 → 待 Team Lead 复核

---

## 0. 文档说明

本文档定义全平台错误码统一标识。任何业务异常、参数校验失败、权限不足、系统错误都必须返回本文档登记的错误码；新增错误码需向架构师 Agent 提 PR。

**阅读对象**：
- 后端开发 Agent：通过 `ErrorCode` 枚举实现 `BusinessException` 抛出
- 前端开发 Agent：根据 code 做用户提示 / 跳转 / 拦截
- 测试 Agent：编写异常用例时使用本文档
- 客服 / OPS：排查问题时按错误码定位

---

## 1. 编码规则

### 1.1 格式

```
{CATEGORY}_{MODULE}_{NNN}
```

| 段 | 说明 | 示例 |
|---|---|---|
| `CATEGORY` | 大类（7 类，详见 §1.2） | `AUTH` / `PERMISSION` / `VALIDATION` 等 |
| `MODULE` | 子模块（与 02-modules.md 对齐） | `ACCOUNT` / `INVENTORY` 等 |
| `NNN` | 3 位序号（从 001 开始） | `001` ~ `999` |

### 1.2 七大类（参考 PRD 05 §13）

| 大类 | 用途 | HTTP 状态 | code 数值范围 |
|---|---|---|---|
| `AUTH` | 鉴权失败（未登录 / Token 失效 / 验证码错误） | 401 | 41000–41999 |
| `PERMISSION` | 权限不足（角色不匹配 / 跨租户访问） | 403 | 42000–42999 |
| `VALIDATION` | 参数校验失败 | 200 | 40000–40999 |
| `STATE` | 状态机错误（单据状态不允许此操作） | 200 | 50000–50999 |
| `BUSINESS` | 业务规则错误（库存不足 / 余额不足 / 黑名单） | 200 | 60000–69999 |
| `LIMIT` | 限流 / 配额超限 | 429 | 43000–43999 |
| `SYSTEM` | 系统级（数据库 / 第三方服务 / 未知异常） | 500/503 | 90000–99999 |

> `code` 字段返回**数字**便于前端 switch；`errorCode` 字段返回**字符串**便于日志检索。

### 1.3 字段约定

每条错误码包含：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | 数字码（前端用）|
| `errorCode` | string | 字符串码（日志用）|
| `httpStatus` | int | HTTP 状态码 |
| `userMessage` | string | 中文用户提示（直接展示）|
| `developerMessage` | string | 英文开发提示（日志/排错用）|
| `resolution` | string | 处理建议（运维/客服） |

---

## 2. AUTH · 鉴权（41000–41999）

### AUTH_BASIC（41000–41099）通用鉴权

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 41001 | `AUTH_BASIC_001` | 401 | 您尚未登录，请先登录 | Token missing | 前端跳转登录页 |
| 41002 | `AUTH_BASIC_002` | 401 | 登录已过期，请重新登录 | Token expired | 清本地 token 后跳登录 |
| 41003 | `AUTH_BASIC_003` | 401 | 您的账号已在其他设备登录 | Token kicked out by another device | 同上 |
| 41004 | `AUTH_BASIC_004` | 401 | 账号已被冻结，请联系平台 | User frozen | 引导客诉 |
| 41005 | `AUTH_BASIC_005` | 401 | Token 无效 | Token invalid format | 清本地 token |

### AUTH_ACCOUNT（41100–41199）账号

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 41101 | `AUTH_ACCOUNT_001` | 200 | 账号或密码错误 | Invalid credentials | 显示剩余尝试次数 |
| 41102 | `AUTH_ACCOUNT_002` | 200 | 账号已锁定，请 15 分钟后重试 | Account locked due to too many failures | 显示锁定剩余时间 |
| 41103 | `AUTH_ACCOUNT_003` | 200 | 手机号未注册 | Phone not registered | 引导注册 |
| 41104 | `AUTH_ACCOUNT_004` | 200 | 该手机号已注册，请直接登录 | Phone already registered | 引导登录 |
| 41105 | `AUTH_ACCOUNT_005` | 200 | 新密码与旧密码相同 | New password same as current | 提示修改 |
| 41106 | `AUTH_ACCOUNT_006` | 200 | 新密码不能与最近 5 次密码相同 | New password matched history | 提示修改 |
| 41107 | `AUTH_ACCOUNT_007` | 200 | 旧密码错误 | Old password incorrect | — |
| 41110 | `ACCOUNT_ALL_ROLES_DISABLED` | 200 | 账号已被禁用，请联系商户管理员 | 有角色记录但全部非 ACTIVE（如 R17 禁用的单角色 WE）；不兜底 TA 放行（P2 Wave3，WEM-S5-01） | 联系 WA 恢复 |

### AUTH_SMS（41200–41299）短信验证码

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 41201 | `AUTH_SMS_001` | 200 | 验证码已过期，请重新获取 | SMS code expired | 引导重发 |
| 41202 | `AUTH_SMS_002` | 200 | 验证码错误 | SMS code mismatch | 显示剩余尝试次数 |
| 41203 | `AUTH_SMS_003` | 200 | 验证码错误次数过多，请 15 分钟后重试 | SMS verify lockout | 显示锁定时间 |
| 41204 | `AUTH_SMS_004` | 200 | 请 60 秒后再获取验证码 | SMS interval too short | 显示倒计时 |
| 41205 | `AUTH_SMS_005` | 200 | 今日验证码次数已达上限 | SMS daily limit reached | 引导次日重试 |
| 41206 | `AUTH_SMS_006` | 200 | 验证码尚未获取，请先获取 | SMS code not found | — |

### AUTH_INVITE（41300–41399）邀请码

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 41301 | `AUTH_INVITE_001` | 200 | 邀请码无效 | Invite code not found | — |
| 41302 | `AUTH_INVITE_002` | 200 | 邀请码已过期 | Invite code expired | — |
| 41303 | `AUTH_INVITE_003` | 200 | 邀请码已用完 | Invite code exhausted | — |
| 41304 | `AUTH_INVITE_004` | 200 | 邀请码与目标角色不匹配 | Invite code role mismatch | — |

---

## 3. PERMISSION · 权限（42000–42999）

### PERMISSION_ROLE（42000–42099）角色权限

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 42001 | `PERMISSION_ROLE_001` | 403 | 您没有此操作的权限 | Role not permitted | 检查角色绑定 |
| 42002 | `PERMISSION_ROLE_002` | 403 | OPS 平台操作仅限 OPS 角色 | OPS-only API accessed by non-OPS | — |
| 42003 | `PERMISSION_ROLE_003` | 403 | 仅限 TA 操作 | TA-only API | — |
| 42004 | `PERMISSION_ROLE_004` | 403 | WE 角色无此权限，请联系 WA | WE role limited | — |
| 42005 | `PERMISSION_ROLE_005` | 403 | 仅限本店 WK 操作 | Cross-store WK access denied | — |

### PERMISSION_TENANT（42100–42199）多租户隔离

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 42101 | `PERMISSION_TENANT_001` | 403 | 您没有访问此租户数据的权限 | Cross-tenant access denied | 检查 tenant_id 上下文 |
| 42102 | `PERMISSION_TENANT_002` | 500 | 系统正在处理，请稍后重试 | TenantInterceptor leak detected | 立刻告警 OPS（数据泄漏风险） |
| 42103 | `PERMISSION_TENANT_003` | 403 | 您的批发商身份不在此租户下 | Wholesaler not in target tenant | — |

### PERMISSION_OWNERSHIP（42200–42299）数据归属

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 42201 | `PERMISSION_OWNERSHIP_001` | 403 | 此单据非您所有，无法操作 | Resource not owned | — |
| 42202 | `PERMISSION_OWNERSHIP_002` | 403 | 此 SKU 非您所属批发商，无法修改 | SKU ownership mismatch | — |
| 42203 | `PERMISSION_OWNERSHIP_003` | 403 | 您不是此询价单的接收方 | Inquiry recipient mismatch | — |

---

## 4. VALIDATION · 参数校验（40000–40999）

### VALIDATION_BASIC（40000–40099）

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 40001 | `VALIDATION_BASIC_001` | 200 | 参数校验失败 | Bean validation failed | 看 details.fields |
| 40002 | `VALIDATION_BASIC_002` | 200 | 请求体格式错误 | JSON parse error | 检查请求体 |
| 40003 | `VALIDATION_BASIC_003` | 200 | 缺少必填参数：{field} | Required parameter missing | — |
| 40004 | `VALIDATION_BASIC_004` | 200 | 参数 {field} 超出范围 | Parameter out of range | — |

### VALIDATION_FORMAT（40100–40199）

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 40101 | `VALIDATION_FORMAT_001` | 200 | 手机号格式不正确 | Invalid phone format | 11 位大陆手机号 |
| 40102 | `VALIDATION_FORMAT_002` | 200 | 密码强度不足（6–20 位，含字母数字） | Password too weak | PRD §16.2 |
| 40103 | `VALIDATION_FORMAT_003` | 200 | 金额格式不正确（≤ 2 位小数） | Amount format invalid | — |
| 40104 | `VALIDATION_FORMAT_004` | 200 | 日期格式不正确 | Date format invalid | ISO-8601 |
| 40105 | `VALIDATION_FORMAT_005` | 200 | 坐标超出有效范围 | Coordinate out of range | lng -180~180 / lat -90~90 |
| 40106 | `VALIDATION_FORMAT_006` | 200 | 文件大小超过限制 | File size exceeded | <5MB 图片 / <60s 语音 |
| 40107 | `VALIDATION_FORMAT_007` | 200 | 文件类型不支持 | File type not allowed | — |

### VALIDATION_BUSINESS（40200–40299）业务校验

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 40201 | `VALIDATION_BUSINESS_001` | 200 | 数量必须大于 0 | Qty must be positive | — |
| 40202 | `VALIDATION_BUSINESS_002` | 200 | 数量必须大于起批量 {minQty} | Qty below wholesale threshold | — |
| 40203 | `VALIDATION_BUSINESS_003` | 200 | 单价必须大于 0 | Unit price must be positive | — |
| 40204 | `VALIDATION_BUSINESS_004` | 200 | 折扣金额不能大于小计 | Discount exceeds subtotal | — |
| 40205 | `VALIDATION_BUSINESS_005` | 200 | 生产日期不能晚于今天 | Production date in the future | — |
| 40206 | `VALIDATION_BUSINESS_006` | 200 | 保质期日期不能早于生产日期 | Expiry date before production date | — |
| 40207 | `VALIDATION_BUSINESS_007` | 200 | 同一手机号同一 SKU 只能设置一个有效专属价 | Duplicate customer price | — |

---

## 5. STATE · 状态机（50000–50999）

### STATE_DOCUMENT（50000–50099）单据状态

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50001 | `STATE_DOCUMENT_001` | 200 | 单据状态已变更，请刷新后重试 | Document state changed | — |
| 50002 | `STATE_DOCUMENT_002` | 200 | 当前状态不允许此操作（当前：{status}） | Invalid state transition | 提示当前状态 |
| 50003 | `STATE_DOCUMENT_003` | 200 | 入库单已登记，不能撤回 | Cannot withdraw registered inbound | — |
| 50004 | `STATE_DOCUMENT_004` | 200 | 代建出库单不可异议 | Proxy outbound cannot be disputed | PRD 锁定规则 |
| 50005 | `STATE_DOCUMENT_005` | 200 | 询价已确认，不能再撤回 | Inquiry already confirmed | — |
| 50006 | `STATE_DOCUMENT_006` | 200 | 询价已过期，请重新提交 | Inquiry expired | — |
| 50007 | `STATE_DOCUMENT_007` | 200 | 72 小时确认期已过，单据自动确认 | Auto-confirmed after 72h | — |
| 50008 | `STATE_DOCUMENT_008` | 200 | 此单据正在仲裁中，请等待平台处理 | Document under arbitration | — |

### STATE_TENANT（50100–50199）租户状态

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50101 | `STATE_TENANT_001` | 200 | 租户审核中，暂不可操作 | Tenant pending audit | — |
| 50102 | `STATE_TENANT_002` | 200 | 租户已冻结，所有操作受限 | Tenant frozen | 联系平台 |
| 50103 | `STATE_TENANT_003` | 200 | 租户已下线 | Tenant offline | — |

### STATE_WHOLESALER（50200–50299）批发商状态

> P2 入驻 Wave1 落地（决策 O-3）：50201–50205 已在 ErrorCode 枚举实现；50203/50204 语义
> 由原「退驻前置」预留调整为入驻主链实际用途（Team Lead 拍板，Wave1 任务指令）。
> R13 退驻前置校验（账单未结清/库存未清空）在 Wave2 落地时使用溢出段 50312+。

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50201 | `WHOLESALER_APPLICATION_PENDING` | 200 | 批发商入驻审核中，请勿重复申请 | Duplicate pending application | 等待 TA 审批 |
| 50202 | `WHOLESALER_WITHDRAWN` | 200 | 批发商已退驻 | Wholesaler withdrawn（Wave2 已落地：已退驻再发起退驻 / 已退驻→已下架等所有 from=WITHDRAWN 的不可达转移统一由状态机抛此码） | 60 天内可恢复或重新入驻 |
| 50203 | `WHOLESALER_APPLICATION_NOT_AUDITABLE` | 200 | 入驻申请不存在或当前状态不可审核 | Application not found / not PENDING（含跨租户不可见、并发审核被抢占） | 刷新列表 |
| 50204 | `WHOLESALER_ALREADY_ONBOARDED` | 200 | 该账号已入驻批发商，一个账号仅可入驻一个仓库 | WA already bound to an active wholesaler | — |
| 50205 | `BLACKLIST_HIT` | 200 | 已被列入平台黑名单，无法入驻 | Blacklist hit（自助申请 / OPS 代建 / TA 自营三路径同检，决策 O-2） | 联系平台 |

### 黑名单管理（50310–50329，O-3 溢出段，P2 Wave1）

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50310 | `BLACKLIST_ENTRY_EXISTS` | 200 | 黑名单条目已存在 | Duplicate blacklist entry (type,value) | — |
| 50311 | `BLACKLIST_ENTRY_NOT_FOUND` | 200 | 黑名单条目不存在 | Entry not found or already removed | — |

### R13 退驻 / R14 强制下架（50312–50318，O-3 溢出段，P2 Wave2）

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50312 | `WITHDRAW_STOCK_NOT_ZERO` | 200 | 退驻前须清空库存（当前仍有在库商品） | R13 前置：inventory 域 qty>0 行存在（发起与审批通过双检） | 清库存后重试 |
| 50313 | `WHOLESALER_NOT_ACTIVE` | 200 | 该批发商已下架或退驻，无法受理新业务 | R14 新拒老放分界：新询价创建 / PENDING 询价确认在商户非 ACTIVE 时拒绝（document 域校验点）；已确认询价与已生成出库单允许走完 | — |
| 50314 | `WITHDRAW_OPEN_DOCS_EXIST` | 200 | 退驻前须结清单据（存在未完结的询价/出库单） | R13 前置：询价 PENDING/CONFIRMED 或出库非 COMPLETED 存在 | 结清单据后重试 |
| 50315 | `WITHDRAW_APPLICATION_NOT_AUDITABLE` | 200 | 退驻申请不存在或当前状态不可审核 | Not found / not PENDING（含跨租户不可见、并发审核 CAS 被抢占、撤回目标已被审批） | 刷新列表 |
| 50316 | `WITHDRAW_APPLICATION_PENDING` | 200 | 已有退驻申请审核中，请勿重复提交 | Duplicate pending withdraw application | 等待 TA 审批或撤回 |
| 50317 | `WITHDRAW_RESTORE_EXPIRED` | 200 | 退驻恢复窗口已过（超 60 天或已归档） | 恢复窗口 <60 天（数据库时间，起点=审批通过时刻 withdrawn_at）；>=60 天整归档，两口径互补 | 重新入驻 |
| 50318 | `WHOLESALER_STATE_TRANSITION_INVALID` | 200 | 批发商当前状态不允许该操作 | 状态机不可达转移兜底（已下架→正常 / 已下架→已退驻 / 已归档→任意 等；from=WITHDRAWN 的不可达用 50202） | — |

### WE 批发商员工（50319–50322，O-3 溢出段，P2 Wave3）

> 原「50319 起预留 P4 billing」顺延为 **50323 起**（Wave3 占用 50319–50322；决策 O-5 占位不变，代码中 TODO 标注）。

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50319 | `EMPLOYEE_INVITE_PERMISSION_INVALID` | 200 | 员工授权项非法（仅允许 PRICE_EDIT/INQUIRY_CONFIRM） | WE 授权位白名单校验（生码 permissions / 员工授权变更共用，G-3.1） | — |
| 50320 | `EMPLOYEE_NOT_FOUND` | 200 | 员工不存在或不属于本商户 | user_roles 行不存在 / role≠WE / 跨商户按不存在处理（SEC-S4-10 不泄漏存在性） | 刷新员工列表 |
| 50321 | `EMPLOYEE_STATE_INVALID` | 200 | 员工当前状态不允许该操作 | 重复禁用（CAS 防 disabled_at 改写续期）/ 对 ACTIVE 员工 restore 等 | — |
| 50322 | `EMPLOYEE_RESTORE_EXPIRED` | 200 | 员工禁用已超 30 天，无法恢复 | 恢复窗口 <30 天整（数据库时间，起点=disabled_at；口径同 50317 的 60 天窗口） | 重新生码入驻 |

> 关联落地（Wave3）：`42004 PERMISSION_ROLE_004`（本文件 §3 已预留）在枚举实装，用于 WE 未持
> PRICE_EDIT / INQUIRY_CONFIRM 授权位调用对应写路径；`41110 ACCOUNT_ALL_ROLES_DISABLED`
> 新增——账号存在但全部角色被禁用（如 R17 禁用的单角色 WE）登录时语义拒绝，不再兜底 TA 放行（WEM-S5-01）。

> 预留：50323 起留给 P4 billing 的退驻账单未结清校验（决策 O-5 占位，代码中 TODO 标注）。

### P3 单据异常链（50330–50349，12-p3-design §6.2 分配段，BE-W1 落地）

> 勘误注（12 §6.2 要求补录）：代码 ErrorCode 枚举实占段 50201–50205、50240–50253、50260、
> 50270–50274、50280–50287、50290–50292、50300–50306、50310–50322 均为各批次自定义扩展实装值，
> 与本文件早期蓝图段（如 STATE_BILL 50300–50399）存在重叠——**以代码枚举为准**；
> P4 billing 落地时账单状态码改用其它空段另行分配。

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50330 | `DOC_STATE_TRANSITION_INVALID` | 200 | 当前状态不允许此操作 | 状态机不可达兜底（引擎统一抛，迁移矩阵 12 §1.2/§2.1） | — |
| 50331 | `DOC_STATE_CAS_CONFLICT` | 200 | 单据状态已变更，请刷新后重试 | CAS affected!=1（并发被抢占/重复提交） | 刷新后重试 |
| 50332 | `INBOUND_CONFIRM_WINDOW_CLOSED` | 200 | 72 小时确认期已过，单据已自动确认 | 超窗 confirm/dispute（50007 语义并入本码） | — |
| 50333 | `ARBITRATION_CONCLUSION_INVALID` | 200 | 结论选项与仲裁类型不符 | biz_type × conclusion 错配 / REJECTED 缺 remark | — |
| 50334 | `ARBITRATION_NOT_PENDING` | 200 | 该仲裁已有结论 | 不存在/已裁决/跨租户按不存在（不泄漏存在性） | — |
| 50335 | `OUTBOUND_NOT_WITHDRAWABLE` | 200 | 当前状态不可撤回（已出库请走退货） | R4 状态不符（BE-W2 启用） | — |
| 50336 | `OUTBOUND_NO_WITHDRAW_REQUEST` | 200 | 该单无待确认的撤回申请 | WK confirm-withdraw 无 flag（BE-W2 启用） | — |
| 50337 | `INQUIRY_NOT_VOIDABLE` | 200 | 意向单当前不可作废（存在已出库单据） | R8 前置不满足（BE-W2 启用） | — |
| 50338 | `OUTBOUND_LARGE_CONFIRM_REQUIRED` | 200 | 大额出库需复述件数确认 | 代建 >50% 未复述/未二次确认（BE-W2 启用） | 复述件数 |
| 50339 | `OUTBOUND_COMPLAINT_WINDOW_CLOSED` | 200 | 客诉期已过（出库后 30 天内可提） | 超窗客诉（BE-W2 启用） | — |
| 50340 | `FILE_UPLOAD_INVALID` | 200 | 文件格式或大小不符合要求 | 上传魔数（jpg/png/webp）/≤5MB/空文件校验（12 §4.4） | 换图片重传 |
| 50341 | `NOTIFICATION_NOT_FOUND` | 200 | 消息不存在 | 已读非本人/不存在（按不存在，不泄漏存在性） | — |
| 50342 | `ARBITRATION_LIABILITY_INVALID` | 200 | 差额定责选项缺失或不适用 | liability 三态：REJECTED∧shortfall>0 必填；其余必空；枚举非法（12 §4.1 刚性规则） | — |
| 50343–50349 | 预留 | — | — | 缓冲段（P3b 三主题依 D-14 拍板改用 50350–50369 溢出段，见下节；本段留作后续增补） | — |

> 关联落地（P3 BE-W1）：`50319 EMPLOYEE_INVITE_PERMISSION_INVALID` 文案随 WE 授权位白名单
> 扩 `INBOUND_CONFIRM`（G7）同步为「仅允许 PRICE_EDIT/INQUIRY_CONFIRM/INBOUND_CONFIRM」；
> WE 未持 INBOUND_CONFIRM 调用入库 confirm/dispute 复用 `42004 PERMISSION_ROLE_004`。

### P3b 正向申请链 / 退货盘点 / 批次临期（50350–50369，13-p3b-design §4.2 分配段，D-14 拍板）

> 50343–50349 仅 7 枚不够 T1+T3+T4 三主题（10-p3b-requirements D-14），溢出段定 **50350–50369**：
> 50350–50354 归 T1-BE，50355–50359 归 T3，50360–50369 归 T4（50360 例外随 T3-W1 先落禁改防御）。
> 状态机不可达/CAS 并发一律复用 50330/50331；库存不足复用 `STOCK_NOT_ENOUGH(50251)`；
> WE 授权位随 T1-BE 扩第 4 枚 `INBOUND_SUBMIT`（50319 文案同步），未持位复用 42004。

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50350 | `INBOUND_NOT_WITHDRAWABLE` | 200 | 申请已受理，无法撤回 | R1：仅 SUBMITTED 可撤，受理锁单后须走 WK 流转（T1-BE 启用） | 联系 WK 驳回 |
| 50351 | `INBOUND_QTY_DIFF_EXCEEDED` | 200 | 实收与申请件数差异超 5%，请驳回后重新申请 | T1-5 登记差异边界：≤5% 按实登记+备注必填，>5% 拒登记走 R2（T1-BE） | 走驳回流程 |
| 50352 | `INBOUND_CORRECTION_WINDOW_CLOSED` | 200 | 登记已超 24 小时，请通过盘点调整 | R3 纠错超窗（registered_at+24h，数据库时间比对；T1-BE） | 走盘点（T3） |
| 50353 | `INBOUND_CORRECTION_PENDING_EXISTS` | 200 | 该单已有待审批的纠错申请 | R3 防重（inbound_corrections pending_flag 部分唯一兜底，V13 先例；T1-BE） | 等待 TA 审批 |
| 50354 | `INBOUND_CORRECTION_INVALID` | 200 | 纠错件数无效 | new_qty<0 / 与实登相同 / 非正向链（source≠WA_SUBMIT）或非 CONFIRMED 单据（T1-BE） | — |
| 50355 | `STOCKTAKE_ITEMS_INVALID` | 200 | 盘点明细为空或存在重复商品 | items 空 / 同单 SKU 重复行 / 实物数<0（T3-W2） | — |
| 50356 | `STOCKTAKE_OPEN_EXISTS` | 200 | 该商户已有进行中的盘点单 | 同商户 DRAFT/PENDING_APPROVAL 在途（pending_flag 部分唯一，防双重盈亏；T3-W2） | 先完结在途盘点单 |
| 50357–50359 | 预留 | — | — | T3 后续增补 | — |
| 50360 | `BATCH_FEATURE_NOT_READY` | 200 | 批次功能开发中，暂不可开启 | D-13 禁改防御：店铺设置接口拒改 batch_enabled（T3-W1 落；T4-W1 起转「仅限 batch-toggle 专用端点」语义保留） | 等 T4 上线 |
| 50361 | `BATCH_TOGGLE_RATE_LIMITED` | 200 | 批次开关 24 小时内最多操作 2 次 | T4-1：Redis 计数 `batch:toggle:{tenantId}` TTL 24h（T4-W1） | 次日再试 |
| 50362 | `BATCH_NO_DUPLICATE` | 200 | 该批次号已存在 | uk(wholesaler_id, sku_id, batch_no) 冲突转译（T4-W1） | 换批次号 |
| 50363 | `BATCH_NOT_FOUND` | 200 | 批次不存在 | 不存在/跨商户按不存在（不泄漏存在性；T4-W1） | 刷新列表 |
| 50364 | `BATCH_EXPIRED_CONFIRM_REQUIRED` | 200 | 该批次已过期，入库需二次确认 | 04 §3.1 强警告：expiredConfirmed 凭据缺失；临期仅警告放行（T4-W1） | 二次确认后提交 |
| 50365 | `CLEARANCE_BATCH_NOT_CLEARABLE` | 200 | 该批次无需清库 | 非 PENDING_CLEARANCE / 推算剩余为 0 / 同批次在途 QK 已存在（T4-W2） | — |
| 50366 | `CLEARANCE_PHOTO_REQUIRED` | 200 | 清库须上传实物照片 | R19 刚性：attachments 必填 ≥1，不受 photo_mode 开关影响（T4-W2） | 上传照片 |
| 50367 | `EXPIRY_NOTIFY_RATE_LIMITED` | 200 | 24 小时内已通知过该批次 | D-12：WK 手动一键通知同批次 24h 限 1（manual_notified_at 比对；T4-W2） | 次日再通知 |
| 50368–50369 | 预留 | — | — | T4 后续增补 | — |

### STATE_BILL（50300–50399）账单状态

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50301 | `STATE_BILL_001` | 200 | 账单尚未生成 | Bill not generated yet | 等待月初定时任务 |
| 50302 | `STATE_BILL_002` | 200 | 账单已下发，不能直接调整 | Cannot adjust dispatched bill | 先撤回 |
| 50303 | `STATE_BILL_003` | 200 | 账单已结清，无法继续操作 | Bill fully paid | — |
| 50304 | `STATE_BILL_004` | 200 | 该账单存在未处理申诉 | Bill has pending disputes | 先处理申诉 |

---

## 6. BUSINESS · 业务规则（60000–69999）

### BUSINESS_INVENTORY（60000–60099）库存

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 60001 | `BUSINESS_INVENTORY_001` | 200 | 库存不足（剩余 {available} 件） | Insufficient stock | 显示可用量 |
| 60002 | `BUSINESS_INVENTORY_002` | 200 | SKU 已下架 | SKU unlisted | — |
| 60003 | `BUSINESS_INVENTORY_003` | 200 | 该批次已过期 | Batch expired | — |
| 60004 | `BUSINESS_INVENTORY_004` | 200 | 批次开关关闭，无法按批次操作 | Batch mode disabled | TA 设置开关 |
| 60005 | `BUSINESS_INVENTORY_005` | 200 | 仓库容量已满，无法入库 | Storage capacity full | TA 检查容量 |
| 60006 | `BUSINESS_INVENTORY_006` | 200 | 临期阈值内不可此操作 | Action blocked for expiring batch | — |
| 60007 | `BUSINESS_INVENTORY_007` | 200 | 盘点差异过大需 TA 二次确认 | Count diff exceeds threshold | — |

### BUSINESS_PRICE（60100–60199）价格

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 60101 | `BUSINESS_PRICE_001` | 200 | SKU 公开价未设置，无法浏览 | Public price missing | WA 设价格 |
| 60102 | `BUSINESS_PRICE_002` | 200 | 起批价缺失，请补全 | Wholesale price missing | — |
| 60103 | `BUSINESS_PRICE_003` | 200 | 价格匹配失败，请联系 WA | Price resolve failed | 客诉 |
| 60104 | `BUSINESS_PRICE_004` | 200 | 批量调价正在执行中，请稍后 | Batch price update in progress | 同 WA 锁 |
| 60105 | `BUSINESS_PRICE_005` | 200 | 议价价格低于成本价 | Bargained price below cost | WA 二次确认 |
| 60106 | `BUSINESS_PRICE_006` | 200 | 客户专属价已存在并生效 | Customer price already active | 提示覆盖 |

### BUSINESS_BILLING（60200–60299）账单

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 60201 | `BUSINESS_BILLING_001` | 200 | 已收款金额超出账单应收 | Payment exceeds bill amount | — |
| 60202 | `BUSINESS_BILLING_002` | 200 | 此账单已结清，无需重复登记 | Bill already paid | — |
| 60203 | `BUSINESS_BILLING_003` | 200 | 账单生成失败，请联系 OPS | Bill generation failed | 重试 / 报警 |
| 60204 | `BUSINESS_BILLING_004` | 200 | 申诉期已过 | Dispute window closed | PRD §账单申诉 7 天 |
| 60205 | `BUSINESS_BILLING_005` | 200 | 已收款冲销需 OPS 二次确认 | Payment reverse requires OPS confirm | — |

### BUSINESS_DOCUMENT（60300–60399）单据业务

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 60301 | `BUSINESS_DOCUMENT_001` | 200 | 代建大额出库需二次确认 | Large amount proxy outbound needs confirm | 触发 PRD §5 二次确认 |
| 60302 | `BUSINESS_DOCUMENT_002` | 200 | 出库申请明细不能为空 | Outbound items empty | — |
| 60303 | `BUSINESS_DOCUMENT_003` | 200 | RT 手机号在黑名单中 | RT in blacklist | — |
| 60304 | `BUSINESS_DOCUMENT_004` | 200 | 入库照片未上传，TA 已设为必填 | Inbound photo required | TA 开关 |
| 60305 | `BUSINESS_DOCUMENT_005` | 200 | 临期清库需 TA 审批 | Clearance needs TA approval | — |
| 60306 | `BUSINESS_DOCUMENT_006` | 200 | 此询价单已转为出库单，请查看出库 | Inquiry already converted | 跳转出库 |

### BUSINESS_VOICE（60400–60499）语音

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 60401 | `BUSINESS_VOICE_001` | 200 | 语音识别置信度过低，请重试 | Low ASR confidence | 引导重录 |
| 60402 | `BUSINESS_VOICE_002` | 200 | 语音过长，请控制在 60 秒以内 | Voice too long | — |
| 60403 | `BUSINESS_VOICE_003` | 200 | NLU 未识别到有效 SKU | NLU SKU match failed | 手动选择 |
| 60404 | `BUSINESS_VOICE_004` | 200 | 录音文件已过期（30 天） | Voice file expired | — |

### BUSINESS_FILE（60500–60599）文件

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 60501 | `BUSINESS_FILE_001` | 200 | 文件上传失败，请重试 | OSS upload failed | — |
| 60502 | `BUSINESS_FILE_002` | 200 | STS 临时令牌已过期 | STS token expired | 重新获取 |
| 60503 | `BUSINESS_FILE_003` | 200 | 文件不存在或已被删除 | File not found | — |

---

## 7. LIMIT · 限流（43000–43999）

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 43001 | `LIMIT_RATE_001` | 429 | 操作过于频繁，请稍后再试 | Rate limit exceeded | 看 Retry-After |
| 43002 | `LIMIT_QUOTA_001` | 429 | 今日操作次数已达上限 | Daily quota exceeded | — |
| 43003 | `LIMIT_SMS_001` | 429 | 短信发送频率受限，请稍后 | SMS rate limited | — |
| 43004 | `LIMIT_ASR_001` | 429 | 语音识别配额已用完 | ASR quota exceeded | OPS 联系阿里云 |

---

## 8. SYSTEM · 系统级（90000–99999）

### SYSTEM_INTERNAL（90000–90099）

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 90001 | `SYSTEM_INTERNAL_001` | 500 | 系统繁忙，请稍后再试 | Unknown exception | 看 trace_id 排查 |
| 90002 | `SYSTEM_INTERNAL_002` | 500 | 数据库异常 | Database error | DBA 检查 |
| 90003 | `SYSTEM_INTERNAL_003` | 500 | 缓存异常 | Redis error | 运维 |
| 90004 | `SYSTEM_INTERNAL_004` | 500 | 消息队列异常 | RocketMQ error | 运维 |
| 90005 | `SYSTEM_INTERNAL_005` | 503 | 系统维护中，请稍候 | Maintenance | 看公告 |

### SYSTEM_THIRD_PARTY（90100–90199）第三方服务

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 90101 | `SYSTEM_THIRD_PARTY_001` | 503 | 短信服务暂不可用 | SMS provider unavailable | 主备切换 |
| 90102 | `SYSTEM_THIRD_PARTY_002` | 503 | 语音服务暂不可用 | ASR provider unavailable | 备份通道 |
| 90103 | `SYSTEM_THIRD_PARTY_003` | 503 | 文件存储暂不可用 | OSS unavailable | — |
| 90104 | `SYSTEM_THIRD_PARTY_004` | 503 | 地图服务暂不可用 | Amap unavailable | 切腾讯 |

### SYSTEM_CONCURRENCY（90200–90299）并发

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 90201 | `SYSTEM_CONCURRENCY_001` | 200 | 操作冲突，请刷新后重试 | Optimistic lock conflict | 前端重新拉数据 |
| 90202 | `SYSTEM_CONCURRENCY_002` | 200 | 资源锁定中，请稍后 | Redisson lock timeout | — |
| 90203 | `SYSTEM_CONCURRENCY_003` | 200 | 重复请求已被拦截 | Idempotent key duplicate | — |

---

## 9. 错误码使用约定

### 9.1 后端实现

定义枚举：
```java
public enum ErrorCode {
    AUTH_BASIC_001(41001, "AUTH_BASIC_001", 401,
        "您尚未登录，请先登录", "Token missing"),
    BUSINESS_INVENTORY_001(60001, "BUSINESS_INVENTORY_001", 200,
        "库存不足（剩余 {0} 件）", "Insufficient stock, available={0}"),
    ;
    // 字段：code / errorCode / httpStatus / userMessage / devMessage
}
```

抛出：
```java
throw new BusinessException(ErrorCode.BUSINESS_INVENTORY_001, available);
// 自动填充占位符并设置 HTTP 状态
```

GlobalExceptionHandler 统一转换：
```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ApiResponse<Void>> handle(BusinessException e) {
    return ResponseEntity.status(e.getHttpStatus())
        .body(ApiResponse.error(e.getCode(), e.getErrorCode(), e.getMessage(), e.getDetails()));
}
```

### 9.2 前端处理

```ts
axios.interceptors.response.use(res => {
  const { code, message } = res.data;
  if (code === 0) return res.data;
  // 鉴权类 → 跳登录
  if (code >= 41000 && code < 42000) { router.push('/login'); }
  // 限流类 → 显示 Retry-After
  if (code >= 43000 && code < 44000) { showRetryToast(res.headers['retry-after']); }
  // 默认 → toast
  ElMessage.error(message);
  return Promise.reject(res.data);
});
```

### 9.3 trace_id 全链路

- 网关入口生成 / 透传 `X-Request-Id`
- 后端 MDC 注入 logback 的 `%X{traceId}`
- 异常响应必须含 `traceId`
- 用户截屏给客服后，按 traceId 在 ELK 中检索全链路日志

---

## 10. 新增错误码流程

1. 评估是否已有近似码（避免重复）
2. 在本文档对应大类追加（序号顺延）
3. 同步更新后端 `ErrorCode` 枚举 + 国际化文案文件 `errors.zh-CN.properties` / `errors.en-US.properties`
4. 提 PR 至架构师 Agent Review

> 文案规则：
> - userMessage 不超过 30 字，避免技术术语
> - 涉及变量用 `{0}` `{1}` 占位
> - 避免负面情绪词（"严重" / "崩溃" / "失败"），多用建议性表达

---

## 11. 速查清单（按子域）

| 大类 | 范围 | 已登记数 |
|---|---|---|
| AUTH | 41000–41999 | 21 |
| PERMISSION | 42000–42999 | 11 |
| VALIDATION | 40000–40999 | 18 |
| STATE | 50000–50999 | 18 |
| BUSINESS | 60000–69999 | 32 |
| LIMIT | 43000–43999 | 4 |
| SYSTEM | 90000–99999 | 12 |

**合计**：≥ 116 个错误码已落地

---

## 12. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-02 | 首版，七大类 ≥ 116 个错误码 |
| v1.x | 2026-07-30 | P3b 段登记（13-p3b-design §4.2，D-14 拍板）：新增 50350–50369 溢出段（实分配 15 枚：50350–50356 / 50360–50367，余预留）；50343–50349 改标缓冲段 |

---

> 下一步：06-deployment.md（部署架构与运维）
