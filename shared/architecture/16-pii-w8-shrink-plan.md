# 16 · PII-W8 实施拆解（阶段 2 明文收缩，唯一不可逆段）

> 角色：架构师 Agent（arch-w8）｜ 日期：2026-09-01｜ 基线：main=89c6f82，后端 49 测试类全绿
> 真源：`15-pii-hardening-v2.md`（§4 阶段2 / §5.1）+ `task_plan.md`「PII-S2 收口（PII-W8）」+ 产品决策 `shared/product/w8-pii-s2-decisions.md`（v1）
> 性质：实施拆解文档（**不写 SQL 文件、不改代码、不跑迁移**），供 Team Lead 派发后端/前端开发 Agent
> 关键依赖：**cipher 密文列当前不存在**（见 §1.0），本拆解把「补 cipher」纳入 W8 前置必做项

---

## 0. 一页结论（给 Team Lead 的裁决摘要）

| # | 问题 | 现状核实 | 裁决 |
|---|------|---------|------|
| 0.1 | 15 号设计是「cipher 密文列 + hmac 盲索引」双列，代码实际只有 hmac 列 | 迁移 V27/V30 只加 5 个 `*_hmac` 列；`PiiCrypto` 只有 `phoneHmac`，**没有 AES-GCM**；`PiiRevealService` 注释写"W8 改 cipher 解密"但当前直接读明文列 | **必须补 cipher 列 + AES-GCM，作为 W8 前置步骤（V31 加列 → 双写 → 回填 → 闸门）**。不补 cipher 则 V29 收缩后查全号（reveal/C5/staff 全号）永久失去数据来源，方案不可行（详见 §1.0/§1.6） |
| 0.2 | 设计里的 V28「hmac 唯一索引升级」从未执行 | V27 注释明说"留待 V28"，但不存在 V28；现有 hmac 索引都是普通索引 | W8 必须补上「hmac 唯一索引 + 去重闸门」（V32），否则 V34 drop `uk_phone_hash` 后登录命门失去唯一性保障 |
| 0.3 | D1 自检：`revealInquiry` 用 `selectById` 而非已有的 `selectInquiryIgnoreTenant` | `inquiry_requests` 在 TenantLine 白名单（`MybatisPlusConfig` L42）；`PiiRevealService` L122 直接 `selectById`，L150 的 `selectInquiryIgnoreTenant` 是死代码 | **W7 遗留缺陷，W8 必改**：`revealInquiry` 一行改为 `selectInquiryIgnoreTenant(id)`（与 `revealWaApplication` 同款语义），否则带 TenantContext 的 WA/WE 查全号会被 TenantLine 注入过滤成 404 |
| 0.4 | D3 员工保留全号（显式例外） | `WholesalerEmployeeServiceImpl` 约 L201 `.phone(user.getPhone())` 直读明文 | 收缩后由 `users.phone_cipher` 解密供给 `WholesalerEmployeeVo.phone`（§1.6 / §3.5），并在 guardrails 登记该例外 |
| 0.5 | D4 纯前端（ta/Pricing.vue 移除客户端打码过滤残留——`fetchCpPage` 手机号子串 includes + `maskPhone(kw)` 分支；保留 SKU 名过滤与展示列，产品决策 v2） | 无后端检索端点 | 无后端增量，仅前端 |

**迁移编号方案（设计 V29 → 实际 V31~V34）**：当前 Flyway 最大版本 = V30，V28/V29 已被跳过不可插号，因此 W8 新增四个迁移：

| 实际文件 | 设计对应 | 内容 | 可逆性 |
|---------|---------|------|--------|
| `V31__pii_add_cipher_columns.sql` | 15 §4 阶段0「cipher 列」+ §4 阶段2-1 | 补 cipher / last4 列（全部 NULL，§1.6；**含 `customer_prices.rt_phone_last4`，产品决策 v3 并入**） | 可逆（加列） |
| `V32__pii_unique_hmac_indexes.sql` | 15 §4 V28（跳过项） | hmac 索引升 UNIQUE + 去重闸门（§1.4.1） | 可逆（删索引） |
| `V33__pii_shrink_rename.sql` | 15 §4 阶段2-2「窗口1」 | 明文列 RENAME 为 `*__bak`（§1.3）；**`rt_phone_last4` 保留不 rename** | 秒级可逆（DDL） |
| `V34__pii_shrink_drop.sql` | 15 §4 阶段2-3「窗口2」 | DROP `*__bak` + 旧唯一键 + blacklist PHONE 行改写 last4 摘要（§1.4/§1.5）；**保留 `rt_phone_last4`** | **不可逆**（备份还原） |

> 产品决策 v3：`customer_prices.rt_phone_last4` 并入 V31（V31 未上线、改列成本最低），V33/V34 保持窗口语义，V34 保留该摘要列不 drop（§1.6.1）。

---

## 1. V29 明文收缩脚本设计

### 1.0 关键前提：cipher 列必须补（Team Lead 实测偏差的裁决）

**事实**（已核实）：
- `V27__pii_add_hmac_columns.sql`：`users.phone_hmac` + `idx_users_phone_hmac`；`blacklist.target_value_hmac` + `idx_blacklist_type_hmac`。
- `V30__pii_add_hmac_columns_pricing_sms.sql`：`customer_prices.rt_phone_hmac`、`sms_codes.phone_hmac`、`inquiry_requests.rt_phone_hmac` 及其索引。
- 全后端 `grep cipher` 仅命中 `PiiRevealService` 注释；`PiiCrypto` 只有 HMAC（`phoneHmac`），无 AES-GCM；实体无任何 `*Cipher` 字段。
- `PiiRevealService` 四类 biz 全部 `getXxx()` 直读明文列。

**后果**：V34 drop 明文列后，查全号（`PII-REVEAL` 四 biz、C5 取号链 `AccountService.getPhoneByUserId`、D3 员工全号列表）将无数据来源。hmac 是单向盲索引，**不能**从 hmac 反推全号。

**方案裁定**（Option A，推荐）：**补 cipher 列 + AES-GCM，走「V31 加列 → 双写 → 回填 → 闸门」前置，再进 V33/V34 收缩**。
- 影响面：1 个加列迁移 + `PiiCrypto` 增加 `encrypt/decrypt`（+KAT）+ 7 张表写切点扩写 cipher + 回填逻辑扩写 cipher + reveal/C5/staff 改解密。
- 工作量评估：后端 1 个专职任务（约等于 PII-W1 双写量的 60%），因 AES-GCM 比 HMAC 实现简单、无历史数据量（开发库）。
- 理由：这是唯一能同时满足「明文收缩」与「查全号可用」的路径；收缩是唯一不可逆段，错过此窗口 cipher 永远补不上。

