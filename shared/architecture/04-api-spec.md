# 04 · API 接口规范（v1）

> 项目：仓储云
> 版本：v1 · 2026-06-02
> 编写：架构师 Agent
> 依赖：99-arch-decisions.md / 02-modules.md / 03-database-schema.sql / PRD 04
> 状态：草案 → 待 Team Lead 复核

---

## 0. 文档说明

本文档定义后端 REST API 设计规范、通用约定、核心接口清单与典型 JSON 示例。前端按本文档对接，后端按本文档实现 Controller。

**阅读对象**：
- 后端开发 Agent：定义 Controller / Mapper / Service 入口
- 前端开发 Agent：生成 axios / uni.request 调用层 + TS 类型（OpenAPI 自动化）
- 测试 Agent：接口测试用例

---

## 1. 路径风格与版本

### 1.1 URL 规则

格式：`/api/v{N}/{module}/{resource}[/{id}][/{action}]`

| 段 | 说明 | 示例 |
|---|---|---|
| `api` | 固定前缀 | — |
| `v{N}` | 版本号（v1 起步） | `v1` |
| `{module}` | 业务模块（与角色域对齐） | `ops` / `tenant` / `wholesaler` / `rt` / `common` |
| `{resource}` | 资源（复数名词） | `tenants` / `bills` / `inquiries` |
| `{id}` | 资源 ID（雪花 ID）或单据号 | `1842378923748234` |
| `{action}` | 非标准动作（动词） | `approve` / `dispatch` / `reverse` |

**示例**：
- `POST /api/v1/tenant/inbound-requests`：WK 创建入库申请
- `POST /api/v1/tenant/bills/{billId}/dispatch`：ST 下发账单
- `GET /api/v1/rt/wholesalers?storeId=xxx`：RT 浏览批发商列表
- `POST /api/v1/ops/tenants/{tenantId}/approve`：OPS 审核租户

### 1.2 模块前缀

| 前缀 | 角色域 | Sa-Token namespace | 鉴权 |
|---|---|---|---|
| `/api/v1/ops/**` | OPS | `ops` | 强鉴权 + `@SaCheckRole("OPS")` |
| `/api/v1/tenant/**` | TA / WK / ST（按接口差异） | 默认 | 强鉴权 + `@SaCheckRole({"TA","WK","ST"})` |
| `/api/v1/wholesaler/**` | WA / WE | 默认 | 强鉴权 + `@SaCheckRole({"WA","WE"})` |
| `/api/v1/rt/**` | RT | `rt` | 部分免登录（浏览类）+ 鉴权（操作类） |
| `/api/v1/common/**` | 所有角色 | 任意 | 登录态校验 |
| `/api/v1/public/**` | 公开 | 无 | 免登录（注册/登录/SMS） |

### 1.3 HTTP 方法约定

| 方法 | 用途 | 幂等 |
|---|---|---|
| GET | 查询 | 是 |
| POST | 创建 / 非标准动作（带状态转移） | 否（除幂等键场景） |
| PUT | 全量更新（少用） | 是 |
| PATCH | 部分更新 | 是 |
| DELETE | 软删除 | 是 |

> 状态机变更优先用 `POST /resources/{id}/{action}`，例如 `POST /api/v1/tenant/inbound-requests/{id}/accept`，**避免 PATCH 多义性**。

---

## 2. 鉴权方案（基于 ADR-010 Sa-Token）

### 2.1 Token 携带

请求头：
```
Authorization: Bearer {satoken}
X-Tenant-Id: {tenantId}        // 多租户上下文，前端必带（OPS / RT 可省略）
X-Request-Id: {uuid}            // 链路追踪
X-Client-Device: PC|H5|MP|APP   // 设备类型
```

### 2.2 多账户体系

| Namespace | StpUtil 实例 | 适用接口前缀 | 同手机号独立性 |
|---|---|---|---|
| 默认 | `StpUtil` | `/api/v1/tenant/**` `/api/v1/wholesaler/**` | TA / WK / ST / WA / WE 共享同 token |
| `ops` | `StpOpsUtil` | `/api/v1/ops/**` | OPS 独立 token |
| `rt` | `StpRtUtil` | `/api/v1/rt/**` | RT 独立 token（与 WA 同手机号可分别登录） |

### 2.3 Token 失效场景（踢人下线）

