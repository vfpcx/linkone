# 11 · X 生产硬化技术方案（PII 加密 / Redis ACL / Sa-Token / 日志 / Boot 升级）

> 角色：架构师 Agent ｜ 日期：2026-07-15 ｜ 状态：**方案设计稿（未实施，排期由 Team Lead 定）**
> 对应：`shared/00-roadmap.md` X·生产硬化（上线前必过）· 缺陷 D-14（`test-plan/03-defect-findings.md`）· CVE 报告（`test-plan/06-dependency-cve-scan.md`）
> 约定：本文只做设计，不改任何代码/配置。每项含：现状 → 目标方案 → 改动面 → 迁移步骤 → 回滚 → 风险 → 验证。

---

## 0. 总览与建议实施顺序

| # | 项 | 复杂度 | 可否与功能开发并行 | 建议顺序 |
|---|---|---|---|---|
| H2 | Redis 密码 + ACL | 低（纯配置） | ✅ 可并行 | **第 1 批**（快赢） |
| H4 | SQL stdout 关闭 / 日志分级 profile 化 | 低 | ✅ 可并行 | **第 1 批**（快赢） |
| H3 | Sa-Token active-timeout | 低 | ✅ 可并行 | **第 1 批**（快赢） |
| H5 | Spring Boot 3.2.5 → 3.5.x | 中（全仓依赖变更） | ❌ 需冻结窗口 | **第 2 批**（TOP1 CVE 根治，先于 PII 切读，避免双重回归） |
| H1 | 手机号 PII 加密 | 高（四阶段） | 阶段 0–2 ✅ 可并行；阶段 3 ❌ 冻结 | **第 3 批**（双写/回填可与 P2 开发并行铺开，切读进冻结窗口） |

**TOP 风险速览**（详见各节）：
1. **现有 `phone_hash` 是无盐 SHA-256**（`AccountServiceImpl` 用 `DigestUtil.sha256Hex(phone)`）——中国手机号空间仅 ~4×10⁹，普通 GPU 分钟级即可全空间碰撞还原，**该列在攻击者眼里等同明文**，且它是登录唯一键（`uk_phone_hash`）。换成 HMAC 盲索引时它同时是"登录的钥匙"，回填/切换出错 = 全体用户无法登录 → 必须双列双读过渡（§1.4）。
2. **Boot 3.2→3.5 连带 MyBatis-Plus/jsqlparser 大版本变化**，`TenantLineInnerInterceptor` 是全仓租户隔离的命门（D-02）——升级后 TN-S4 租户隔离场景必须重跑（§5.6）。
3. **pricing 唯一键 `(wholesaler_id, rt_phone, sku_id)` 要换列**，双写窗口内新旧唯一键并存，upsert 冲突路径要专门测（§1.6-R3）。
4. Redis 加密码时，dev 已知怪癖：`password:` 空串会让 Redisson 发 AUTH 空密码直接启动失败（application-dev.yml 注释已记录）——dev/prod 必须分 profile 配，不能用 `${REDIS_PASSWORD:}` 默认空串（§2.3）。

---

## 1. H1 · 手机号 PII 加密（重点）

### 1.1 现状盘点（明文分布全清单）

**数据库（来源：`backend/src/main/resources/db/migration/` V1/V2/V8/V9 + 架构稿 03-database-schema.sql）**

| 表.列 | 类型 | 用途 / 等值查询点 | 现状 |
|---|---|---|---|
| `users.phone` | VARCHAR(20) NOT NULL | 展示、`getPhoneByUserId()`（供 pricing 解析 RT 身份）、RT 登录昵称截尾 | **明文** |
| `users.phone_hash` | VARCHAR(64)，`uk_phone_hash` 唯一 | 登录/注册查重/找回/换绑/RT 免密登录 全部 `.eq(User::getPhoneHash, …)`（AccountServiceImpl:190/241/366/423/452） | **无盐 SHA-256 ≈ 明文** |
| `sms_codes.phone` | VARCHAR(20) | 验证码校验 `.eq(SmsCode::getPhone, phone)`（AccountServiceImpl:515） | 明文（短生命周期数据） |
| `tenants.contact_phone` / `tenant_applications.contact_phone` | VARCHAR(20) | 展示为主，无等值检索 | 明文 |
| `inquiry_requests.rt_phone` | VARCHAR(32) | 询价确认后传给 `pricingService.settleFromInquiry(…, rtPhone, …)` 沉淀专属价 | 明文 |
| `customer_prices.rt_phone` | VARCHAR(32)，`uk_custprice_wh_phone_sku(wholesaler_id, rt_phone, sku_id)` + `idx_custprice_phone` | 专属价 upsert / `resolvePrice` 匹配 / 批量调价过滤，全部等值 | 明文 |
| `blacklist.target_value`（P2 规划，**尚未建表**） | VARCHAR(64)，`uk_type_value` | `isBlacklisted(phone)` 等值命中 | 设计稿为明文 → **建表时直接用密文口径，不产生存量** |

