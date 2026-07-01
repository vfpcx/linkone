# 仓储云 · 安全编码规约（写代码前自检清单）

> 编写：Team Lead · 2026-06-28
> 由来：本轮三方审查 + 24 场景测试暴露的缺陷（见 `../test-plan/03-defect-findings.md`），逆向提炼为"提前规避"规则。
> 适用：所有后端/前端开发 Agent。**每写一个接口/功能，先过这张清单；每条规约都绑定一类 S1–S9 场景用例，缺测视为不达标。**

---

## 0. 使用方式（强制 · 执行约定）

本规约对所有开发 Agent **强制生效**，执行约定如下：
1. **派发即引用**：Team Lead 派发任何后端/前端实现切片时，prompt 必须写「先读并遵守 `shared/architecture/05-secure-coding-guardrails.md`，按 §10 并发模式实现，提交前过 §12 自检卡」。
2. **开发自检**：开发任一接口前对照逐条自检；状态变更/资源扣减/限额/唯一 必须套用 §10 标准模式（红线：禁止先查后写控并发）。
3. **测试绑定**：每条规约绑定 S1–S9 场景用例（见 `../test-plan/02-scenario-test-plan.md`），缺测视为不达标。
4. **审查为闸**：Review/测试 Agent 以本清单 + §12 自检卡为验收基线，不达标项**阻塞合并**。

规约编号 G-x 与缺陷 D-xx、场景 Sx 对应。

---

## 1. 鉴权与会话（对应 D-01 · S4）

- **G-1.1 新接口必须显式声明鉴权归属**：要么纳入 `SaInterceptor` 的 `addPathPatterns`，要么方法级 `@SaCheckLogin`/`@SaCheckRole`。**禁止**依赖 Controller 内 `getLoginIdAsLong()` 抛异常做唯一鉴权。
- **G-1.2 改 path 前缀必同步拦截器**：任何新增/改动的 URL 前缀，必须同步更新拦截器 include/exclude，并用**真实路径**（禁止写规划中但未实现的前缀，如 `/public/*`）。
- **G-1.3 越权必测**：每个"需登录/限角色"接口必配 S4 用例——无 token、伪造/过期 token、错误角色 token，各断言被拒（41001/42xxx）。

## 2. 多租户数据隔离（对应 D-02 · S4）

- **G-2.1 tenantId 只信登录态**：租户上下文以登录用户角色记录推导为唯一可信来源；`X-Tenant-Id` 请求头仅用于已授权的多租户切换，且必须校验该用户确属目标租户。
- **G-2.2 全局过滤兜底**：数据访问层启用 `TenantLineInnerInterceptor`（SELECT/UPDATE/DELETE 自动加 tenant 条件），不把隔离寄托于"每次手写 `eq(tenantId)`"。
- **G-2.3 跨租户必测**：每个含租户数据的接口配 S4 用例——A 租户用户尝试读/写 B 租户数据，断言隔离（50210/拒绝）。

## 3. 输入校验（对应 D-07/D-08 · S2）

- **G-3.1 全字段校验**：所有外部输入做 格式/范围/枚举/必填 校验（`@Valid` + Bean Validation + 自定义校验器）。枚举字段（role 等）必须白名单；数值（经纬度/金额/数量）必须范围；字符串必须长度上限。
- **G-3.2 非法输入必测**：每个写接口配 S2 用例——枚举外值、越界值、缺必填、超长串，断言对应 40x 错误码（不得返回 0）。

## 4. 状态机（对应 D-05 · S5）

- **G-4.1 状态变更前置校验**：任何状态流转前先校验当前状态是否允许该转移（审核/出入库/账单等）；非法转移返回明确错误码。集中维护"合法转移表"，禁止散落 if。
- **G-4.2 非法转移必测**：每个状态机接口配 S5 用例——重复执行（已通过再审核）、逆向操作（已驳回再确认）、跳步（未审核就改设置），断言被拒。

## 5. 幂等与唯一性（对应 D-06/D-09 · S6）