**否决项**：Option B「不补 cipher、查全号改读 hmac+兜底库」——hmac 不可逆，无兜底库存在，等于永久阉割 reveal/C5/staff；Option C「保留部分明文」——违背 W8 目标，且明文残留违反 05-guardrails G-8。

### 1.1 现状核对（建表 SQL 真源）

| 表.列 | 来源 | 定义 | 现状唯一键/索引 |
|-------|------|------|----------------|
| `users.phone` | V1 L6 | `VARCHAR(20) NOT NULL` | — |
| `users.phone_hash` | V1 L7 | `VARCHAR(64) NOT NULL` | `uk_phone_hash(phone_hash)`（V1 L20，登录命门） |
| `sms_codes.phone` | V1 L43 | `VARCHAR(20) NOT NULL` | —（按 phone 取最近一条） |
| `tenants.contact_phone` | V2 L12 | `VARCHAR(20) NOT NULL` | — |
| `tenant_applications.contact_phone` | V2 L104 | `VARCHAR(20) NOT NULL` | — |
| `inquiry_requests.rt_phone` | V8 L11 | `VARCHAR(32) NOT NULL` | —（有 `idx_inq_req_*` 但非 phone） |
| `customer_prices.rt_phone` | V9 L9 | `VARCHAR(32) NOT NULL` | `uk_custprice_wh_phone_sku(wholesaler_id, rt_phone, sku_id)`（V9 L19）、`idx_custprice_phone(rt_phone)`（V9 L21） |
| `wholesaler_applications.contact_phone` | V10 L13 | `VARCHAR(20) NULL` | — |
| `blacklist.target_value` | V10 L33 | `VARCHAR(64) NOT NULL` | `uk_blacklist_type_value(target_type, target_value)`（V10 L41，B2 兜底） |

已具备的 hmac 盲索引列（V27/V30，普通索引）：`users.phone_hmac`、`blacklist.target_value_hmac`、`customer_prices.rt_phone_hmac`、`sms_codes.phone_hmac`、`inquiry_requests.rt_phone_hmac`。

### 1.2 迁移编号与顺序（两窗口）

```
V31 补列（cipher/last4 + customer_prices.rt_phone_last4，§1.6/§1.6.1）→ 代码：写切点扩写 cipher + 回填扩写 cipher/last4（B3 顺带 last4 写点）
V32 唯一索引升级（§1.4.1，去重闸门）      → 代码：无关
──────────────────────────────────── 窗口 1（可逆段）────────────────────────────
V33 RENAME 明文列 → *___bak（§1.3）      → 代码：W8 主改动同批发布（entity 删明文字段、读改 hmac/cipher、reveal 改解密）
──────────────────────────────────── 观察期（闸门，§6.1）────────────────────────
V34 DROP *___bak + 旧唯一键 + blacklist 改写（§1.4/§1.5）  → 代码：无（纯 DDL + UPDATE）
```

**两窗口关键规则**：V33 与 W8 代码**同批发布**；V34 必须在观察期闸门全部通过后**单独发布**（跨一个发布周期），保证 V33 回滚时不影响线上（§6.2）。

### 1.3 窗口 1：RENAME 列清单（V33）

**共 8 列改名，blacklist.target_value 不 rename（保留供 LICENSE_NO，见 §1.5）**：

| # | 表.列 | 改名后 | 随迁索引/约束 |
|---|-------|--------|--------------|
| 1 | `users.phone` | `users.phone__bak` | — |
| 2 | `users.phone_hash` | `users.phone_hash__bak` | `uk_phone_hash` 在 rename 前已 drop（§1.3 设计要点 4） |
| 3 | `sms_codes.phone` | `sms_codes.phone__bak` | — |
| 4 | `tenants.contact_phone` | `tenants.contact_phone__bak` | — |
| 5 | `tenant_applications.contact_phone` | `tenant_applications.contact_phone__bak` | — |
| 6 | `wholesaler_applications.contact_phone` | `wholesaler_applications.contact_phone__bak` | — |
| 7 | `inquiry_requests.rt_phone` | `inquiry_requests.rt_phone__bak` | — |
| 8 | `customer_prices.rt_phone` | `customer_prices.rt_phone__bak` | `uk_custprice_wh_phone_sku`、`idx_custprice_phone` 在 rename 前已 drop（§1.3 设计要点 4） |

设计要点：
- RENAME 用 `ALTER TABLE ... RENAME COLUMN old TO new`（MySQL 8）；**H2 测试兼容性**需在首个 PR 验证（H2 支持 `ALTER COLUMN ... RENAME TO`，MySQL 模式语法兼容以实测为准，§7-R6）。
- 不改任何列类型/默认值，`__bak` 后缀统一，便于 grep 和人工识别。
- 本窗口**不删任何数据**，是回滚锚点（§6.2）。
- **V33 rename 前顺带 DROP 3 个旧索引**：`uk_phone_hash`、`uk_custprice_wh_phone_sku`、`idx_custprice_phone`（置于 rename 语句之前）——三者自 V32（`uk_phone_hmac`/`uk_custprice_wh_hmac_sku`）起已冗余。索引 drop 是可重建 DDL、不删数据，窗口1 可逆性不变（§5.2 回滚补重建）。
- **V33/V34 采用 Flyway database-specific 拆分**（B3 实测 R7，9/1 已裁决）：`V33__pii_shrink_rename__mysql.sql`/`V33__pii_shrink_rename__h2.sql`、`V34__pii_shrink_drop__mysql.sql`/`V34__pii_shrink_drop__h2.sql`，两库各自自然方言、**目标终态完全一致**。根因：① H2 对 V1/V9 内联 `UNIQUE KEY` 的支撑索引名是**自动生成**（实测 `UK_PHONE_HASH_INDEX_3`/`UK_CUSTPRICE_WH_PHONE_SKU_INDEX_6`），MySQL 则沿用声明名——`DROP INDEX uk_phone_hash` 在 H2 从 V1 时代起就不存在，移到 rename 前同样 not found；H2 需 `DROP CONSTRAINT`（声明名，实测通过），MySQL 需 `DROP INDEX`，单文件无交集语法；② H2 不支持 MySQL `UPDATE ... JOIN ... SET`，V34 blacklist 改写（§1.5）H2 版需等价写法。普通索引 `idx_custprice_phone` 两库均保留声明名，可直接 drop。

### 1.4 窗口 2：DROP 列 + 约束清单（V34）

**DROP 8 个 `*__bak` 列**（3 个旧索引已在 V33 rename 前 drop，§1.3 设计要点 4；V33/V34 为 Flyway database-specific 拆分，MySQL/H2 变体终态一致）：