| 场景 | 触发动作 |
|---|---|
| 改密成功 | `StpUtil.kickout(userId)` |
| 换绑手机号成功 | `StpUtil.kickout(userId)` |
| OPS 冻结/封禁用户 | `StpUtil.kickout(userId)` |
| WA 退驻成功 | `StpUtil.kickout(waUserId)` 该批发商所有 WA/WE |
| RT 注销冷静期结束 | `StpRtUtil.kickout(rtUserId)` |
| 手动退出 | `StpUtil.logout()` |

### 2.4 Token 时效（与 PRD §16.3 对齐）

| 设备 | 有效期 | 续签 |
|---|---|---|
| PC | 8 小时 | activity 自动续签 |
| H5 | 30 天 | activity 自动续签 |
| MP（小程序） | 7 天 | 同 H5 |
| APP（v2） | 30 天 | refresh token |

### 2.5 RT 免密验证码登录

```
POST /api/v1/public/rt/sms-login
{
  "phone": "13800138000",
  "code": "123456",
  "device": "H5"
}
→ 200 OK
{
  "code": 0,
  "data": {
    "token": "...",
    "userId": 184237892,
    "isNew": false,
    "expireAt": "2026-07-02T10:30:00+08:00"
  }
}
```

RT 未注册时自动创建（PRD R4.b）。

---

## 3. 通用约定

### 3.1 请求格式

- Content-Type: `application/json;charset=UTF-8`（文件上传走 `multipart/form-data`）
- 时间格式：ISO-8601（含时区）`2026-06-02T10:30:00+08:00`
- 金额：JSON Number，最多 2 位小数（单价 4 位）
- 雪花 ID：JSON Number（注意 JS 精度，前端用 String 序列化，详见 §3.6）

### 3.2 统一响应格式

```json
{
  "code": 0,
  "message": "ok",
  "data": { ... },
  "traceId": "5f3e8c9a-2b1d-4567-89ab-cdef01234567",
  "timestamp": "2026-06-02T10:30:00+08:00"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | 0 = 成功；非 0 = 业务错误（详见 05-error-codes.md） |
| `message` | string | 用户可见的中文提示 |
| `data` | object/array/null | 业务数据 |
| `traceId` | string | 服务端生成，全链路追踪 |
| `timestamp` | string | 响应时间 |

**HTTP 状态码与 code 的关系**：
| HTTP | code | 场景 |
|---|---|---|
| 200 | 0 | 业务成功 |
| 200 | 非 0 | 业务错误（参数 / 权限 / 状态等） |
| 401 | 任意 | 未登录或 Token 失效 |
| 403 | 任意 | 已登录但角色无权限 |
| 404 | 任意 | 资源不存在或路径错误 |
| 429 | 任意 | 限流（短信发送 / 高频接口） |
| 500/503 | 任意 | 系统异常（SYSTEM_*） |

> 业务异常优先返回 HTTP 200 + 非 0 code，便于前端统一拦截器处理；仅鉴权/限流/系统错误返回非 200。

### 3.3 分页

请求参数：
```
?page=1&pageSize=20&sort=createdAt,desc
```

响应：
```json
{
  "code": 0,
  "data": {
    "records": [ {...}, {...} ],
    "total": 1234,
    "page": 1,
    "pageSize": 20,
    "totalPages": 62
  }
}
```

- `page` 从 1 开始
- `pageSize` 默认 20，最大 100
- `sort` 格式：`{field},{asc|desc}`，多字段逗号分隔

### 3.4 列表过滤

约定：
- 简单过滤通过 query 参数：`?status=ACTIVE&wholesalerId=123`
- 复杂过滤通过 POST 搜索接口：`POST /api/v1/.../search`，body 含 filter DTO
- 日期范围：`createdAtFrom` / `createdAtTo`（ISO-8601）
- 模糊匹配：`keyword` 单字段；不支持任意字段 LIKE

### 3.5 错误响应

```json
{
  "code": 41001,
  "message": "短信验证码已过期，请重新获取",
  "data": null,
  "traceId": "5f3e8c9a-2b1d-4567-89ab-cdef01234567",
  "timestamp": "2026-06-02T10:30:00+08:00",
  "details": {
    "field": "code",
    "rejected": "654321"
  }
}
```

字段校验错误返回 `details.fields` 数组（多字段）：
```json
{
  "code": 40001,
  "message": "参数校验失败",
  "data": null,
  "details": {
    "fields": [
      { "field": "phone", "message": "手机号格式不正确" },
      { "field": "password", "message": "密码长度至少 8 位" }
    ]
  }
}
```

完整错误码见 [05-error-codes.md](./05-error-codes.md)。

### 3.6 雪花 ID 序列化

JS Number 最大安全整数 2^53 - 1（16 位十进制），雪花 ID 19 位会精度丢失。

**强制规范**：
- 后端：`@JsonSerialize(using = ToStringSerializer.class)` 所有 ID 字段
- 前端 TS 类型：所有 ID 字段用 `string`
- 文档中 JSON 示例 ID 字段统一带引号

### 3.7 上传文件

```
POST /api/v1/common/files/upload
Content-Type: multipart/form-data

