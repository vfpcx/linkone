# 仓储云后端手动测试指南

> 后端启动：http://localhost:8080
> 健康检查：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`
>
> 全部接口前缀：`/api/v1`
> Sa-Token Header：`Authorization: <token>`（从登录响应里 `data.token` 拿）

---

## A · 准备 · 直接生成 token（最快）

```bash
# 注册 TA 用户（验证码固定 888888，是 mock）
curl -X POST http://localhost:8080/api/v1/account/register \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800000001",
    "password": "TaPass123",
    "smsCode": "888888",
    "role": "TA"
  }'
```

返回示例：
```json
{
  "code": 0,
  "msg": "OK",
  "data": {
    "token": "abc...",
    "userId": "xxx",
    "primaryRole": "TA",
    "roleList": [...]
  }
}
```

**保存这个 token，下面所有 TA 接口都用它。**

---

## B · 账号模块（7 个接口）

### 1. 注册（同上 A）

不同角色注册：把 `role` 改成 `OPS` / `WK` / `ST` / `WA` / `WE` / `RT`。

### 2. 登录（密码方式）

```bash
curl -X POST http://localhost:8080/api/v1/account/login \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800000001",
    "password": "TaPass123",
    "loginType": "PASSWORD"
  }'
```

### 3. 登录（短信验证码）

```bash
curl -X POST http://localhost:8080/api/v1/account/login \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800000001",
    "smsCode": "888888",
    "loginType": "SMS_CODE"
  }'
```

### 4. 改密码（需 token）

```bash
TOKEN="<上面登录拿到的 token>"
curl -X PUT http://localhost:8080/api/v1/account/password \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{
    "oldPassword": "TaPass123",
    "newPassword": "NewPass456"
  }'
```

### 5. 找回密码（不需 token）

```bash
curl -X POST http://localhost:8080/api/v1/account/password/reset \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800000001",
    "smsCode": "888888",
    "newPassword": "ResetPass789"
  }'
```

### 6. 换绑手机号（需 token）

```bash
curl -X PUT http://localhost:8080/api/v1/account/phone \
  -H "Content-Type: application/json" \
  -H "Authorization: $TOKEN" \
  -d '{
    "newPhone": "13900000001",
    "smsCode": "888888"
  }'
```

### 7. RT 免密验证码登录（首次自动注册）

```bash
curl -X POST http://localhost:8080/api/v1/account/login/rt \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13700000001",
    "smsCode": "888888"
  }'
```

### 8. 退出登录

```bash
curl -X POST http://localhost:8080/api/v1/account/logout \
  -H "Authorization: $TOKEN"
```

---

## C · 租户模块（8 个接口）

### 1. TA 自助注册仓库（需 TA token）

```bash
TA_TOKEN="<TA 的 token>"
curl -X POST http://localhost:8080/api/v1/tenant/apply \
  -H "Content-Type: application/json" \
  -H "Authorization: $TA_TOKEN" \
  -d '{
    "name": "我的测试仓库",
    "legalName": "测试仓储有限公司",
    "contactPhone": "13800000001",
    "addressText": "浙江省杭州市西湖区文三路 100 号",
    "lng": 120.1552,
    "lat": 30.2741
  }'
```

返回有 `tenantId`，记住这个。

### 2. OPS 审核入驻（需 OPS token）

```bash
# 先注册一个 OPS 账号
curl -X POST http://localhost:8080/api/v1/account/register \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "15800000001",
    "password": "OpsPass123",
    "smsCode": "888888",
    "role": "OPS"
  }'

# 用 OPS token 审核
OPS_TOKEN="<OPS 的 token>"
TENANT_ID="<上面 apply 返回的 tenantId>"
curl -X POST "http://localhost:8080/api/v1/admin/tenant/$TENANT_ID/audit" \
  -H "Content-Type: application/json" \
  -H "Authorization: $OPS_TOKEN" \
  -d '{
    "action": "APPROVED",
    "remark": "资质合格"
  }'
```

### 3. OPS 代建租户（直接 ACTIVE + 短信临时密码）

```bash
curl -X POST http://localhost:8080/api/v1/admin/tenant/create \
  -H "Content-Type: application/json" \
  -H "Authorization: $OPS_TOKEN" \
  -d '{
    "name": "OPS代建仓库",
    "legalName": "代建仓储有限公司",
    "contactPhone": "13800000002",
    "addressText": "上海市浦东新区张江路 88 号"
  }'
```

### 4. 查我的店铺设置（TA token，审核通过后才能查）

```bash
curl -X GET http://localhost:8080/api/v1/tenant/me \
  -H "Authorization: $TA_TOKEN"
```

### 5. 改店铺设置 / 计费规则 / 五开关（TA token）

```bash
curl -X PUT http://localhost:8080/api/v1/tenant/me \
  -H "Content-Type: application/json" \
  -H "Authorization: $TA_TOKEN" \
  -d '{
    "name": "改名后的仓库",
    "batchEnabled": 1,
    "photoMode": "REQUIRED",
    "billingDim": "PALLET",
    "expiryThresholdDays": 30,
    "lng": 120.1552,
    "lat": 30.2741
  }'
```

### 6. 生成店铺二维码（TA token）

```bash
curl -X POST http://localhost:8080/api/v1/tenant/store-qr \
  -H "Authorization: $TA_TOKEN"
```

返回 `qrUrl` 和 `tenantSimpleCode`。

### 7. 生成员工注册码（TA token）

```bash
curl -X POST "http://localhost:8080/api/v1/tenant/invite-code?targetRole=WK&maxUses=5&expireDays=30" \
  -H "Authorization: $TA_TOKEN"
```

返回的 `code` 可以给员工注册时填进 `inviteCode` 字段。

### 8. 实时容量查询（公开接口，不需 token）

```bash
curl "http://localhost:8080/api/v1/tenant/capacity?tenantId=$TENANT_ID"
```

---

## D · 常见错误码（按 05-error-codes.md）

| 段 | 含义 | 常见场景 |
|---|---|---|
| 0 | 成功 | — |
| 40001 | 参数校验失败 | 字段格式不对（如手机号 13 位） |
| 40101 | 手机号格式不正确 | 不符合 `^1[3-9]\d{9}$` |
| 41001 | 未登录 / token 失效 | 没传 Authorization 或过期 |
| 41003 | 验证码错误 | 不是 888888 |
| 50210 | 租户不存在 | 当前用户没绑定租户 |

---

## E · 推荐测试流程

1. **基础链路**：注册 TA → 申请仓库 → 注册 OPS → 审核通过 → TA 查店铺 → 改店铺设置
2. **边界测试**：手机号格式错（如 `12345`）→ 应返回 40101
3. **鉴权测试**：不带 token 调 `/api/v1/tenant/me` → 应返回 41001
4. **公开接口**：不带 token 调容量查询 → 应正常返回（除非该仓库设了"不公开"）

---

## F · GUI 工具推荐（比 curl 友好）

- **Postman**（推荐）：导入下面的环境变量
  - `baseUrl = http://localhost:8080`
  - `taToken = <TA 的 token>`
  - `opsToken = <OPS 的 token>`
- **VS Code REST Client 插件**：可以直接在 .http 文件里调
- **浏览器**：只能调 GET（不带 token 的容量查询）

---

## G · 停掉后端

如果 mvn 还在前台运行，按 Ctrl+C。
如果 background mode，找 java 进程：

```bash
tasklist | grep java   # 找到 PID
taskkill /PID <PID> /F
```