| # | DROP 对象 | 前置条件（必须已存在） |
|---|-----------|----------------------|
| 1 | `users.phone__bak`、`users.phone_hash__bak` | `uk_phone_hmac`（V32 已建） |
| 2 | `sms_codes.phone__bak` | — |
| 3 | `tenants.contact_phone__bak` | — |
| 4 | `tenant_applications.contact_phone__bak` | — |
| 5 | `wholesaler_applications.contact_phone__bak` | — |
| 6 | `inquiry_requests.rt_phone__bak` | — |
| 7 | `customer_prices.rt_phone__bak`；**保留 `rt_phone_last4` 不 drop**（产品决策 v3） | `uk_custprice_wh_hmac_sku`（V32 已建） |
| 8 | blacklist：**不 drop 列**，原地改写 PHONE 行 `target_value`（§1.5） | `uk_blacklist_type_hmac`（V32 已建） |

#### 1.4.1 前置：hmac 唯一索引升级（V32，设计 V28 的补做项）

```sql
-- users：登录命门（原 uk_phone_hash 的唯一性由 uk_phone_hmac 承接）
DROP INDEX idx_users_phone_hmac ON users;
CREATE UNIQUE INDEX uk_phone_hmac ON users(phone_hmac);
-- blacklist：PHONE 行物理唯一（LICENSE_NO 行 hmac=NULL，MySQL 唯一键允许多 NULL）
DROP INDEX idx_blacklist_type_hmac ON blacklist;
CREATE UNIQUE INDEX uk_blacklist_type_hmac ON blacklist(target_type, target_value_hmac);
-- customer_prices：定价身份唯一（承接 uk_custprice_wh_phone_sku）
DROP INDEX idx_customer_prices_ws_hmac_sku ON customer_prices;
CREATE UNIQUE INDEX uk_custprice_wh_hmac_sku ON customer_prices(wholesaler_id, rt_phone_hmac, sku_id);
```

**去重闸门（V32 执行前必须通过，对应 15 §4 V28「R1/R3 失败=不升 UNIQUE」）**：
- `users`：`SELECT phone_hmac, COUNT(*) FROM users GROUP BY phone_hmac HAVING COUNT(*)>1` → 必须 0 行（R1 规范化漂移风险：同号不同空白格式会 sha256 不同但 hmac 相同）。
- `customer_prices`：按 `(wholesaler_id, rt_phone_hmac, sku_id)` 分组 → 必须无重复组（R3 脏数据）。
- `blacklist`：`(target_type, target_value_hmac)` 分组 → PHONE 行必须无重复。
- 闸门不过 → **停 V32**，先修数据再升（迁移脚本里以 `INSERT ... SELECT` 探活或部署脚本先跑检查 SQL）。

### 1.5 blacklist PHONE 行改写 last4 摘要设计（V34 内 UPDATE，不写 SQL 文件）

**格式（明确）**：PHONE 行 `target_value` 改写为 `PHONE_****{last4}`，例如 `PHONE_****1234`。

**唯一冲突消歧**：`uk_blacklist_type_value(target_type, target_value)` 仍保留（仅服务 LICENSE_NO 查重，见下）。改写后若两个不同 PHONE 行 last4 相同会撞唯一键 → 对撞行追加 hmac 尾 4 位消歧：`PHONE_****{last4}:{RIGHT(target_value_hmac,4)}`，例如 `PHONE_****1234:a1b2`（hmac 尾 4 位不可反推原文，仅作唯一后缀）。

**改写算法（V34 内两条 UPDATE，顺序固定；MySQL 版用 `UPDATE ... JOIN`，H2 版用等价子查询计数写法，终态一致）**：
1. 先改「last4 无冲突」的 PHONE 行：`target_value = CONCAT('PHONE_****', RIGHT(target_value, 4))`（对 `(PHONE, last4)` 分组 COUNT=1 的行）。
2. 再改冲突行：追加 `:{RIGHT(target_value_hmac, 4)}`（`target_value_hmac` 在 V31/V32 阶段已全量回填，V34 时可用）。
3. 不改 LICENSE_NO 行（`target_type='LICENSE_NO'` 原样保留，不与 `PHONE_****` 前缀冲突）。

**对 LICENSE_NO 的隔离**：
- LICENSE_NO 行 hmac=NULL、target_value 保持执照号原文 → `uk_blacklist_type_value` 继续承载 LICENSE_NO 的「插入撞唯一键 → 50310 兜底」语义（`BlacklistServiceImpl.add` 的并发兜底，W5 已测）。
- 唯一键仅对 PHONE 行的影响：改写后 PHONE 行的 target_value 是 last4 摘要（含消歧），互不冲突、也不与 LICENSE_NO 撞（前缀不同）→ **该键保留，仅服务 LICENSE_NO**，与 15 §2-1 口径一致。

**改写后的行为变化（代码侧，见 §3.5）**：
- 黑名单列表（`page`）PHONE 行不再需要 `SmsUtil.maskPhone` 打码（`target_value` 已是摘要）；`page` 中 `eq(target_value, kw)` 的 11 位精确分支删除（只留 hmac 精确 + `RIGHT(target_value,4)` 尾号 + LICENSE_NO LIKE）。
- B2 新增/复活 PHONE 行时，`setTargetValue` 也按摘要格式落库（与存量格式一致），并**无条件**写 `target_value_hmac` + `target_value_cipher`（不再受 `isDualWrite()` 开关限制）。
- B4 日志脱敏 `maskIfPhone` 对 PHONE 行退化为原样输出（已是摘要）。

### 1.6 cipher / last4 补列设计（V31）

**列清单（全部 `VARCHAR` NULL，回填后非空）**：

| 表.列 | 类型 | 依据（全号消费点） |
|-------|------|-------------------|
| `users.phone_cipher` | `VARCHAR(256)` | C5 取号链 `getPhoneByUserId`、`UserService.getPhone`、改绑 `verifySmsCode`、D3 员工全号列表 |
| `users.phone_last4` | `VARCHAR(4)` | 列表/日志免解密展示 |
| `sms_codes.phone_last4` | `VARCHAR(4)` | 发码排障日志（无全号消费点，不加 cipher） |
| `tenants.contact_phone_cipher` | `VARCHAR(256)` | reveal TENANT |
| `tenant_applications.contact_phone_cipher` | `VARCHAR(256)` | **可选（无当前消费点，写后不读）**——建议加，成本一行回填，保住唯一不可逆段后的可恢复性；最小化方案可省 |
| `wholesaler_applications.contact_phone_cipher` | `VARCHAR(256)` | reveal WA_APPLICATION |
| `inquiry_requests.rt_phone_cipher` | `VARCHAR(256)` | reveal INQUIRY + C2 settle 透传（`InquiryServiceImpl` L290-294 读 `rt_phone`） |
| `blacklist.target_value_cipher` | `VARCHAR(256)` | reveal BLACKLIST（PHONE 行） |
| ~~`customer_prices.rt_phone_cipher`~~ | — | **不加 cipher**：身份键走 hmac，无全号消费点（C5 解密后经 redisKeyPart 派生 hmac，不落库） |
| `customer_prices.rt_phone_last4` | `VARCHAR(4)` | **D4 列表打码展示**（`toVo` 输出 `****1234`，§1.6.1）；V31 并入（产品决策 v3） |

