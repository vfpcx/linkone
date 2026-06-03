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
| 40102 | `VALIDATION_FORMAT_002` | 200 | 密码强度不足（8–32 位，含字母数字） | Password too weak | PRD §16.2 |
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

| code | errorCode | HTTP | 用户提示 | 开发提示 | 处理建议 |
|---|---|---|---|---|---|
| 50201 | `STATE_WHOLESALER_001` | 200 | 批发商入驻审核中 | Wholesaler pending audit | — |
| 50202 | `STATE_WHOLESALER_002` | 200 | 批发商已退驻 | Wholesaler withdrawn | — |
| 50203 | `STATE_WHOLESALER_003` | 200 | 退驻申请前需结清账单 | Cannot withdraw with unpaid bills | — |
| 50204 | `STATE_WHOLESALER_004` | 200 | 退驻申请前需清空库存 | Cannot withdraw with non-zero stock | — |
| 50205 | `STATE_WHOLESALER_005` | 200 | 批发商已在黑名单中，无法入驻 | Wholesaler in blacklist | — |

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

---

> 下一步：06-deployment.md（部署架构与运维）