**数据库之外的明文泄露面（同批治理，否则加密白做）**

| 位置 | 现状 |
|---|---|
| Redis 键 `price:match:{wholesalerId}:{rtPhone}:{skuId}`（PricingServiceImpl:496） | 键名含明文手机号 |
| 业务日志：InquiryServiceImpl:151、PricingServiceImpl:155/205 `log.info(... rtPhone ...)` | 明文入日志文件 |
| MyBatis StdOutImpl SQL 参数打印 | 所有 phone 参数明文进 stdout（→ 见 §4，一并关闭） |
| VO 回传：`InquiryVo.rtPhone`、`CustomerPriceVo` 等 | 明文回给前端（WA 侧按业务需要可见，但应在 VO 层收敛为脱敏 `138****1234`，详情页按权限放全量） |
| （已达标项）SmsUtil 日志已脱敏；登录限流/短信防刷 Redis 键已用 sha256 | — |

### 1.2 算法选型：AES-GCM（随机 IV）密文列 + HMAC-SHA256 盲索引列（推荐），否决"确定性加密单列"

三条业务约束（题设点名）：**blacklist 按手机号命中、pricing 按 rt_phone 匹配、账号按手机号登录**——全部是等值查询，方案必须支持等值。

| 候选 | 等值查询 | 安全性 | 结论 |
|---|---|---|---|
| **A. AES-256-GCM（每行随机 96-bit IV）密文列 + HMAC-SHA256(盲索引密钥) 索引列** | ✅ 走 HMAC 列 | 密文 IND-CPA；HMAC 有密钥，离线字典攻击不可行（区别于现在的无盐 SHA-256）；两个密钥分离，泄一不破全 | **✅ 推荐** |
| B. 确定性加密单列（AES-GCM 固定 IV 或 AES-SIV），密文本身当索引 | ✅ | 固定 IV 的 GCM 是**严重密码学错误**（IV 复用 → 泄 auth key）；AES-SIV 正确但 JDK 无原生实现需引 BouncyCastle，且确定性密文同样泄露相等关系，安全上并不优于 A，还把"查询键"和"解密体"耦死、无法独立轮换 | ❌ 否决 |
| C. 只哈希不加密（纯盲索引，不存原文） | ✅ | 无法还原手机号 → 发短信、客服联系、换绑展示全断 | ❌ 业务不允许 |

**方案 A 细则：**

- **密文列** `*_cipher`：`AES/GCM/NoPadding`，256-bit 数据密钥（DEK），每次加密随机 12 字节 IV，存储格式 `v{keyVer}:base64(iv ∥ ciphertext ∥ tag)`，列类型 `VARBINARY(128)` 或 `VARCHAR(128)`。AAD 留空（跨表迁移方便）。
- **盲索引列** `*_hmac`：`HMAC-SHA256(indexKey, 规范化手机号)` hex 64 字符，`VARCHAR(64)`。规范化 = 去空白、去 `+86` 前缀、纯 11 位数字（现状入参已是 11 位，规范化函数集中一处防口径漂移）。
- **展示列（可选优化）** `phone_last4 CHAR(4)`：列表页脱敏展示（`138****1234` 前 3 位对 13x 号段近乎常量，last4 足够），避免列表页批量解密。
- **密钥管理**（当前 Windows 单机部署，无 KMS）：
  - 两把 256-bit 密钥：`PII_DEK_V1`（加密）、`PII_IDX_KEY`（盲索引），**环境变量注入**（`cangchu.pii.dek.v1` / `cangchu.pii.index-key`），生产由部署脚本从受控文件读入，禁止进 git / application*.yml 明文。
  - 密文自带 `v{keyVer}` 前缀 → DEK 可轮换（新写 v2、后台任务重加密存量、期间双密钥解密）。**盲索引密钥原则上不轮换**（轮换=全量重算+索引重建，代价等同再做一次迁移），故 `PII_IDX_KEY` 保管等级最高。
  - 中期演进：接入云 KMS/信封加密时，只需替换 `PiiCryptoService` 的密钥装载层，存储格式不变。