说明：
- AES-GCM 密文长度：12B IV + 11B 明文 + 16B tag ≈ 39B，Base64 ≈ 52 字符，`VARCHAR(256)` 余量充足。
- 展示类打码（`AdminTenantItemVo`、`WholesalerApplicationVo`、黑名单列表）一律改为「cipher 解密 → `maskPhone`」或「读 last4 列」，二选一；**建议展示列表读 last4 列，单对象 reveal 走 cipher 解密**。
- 密钥：新增 `cangchu.pii.dek-v1`（Base64 256-bit，prod 走 `${PII_DEK_V1}`，无默认值 fail-fast + 加解密 KAT，复刻 `hmac-key/hmac-kat` 先例）。

**回填闸门（进 V34 前）**：cipher 回填完成率 100%（`phone_cipher IS NOT NULL` 计数 == 明文行数）；抽样 N 行解密比对 == 原明文。

### 1.6.1 customer_prices.rt_phone_last4 补列（B3 卡点裁决，产品决策 v3）

**背景**：D4 要求 `ta/Pricing.vue` 列表 `cp.rtPhone` 打码展示保持（Pricing.vue:647-651），`PricingServiceImpl.toVo` 现用 `SmsUtil.maskPhone(cp.getRtPhone())`；V34 删 `rt_phone` 后无数据源（hmac 不可逆、无 cipher）。

**裁决**：
- **列归属：并入 V31**（产品决策 v3 + 本文档裁决采纳）——V31 未上线、改列成本最低；保持 V33 窗口1 rename-only 纯度。`customer_prices.rt_phone_last4 VARCHAR(4) NULL`，V33 rename 前从明文 `rt_phone` 回填 `RIGHT(4)`。
- **实施分派**：B1 返工仅一行——V31 SQL 加列 + 注释（dev-be-w8，5 分钟量级，B1 测试重跑确认绿）；**写点/实体/回填/reconcile 的 last4 由 B3 顺带**——B3 本来就重写 `PricingServiceImpl`/`CustomerPrice`（删 rtPhone 字段），同批加 `rtPhoneLast4` 字段与写点最自然，避免两处同改一文件产生冲突；8.1 闸门在 B3 交付时把关。
- **PII 口径**：last4 尾号非全号，与 `users.phone_last4`/`sms_codes.phone_last4`（V31 已落地）同口径，不踩 G-8；V34 后 `rt_phone__bak` 已删，last4 是 customer_prices 唯一残留信息，泄露面=尾号 4 位，与全站打码口径一致。
- **展示形态**：`****1234` 是**唯一可行解**——138 前缀在 V34 后无任何数据来源（明文已删、hmac 不可逆、无 cipher）；若产品坚持 `138****1234` 只能补 cipher 列（违背「无全号消费点」裁决），不建议。与黑名单 `PHONE_****1234` 口径统一。
- **V34 保留** `rt_phone_last4` 摘要列不 drop（产品决策 v3）。

**代码/测试影响（B3 范围）**：
- `CustomerPrice` 实体：删 `rtPhone`；增 `rtPhoneLast4`（`@JsonIgnore`）；保留 `rtPhoneHmac`。
- `PricingServiceImpl`：C1/C2/C3 读改 hmac、缓存失效键改 hmac、写切点 setRtPhoneLast4；`toVo` 输出 `"****" + rtPhoneLast4`（不再 `maskPhone(getRtPhone())`）。
- `reconcile()`：`customerPricesColumn()` 带 last4 函数（hasLast4 维度已支持），八表 clean 覆盖 last4。
- 8.1 回填闸门：`customer_prices.rt_phone_last4 IS NOT NULL == 明文行数`。
- 8.2 R4 grep：`rt_phone` 子串会命中 `rt_phone_last4`，允许例外「`rtPhoneLast4`/`rt_phone_last4` 仅用于 VO 打码展示 + 实体注解」（§6.1 R4）。

---

## 2. 代码删除/保留/改造清单

### 2.1 `PiiProperties` 字段处置

| 字段 | 现状 | 处置 |
|------|------|------|
| `hmacKey` / `hmacKat` | 保留 | **保留**（盲索引仍需 HMAC） |
| `writeMode`（legacy/dual）| `isDualWrite()` | **删除**——收缩后写切点无条件写 hmac+cipher，无双写开关 |
| `backfillOnStartup` / `backfillBatchSize` | — | **删除**——回填作业整体下线（§2.2） |
| `readMode`（plain/shadow/hmac）| `isShadowRead` / `readMode(module)` / `isHmacRead` | **删除**——hmac 是唯一读路径，无模式可拨 |
| `readModes`（分模块覆写）| — | **删除** |
| `dekV1` / `cipherKat` | 新增 | **新增**——AES-GCM 密钥 + 加解密 KAT 期望值 |

### 2.2 PII 基础设施类处置（整类删 vs 保留接口）

| 类 | 现状职责 | 处置 | 理由 |
|----|---------|------|------|
| `PiiShadowReader` | 影子期明文/hmac 双查比对 | **整类删除** | 无明文列可比对，职责消亡 |
| `PiiFallbackHealer` | 登录链 hmac 未命中→旧列兜底 + 自愈补写 | **整类删除** | `phone_hash`/明文列已删，兜底查询不可能执行 |
| `PiiBackfillService` / `PiiBackfillRunner` | 存量回填 hmac | **整类删除** | 明文列已删，回填无源数据；cipher 回填在 V31~V33 期间完成即下线 |
| `PiiReadRouter` | 读路由（plain/shadow/hmac 三档 + redisKeyPart） | **整类删除**，调用点改为直连 `PiiHmacQueries` + `mapper.selectOne` | 路由存在的意义就是切换，收缩后 hmac 查询是唯一路径（`PiiHmacQueries` 保留） |
| `PiiHmacQueries` | hmac 查询构造入口 | **保留** | 谓词单一来源，登录/黑名单/定价/SMS 均依赖 |
| `PiiCrypto` | 仅 HMAC | **保留并扩展**（§2.3） | reveal/C5/staff 需要 `decrypt`；盲索引仍要 `phoneHmac` |
| `PiiRevealService` / `PiiRevealController` | 查全号四 biz | **保留**，内部改 cipher 解密（§2.4） | 接口形态不变，是收缩后唯一明文出口 |
| 各域写切点（A1-A6/B1/B2/C1-C3/SMS/C2） | `if (piiCrypto.isDualWrite())` 门控写 hmac | **去开关**：无条件写 hmac（+cipher），删 `isDualWrite()` 分支 | 双写概念消亡，写就是常态 |

### 2.3 `PiiCrypto` 扩展方案