file: (binary)
scene: INBOUND_PHOTO | LICENSE | COMPLAINT | ...

→ 200
{
  "code": 0,
  "data": {
    "fileId": "184237892374823400",
    "fileUrl": "https://oss.../signed?...",
    "ossKey": "...",
    "size": 102400
  }
}
```

大文件（语音流）走 STS：
```
GET /api/v1/common/files/sts-token?scene=VOICE
→
{
  "code": 0,
  "data": {
    "accessKeyId": "...",
    "accessKeySecret": "...",
    "stsToken": "...",
    "bucket": "cangchu-cloud-prod-voices",
    "region": "cn-hangzhou",
    "expireAt": "2026-06-02T11:00:00+08:00"
  }
}
```

---

## 4. 接口清单（按模块）

> 完整 JSON 字段见 03-database-schema.sql 同名表；本节只列方法 + 路径 + 说明。

### 4.1 公共 / 账号（/api/v1/public/account, /api/v1/common/account）

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/v1/public/account/sms-code` | 发送短信验证码 | 公开 |
| POST | `/api/v1/public/account/register` | 注册（手机号 + 密码 + 验证码 + 可选邀请码） | 公开 |
| POST | `/api/v1/public/account/login` | 登录（手机号 + 密码） | 公开 |
| POST | `/api/v1/public/rt/sms-login` | RT 验证码登录（自动注册） | 公开 |
| POST | `/api/v1/common/account/logout` | 退出登录 | 任意 |
| POST | `/api/v1/common/account/change-password` | 改密 | 任意 |
| POST | `/api/v1/public/account/reset-password` | 找回密码（验证码 + 新密码） | 公开 |
| POST | `/api/v1/common/account/change-phone` | 换绑手机号 | 任意 |
| GET  | `/api/v1/common/account/profile` | 当前用户资料 | 任意 |
| PATCH| `/api/v1/common/account/profile` | 修改资料（昵称/头像） | 任意 |
| GET  | `/api/v1/common/account/roles` | 当前账号下所有角色 | 任意 |
| POST | `/api/v1/common/account/switch-role` | 切换主操作角色（多角色场景） | 任意 |

### 4.2 OPS 平台（/api/v1/ops/**）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | `/api/v1/ops/tenants` | 租户列表（含审核中） |
| POST | `/api/v1/ops/tenants` | OPS 代建租户 |
| POST | `/api/v1/ops/tenants/{id}/approve` | 审批通过 |
| POST | `/api/v1/ops/tenants/{id}/reject` | 审批驳回 |
| POST | `/api/v1/ops/tenants/{id}/freeze` | 冻结 |
| POST | `/api/v1/ops/tenants/{id}/unfreeze` | 解冻 |
| POST | `/api/v1/ops/tenants/{id}/offline` | 下线 |
| GET  | `/api/v1/ops/spus` | SPU 列表 |
| POST | `/api/v1/ops/spus` | 创建 SPU |
| PATCH| `/api/v1/ops/spus/{id}` | 修改 SPU |
| POST | `/api/v1/ops/spus/{id}/merge` | 合并 SPU |
| POST | `/api/v1/ops/spus/{id}/offline` | 下架 SPU |
| GET  | `/api/v1/ops/spec-types` | 规格类型列表 |
| POST | `/api/v1/ops/spec-types` | 新增规格类型 |
| GET  | `/api/v1/ops/blacklist` | 黑名单列表 |
| POST | `/api/v1/ops/blacklist` | 加入黑名单 |
| DELETE | `/api/v1/ops/blacklist/{id}` | 移除黑名单 |
| GET  | `/api/v1/ops/complaints` | 客诉列表 |
| POST | `/api/v1/ops/complaints/{id}/assign` | 指派 |
| POST | `/api/v1/ops/complaints/{id}/resolve` | 处理结案 |
| GET  | `/api/v1/ops/announcements` | 公告列表 |
| POST | `/api/v1/ops/announcements` | 发布公告 |
| POST | `/api/v1/ops/announcements/{id}/withdraw` | 撤回公告 |
| POST | `/api/v1/ops/wholesalers/proxy-create` | 代建批发商（PRD R3） |
| GET  | `/api/v1/ops/dashboard` | OPS 后台仪表盘（租户数 / 账单 / 客诉） |