- **G-5.1 唯一性双层防护**：唯一字段（手机号/仓库名/店铺码/账单幂等键）在 **DB 唯一索引** + 应用层校验 双保险；捕获唯一约束冲突转语义化错误码。
- **G-5.2 幂等必测**：重复提交、重复注册、重复下发，配 S6 用例断言幂等（拒绝或返回同一结果，不产生重复数据）。

## 6. 限流与防爆破（对应 D-04/D-09 · S3/S7）

- **G-6.1 计数走 Redisson 原子操作**：登录失败计数+锁定、短信防重+日限、找回密码频率，统一用 Redisson 原子计数+TTL，**禁止**"先 select 再 insert"的 DB 计数（TOCTOU 竞态）。定义的阈值常量必须真正生效（杜绝写死假文案）。
- **G-6.2 多维度限流**：敏感接口按 手机号 + IP 双维度限流；IP 取值正确处理 `X-Forwarded-For`。
- **G-6.3 边界必测**：配 S3 用例——第 N 次触发锁定、锁定后再试提示剩余时间；并发场景配 S7 用例（Java21 虚拟线程）验证计数不被击穿。

## 7. 配置与密钥安全（对应 D-03/D-14/D-15）

- **G-7.1 mock/测试桩仅 dev**：验证码 `888888`、固定坐标等 mock 必须 `@Profile("dev")` 或显式环境判断隔离；prod 配置强制关闭；主 `application.yml` 不得开 mock。
- **G-7.2 敏感配置全外置**：DB/Redis 密码等用 `${ENV:默认}` 注入，默认值不含真实凭据；所有环境 Redis 设密码。参数化要对称（host/port/db/password 一起）。
- **G-7.3 不留失效配置**：被替换的配置块（如 Redisson 接管后的 `lettuce.pool`）删除或注释说明，避免后人误改。

## 8. 输出与错误处理（对应 D-10 · S2）

- **G-8.1 防账号枚举**：登录失败统一返回"账号或密码错误"，不区分"手机号未注册"与"密码错"；找回密码不暴露账号是否存在。
- **G-8.2 不泄露内部信息**：未知异常统一兜底（如 90001），堆栈仅入日志不返回客户端；prod 关闭 SQL stdout、降日志级别，避免手机号/参数入日志。

## 9. 契约先行（对应 D-11/D-12/D-13）

- **G-9.1 契约即真源**：改字段名/路由/错误码，先更新 `api-contract-account.md` 等契约文档 + 前后端共享类型（api-types），**再**写实现；前后端字段名/路由/错误码以契约为准（本轮 `roleList→roles`、路由表对齐即教训）。
- **G-9.2 规则唯一**：同一规则（如密码 6–20 位）在文档、后端校验、前端校验三处一致，禁止各写一套。
- **G-9.3 时间带时区**：对外时间字段统一带时区偏移（`OffsetDateTime`/带 zone 序列化）。

---

## 10. 并发与一致性 · 标准实现模式（强制，按场景照抄）

> 任何"读-改-写"或"状态流转/限额/唯一"操作，必须套用下表对应模式。**红线：禁止用「先 SELECT 判断、再 INSERT/UPDATE」来保证 唯一性/限额/状态唯一流转**——这是 TOCTOU 竞态，并发必穿。

| 场景 | 标准手段 | 本仓范例 |
|---|---|---|
| 共享数值资源扣减（库存/额度），需读-改-写 | Redisson 分布式锁（**先锁后事务 + self 代理**） | `InventoryServiceImpl.deductStock` |
| 单据/记录状态流转（防重复提交/双击） | **条件 UPDATE CAS**：`SET status=新 WHERE id=? AND status=旧`，校验 affected==1 | `confirmByWa` 应改造为此式 |
| 计数/配额（失败次数/日限/注册码用量） | Redisson 原子计数+TTL 或 DB 条件自增 CAS | 登录锁定 / 短信防刷 / `consumeInviteForRegister` |
| 全局序列号（单据号） | Redis INCR + DB 唯一索引兜底 | `DocumentNumberService` |
| 唯一约束（手机号/名称） | DB 唯一索引 + 捕获 `DuplicateKeyException`→语义码 | wholesalers `uk_tenant_id_name` |