```java
public String encrypt(String plain)          // AES-GCM：Base64(iv ‖ ciphertext ‖ tag)，null/空白→null
public String decrypt(String cipherText)     // 反解；格式非法/解密失败→抛 PII_DECRYPT_ERROR（或 decryptOrNull 供展示层降级）
public String last4(String phone)            // 摘要格式统一入口：空→null；与黑名单 PHONE_**** 格式同源
public boolean isDualWrite()                 // 删除
```
- 启动自检新增：`cangchu.pii.dek-v1` fail-fast + `cipherKat`（`decrypt(encrypt(KAT_VECTOR)) == KAT_VECTOR` 或固定向量比对）。
- `phoneHmac` 保持不变（盲索引）；`normalize` 保持不变（R1）。

### 2.4 `PiiRevealService` 改 cipher 解密（接口形态不变）

| biz | 现取数（明文列） | 改为 | 附加改动 |
|-----|-----------------|------|---------|
| `BLACKLIST` | `b.getTargetValue()` | `piiCrypto.decrypt(b.getTargetValueCipher())` | 保留「非 PHONE 行拒绝」校验 |
| `TENANT` | `t.getContactPhone()` | `piiCrypto.decrypt(t.getContactPhoneCipher())` | — |
| `WA_APPLICATION` | `app.getContactPhone()` | `piiCrypto.decrypt(app.getContactPhoneCipher())` | 保留 `selectIgnoreTenant` + 归属校验 |
| `INQUIRY` | `inq.getRtPhone()` | `piiCrypto.decrypt(inq.getRtPhoneCipher())` | **修复 D1**：改用 `selectInquiryIgnoreTenant(id)`（L150 死代码），否则 WA/WE 带 TenantContext 时被 TenantLine 过滤 |

`reveal(Long, String, Long)` 签名、权限校验、审计日志格式全部不变（05-guardrails G-8.3 口径）。

### 2.5 各业务域改动点（读/写入口）

**account 域**
- `User` 实体：删 `phone`/`phoneHash`；增 `phoneCipher`/`phoneLast4`（`@JsonIgnore`）。`userMapper` 的所有 `setPhone/setPhoneHash` 落库点改 `setPhoneCipher/setPhoneLast4`；`uk_phone_hash` 相关逻辑清除。
- `AccountServiceImpl`：A1-A6 登录链 `piiReadRouter.user(...)` → `userMapper.selectOne(PiiHmacQueries.user(hmac))`；`verifySmsCode` 的 `piiReadRouter.smsCode(...)` → `PiiHmacQueries.smsCode(...)` 直查；改绑旧号校验 `user.getPhone()` → `decrypt(user.getPhoneCipher())`；`smsCode` 写点改 `setPhoneHmac/setPhoneLast4`；删除全部 `isDualWrite()` 分支。
- `UserServiceImpl`：`getPhoneByUserId`（约 L84 `user.getPhone()`）→ `decrypt(user.getPhoneCipher())`。
- `SmsCode` 实体：删 `phone`；增 `phoneLast4`（hmac 已有）。
- `login:fail`/`sms:ip`/`tenants:dir:ip` 的 sha256 保留（非手机号 PII，与本次无关）。

**tenant 域**
- `BlacklistServiceImpl`：`page` 删 `eq(target_value, kw)` 明文精确分支、PHONE 行不打码；`add/revive` 删 `isDualWrite()` 门控，PHONE 行无条件写 hmac+cipher、target_value 按 §1.5 摘要格式；`isBlacklisted` B1 走 `PiiHmacQueries.blacklistActiveHit`；`maskIfPhone` 对 PHONE 原样输出。
- `TenantServiceImpl`：L617 `AdminTenantItemVo.contactPhone` → 解密后 mask 或 last4；L92/L836 写点改 cipher。
- `WholesalerApplicationServiceImpl`：L73 `userService.getPhone(userId)`（默认联系电话）→ 解密；L415 VO 打码 → 解密后 mask 或 last4。
- `TenantApplication` 实体：删 `contactPhone`，增 `contactPhoneCipher`（如 §1.6 采纳）。

**pricing / document 域**
- `CustomerPrice` 实体：删 `rtPhone`；增 `rtPhoneLast4`（`@JsonIgnore`）；保留 `rtPhoneHmac`。`PricingServiceImpl` C1/C2/C3 `piiReadRouter.customerPrice(...)` → `PiiHmacQueries.customerPrice/customerPriceRows` 直查；settle 写点写 hmac+last4；`redisKeyPart` 无条件 `phoneHmac`；`toVo` 展示改 `"****" + rtPhoneLast4`（§1.6.1）。
- `InquiryRequest` 实体：删 `rtPhone`；增 `rtPhoneCipher`。`InquiryServiceImpl` 建单写 cipher+hmac；L290-294 settle 透传 `inq.getRtPhone()` → `decrypt(inq.getRtPhoneCipher())`。

**storefront 域**
- `RtStoreController.currentRtPhone()` → `AccountService.getPhoneByUserId` 已改为解密（见上），接口无感知。

---

## 3. 依赖与顺序：前端决策 → 后端增量

### 3.1 已拍板决策（w8-pii-s2-decisions.md v1）

| 决策 | 内容 | 后端影响 |
|------|------|---------|
| D3 | 员工保留全号（显式例外）：`wa/Staff.vue` 员工列表收缩后仍展示全号 | **users.phone_cipher 解密供给 `WholesalerEmployeeVo.phone`**（必做，§2.5）；另在 05-guardrails 登记该例外（§8） |
| D1 | 后端自检：`revealInquiry` 接线 | 一行改 `selectInquiryIgnoreTenant`（§2.4，W7 遗留缺陷） |
| D4 | `ta/Pricing.vue` 移除客户端打码过滤残留（`fetchCpPage` 手机号子串 includes + `maskPhone(kw)` 比较分支，产品决策 v2） | **无后端改动**，不新增检索端点 |
| （产品决策 3） | 若未来放开 WE 查看员工页，该例外失效须改方案 B | 建议 guardrails 登记 + 预埋权限位（见 §8） |

### 3.2 若产品选 B（新增 STAFF 查全号 biz）的后端增量清单

仅在 D3「列表保留全号」被未来收紧、改为「列表打码 + 逐行查全号」时才需要，**当前不实施**，但预埋如下（供 Team Lead 拿到产品决策后直接派活）：

1. `PiiRevealService` 新增 `BIZ_STAFF = "STAFF"` 常量 + `revealStaff(operatorUserId, employeeUserId)`。
2. 取数：`WholesalerEmployee` → `employee.userId` → `User.phoneCipher` 解密（复用 `PiiCrypto.decrypt`）。
3. 权限校验：仅目标员工的 WA（`hasWholesalerRole(WA, wholesalerId)`）；若未来放开 WE 看本商户员工，则追加 WE 权限位 + `hasWholesalerPermission(..., STAFF_VIEW)`。
4. 审计：复用 `[PII-REVEAL] operator=... biz=STAFF id=...` 日志格式。
5. `PiiRevealController` 不改（按 biz 分发，枚举校验需同步加 `STAFF`）。
6. 测试：新增 revealStaff 权限/越权/审计 3~4 例。
7. 前端：`wa/Staff.vue` 列表列改打码（last4）+ 行内「查全号」按钮（复用现成 reveal 弹窗组件）。