- **实现载体**：MyBatis-Plus `TypeHandler`（如 `PhoneCipherTypeHandler`，实体字段 `@TableField(typeHandler = …)`），业务代码继续读写 `String phone`，加解密对 service 透明；盲索引由 `MetaObjectHandler` 或 service 层统一函数 `PiiCrypto.phoneIndex(phone)` 计算（等值查询处要显式用它构造条件，无法完全透明——这是本方案最大的代码触点）。**明确否决 MySQL `AES_ENCRYPT()`**：密钥会出现在 SQL 语句/审计日志中。

### 1.3 目标数据模型（逐表）

| 表 | 新增列 | 索引变更 | 最终删除 |
|---|---|---|---|
| `users` | `phone_cipher`、（`phone_last4`） | `uk_phone_hash` 的语义由"无盐 SHA-256"切为"HMAC"——**新增列 `phone_hmac` + `uk_phone_hmac`，与旧列并存过渡**，切读后删 `uk_phone_hash` | `phone`（明文）、`phone_hash`（旧 SHA-256） |
| `sms_codes` | 不加密文列，改存 `phone_hmac`（+`phone_last4` 供日志）——验证码只需等值匹配，无需还原原文；表数据短生命周期（建议顺带加定期清理任务，>24h 即删） | `idx` 若有按 phone 的查询路径改 hmac | `phone` |
| `tenants` / `tenant_applications` | `contact_phone_cipher`（无等值检索 → **不需要 hmac 列**） | 无 | `contact_phone` |
| `inquiry_requests` | `rt_phone_cipher` + `rt_phone_hmac`（settle 链路要传身份键；详情页 WA 可见全号 → 需密文列） | 无索引要求（按单据号查） | `rt_phone` |
| `customer_prices` | `rt_phone_cipher` + `rt_phone_hmac` | 新增 `uk_custprice_wh_hmac_sku(wholesaler_id, rt_phone_hmac, sku_id)`、`idx_custprice_hmac(rt_phone_hmac)`；切读后删旧唯一键/索引 | `rt_phone` |
| `blacklist`（P2 建表） | **直接按密文口径建**：`target_value_hmac`（PHONE 类型时）+ `target_value_cipher`，`uk_type_value` 建在 hmac 上；LICENSE_NO 非 PII 可保留明文分支 | — | 无存量 |

**身份键口径变更**：pricing 的"客户身份 = rt_phone 明文"整体切换为"客户身份 = rt_phone_hmac"。传递链 `AccountService.getPhoneByUserId()`（明文，AccountServiceImpl:501）→ storefront `rtPhone` 参数 → `PricingService.resolvePrice(…, rtPhone, …)` → Redis 键 `price:match:…:{rtPhone}:…`，切读阶段整链换成 hmac（新增 `getPhoneIndexByUserId()`，Redis 键变为 `price:match:{wid}:{rtPhoneHmac}:{skuId}`——**键变更即旧缓存自然失效，无需清洗，命中率短暂下降属预期**）。SubmitInquiryDto 的 `rtPhone` 入参仍收明文（RT 登录态推导），入库时转 cipher+hmac。

### 1.4 分阶段迁移（双写 → 回填 → 切读 → 清明文）

配置开关：`cangchu.pii.write-mode: legacy|dual`、`cangchu.pii.read-mode: plain|cipher`（默认 legacy/plain，逐阶段拨动；开关本身随第 1 批配置项先行合入）。

| 阶段 | 内容 | DB 动作（Flyway） | 代码行为 | 可回滚性 |
|---|---|---|---|---|
| **阶段 0 加列** | V10：全部新增列 NULLable + 新索引先建**普通索引**（唯一约束等回填去重核验后再升级为 UNIQUE，避免脏数据卡迁移） | V10__pii_add_cipher_columns.sql | 无行为变化 | 纯加列，随时可弃 |
| **阶段 1 双写** | `write-mode=dual`：insert/update 同时写 明文列 + cipher + hmac；读仍走明文/旧 hash | — | TypeHandler + 等值查询点双写 | 拨回 legacy 即回滚，新列数据留着无害 |
| **阶段 2 回填** | 后台批任务（建议独立 CommandLineRunner，`--pii.backfill=users,customer_prices,…` 触发；**不要用 Flyway Java migration**，失败重跑语义差）：分页扫明文列非空且 cipher 为空的行 → 补 cipher/hmac。完成后：①行数核验 `count(明文非空)=count(cipher非空)`；②抽样 1% 解密比对；③hmac 无重复后，V11 把普通索引升级为 UNIQUE | V11__pii_unique_index.sql | 任务幂等、可断点重跑 | 任务只写新列，可反复执行 |
| **阶段 3 切读** | `read-mode=cipher`：等值查询全部改走 hmac 列；展示走 cipher 解密/last4；pricing 身份键与 Redis 键切 hmac；日志点改脱敏 | — | **本阶段是唯一需要冻结窗口的阶段**（登录/定价主链路行为切换） | 拨回 `read-mode=plain` 秒级回滚（双写仍在，明文列持续新鲜） |
| **阶段 4 清明文** | 观察期（建议 ≥2 周、跨一次完整回归）后：V12 删明文列 + 旧 `phone_hash` 列/索引；代码删双写分支与开关 | V12__pii_drop_plaintext.sql | 删代码分支 | **不可逆**。前置条件：全库备份 + 阶段 3 零回退记录。删列前先 `RENAME` 保留一版（如 `phone__bak`）跨一个发布周期更稳 |