**P1 资源扣减（库存型）** — 先获锁、再开事务、self 代理（`this` 自调用会使 `@Transactional` 失效）：
```java
RLock lock = redisson.getLock("lock:inv:"+wid+":"+skuId);
if (!lock.tryLock(WAIT, LEASE, SECONDS)) throw biz(SYSTEM_BUSY);
try { self.doDeductInTx(ctx); }          // self = @Lazy 自注入代理
finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }  // 事务提交后才释放
```

**P2 状态流转防重复提交** — 条件 UPDATE CAS（`confirmByWa`/审核/任何"只能成功一次"的流转都按此）：
```java
int n = mapper.update(null, Wrappers.<Inquiry>lambdaUpdate()
    .set(Inquiry::getStatus, CONFIRMED)
    .eq(Inquiry::getId, id)
    .eq(Inquiry::getStatus, PENDING));   // 旧态作为 CAS 条件
if (n != 1) throw biz(STATE_CONFLICT);   // 并发只有一个赢, 其余拒绝
// n==1 后再做后续(建出库单/扣库存), 全在同一事务, 失败整体回滚
```

**P3 配额/计数 CAS** — 条件自增、用尽即拒：
```java
int n = mapper.update(null, lambdaUpdate()
    .setSql("used_count = used_count + 1")
    .eq(InviteCode::getId, id)
    .lt(InviteCode::getUsedCount, maxUses));
if (n != 1) throw biz(EXHAUSTED);
```

**P4 单据号**：`redisson.getAtomicLong("docno:"+type+":"+code+":"+yyyymmdd).incrementAndGet()` + 表 `doc_no` 唯一索引；冲突转 `DOC_NO_GENERATE_FAILED`。

**P5 唯一性**：建表唯一索引 + insert 捕获 `DuplicateKeyException` → 语义错误码（**不靠**应用层先查）。

**事务边界**：跨多写的业务（确认→建单→扣库存）必须**单事务**，任一步失败整体回滚；分布式锁要包住整个提交窗口（先锁后事务，提交后释放）。

---

## 11. 迁移与环境 · 红线
- **MySQL 迁移**：禁用 `CREATE INDEX IF NOT EXISTS`（MySQL 不支持，H2 能过→部署才炸，V7 踩坑）；索引**内联进 CREATE TABLE 的 `KEY` 子句**，确保 H2(测试)+MySQL(运行) 双兼容。新表索引名加表前缀（H2 约束名全局唯一）。
- **测试(JDK21)**：surefire `argLine` 需含 `-XX:+EnableDynamicAgentLoading -Djdk.attach.allowAttachSelf=true`（已在 pom，勿删）。
- mock 验证码/密钥/日志 见 §7。

---

## 12. 接口提交前自检卡（贴进 PR 模板）

```
[ ] 鉴权：接口已纳入拦截器或注解；越权(S4)用例已写
[ ] 租户：tenantId 来自登录态；跨租户(S4)用例已写
[ ] 校验：枚举/范围/必填已校验；非法输入(S2)用例已写
[ ] 状态机：非法转移已拒绝；S5 用例已写
[ ] 并发：读-改-写资源加锁(先锁后事务+self代理)；状态流转用条件CAS(校验affected==1)；不用先查后写控并发；S7 用例已写
[ ] 唯一/幂等：DB 唯一索引 + 捕获DuplicateKey；重复提交不产生重复数据；S6 用例已写
[ ] 限流：敏感接口 Redisson 计数+TTL；S3 用例已写
[ ] 迁移：无 CREATE INDEX IF NOT EXISTS；索引内联 KEY；H2+MySQL 双兼容
[ ] 配置：无 mock 泄漏到 prod；密钥外置
[ ] 错误：防枚举 + 不泄露堆栈
[ ] 契约：文档 + 前后端类型已同步
```

---

## 13. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-28 | 首版：由 15 缺陷逆向提炼 9 类编码规约 + 提交自检卡 |
| v2 | 2026-06-30 | 补 §10 并发与一致性标准实现模式(P1-P5 可照抄)、§11 迁移/环境红线；自检卡加 并发/迁移 项 |