---

## 4. 测试影响面

### 4.1 PII 关卡类处置矩阵

| 测试类 | 例数 | 依赖明文列/双写开关的断言 | 收缩后处置 |
|--------|------|--------------------------|-----------|
| `PiiDualWriteBackfillScenarioTest` | 20 | KAT 2 + normalize 1（纯密码学，保留）；双写切点 ~11 例断言 `saved.getPhone()==phone` / `getPhoneHash()` / `soleCustomerPrice(wid, rtPhone, skuId)` / `soleSmsCode(phone)` / `soleInquiry(wid, rtPhone)` 全查明文列；回填 5 + 对账 1 依赖 `PiiBackfillService` | **拆三类**：KAT/normalize 保留；切点 11 例改写为「无条件写 hmac+cipher、`decrypt(phoneCipher)==phone`」（查重改按 hmac 构造 wrapper）；回填 5 + 对账 1 **删除** |
| `PiiShadowReadScenarioTest` | 19 | 全部依赖 `PiiShadowReader` | **整类删除**（影子器下线） |
| `PiiLoginHmacReadScenarioTest` | 18 | 依赖 `PiiReadRouter.user` 双读兜底 + `PiiFallbackHealer` | **整类删除**（登录兜底下线；登录 hmac-only 行为由业务场景测试 + `PiiWriteScenarioTest` 承接，9/1 裁定同 HmacRead） |
| `PiiHmacReadScenarioTest` | 22 | hmac 命中==旧列命中/未命中==旧列未命中（依赖明文列对比）；拨回 shadow/默认值/启动校验（依赖开关）；C4 redis key 3 例（依赖 `redisKeyPart` 模式分支） | **整类删除**（9/1 裁定替代改写）：V34 后明文列已删，「只读 hmac 直连」为结构性唯一路径；读路径覆盖由业务场景测试（登录/定价/黑名单/员工等）+ `PiiWriteScenarioTest` 承接（详见 §4.2 裁定记录）；C4 由 redisKeyPart 无条件 hmac 派生改造点单独保留 |
| `PiiRevealScenarioTest` | 6 | REV-01..06 直读明文列断言（黑名单/租户/申请/询价查全号 + 搜索） | **改写**：种子数据落 cipher 列，断言 `reveal` 解密还原；搜索断言改 hmac 精确 + last4 + LICENSE_NO LIKE；新增 cipher 加解密往返 1~2 例 |
| 其余 42 个测试文件 | — | `setPhone/setPhoneHash/setRtPhone/setContactPhone/setTargetValue` 构造实体（编译期将失败） | **连锁机械改**：实体删明文字段后所有构造点改 hmac/cipher/last4 字段或走领域服务 |

> 42 文件清单（search_file 实测）：account 4（AccountControllerTest/AccountScenarioTest/UserServiceScenarioTest/SessionActiveTimeoutTest）、tenant 9、document 10、storefront 2、product 1、pricing 4、billing 6、pii 5、其余 1。具体到 W8 由后端 Agent 按「编译错误逐文件修」推进。

### 4.2 收缩后测试策略（目标形态）

1. **保留**：密码学 KAT（HMAC 原样 + cipher 新增）、normalize、`PiiHmacQueries` 构造单元、C4 redis key（无条件 hmac）。
2. **改写**：`PiiDualWriteBackfillScenarioTest` → 更名为 `PiiWriteScenarioTest`（断言写切点落库 hmac+cipher 且解密还原，数据不再含明文）；`PiiRevealScenarioTest`（断言解密供给）。
3. **删除**：`PiiShadowReadScenarioTest`（19）、`PiiLoginHmacReadScenarioTest`（18）、`PiiHmacReadScenarioTest`（22，9/1 裁定整类删除替代改写）、DualWrite 回填/对账 6 例。
4. **新增**：cipher KAT、`decrypt` 失败路径（损坏密文 → 语义错误码）、黑名单 `PHONE_****` 摘要格式 + 冲突消歧单测、reveal D1 修复回归（带 TenantContext 的 WA 查全号不 404）。
5. **目标**：收缩后 PII 关卡例数较初版预算下修（9/1 裁定 `PiiHmacReadScenarioTest` 整类删除后约少 ~14），全量测试类保持全绿，且**在 V33+V34 后的 H2 schema 上直接跑**（验证迁移与代码同构）。
6. **裁定记录（9/1 验收）**：`PiiHmacReadScenarioTest` 处置由「改写为 `PiiHmacReadOnlyScenarioTest`」改为「整类删除 + 替代覆盖」。理由：V34 后明文列已删，「只读 hmac 直连」是结构性唯一路径（无 legacy 分支可选），专项只读测试无额外覆盖价值；`PiiWriteScenarioTest` 已覆盖写切点落 hmac+cipher + decrypt 还原 + 摘要消歧，业务场景测试（登录/定价/黑名单/员工等）覆盖读路径。**附加生效条件**：B3 删双写完成后，读路径核心业务场景测试必须全绿，作为替代覆盖的生效证据（验收 R7/§8.3 复核）。

---

## 5. 回滚与闸门

### 5.1 全库备份清单（V34 执行前必做）

| 项 | 内容 |
|----|------|
| 数据库全量 | `mysqldump --single-transaction --routines --triggers` 全库（或云 RDS 快照）+ 记录 binlog 位点 |
| 关键表导出 | `users / sms_codes / tenants / tenant_applications / wholesaler_applications / inquiry_requests / customer_prices / blacklist` 各一份 `INSERT` 脚本（明文列仍完整） |
| 还原演练 | 备份还原到临时库，抽样对比行数与关键列校验和（不可逆段前必须演练过一次） |
| 迁移回滚脚本 | V33 反向 rename 脚本（§5.2）与 V34 还原说明一并入库 `shared/ops/` 或随部署脚本 |

### 5.2 rename 窗口回滚（窗口 1，秒级 DDL）