### 4.3 TA（/api/v1/tenant/**）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | `/api/v1/tenant/settings` | 当前店铺设置（5 开关 + 容量） |
| PATCH| `/api/v1/tenant/settings` | 修改设置（含副作用提示） |
| GET  | `/api/v1/tenant/store-front` | 撮合页内容 |
| PATCH| `/api/v1/tenant/store-front` | 编辑撮合页 |
| GET  | `/api/v1/tenant/employees` | 员工列表（WK / ST） |
| POST | `/api/v1/tenant/employees` | 邀请员工（生成注册码） |
| POST | `/api/v1/tenant/employees/{id}/disable` | 禁用员工 |
| POST | `/api/v1/tenant/employees/{id}/restore` | 恢复员工 |
| GET  | `/api/v1/tenant/invite-codes` | 邀请码列表 |
| POST | `/api/v1/tenant/invite-codes` | 生成邀请码 |
| GET  | `/api/v1/tenant/wholesaler-applications` | WA 入驻申请列表 |
| POST | `/api/v1/tenant/wholesaler-applications/{id}/approve` | 审批通过 |
| POST | `/api/v1/tenant/wholesaler-applications/{id}/reject` | 驳回 |
| POST | `/api/v1/tenant/wholesalers/self-operated` | 创建自营批发商（R5） |
| POST | `/api/v1/tenant/wholesalers/{id}/force-offline` | 强制下架（R14） |
| GET  | `/api/v1/tenant/approval-center` | 审批中心聚合（待审批入库 / 盘点 / 清库） |
| GET  | `/api/v1/tenant/bills-overview` | 账单总览（全 WA 汇总） |
| POST | `/api/v1/tenant/inbound-requests/{id}/arbitrate` | 代建入库异议仲裁 |

### 4.4 WK（/api/v1/tenant/wk/**）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/tenant/wk/inbound-requests` | 代建入库申请 |
| POST | `/api/v1/tenant/wk/inbound-requests/{id}/accept` | 接受入库 |
| POST | `/api/v1/tenant/wk/inbound-requests/{id}/reject` | 驳回 |
| POST | `/api/v1/tenant/wk/inbound-requests/{id}/register` | 实际入库登记（批次/拍照/托盘） |
| POST | `/api/v1/tenant/wk/outbound-requests` | 代建出库申请 |
| POST | `/api/v1/tenant/wk/outbound-requests/{id}/print` | 打印出库单 |
| POST | `/api/v1/tenant/wk/outbound-requests/{id}/register` | 出库登记 |
| POST | `/api/v1/tenant/wk/count-sheets` | 提交盘点 |
| POST | `/api/v1/tenant/wk/expiry-clearances` | 提交临期清库 |
| GET  | `/api/v1/tenant/wk/inventory` | 库存查询（支持按 WA / SKU / 批次） |
| GET  | `/api/v1/tenant/wk/expiring-batches` | 临期批次列表 |
| GET  | `/api/v1/tenant/wk/wholesalers` | 本店 WA 列表（出库下拉用） |
| GET  | `/api/v1/tenant/wk/skus-in-stock` | WA 在库 SKU（出库下拉用） |

### 4.5 ST（/api/v1/tenant/st/**）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | `/api/v1/tenant/st/bills` | 账单列表（按 WA / 月份过滤） |
| GET  | `/api/v1/tenant/st/bills/{id}` | 账单详情 |
| POST | `/api/v1/tenant/st/bills/{id}/adjust` | 账单调整（折扣/减免） |
| POST | `/api/v1/tenant/st/bills/{id}/dispatch` | 下发账单（推 WA） |
| POST | `/api/v1/tenant/st/bills/{id}/withdraw` | 下发撤回（R11） |
| POST | `/api/v1/tenant/st/bills/{id}/reverse-item` | 冲销账单项 |
| POST | `/api/v1/tenant/st/bills/{id}/payments` | 登记已收款 |
| POST | `/api/v1/tenant/st/payments/{id}/reverse` | 已收款冲销（R12） |
| GET  | `/api/v1/tenant/st/bills/{id}/export?format=pdf|excel` | 导出 |
| GET  | `/api/v1/tenant/st/bill-disputes` | 账单申诉列表 |
| POST | `/api/v1/tenant/st/bill-disputes/{id}/resolve` | 处理申诉 |
| GET  | `/api/v1/tenant/st/billing-rules` | 计费规则列表（含历史版本） |
| POST | `/api/v1/tenant/st/billing-rules` | 新建/调整计费规则（R20） |