### 1.5 改动面（文件清单）

| 层 | 文件 | 改动 |
|---|---|---|
| 新增 | `common/crypto/PiiCryptoService.java`（AES-GCM + HMAC + 规范化 + 密钥装载）、`common/crypto/PhoneCipherTypeHandler.java`、`common/crypto/PiiProperties.java`、回填任务 `common/crypto/PiiBackfillRunner.java` | 核心新组件 |
| 实体 | `account/entity/User.java`、`SmsCode.java`、`tenant/entity/Tenant.java`、`TenantApplication.java`、`document/entity/InquiryRequest.java`、`pricing/entity/CustomerPrice.java`（+P2 的 `Blacklist.java` 直接按新口径） | 加 cipher/hmac 字段 + TypeHandler 注解 |
| 服务 | `account/service/impl/AccountServiceImpl.java`（6 处 `.eq(phoneHash)` 换 hmac、`getPhoneByUserId` 增 hmac 版）、`pricing/service/impl/PricingServiceImpl.java`（upsert 唯一键条件、`matchKey()`、批量过滤、日志脱敏）、`document/service/impl/InquiryServiceImpl.java`（落库+settle 传参+日志）、`storefront/service/impl/StoreFrontServiceImpl.java`（rtPhone→hmac 透传） | 等值查询/身份键切换 |
| VO | `document/vo/InquiryVo.java`、`pricing/vo/CustomerPriceVo.java` 等 | 列表脱敏、详情按权限全量 |
| 配置 | `application.yml`（pii 开关默认值）、prod/dev profile（密钥 env 引用） | — |
| DB | Flyway V10/V11/V12（见 §1.4） | — |
| 测试 | AccountScenarioTest/PricingServiceTest 等：加"密文态登录/定价匹配/唯一键冲突/回填幂等"用例 | — |

### 1.6 风险

- **R1（最高）**：hmac 列 = 登录钥匙。规范化口径不一致（如未来出现 +86 前缀）→ 同号双账号 or 无法登录。缓解：规范化函数唯一入口 + 阶段 2 双算核验（对全量用户断言 `HMAC(phone)==phone_hmac`）。
- **R2**：`PII_IDX_KEY` 丢失 = 全部等值查询失效（等同全库手机号索引报废）；泄露 = 盲索引退化为可字典攻击。缓解：密钥备份进离线保管 + 权限最小化。
- **R3**：双写窗口内 `customer_prices` 新旧两个唯一键并存，历史脏数据（同号不同格式）可能在 V11 升 UNIQUE 时冲突。缓解：V11 前先跑重复检测 SQL，冲突行人工归并。
- **R4**：加密后 DB 层丧失 `LIKE '138%'` 模糊检索能力（运营后台按号段查用户不可行）。属**接受的产品折衷**，检索需求走 last4 精确尾号 + 运营导出流程。
- **R5**：TypeHandler 对 H2 测试库同样生效，测试密钥需在 test profile 固定注入，否则 142+ 存量测试全挂。

### 1.7 验证

1. 单测：PiiCryptoService 加解密/规范化/密钥轮换（v1→v2）矩阵。
2. 场景回归：AC-S1~S6（注册/登录/找回/换绑全链路在 `read-mode=cipher` 下重跑）、TN-S4、pricing 全量（含 `settleFromInquiry` 沉淀 + `resolvePrice` ≤200ms 缓存命中——**hmac 计算在应用侧，纳入压测确认不吃掉 200ms 预算**，HMAC-SHA256 单次 <10µs，理论无虞）。
3. 数据核验 SQL 三件套（行数一致 / 抽样解密比对 / hmac 唯一性），归档到 shared/test-plan。
4. 泄露面复查：grep 日志文件与 Redis `KEYS price:match:*` 确认无 11 位明文手机号。