- 反向操作：`ALTER TABLE ... RENAME COLUMN phone__bak TO phone`（8 列对调），**无数据丢失**，纯 DDL 秒级。
- **顺带重建 3 个旧索引**：`uk_phone_hash` / `uk_custprice_wh_phone_sku` / `idx_custprice_phone`（V33 rename 前已 drop）——同样 DDL 秒级，随 rename 回滚一并恢复（回滚仅 MySQL prod 场景，用 MySQL 版 DDL：`ALTER TABLE ... ADD UNIQUE KEY` / `ADD INDEX`）。
- **真实约束**：V33 与 W8 代码同批发布，窗口期内新数据只写 cipher/hmac/last4、不写明文列。因此回滚=「代码回滚 + rename 回滚」双回滚；对窗口期内新写入记录，用 cipher 解密回填 `phone__bak`（可复用回填工具的反向脚本，分钟级）。**不建议跨天回滚**——窗口 1 的观察期应控制在 1 个发布周期内。

### 5.3 drop 后不可逆（窗口 2）

- V34 执行后明文永久删除，**唯一恢复手段=§5.1 全库备份还原**（停机 + 数据回放）。
- **失败操作口径（9/1 幂等核查补充，必读）**：V34 中途失败**禁止 `flyway repair` + 重跑**——MySQL 的 `ALTER TABLE DROP COLUMN` 为隐式提交，失败时已 drop 的列不会回滚，重跑必报「列不存在」；blacklist UPDATE（8.1/8.2）在全部 DDL 之后，DDL 成功但 UPDATE 失败的场景同样无重跑路径。任何失败一律走 §5.1 备份还原。8.1/8.2 的 `target_value NOT LIKE 'PHONE_****%'` 守卫（9/1 修复，MySQL/H2 两变体均加）仅为**意外重跑**提供数据层兜底（跳过已改写行、防撞唯一键），**不作为恢复路径**。
- V34 前闸门（全部通过才发布）：
  1. **代码残留引用 grep = 0**（§7 口径，全后端 `src/main` + `src/test`）；
  2. 观察期内零 PII 兜底/回滚命中日志（`PiiFallbackHealer`、`PiiShadowReader` 计数器全 0）；
  3. cipher 抽样解密比对 100% 通过；
  4. 全量测试绿（V33+V34 schema）。

---

## 6. 风险与红线

### 6.1 代码残留引用 grep 口径（V34 前必须全 0）

| # | 主题 | grep 模式（`src/main/java` + `src/test/java`） | 允许例外 |
|---|------|-----------------------------------------------|---------|
| R1 | users.phone 等值读/展示 | `User::getPhone\|user.getPhone()\|setPhone(\|users.phone\|getPhoneHash\|setPhoneHash\|phone_hash` | 仅 `PiiCrypto.decrypt`/DTO 入参等内存值；`@TableField(exist=false)` 不命中 |
| R2 | sms_codes.phone | `SmsCode::getPhone\|setPhone(\|sms_codes.phone` | 仅 `phoneLast4`/`phoneHmac` |
| R3 | contact_phone 等值读/展示 | `getContactPhone()\|setContactPhone(\|contact_phone\|contactPhone` | 仅 VO 解密后 mask / last4 |
| R4 | rt_phone 等值读/展示 | `getRtPhone()\|setRtPhone(\|rt_phone` | 仅 cipher 解密透传点（InquiryServiceImpl settle）+ `rtPhoneLast4`/`rt_phone_last4` 仅用于 VO 打码展示与实体注解（§1.6.1）——`rt_phone` 子串命中 `rt_phone_last4` 属允许例外 |
| R5 | blacklist target_value 手机号等值读 | `eq(Blacklist::getTargetValue\|target_value` | 仅 LICENSE_NO 等值/LIKE + PHONE last4 `RIGHT(target_value,4)`；PHONE 行等值只走 hmac |
| R6 | 开关/下线类 | `isDualWrite()\|writeMode\|readMode\|readModes\|PiiShadowReader\|PiiFallbackHealer\|PiiBackfillService\|PiiBackfillRunner\|piiReadRouter\|PII_SHRINK\|phone__bak` | 迁移 SQL 与文档；`phone__bak` 仅出现在 V33/V34 |
| R7 | H2 兼容性 | V33/V34 为 Flyway database-specific 拆分（`__mysql.sql`/`__h2.sql`）：MySQL 版 `DROP INDEX` + `UPDATE ... JOIN`；H2 版 `DROP CONSTRAINT`（声明名，支撑索引自动名不同）+ H2 兼容 UPDATE（B3 实测，9/1 已裁决） | 两库变体**目标终态完全一致**；冒烟用 `INFORMATION_SCHEMA.INDEX_COLUMNS`（H2 2.x）对两变体断言相同结果 |

执行方式：CI 加入 `search_content`/grep 检查步骤（或后端 Agent 提交前跑一遍），命中即 PR 阻断。

### 6.2 其它风险

- **V32 唯一索引建不上**（R1 规范化漂移 / R3 脏数据）→ 闸门 SQL 先行，失败即停，修数据后重跑。
- **blacklist 改写撞唯一键** → §1.5 消歧算法兜底；仍失败则以备份还原并人工合并。
- **Flyway checksum**：V27/V30 已入库不可改动；V31~V34 命名不可重排。
- **实体字段删除的编译连锁**：42 个测试文件 + 主代码（§4.1），需机械但系统化处理，避免遗漏 `select(phone)` 等显式列选择。
- **展示口径变化**：`phone` 字段从所有 VO/日志消失后，前端需按 last4/摘要展示（D4 已覆盖 ta/Pricing；wa/Inquiry、ops/Blacklist 由既有 W7 前端改动承接）。
- **D3 例外漂移**：一旦未来放开 WE 查看员工页，全号供给需从「WA 专用解密」改为「权限位门控」——已在 §8 登记。

---

## 7. 交付顺序建议（给 Team Lead 派活）

按依赖切成 4 个后端单 + 1 个前端单（可并行 2/3）：

1. **B1（前置，依赖 0）**：V31 加列 + `PiiCrypto` AES-GCM（encrypt/decrypt/last4 + KAT）+ 写切点扩写 cipher + 回填扩写 + 闸门 SQL（§1.6/§2.2/§2.3）。产出：V31 迁移 + 代码 + `PiiCryptoTest` 新增 KAT。**返工（产品决策 v3，一行）**：V31 SQL 并入 `customer_prices.rt_phone_last4`（§1.6.1），B1 测试重跑确认绿。
2. **B2（依赖 B1）**：V32 唯一索引 + 去重闸门（§1.4.1）。
3. **B3（依赖 B1，可与 B2 并行）**：W8 主改造——实体删明文字段、读路径改 hmac/cipher、reveal 改解密 + D1 接线、blacklist 摘要逻辑、C5/staff 解密供给、PII 类删除（§2.2/§2.4/§2.5）；**顺带 customer_prices last4**（实体 `rtPhoneLast4` + 写点 + 回填/reconcile + `toVo` 改 `"****"+last4`，§1.6.1）。产出：V33 迁移 + 全部主代码改动 + 测试改写/删除（§4）。
4. **B4（依赖 B3 闸门）**：V34 迁移 + 备份清单 + 回滚脚本 + 观察期闸门（§1.4/§5）。
5. **F1（前端，依赖 D4）**：`ta/Pricing.vue` 过滤收紧 + 查全号交互回归（无后端新端点）。