### 4.6 WA（/api/v1/wholesaler/**）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/wholesaler/applications` | 入驻申请 |
| GET  | `/api/v1/wholesaler/profile` | 我的批发商资料 |
| PATCH| `/api/v1/wholesaler/profile` | 修改撮合介绍 |
| POST | `/api/v1/wholesaler/withdraw` | 退驻申请（R13） |
| GET  | `/api/v1/wholesaler/skus` | 我的 SKU 列表 |
| POST | `/api/v1/wholesaler/skus` | 新建 SKU |
| PATCH| `/api/v1/wholesaler/skus/{id}` | 修改 SKU |
| POST | `/api/v1/wholesaler/skus/{id}/toggle-listing` | 上下架 |
| POST | `/api/v1/wholesaler/skus/batch-price-update` | 批量调价（公开价/起批价） |
| GET  | `/api/v1/wholesaler/customer-prices` | 客户专属价列表 |
| POST | `/api/v1/wholesaler/customer-prices` | 设置专属价 |
| PATCH| `/api/v1/wholesaler/customer-prices/{id}` | 修改专属价 |
| POST | `/api/v1/wholesaler/customer-prices/batch-update` | 批量调专属价 |
| DELETE | `/api/v1/wholesaler/customer-prices/{id}` | 撤销专属价 |
| GET  | `/api/v1/wholesaler/price-change-logs` | 调价历史 |
| POST | `/api/v1/wholesaler/inbound-requests` | 入库申请 |
| GET  | `/api/v1/wholesaler/inbound-requests` | 我的入库单列表 |
| POST | `/api/v1/wholesaler/inbound-requests/{id}/withdraw` | 撤回 |
| POST | `/api/v1/wholesaler/inbound-requests/{id}/confirm` | 代建入库确认 |
| POST | `/api/v1/wholesaler/inbound-requests/{id}/dispute` | 代建入库异议 |
| POST | `/api/v1/wholesaler/outbound-requests` | 出库申请 |
| GET  | `/api/v1/wholesaler/outbound-requests` | 我的出库单列表 |
| POST | `/api/v1/wholesaler/outbound-requests/{id}/complaint` | 出库异议（PRD §5.5） |
| GET  | `/api/v1/wholesaler/inquiries` | 收到的询价列表 |
| POST | `/api/v1/wholesaler/inquiries/{id}/confirm` | 确认询价（含成交价/沉淀选项） |
| POST | `/api/v1/wholesaler/inquiries/{id}/bargain` | 议价回价 |
| POST | `/api/v1/wholesaler/inquiries/{id}/reject` | 拒绝询价 |
| POST | `/api/v1/wholesaler/inquiries/{id}/void` | WA 作废询价（R8） |
| POST | `/api/v1/wholesaler/inquiries/proxy-submit` | WA 代下询价（PRD §6.5） |
| GET  | `/api/v1/wholesaler/bills` | 收到的账单列表 |
| POST | `/api/v1/wholesaler/bills/{id}/dispute` | 提交账单申诉 |
| GET  | `/api/v1/wholesaler/inventory` | 我的库存（自助查询） |
| GET  | `/api/v1/wholesaler/pending-inbound-confirms` | 待确认代建入库（72h 倒计时） |
| GET  | `/api/v1/wholesaler/employees` | WE 员工列表 |
| POST | `/api/v1/wholesaler/employees` | 邀请 WE |

### 4.7 WE（/api/v1/wholesaler/we/**）

WE 受限角色，路径复用 WA 的子集：
- 仅可查看入库、出库、库存、SKU
- 不可改价、不可处理询价（除非授权）
- 不可退驻

通过 `@SaCheckPermission("wholesaler:read")` 等注解控制。