---

## 2. H2 · Redis 密码 + ACL

### 2.1 现状

- 本机 Memurai（Windows Redis 兼容发行版）`localhost:6379` **无密码**，`application-dev.yml` 显式注释"不能写 `password: ${REDIS_PASSWORD:}`，空串会让 Redisson 发 AUTH 空密码启动失败"。
- 承载内容：Sa-Token 会话（sa-token-redis-jackson）、登录限流/短信防刷计数、pricing 匹配缓存、Redisson 锁/INCR。全部经 **Redisson 单客户端**（RedisConfig.redissonCustomizer，含 pingConnectionInterval=30s 稳态定制——Bug A 修复，不得丢失）。
- CVE 报告 TOP3：无密码 + Redis Lua RCE（CVE-2025-49844）组合风险。

### 2.2 目标方案

1. **网络面**：`bind 127.0.0.1`（生产若应用与 Redis 同机）+ `protected-mode yes`。跨机部署才开内网 IP 并配防火墙白名单。
2. **认证**：`requirepass` 设强随机密码（≥32 字符）作为兜底；在其上启用 **ACL 应用账户**：
   ```
   # memurai.conf / users.acl
   user default off
   user cangchu_app on >密码 ~* &* +@all -@admin -@dangerous +info +client|setname +client|getname
   ```
   说明:Redisson 需要 `EVAL/EVALSHA`（RLock/RAtomicLong 走 Lua）、`CLIENT SETNAME`、`INFO`、pub/sub（锁续期通知 `&*`），因此给 `+@all` 再减 `-@admin -@dangerous`（含 FLUSHALL/FLUSHDB/CONFIG/SHUTDOWN/DEBUG/KEYS），比白名单逐条枚举稳（Redisson 版本升级可能引入新命令）。`~*` 键空间不设限（业务键无统一前缀，收敛键前缀属后续优化）。
3. **应用配置**：`spring.data.redis.username: cangchu_app` + `password: ${REDIS_PASSWORD}`（prod **无默认值**，缺失即启动失败=故意 fail-fast）。Redisson starter 会从 spring.data.redis 继承；`redissonCustomizer` 不动。
4. **dev/prod 差异化**：dev 保持本机无密码现状（不加 password 键）；新增 `application-prod.yml` 持有 username/password 引用。**版本核对项**：Memurai 需确认对应 Redis ≥7.2.11 补丁线（RediShell 修复），写进上线检查单。

### 2.3 改动面 / 迁移 / 回滚 / 风险 / 验证

- **改动面**：Memurai 配置文件 + Windows 服务重启；`application-prod.yml`（新增）；`application-dev.yml` 不动；无任何缓存键/代码改动（**对现有缓存键影响：无**——认证不改变键空间，数据原地保留）。
- **迁移步骤**：① 生成密码与 ACL 文件 → ② 改 memurai.conf → ③ 重启 Memurai 服务（**会话全丢 = 全员重新登录**，选低峰执行；Sa-Token timeout 28800s 本就接受重登）→ ④ 带新配置启动应用 → ⑤ 验证。
- **回滚**：注释掉 requirepass/ACL 行、重启服务、应用去掉 password 配置。分钟级。
- **风险**：R1 Redisson 对 ACL 受限账户的命令面踩线（如新版本用了被禁命令）→ 用 `-@admin -@dangerous` 的减法策略缓解，且验证步骤覆盖锁/INCR/会话三类操作；R2 密码进了进程环境变量，Windows 上注意服务账户权限；R3 重启窗口内登录限流计数清零（可接受）。
- **验证**：① `redis-cli -u redis://cangchu_app:pwd@127.0.0.1:6379 ping`；② 匿名 `redis-cli ping` 应拒绝；③ 应用启动后跑登录（Sa-Token 写会话）+ 批量调价（RLock+Lua）+ 短信防刷（RAtomicLong）三链路冒烟；④ Bug A 稳态复测：空闲 >60s 后再请求无 90001。

---

## 3. H3 · Sa-Token active-timeout（会话闲置过期）

### 3.1 现状

`application.yml`：`timeout: 28800`（8h 绝对过期）、`active-timeout: -1`（**永不因闲置过期**）、`is-concurrent: true` + `is-share: true`（多端共享同一 token）。踢出链路现用 `StpUtil.kickout(userId)`：改密（AccountServiceImpl:349 找回密码、397 修改密码）、换绑手机（437）；登出走 `StpUtil.logout`。

