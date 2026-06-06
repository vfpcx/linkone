# Cangchu Backend

仓储云 SaaS 平台后端（Spring Boot 3.2.5 + JDK 17 + MyBatis Plus + Sa-Token）。

## 状态

**WIP（未编译验证）** — Agent 在 token 耗尽前完成代码生成，本机未装 Maven，未跑 `mvn clean test`。后续启动前必须：

1. `mvn clean compile` 验证编译
2. `mvn test` 验证单元测试
3. 修复任何编译/测试问题

## 已实现模块

### Common 基础设施
- `common/config/` — MyBatis Plus / Redis / Sa-Token 配置
- `common/exception/` — `BizException` + `ErrorCode` 枚举 + `GlobalExceptionHandler`
- `common/response/R<T>` — 统一响应体
- `common/tenant/` — `TenantContext` + `TenantInterceptor`（ADR-001 多租户隔离）
- `common/util/` — `SnowflakeIdUtil`（雪花 ID）+ `SmsUtil`（MVP 阶段 mock）

### Account 账号模块（7 接口）
基于 PRD `US-COMMON-01 ~ 07`：

| 接口 | 路径 |
|---|---|
| 注册 | `POST /api/v1/account/register` |
| 登录 | `POST /api/v1/account/login` |
| 改密 | `PUT /api/v1/account/password` |
| 找回密码 | `POST /api/v1/account/password/reset` |
| 换绑手机号 | `PUT /api/v1/account/phone` |
| RT 免密登录 | `POST /api/v1/account/login/rt` |
| 退出登录 | `POST /api/v1/account/logout` |

### Tenant 租户模块（8 接口）
基于 PRD §2 + §1 OPS：

| 接口 | 路径 |
|---|---|
| TA 自助注册 | `POST /api/v1/tenant/apply` |
| OPS 审核入驻 | `POST /api/v1/admin/tenant/{id}/audit` |
| OPS 代建租户 | `POST /api/v1/admin/tenant/create` |
| 查看店铺设置 | `GET /api/v1/tenant/me` |
| 改店铺设置 | `PUT /api/v1/tenant/me` |
| 生成店铺码 | `POST /api/v1/tenant/store-qr` |
| 生成员工注册码 | `POST /api/v1/tenant/invite-code` |
| 实时容量查询 | `GET /api/v1/tenant/capacity` |

## 数据库迁移

Flyway 自动执行：
- `V1__init_account.sql` — 账号相关表（users / user_roles / sms_codes / login_sessions / password_history）
- `V2__init_tenant.sql` — 租户相关表（tenants / stores / invite_codes / capacity_publish / tenant_applications）

## 启动

```bash
# 准备
cp src/main/resources/application-dev.yml src/main/resources/application-local.yml
# 编辑 application-local.yml 改本地 MySQL/Redis 连接

# 启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## TODO（留给后续模块）

- 商品 / 价格模块（含客户专属价 + 议价沉淀）
- 库存 / 流水（FIFO + 批次开关 + 托盘）
- 单据（入库 / 出库 / 询价 / 盘点 / 退货 + 代建联动）
- 账单（异步生成 + 冲销 + 已收款登记）
- 撮合（基于位置推荐）
- 操作日志 AOP（钩子已留）

## Mock 列表

| 服务 | 实现 |
|---|---|
| 短信 | `SmsUtil` 打印控制台 + 写库 sms_codes 表 + 返回固定 code（联调期临时） |
| 地图反查 | 暂未实现，地址字段先按 (text, lng, lat, source=manual) 落库 |

## 已知风险

1. **未跑编译/测试** — 必须先 mvn test 跑通再合入下一波 Agent
2. **DTO/VO 字段** 与 04 API 规范可能有出入，需 review
3. **TenantInterceptor** 实现是否覆盖全部 Mapper 待 review