### 4.8 RT（/api/v1/rt/**）

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET  | `/api/v1/rt/stores` | 仓库广场列表 | 公开 |
| GET  | `/api/v1/rt/stores/{id}` | 仓库详情（撮合页） | 公开 |
| GET  | `/api/v1/rt/stores/recommend` | 位置推荐（按距离/容量） | 公开 |
| GET  | `/api/v1/rt/stores/{id}/wholesalers` | 店内 WA 列表 | 公开 |
| GET  | `/api/v1/rt/wholesalers/{id}` | WA 详情 | 公开 |
| GET  | `/api/v1/rt/wholesalers/{id}/skus` | WA 的 SKU 列表（含价格匹配） | 鉴权（匹配专属价时） |
| POST | `/api/v1/rt/inquiries` | 提交询价 | 鉴权 |
| GET  | `/api/v1/rt/inquiries` | 我的意向单列表 | 鉴权 |
| GET  | `/api/v1/rt/inquiries/{id}` | 意向单详情 | 鉴权 |
| POST | `/api/v1/rt/inquiries/{id}/cancel` | RT 撤回询价（R7） | 鉴权 |
| POST | `/api/v1/rt/inquiries/{id}/accept-bargain` | 接受议价 | 鉴权 |
| GET  | `/api/v1/rt/addresses` | 收货地址列表 | 鉴权 |
| POST | `/api/v1/rt/addresses` | 新增地址 | 鉴权 |
| PATCH| `/api/v1/rt/addresses/{id}` | 修改地址 | 鉴权 |
| POST | `/api/v1/rt/account/cancel` | 注销账号（冷静期 30 天） | 鉴权 |

### 4.9 横向（/api/v1/common/**）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/common/files/upload` | 上传文件 |
| GET  | `/api/v1/common/files/sts-token` | STS 临时令牌 |
| POST | `/api/v1/common/voice/start-asr` | 启动实时语音识别（WS 升级） |
| POST | `/api/v1/common/voice/upload` | 上传录音 + 转写 |
| POST | `/api/v1/common/voice/extract` | NLU 字段抽取 |
| GET  | `/api/v1/common/notifications` | 站内信列表 |
| POST | `/api/v1/common/notifications/{id}/read` | 标记已读 |
| POST | `/api/v1/common/notifications/read-all` | 全部已读 |
| GET  | `/api/v1/common/announcements/effective` | 当前有效公告 |
| GET  | `/api/v1/common/maps/geocode` | 高德地理编码代理 |
| GET  | `/api/v1/common/maps/reverse-geocode` | 逆向地理编码代理 |
| GET  | `/api/v1/common/capacity/{tenantId}` | 容量公示查询（PRD §8.3 脱敏） |
| POST | `/api/v1/common/complaints` | 提交客诉（任意角色） |

---

## 5. 完整 JSON 示例

### 5.1 登录（POST /api/v1/public/account/login）

**请求**：
```json
{
  "phone": "13800138000",
  "password": "Secret@123",
  "device": "H5",
  "deviceInfo": "iPhone 15 Pro · iOS 18"
}
```

**成功响应**：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "satoken-eyJhbGciOiJIUzI1Ni...",
    "userId": "184237892374823400",
    "primaryRole": "WA",
    "roles": [
      {
        "role": "WA",
        "tenantId": "184237892374820000",
        "wholesalerId": "184237892374821000",
        "priority": 40
      },
      {
        "role": "RT",
        "tenantId": null,
        "wholesalerId": null,
        "priority": 60
      }
    ],
    "primaryRouter": "/wa/dashboard",
    "expireAt": "2026-07-02T10:30:00+08:00",
    "tenantInfo": {
      "tenantId": "184237892374820000",
      "tenantName": "杭州西湖仓",
      "tenantSimpleCode": "CC01"
    }
  },
  "traceId": "5f3e8c9a-2b1d-4567-89ab-cdef01234567",
  "timestamp": "2026-06-02T10:30:00+08:00"
}
```

**失败响应（密码错误）**：
```json
{
  "code": 41002,
  "message": "账号或密码错误",
  "data": null,
  "details": {
    "remainAttempts": 4,
    "lockoutAt": null
  },
  "traceId": "5f3e8c9a-2b1d-4567-89ab-cdef01234567",
  "timestamp": "2026-06-02T10:30:00+08:00"
}
```

### 5.2 提交出库申请（POST /api/v1/wholesaler/outbound-requests）

**请求**：
```json
{
  "rtPhone": "13900139000",
  "rtName": "张三便利店",
  "deliveryAddressId": "184237892374821100",
  "expectPickupAt": "2026-06-03T09:00:00+08:00",
  "items": [
    {
      "skuId": "184237892374820001",
      "qty": 50,
      "unitPrice": 12.5000,
      "priceSource": "CUSTOMER",
      "itemDiscount": 0
    },
    {
      "skuId": "184237892374820002",
      "qty": 100,
      "unitPrice": 8.8000,
      "priceSource": "WHOLESALE",
      "itemDiscount": 50.00
    }
  ],
  "fullOrderDiscount": 30.00,
  "remark": "客户加急",
  "voiceRecordId": null
}
```

**成功响应**：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "outboundRequestId": "184237892374823500",
    "docNo": "OUT-CC01-20260602-000123",
    "status": "SUBMITTED",
    "totalAmount": 1505.00,
    "subtotalAmount": 1585.00,
    "fullOrderDiscount": 30.00,
    "itemDiscount": 50.00,
    "createdAt": "2026-06-02T10:30:00+08:00",
    "estimatedPickupAt": "2026-06-03T09:00:00+08:00"
  }
}
```