### 3.2 目标方案

- prod：`active-timeout: 1800`（30 分钟闲置冻结；管理后台类产品的常规值），`timeout: 28800` 保持。dev/test：保持 `-1`（避免调试/断点期间掉线、避免存量集成测试偶发翻红）→ 放 profile 差异（主配改 1800，dev.yml 覆盖回 -1；或反向。推荐前者——**默认安全**，dev 显式放松）。
- 行为核对（Sa-Token 1.38 语义，升级 1.39+ 后需复核不变）：
  - 每次经过 `SaInterceptor(checkLogin)` 的请求自动续活（`updateLastActiveTime`），闲置 >1800s 后下一次请求抛 `NotLoginException`（type=TOKEN_FREEZE）→ 全局异常处理需确认该类型同样映射 401 + 现有"登录已过期"错误码，**前端无需新增分支**（已有 401 统一跳登录）。
  - `is-share: true` 下多端共享 token → 任一端活跃即全端续活，属可接受语义；若未来要求端间独立冻结，需关 is-share（本方案不动）。
  - **踢出链路核对项**：kickout 与 active-timeout 相互独立，kickout 的"已被踢下线"(KICK_OUT) 与"已被冻结"(TOKEN_FREEZE) 是不同 NotLoginException type——验证两者的错误文案/码不串。
- 顺带项（CVE 报告 #4）：Sa-Token 若随 H5 升到 1.39+，`active-timeout` 行为有细节变化（新增 min-active-timeout 等配置），升级后以官方 changelog 复核本节配置。

### 3.3 改动面 / 迁移 / 回滚 / 风险 / 验证

- **改动面**：`application.yml`（active-timeout 值）、`application-dev.yml`（覆盖）、全局异常处理器（仅确认，无预期改动）。零表结构、零 Java 逻辑。
- **迁移**：改配置 → 重启。存量在线会话的 last-active 从重启后首次请求起算，无需清 Redis。
- **回滚**：改回 `-1` 重启，秒级。
- **风险**：R1 长表单场景（如租户入驻多页表单）用户填写 >30min 提交即 401 丢数据 → 前端已有 token 过期处理 + 表单本地暂存（确认产品可接受，否则调 3600）；R2 test profile 若未覆盖 -1，142+ 存量测试中含 sleep 的用例可能翻红。
- **验证**：test profile 写一条专测：`active-timeout: 3` + 登录 → sleep 4s → 请求断言 401/TOKEN_FREEZE；再验证 30 分钟内正常请求续活不掉线（时钟可用 Sa-Token 的 mock 时间或缩短值代替）；重跑改密/换绑场景确认 kickout 语义不回归。

---

## 4. H4 · SQL stdout 关闭 / 日志分级 profile 化

### 4.1 现状

- 主配 `application.yml`：`mybatis-plus.configuration.log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`（**所有 SQL+参数打 stdout，含手机号/密码哈希等参数明文**，且 stdout 打印绕过 logback 管控）；`logging.level.com.cangchu: DEBUG`、`com.baomidou.mybatisplus: DEBUG`；`sa-token.is-log: true`。
- `logback-spring.xml`：单一配置无 `<springProfile>` 区分，CONSOLE+FILE 双 appender，`com.cangchu` DEBUG。

### 4.2 目标方案（profile 化，dev 保留、prod 收紧）

| 项 | dev | prod |
|---|---|---|
| mybatis-plus log-impl | `StdOutImpl`（**从主配移到 dev.yml**） | 主配置为 `org.apache.ibatis.logging.nologging.NoLoggingImpl`（显式指定而非留空，杜绝默认值漂移） |
| logging.level com.cangchu | DEBUG | INFO |
| logging.level com.baomidou | DEBUG | WARN |
| sa-token is-log | true | false |
| logback | `<springProfile name="dev">`：CONSOLE(DEBUG)+FILE | `<springProfile name="prod">`：FILE(INFO) 为主，CONSOLE 仅 WARN+（Windows 服务方式运行时 stdout 本就无人看，降噪）；保留 30 天滚动 |

原则：**主配 = 生产安全默认**（与 D-03 教训一致："配置开关驱动 + prod 强制禁用"），dev 显式放开。排查生产偶发 SQL 问题的通道 = 临时把 `com.cangchu` 对应 mapper 包调 DEBUG（MyBatis 原生 slf4j 日志），不回开 StdOutImpl。

### 4.3 改动面 / 迁移 / 回滚 / 风险 / 验证