> 红线重申：**本拆解不写 SQL 文件、不改 pom、不跑迁移**。所有迁移文件名、编号（V31~V34）为建议，落地前请 Team Lead 与后端 Agent 对齐 Flyway 版本号。

---

## 8. B3 闸门检查项清单（V33 发布前逐项核验，并入 B3 派发/验收）

> 与 §1.6（回填闸门）/ §5.3（V34 前闸门）/ §6.1（grep 口径）一致。每项给出检查方式，**全绿才允许发布 V33**。

### 8.0 B3 启动前置（B1/B2 合入确认，缺失即停）
- [ ] **B1 产物在库**：`V31__pii_add_cipher_columns.sql` 存在（**含 `customer_prices.rt_phone_last4`**，产品决策 v3 并入）；`PiiCrypto` 有 `encrypt/decrypt/last4` + cipher KAT；`PiiProperties` 有 `dekV1/cipherKat`；8 表 cipher/last4 列存在；写切点扩写 cipher 完成；`reconcile()` 已扩写至 8 表 + cipher 对账维度。
- [ ] **B2 产物在库**：`V32__pii_unique_hmac_indexes.sql` 存在；`uk_phone_hmac` / `uk_blacklist_type_hmac` / `uk_custprice_wh_hmac_sku` 唯一索引已生效。
- [ ] **V32 去重闸门复核**（B2 已过则只复核结果）：users / blacklist(PHONE) / customer_prices 三组 hmac 唯一分组 `COUNT(*)>1` == 0 行。

### 8.1 cipher 回填闸门（进 V33 前）
- [ ] **完成率 100%**：逐表 SQL 计数，cipher 非空行数 == 原明文行数。六表：`users.phone_cipher`、`tenants.contact_phone_cipher`、`tenant_applications.contact_phone_cipher`、`wholesaler_applications.contact_phone_cipher`、`inquiry_requests.rt_phone_cipher`、`blacklist.target_value_cipher`（仅 PHONE 行）；`sms_codes` 验 `phone_last4`、`customer_prices` 验 `rt_phone_last4`（均无 cipher，§1.6.1）。
- [ ] **抽样解密比对**：每表 ≥10 行（含首/尾/中间/随机）`decrypt(cipher) == 原明文`，比对结果留档可查。
- [ ] **reconcile 扩写验证**：B1 扩写后的 `reconcile()` 八表 clean（`missing==0 && mismatched==0`，含 cipher 对账）。

### 8.2 V33 rename 前代码残留断言（R1–R7 grep 口径，`src/main/java` + `src/test/java`）
- [ ] R1 users.phone：`User::getPhone|user.getPhone()|setPhone(|users.phone|getPhoneHash|setPhoneHash|phone_hash` → 命中 0（仅 `PiiCrypto` 内存值 / 迁移 SQL）。
- [ ] R2 sms_codes.phone：`SmsCode::getPhone|setPhone(|sms_codes.phone` → 命中 0（仅 `phoneLast4`/`phoneHmac`）。
- [ ] R3 contact_phone：`getContactPhone()|setContactPhone(|contact_phone|contactPhone` → 命中 0（仅 VO 解密后 mask/last4）。
- [ ] R4 rt_phone：`getRtPhone()|setRtPhone(|rt_phone` → 命中 0（仅 InquiryServiceImpl settle 解密透传 + `rtPhoneLast4`/`rt_phone_last4` 仅用于 VO 打码展示与实体注解，§1.6.1）。
- [ ] R5 blacklist target_value：`eq(Blacklist::getTargetValue|target_value` → 命中 0（仅 LICENSE_NO 等值/LIKE + PHONE last4 `RIGHT(target_value,4)`）。
- [ ] R6 开关/下线类：`isDualWrite()|writeMode|readMode|readModes|PiiShadowReader|PiiFallbackHealer|PiiBackfillService|PiiBackfillRunner|piiReadRouter` → 命中 0；`phone__bak` 仅出现在 V33/V34 SQL。
- [ ] R7 H2 兼容：V33/V34 Flyway database-specific 拆分（`__mysql.sql`/`__h2.sql`）在 H2 MySQL 模式实测通过（H2 版：V33 `DROP CONSTRAINT uk_phone_hash`/`uk_custprice_wh_phone_sku` + `DROP INDEX idx_custprice_phone` + 8 RENAME；V34 黑名单改写 H2 等价写法）；冒烟用 `INFORMATION_SCHEMA.INDEX_COLUMNS`（H2 2.x），对 MySQL/H2 变体断言**同一终态**（首个 PR 内小样验证）。

### 8.3 测试闸门（在 V33+V34 后的 H2 schema 上直接跑）
- [ ] **全量测试类全绿**（49 测试类 / 约定目标例数）。
- [ ] **PII 关卡改写符合 §4.2**：`PiiWriteScenarioTest`（写切点落 hmac+cipher 且 `decrypt(phoneCipher)==phone`）；`PiiRevealScenarioTest`（种子落 cipher 列，reveal 解密还原）。**只读路径替代覆盖生效证据**（9/1 裁定）：`PiiHmacReadScenarioTest` 整类删除后，读路径核心业务场景测试（登录/定价/黑名单/员工等）在 V33+V34 schema 下全绿。
- [ ] **删除类已删**：`PiiShadowReadScenarioTest`、`PiiLoginHmacReadScenarioTest`、DualWrite 回填/对账用例、HmacRead 开关/回滚用例。
- [ ] **新增类达标**：cipher KAT、`decrypt` 失败路径（损坏密文 → 语义错误码）、blacklist `PHONE_****` 摘要格式 + 冲突消歧单测、D1 修复回归（带 TenantContext 的 WA 查全号不 404）。

### 8.4 G-8.6 解密供给验证（D3 员工全号）
- [ ] WA 员工列表 V33 后 `WholesalerEmployeeVo.phone` == `decrypt(user.phone_cipher)`，与收缩前展示一致。
- [ ] 集成用例：创建员工 → V33 收缩 → 员工列表全号正确；WA 角色可见、WE 角色不可见（权限矩阵未放开）。
- [ ] D1 接线回归：WA 带 TenantContext 查全号（INQUIRY biz）不 404。

### 8.5 V34 前观察期闸门（B4 复用，§5.3）
- [ ] 观察期内零 PII 兜底/回滚命中日志（`PiiFallbackHealer` / `PiiShadowReader` 计数器全 0）。
- [ ] 代码残留引用 grep = 0（同 8.2 口径）。
- [ ] cipher 抽样解密比对 100% 通过。
- [ ] 全量测试绿（V33+V34 schema）。
- [ ] 全库备份 + 8 表 `INSERT` 导出 + 还原演练（§5.1）。