**失败响应（库存不足）**：
```json
{
  "code": 60001,
  "message": "SKU「农夫山泉 500ml」库存不足，剩余 30 件，申请 50 件",
  "data": null,
  "details": {
    "skuId": "184237892374820001",
    "skuName": "农夫山泉 500ml",
    "requested": 50,
    "available": 30
  }
}
```

### 5.3 询价确认沉淀（POST /api/v1/wholesaler/inquiries/{id}/confirm）

**请求**：
```json
{
  "items": [
    {
      "inquiryItemId": "184237892374823601",
      "dealPrice": 11.5000,
      "bargained": true
    },
    {
      "inquiryItemId": "184237892374823602",
      "dealPrice": 8.8000,
      "bargained": false
    }
  ],
  "fullOrderDiscount": 0,
  "sinkOption": "SINK_TO_CUSTOMER",
  "remark": "首次合作，议价沉淀"
}
```

**成功响应**：
```json
{
  "code": 0,
  "data": {
    "inquiryId": "184237892374823600",
    "inquiryStatus": "CONFIRMED",
    "outboundRequestId": "184237892374823700",
    "outboundDocNo": "OUT-CC01-20260602-000124",
    "settledCustomerPrices": [
      {
        "customerPriceId": "184237892374823800",
        "skuId": "184237892374820001",
        "skuName": "农夫山泉 500ml",
        "rtPhone": "13900139000",
        "price": 11.5000,
        "effectiveFrom": "2026-06-02T10:35:00+08:00"
      }
    ],
    "totalAmount": 1455.00,
    "createdAt": "2026-06-02T10:35:00+08:00"
  }
}
```

### 5.4 容量公示查询（GET /api/v1/common/capacity/{tenantId}）

**响应（viewerRole=RT，TIER 精度）**：
```json
{
  "code": 0,
  "data": {
    "tenantId": "184237892374820000",
    "storeId": "184237892374820100",
    "visibility": "PUBLIC",
    "precision": "TIER",
    "tier": "MEDIUM",
    "tierLabel": "余量适中",
    "snapshotAt": "2026-06-02T10:20:00+08:00",
    "expectedNextRefresh": "2026-06-02T10:30:00+08:00"
  }
}
```

**响应（viewerRole=WA，EXACT 精度）**：
```json
{
  "code": 0,
  "data": {
    "tenantId": "184237892374820000",
    "storeId": "184237892374820100",
    "visibility": "WA_ONLY",
    "precision": "EXACT",
    "usedQty": 12450,
    "totalQty": 20000,
    "usedPallet": 124,
    "totalPallet": 200,
    "utilization": 62.25,
    "tier": "MEDIUM",
    "snapshotAt": "2026-06-02T10:20:00+08:00"
  }
}
```

### 5.5 代建入库登记（POST /api/v1/tenant/wk/inbound-requests/{id}/register）

**请求**：
```json
{
  "items": [
    {
      "itemId": "184237892374823901",
      "actualQty": 200,
      "batchNo": "20260520-A",
      "prodDate": "2026-05-20",
      "expiryDate": "2027-05-19",
      "palletQty": 2
    }
  ],
  "photoIds": [
    "184237892374823910",
    "184237892374823911",
    "184237892374823912"
  ],
  "remark": "实物与申请一致"
}
```