- **改动面**：`application.yml`（log-impl 改 NoLogging、level 降 INFO、is-log false）、`application-dev.yml`（加回 StdOutImpl + DEBUG + is-log true）、`logback-spring.xml`（springProfile 拆分）。零 Java。
- **迁移/回滚**：纯配置，重启生效/回退。
- **风险**：唯一实质风险是 dev 体验回退（若 dev.yml 漏配则本地看不到 SQL）——自检项写进 PR checklist。
- **验证**：dev 启动 grep stdout 有 SQL；`--spring.profiles.active=prod` 启动跑一次登录，断言 stdout/日志文件无 SQL 参数、无 DEBUG 行、无明文手机号；142+ 测试在 test profile 不受影响。

---

## 5. H5 · Spring Boot 3.2.5 → 3.5.x 升级

### 5.1 现状与动因

`pom.xml` parent 3.2.5（2024-05 发布，OSS 线 2024-12 EOL）。CVE 报告 §5 TOP1/TOP2：Tomcat 10.1.20（含在野利用的 CVE-2025-24813）、Spring FW 6.1.6、spring-security-crypto 6.2.4（CVE-2025-22228 BCrypt >72 字符）、Netty 4.1.109 —— **一次 Boot 升级全部消除**。3.3/3.4 也已/将 EOL，一步到 **3.5.x 最新 patch**。

### 5.2 兼容版本矩阵（升级时以各官方发布说明终核）

| 组件 | 现版本 | 目标 | 依据/注意 |
|---|---|---|---|
| spring-boot-starter-parent | 3.2.5 | **3.5.x 最新 patch** | 连带 Tomcat ≥10.1.45 / Spring FW 6.2.x / security-crypto ≥6.4.4 / Netty ≥4.1.125 / logback 1.5.18 / Jackson 2.19.x |
| mybatis-plus-spring-boot3-starter | 3.5.6 | **≥3.5.7（建议 3.5.x 该线最新）** | ⚠️ 3.5.7 起 jsqlparser 升 5.x 且拆分为 `mybatis-plus-jsqlparser` 独立坐标——**TenantLineInnerInterceptor / PaginationInnerInterceptor 依赖它，pom 需显式补该依赖**；升级后 TN-S4 全量重跑（见 §5.6） |
| sa-token（starter + redis-jackson） | 1.38.0 | **≥1.39.0** | SaFirewall 加固；⚠️ 新线对 redis 集成模块有重组（sa-token-redis-jackson → sa-token-redis-template 系），选型时核对目标版本的模块坐标与 Boot 3.5 兼容声明；active-timeout 行为复核（§3） |
| redisson-spring-boot-starter | 3.27.2 | **3.3x/3.4x 支持 Boot 3.5 的最新** | 保留 `redissonCustomizer` 全部稳态参数（Bug A）；升级后跑 Redis 稳态复测 |
| flyway-core/mysql | 10.11.1（属性覆盖） | **回归 BOM 管理（11.x）**，删 pom 里 `flyway.version` 覆盖 | 10→11 对既有 V1–V9 迁移历史兼容；首启动前备份 `flyway_schema_history` |
| mysql-connector-j | 8.3.0（BOM） | BOM 自带 9.x | 服务端 MySQL 9.7 兼容 |
| fastjson2 | 2.0.49 | ≥2.0.53 | 常规卫生升级，确认未开 autoType（CVE 报告已列复扫项） |
| hutool / lombok / mapstruct | 5.8.27 / 1.18.32 / 1.5.5 | 保持（lombok 若编译报错升 1.18.34+） | JDK21 OK |
| h2（test） | 2.2.224（BOM） | BOM 自带 2.3.x | ⚠️ 2.3 对部分 DDL/兼容模式更严格，测试建表 SQL 可能要微调 |
| httpclient5（test） | BOM | BOM 自动升 | T-01 修复所依赖的行为不变，AC-S4/S6 覆盖 |

### 5.3 破坏性变更核对清单（3.2 → 3.3 → 3.4 → 3.5 逐跳）