**成功响应**：
```json
{
  "code": 0,
  "data": {
    "inboundRequestId": "184237892374823900",
    "docNo": "IN-CC01-20260602-000045",
    "status": "PENDING_WA_CONFIRM",
    "registeredAt": "2026-06-02T10:40:00+08:00",
    "waConfirmDeadline": "2026-06-05T10:40:00+08:00",
    "createdBatches": [
      {
        "batchId": "184237892374823920",
        "skuId": "184237892374820001",
        "batchNo": "20260520-A",
        "prodDate": "2026-05-20",
        "expiryDate": "2027-05-19"
      }
    ],
    "billingStartAt": "2026-06-02T00:00:00+08:00",
    "notifications": [
      {
        "channel": "INBOX",
        "recipientUserId": "184237892374820200",
        "title": "您的入库申请已登记，请 72h 内确认"
      }
    ]
  }
}
```

---

## 6. WebSocket / SSE 接口

### 6.1 实时语音识别（WebSocket）

```
GET wss://{host}/ws/v1/voice/asr?token={token}&scene=INBOUND
```

**客户端 → 服务端**：
- 二进制帧：PCM 16kHz 16bit 音频数据流（每 100ms 一帧）
- 文本帧：控制指令 `{"type":"start"}` / `{"type":"stop"}`

**服务端 → 客户端**：
```json
{ "type": "partial", "text": "入库一百件", "confidence": 0.92 }
{ "type": "final",   "text": "入库一百件农夫山泉 500ml", "confidence": 0.96, "voiceRecordId": "184..." }
{ "type": "error",   "code": 71001, "message": "ASR 服务暂不可用" }
```

### 6.2 通知推送（SSE，简易）

```
GET /api/v1/common/notifications/stream?token={token}
Accept: text/event-stream
```

事件流：
```
event: new
data: {"id":"184...","title":"账单已下发","biz":"BILL_DISPATCH","bizId":"..."}

event: heartbeat
data: {"ts":"2026-06-02T10:30:30+08:00"}
```

---

## 7. 限流策略

通过 Nginx + 应用层 Sentinel 双层限流：

| 接口 | 维度 | 阈值 |
|---|---|---|
| 短信发送 | 单手机号 | 60s/条，日 10 条 |
| 登录 | 单 IP | 10 次/分钟 |
| RT 询价提交 | 单 RT | 10 单/分钟 |
| WA 批量调价 | 单 WA | 10 次/小时 |
| 通用接口 | 单 token | 60 次/分钟 |

超限返回 HTTP 429 + 业务码 `LIMIT_RATE_001`，响应头：
```
Retry-After: 30
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1717304430
```

---

## 8. 接口文档自动化

### 8.1 SpringDoc OpenAPI

后端 build 阶段输出 `openapi.json` 到 `shared/openapi/`：
- `springdoc-openapi-starter-webmvc-ui` 3.x
- 注解：`@Operation` / `@Schema` / `@Parameter`
- 路径：`/v3/api-docs`（仅 dev 环境暴露）

### 8.2 前端类型生成

```bash
pnpm openapi-typescript-codegen --input shared/openapi/openapi.json --output src/api/generated
```

生成内容：
- 所有 DTO / VO TypeScript 类型
- 按模块分组的 axios 调用方法
- 与后端 Controller 1:1 对齐

---

## 9. 变更与版本演进

| 演进策略 | 说明 |
|---|---|
| 向后兼容字段新增 | 同 v1 内追加新字段，老客户端忽略 |
| 破坏性变更 | 启用 `v2` 前缀，v1 维持 12 个月只读 |
| 字段废弃 | 响应 Header `X-Deprecated-Field: oldField; sunset=2027-01-01` |
| 接口下线 | 提前 30 天发布公告 + 邮件 + 后台横幅 |

---

## 10. 后端 / 前端落地提示

**后端**：
- 所有 Controller 用 `ApiResponse<T>` 包装；切勿直接返回 DTO
- 路径必须按 `/api/v1/{module}/...` 严格命名；CodeReview 检查
- 注解校验：`@Valid` + `@NotNull` / `@NotBlank` / `@Pattern`
- 异常统一抛 `BusinessException(ErrorCode, args)`，由 `GlobalExceptionHandler` 转 JSON
- 多租户：`@SaCheckTenant` 自定义注解强制注入 tenant_id 上下文

**前端**：
- 全局 axios 拦截器：注入 `Authorization` / `X-Tenant-Id` / `X-Request-Id`
- 响应拦截器：统一处理 `code !== 0` → toast + 重定向（401/403）
- 雪花 ID 全部用 string 类型
- 文件上传：< 5MB 走服务端代理；大文件用 STS 直传 OSS
- 表单校验与后端 ErrorCode 对齐（参考 05-error-codes.md）

---

## 11. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-02 | 首版 |

---

> 下一步：05-error-codes.md（错误码字典）