1. **配置属性迁移**：临时加 `spring-boot-properties-migrator`（runtime scope）跑一轮启动，按 WARN 清单改名后移除。已知涉及面小（本项目 yml 精简），重点看 `management.*`、`spring.data.redis.*`。
2. **Boot 3.4**：优雅停机默认开启（`server.shutdown=graceful`）——对 Windows 服务停止行为的影响确认；Bean Validation 消息插值细节变化（参数校验错误文案回归 AC-S2 用例覆盖）。
3. **Boot 3.5**：`@ConfigurationProperties` 扫描与结构化日志新特性无影响；Actuator 端点默认暴露面复核（本项目仅 starter-actuator 默认配置，升级后确认未意外暴露新端点——顺带把 prod 的 `management.endpoints.web.exposure.include` 显式收敛为 `health`）。
4. **Tomcat 10.1.42+ 行为**：multipart 新默认限制（`maxPartCount` 等，CVE-2025-48988 的加固）——本项目当前纯 JSON API 无 multipart，P3 拍照入库落地时再核。
5. **MyBatis-Plus + jsqlparser 5.x**：TenantLineInnerInterceptor 对复杂 SQL（子查询/JOIN/UNION）的解析行为可能变化——白名单表（stores/tenant_settings）与 pricing 的批量 UPDATE 语句逐条核对生成 SQL。
6. **Sa-Token 1.39+**：SaFirewall 默认拦截规则可能拦下带特殊字符的路径参数——E2E 全量过一遍即可暴露。
7. **Jackson 2.15→2.19**：`default-property-inclusion: non_null` 与日期格式行为不变，契约层由前端 Playwright + api-types 校验兜底。

### 5.4 改动面

仅 `backend/pom.xml`（parent 版本 + 4 个属性行 + 可能新增 mybatis-plus-jsqlparser 依赖 + 删 flyway 覆盖）；零业务代码预期（若编译报错按 §5.3 清单逐条处理);`mvn dependency:tree` 输出归档至 `shared/test-plan/`（CVE 报告 §6.3 要求的下次扫描基线）。

### 5.5 迁移步骤与回滚

1. 冻结窗口内单独分支执行；先 `mvn dependency:tree > tree-before.txt`。
2. 升 parent → 编译 → 按矩阵逐个升第三方 starter → 加 properties-migrator 核属性 → 全量测试。
3. 本地全链路：启动 + Flyway 迁移干跑（备份 flyway_schema_history）+ 登录/定价/询价冒烟 + E2E。
4. **回滚**：git revert 单提交即回 3.2.5。⚠️ 唯一不可逆点：**Flyway 11 首次启动会写 schema_history（校验和格式兼容但版本戳前进）**——回滚后若报 validate 失败，按备份恢复 history 表。数据本身无 schema 变更，无数据回滚问题。

### 5.6 验证（升级完成定义）

- `mvn clean verify` 142+ 后端测试全绿（含 AccountScenarioTest / TenantScenarioTest / pricing 全量）。
- **TN-S4 租户隔离场景必须单独确认全绿**（jsqlparser 大版本 = D-02 防线的直接风险点）。
- Bug A Redis 稳态复测（空闲后请求无 90001）+ RLock 批量调价并发用例。
- 前端 Playwright E2E 全绿。
- OWASP dependency-check / Trivy 复扫（CVE 报告 §6 上线门禁），确认 TOP1/TOP2 消除；`tree-after.txt` 归档。

---

## 6. 实施排期建议（供 Team Lead 决策）

```
第1批（可与 P2 功能开发完全并行，各 ≤0.5d，纯配置）
  ├─ H4 日志 profile 化（先做：立刻止住 stdout 明文 PII 泄露）
  ├─ H2 Redis 密码+ACL（低峰重启一次，全员重登）
  └─ H3 active-timeout（随 H4 同一 PR 亦可）
第2批（冻结窗口 0.5–1d，功能开发暂停合并）
  └─ H5 Boot 3.2.5→3.5.x（+ Sa-Token 1.39+/MP 3.5.x/Redisson 同批）→ 全量回归 + CVE 复扫
第3批（PII，横跨 1–2 个迭代）
  ├─ 阶段0+1 加列/双写：与功能开发并行（新列 additive，不冲突）
  ├─ 阶段2 回填+核验：后台任务，随时可跑
  ├─ 阶段3 切读：需冻结窗口 0.5d + 全量回归（建议与某次波次合并的回归合车）
  └─ 阶段4 清明文：观察 ≥2 周后独立小窗口
```

理由：H4/H2 先行成本近零且立即降低泄露面；Boot 升级先于 PII 切读，避免"新加密代码 + 新框架版本"两个变量叠在同一回归里；PII 双写/回填与功能开发天然并行（越晚做存量越大——customer_prices/inquiry_requests 行数随 P2/P3 增长，回填与去重成本线性上涨，**这是"越晚代价越大"的具体机制**）。

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-15 | 首版：H1 PII 四阶段加密方案 + H2 Redis ACL + H3 active-timeout + H4 日志分级 + H5 Boot 升级矩阵 + 排期建议 |
