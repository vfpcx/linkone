# 15 · PII 三段式 v2（手机号加密改造，据 2026-08 现状实测刷新）

> 角色：架构师 Agent ｜ 日期：2026-08-11 ｜ 状态：**方案设计稿（未实施，排期由 Team Lead 定）**
> 前序：`11-hardening-design.md` §1（H1，2026-07-15 基于 V13 时代码写就）。本文是其**三段式刷新版**：
> 代码库已演进到 **main=80501da / Flyway V26**（P2 收尾 + P3 + P3b + P4 四期落地），消费点、泄露面、
> 测试基线全部重新实测。11 号文档 §1 的算法选型（AES-GCM 密文列 + HMAC-SHA256 盲索引列，§1.2）**继续有效**，
> 本文不重复论证；凡与 11 §1.3–§1.7 冲突处，**以本文为准**。
> 约定：本文只做设计，不改任何代码/配置。所有现状结论均带 `文件:行` 实测证据（基线 commit 80501da）。

---

## 0. v1 → v2 关键差异速览（为什么必须刷新）

| # | v1（7/15，V13 基线）的假设 | v2 实测现状（V26 基线） | 影响 |
|---|---|---|---|
| 1 | `blacklist` 尚未建表，"建表时直接用密文口径，不产生存量" | **已按明文建表**：V10__init_onboarding.sql:30-43，`target_value VARCHAR(64)` 明文 + `uk_blacklist_type_value` 建在明文上 | 假设作废：黑名单产生明文存量，**纳入回填范围**；且衍生 3 处明文日志 + 1 处 LIKE 检索（§1.2-B） |
| 2 | 明文表清单 6 张 | 新增第 7 张：`wholesaler_applications.contact_phone`（V10__init_onboarding.sql:13） | 加列/回填范围 +1 |
| 3 | "登录限流/短信防刷 Redis 键已用 sha256"列为**已达标项**（11 §1.1） | 刷新判定：无盐 `sha256Hex(phone)` 键（AccountServiceImpl:95/102/554）**与 phone_hash 同弱**（4×10⁹ 空间分钟级碰撞），不能算达标 | 键派生需同批 HMAC 化；好在 TTL 短、无存量迁移问题（§4 阶段 1） |
| 4 | 担忧 P3/P4 新表扩散手机号列 | **零扩散**：V11–V26 全部迁移 grep `phone` 无一命中（实测 exit=1）；仲裁/客诉/通知/账单/快照表均无手机号列 | 加列范围不扩大——**回填成本没有随四期开发线性上涨**，v1 "越晚代价越大"的机制未兑现，窗口仍在 |
| 5 | 担忧导出/通知成为新泄露面 | 账单 PDF/Excel 导出模型仅 `tenantName`/`wholesalerName`（billing/export/BillExportModel.java:17-18，全文件无 phone 字段）；通知仅 `title`/`content`（notify/entity/Notification.java:131/133），拼装处（ArbitrationServiceImpl 等）无手机号入文案 | **阴性结论**，但需在阶段 2 立规约防回潮（§5.4） |
| 6 | 日志明文 3 处待治理 | W1 已收口：InquiryServiceImpl:165-167、PricingServiceImpl:155-157/206-208、TenantServiceImpl:180、WholesalerServiceImpl:181-182、AccountServiceImpl:523 均已 `SmsUtil.maskPhone`；主配 `log-impl: NoLoggingImpl`（application.yml:41） | **但黑名单是漏网之鱼**：BlacklistServiceImpl:94/111/126 三处 `log.info(... value ...)` 打明文手机号（§1.2-B），应随第 1 波顺手收口 |
| 7 | 测试基线 142+ | 后端 408 用例（80501da 提交注明 408×4 全绿）+ E2E 45×2 + 视觉 18 图 | 各波闸门的"全绿"口径按此刷新 |
| 8 | Flyway 编号 V10/V11/V12 | V10–V26 已被占用 | PII 迁移编号顺延为 **V27（加列）/ V28（唯一索引升级）/ V29（明文收缩）** |

---

## 1. 现状实测：phone / phone_hash 全消费点盘点（V26 基线，全部带证据）

### 1.1 数据库明文分布（7 张表 + 1 个弱哈希列）

| 表.列 | 建列处 | 等值检索？ | 需要列 |
|---|---|---|---|
| `users.phone` | V1__init_account.sql:6 | 否（仅展示/取号） | cipher（+last4） |
| `users.phone_hash`（无盐 SHA-256，登录唯一键 `uk_phone_hash`） | V1__init_account.sql:7/20 | **是——登录命门** | 新列 `phone_hmac` + `uk_phone_hmac`，与旧列并存过渡 |
| `sms_codes.phone` | V1__init_account.sql:43 | 是（验证码校验） | 仅 hmac（无需还原原文；短生命周期） |
| `tenants.contact_phone` / `tenant_applications.contact_phone` | V2__init_tenant.sql:12/104 | 否 | cipher |
| `inquiry_requests.rt_phone` | V8（InquiryRequest 实体沿用） | 否（按单据查；settle 时透传） | cipher + hmac（settle 身份键） |
| `customer_prices.rt_phone`（`uk_custprice_wh_phone_sku` + `idx_custprice_phone`） | V9__init_pricing.sql:19/21 | **是——定价身份键** | cipher + hmac + 新唯一键 |
| `blacklist.target_value`（PHONE 类型行；`uk_blacklist_type_value` 建在明文） | **V10__init_onboarding.sql:33/41（v1 后新增存量）** | **是——入驻风控命中** | hmac（+cipher 供 OPS 展示查全号）；LICENSE_NO 行保留明文分支 |
| `wholesaler_applications.contact_phone` | **V10__init_onboarding.sql:13（v1 后新增）** | 否（黑名单检查用的是入参而非本列） | cipher |

**阴性结论（实测）**：V11–V26 共 16 个迁移（含 V17 仲裁+通知、V24 计费规则、V25 日快照、V26 账单）`grep -i phone` 零命中——P3/P3b/P4 新表**没有任何手机号列**。

### 1.2 代码等值消费点（HMAC 切换的全部触点）

**A. 登录命门链（users.phone_hash，6 处 `.eq(User::getPhoneHash, …)`）**

| # | 场景 | 位置 |
|---|---|---|
| A1 | 注册查重 | AccountServiceImpl:164/194 |
| A2 | 密码登录 | AccountServiceImpl:250/253 |
| A3 | 找回密码 | AccountServiceImpl:372/378 |
| A4 | 换绑新号查重+落库 | AccountServiceImpl:433/435/445 |
| A5 | RT 验证码免密登录（未注册自动建号） | AccountServiceImpl:462/464/469 |
| A6 | WA/OPS 代建幂等开号 `ensureUserByPhone` | UserServiceImpl:42/44/51（调用方：WholesalerServiceImpl:177、TenantServiceImpl:173） |

哈希产生点集中在 `DigestUtil.sha256Hex(phone)`（AccountServiceImpl:90/164/250/372/433/462 + UserServiceImpl:42）——**8 处散落调用，无统一入口**，切 HMAC 前必须先收敛为单一 `PiiCrypto.phoneIndex()`（§3）。

**B. 黑名单链（blacklist.target_value）**

| # | 场景 | 位置 | 备注 |
|---|---|---|---|
| B1 | 入驻命中检查 `isBlacklisted` | BlacklistServiceImpl:131-142（`.eq(TargetValue)` :135/:142） | 调用方 4 处：WholesalerApplicationServiceImpl:75-76/97/254、WholesalerServiceImpl:66 |
| B2 | 加黑查重/复活 | BlacklistServiceImpl:83 | |
| B3 | **LIKE 模糊搜索** | BlacklistServiceImpl:57 `.like(Blacklist::getTargetValue, kw)` | HMAC 后 LIKE 失效——检索改口径（§5.3） |
| B4 | **明文日志 ×3（W1 漏网）** | BlacklistServiceImpl:94/111/126 | 建议随 PII-W1 顺手 maskPhone 收口 |

**C. 定价/店面/询价身份链（customer_prices.rt_phone）**

| # | 场景 | 位置 |
|---|---|---|
| C1 | settle upsert 唯一键匹配 | PricingServiceImpl:177（settleFromInquiry :163）+ 手工设价 upsert（F1 注释 :112 一段） |
| C2 | 价格解析 | PricingServiceImpl:511（resolveCustomUnitPrice :501，resolvePrice :314/:322/:325） |
| C3 | 批量调价按 rtPhone 圈选 | PricingServiceImpl:437（BatchCustomerPriceDto.rtPhone :36） |
| C4 | **Redis 明文键** `price:match:{wid}:{rtPhone}:{skuId}` | PricingServiceImpl:521-522（写 :502、失效 :527/:538-548） |
| C5 | 身份取号链：RT 登录态 → 明文手机号 | RtStoreController:44/62/72-77 → AccountServiceImpl:508 `getPhoneByUserId` → StoreFrontServiceImpl:62/102/114/124 |
| C6 | 询价落库+确认转 settle | SubmitInquiryDto:21 → InquiryServiceImpl:283-287（settle 透传）/:476（VO 回明文） |

**D. 展示型回传（VO 层明文，阶段 2 打码对象）**

| VO 字段 | 位置 | 前端消费页（§5.2 清单） |
|---|---|---|
| InquiryVo.rtPhone | InquiryVo.java:30（InquiryServiceImpl:476） | wa/Inquiry、wa/PriceSettleDialog |
| CustomerPriceVo.rtPhone | CustomerPriceVo.java:28（PricingServiceImpl:801） | ta/Pricing |
| WholesalerEmployeeVo.phone | WholesalerEmployeeVo.java:30（WholesalerEmployeeServiceImpl:201） | wa/Staff |
| AdminTenantItemVo.contactPhone | AdminTenantItemVo.java:30（TenantServiceImpl:616） | ops/TenantAudit |
| WholesalerApplicationVo.contactPhone | WholesalerApplicationVo.java:30（WholesalerApplicationServiceImpl:413） | ta/WholesalerApplications、wa/Apply（本人回显） |
| Blacklist 实体直出（records 原样入 Map） | BlacklistServiceImpl:61-62 | ops/Blacklist |
| （达标先例）TenantDirectoryItemVo 仅 id+name，注释明令禁带 contactPhone | TenantDirectoryItemVo.java:11 | — |
| （阴性）LoginVo 无 phone 字段——登录响应不回手机号 | account/vo/LoginVo.java（grep 无 phone） | — |

**E. Redis 键面（三类）**

| 键 | 位置 | 现状 | v2 处置 |
|---|---|---|---|
| `price:match:{wid}:{rtPhone}:{skuId}` | PricingServiceImpl:522 | **明文手机号入键**；仓库根目录存在 `dump.rdb`（RDB 持久化开着）→ 明文可落盘 | 阶段 1 换 hmac，键变更自然失效 |
| `sms:cd:` / `sms:daily:` + sha256(phone) | AccountServiceImpl:95/102 | 无盐 sha256 ≈ 可碰撞还原 | 阶段 1 换 HMAC 派生；TTL 短（冷却/当日），旧键自然过期 |
| `login:fail:` + sha256(phone) | AccountServiceImpl:81/553-554（读写 :256/:269/:283/:290/:559/:570/:585） | 同上 | 同上；换键瞬间失败计数清零，可接受（先例：11 §2.3-R3） |
| （达标）`sms:ip:daily:`、`tenants:dir:ip:` 为 IP 哈希 | AccountServiceImpl:116、TenantServiceImpl:662 | 非 PII 手机号 | 不动 |

**F. 日志面**：已收口见 §0-6；漏网 = BlacklistServiceImpl:94/111/126（B4）。

**G. 导出/通知面（P4 新增，实测阴性）**

- 账单导出三件套 `billing/export/`（BillExcelWriter / BillPdfRenderer / ExportSupport / BillExportModel）：模型字段止于 `tenantName`/`wholesalerName`（BillExportModel.java:17-18），行明细为 SKU/费目/金额，**PDF 与 Excel 均不含手机号**。
- 通知：`Notification` 仅 title/content（Notification.java:131/133）；仲裁/客诉裁决文案拼装（ArbitrationServiceImpl:229-233/371）不含手机号；billing/notify 两包 `grep -i phone` 零命中。
- **规约**：阶段 2 起在 05-secure-coding-guardrails 增补"导出文件与通知文案禁止出现完整手机号，如业务必须则 last4"——防 P5+ 回潮。

---

## 2. 目标数据模型 delta（相对 11 §1.3 的修订）

只列变化项，未列出的沿用 11 §1.3：

1. **blacklist（修订：从"无存量新表"改为"存量迁移表"）**：加 `target_value_hmac VARCHAR(64)`、`target_value_cipher VARCHAR(128)`（均 NULLable）；PHONE 行回填 hmac+cipher，LICENSE_NO 行 hmac 置 NULL 保留明文。新唯一键 `uk_blacklist_type_hmac(target_type, target_value_hmac)` 仅对 PHONE 行生效——**MySQL 唯一索引对 NULL 不去重，LICENSE_NO 行天然豁免**；LICENSE_NO 的查重仍走旧 `uk_blacklist_type_value`（保留该键，PHONE 行阶段 2 收缩时把 target_value 置为 last4 摘要，不再唯一冲突——收缩细节见 §5.5）。
2. **wholesaler_applications（新增表）**：加 `contact_phone_cipher`，无 hmac（本列无等值检索，黑名单检查用入参：WholesalerApplicationServiceImpl:75-76）。
3. **sms_codes**：维持 11 §1.3 口径（仅 hmac + last4）；另立**数据保洁**：现无清理任务（实测 @Scheduled 仅 5 个业务 job：BillAutoConfirmJob:25、DailySnapshotJob:27、MonthlyBillJob:33、InboundAutoConfirmJob:30 等），V27 波顺带加 `>24h 即删` 定时任务，cron 错峰登记进 SchedulingConfig 占位表（SchedulingConfig.java:10 注释约定）。
4. **迁移编号**：V27 加列（全 NULLable + 普通索引）→ V28 回填核验后升 UNIQUE → V29 明文收缩。

---

## 3. HMAC 密钥管理落点（据现有配置体系，全部有先例可循）

| 决策 | 落点 | 先例证据 |
|---|---|---|
| 配置命名空间 `cangchu.pii.*` | `cangchu.pii.dek-v1`、`cangchu.pii.index-key`、`cangchu.pii.write-mode`、`cangchu.pii.read-mode` | 既有自定义段 `cangchu.sms.*`（application.yml:64-70），组件用 `@Value("${cangchu.sms.mock:false}")`（SmsUtil.java:25-28）；PII 建议升格为 `@ConfigurationProperties(prefix="cangchu.pii")` 的 `PiiProperties`（字段多于 2 个） |
| **prod 密钥无默认值 = fail-fast** | application-prod.yml 增 `cangchu.pii.dek-v1: ${PII_DEK_V1}`、`index-key: ${PII_IDX_KEY}` | 完全复刻既有先例：application-prod.yml:3-5 注释明言"MYSQL_PASSWORD / REDIS_PASSWORD 故意【无默认值】——缺失即启动失败（fail-fast），杜绝生产静默裸奔"，:10/:25 落地 |
| dev/test 固定测试密钥 | application-dev.yml、backend/src/test/resources/application.yml 的 `cangchu:` 段（test yml :46 已有该段；H2 MODE=MySQL :7，TypeHandler 同样生效——11 §1.6-R5 依旧成立，408 用例靠它不挂） | 先例：mock 验证码只进 dev（application-dev.yml:38-43），主配 `cangchu.sms.mock: false`（application.yml:70，D-03 决策） |
| **启动自检（v2 新增，v1 没有）** | `PiiCryptoService` `@PostConstruct` 做 KAT（known-answer test）：固定向量断言 `HMAC(indexKey, "13800138000")` 等于部署时登记的期望值；不匹配即抛异常拒绝启动 | 动机：Spring 占位符只对**缺失** fail-fast，对**错值**（复制错密钥/换错环境）不 fail——错的 `PII_IDX_KEY` 会让全体用户登录失败且现象诡异（查无此人），KAT 把事故拦在启动期。期望值随部署脚本走，不进 git |
| 密钥形态与轮换 | 两把 256-bit 独立密钥，Base64 编码环境变量；DEK 带 `v{n}` 版本前缀可轮换，**索引密钥不轮换**（轮换=全量重算+唯一索引重建，等同重做一次迁移），保管等级最高 | 沿用 11 §1.2；离线备份双份（密钥丢失 = 登录索引全废，11 §1.6-R2） |

---

## 4. 三段式刷新（阶段 0 / 1 / 2）

> 相对 11 §1.4 五阶段的压缩映射：v1 阶段 0+1+2（加列/双写/回填）→ **v2 阶段 0**；v1 阶段 3（切读）→ **v2 阶段 1**（重设计：影子双查 + 双读回退，v1 只有开关回拨）；v1 阶段 4（清明文）→ **v2 阶段 2**（并入脱敏展示口径，v1 未覆盖前端）。
> 开关沿用：`cangchu.pii.write-mode: legacy|dual`、`read-mode: plain|shadow|hmac`（v2 在 plain/hmac 之间**新增 `shadow` 档**，见阶段 1）。

### 阶段 0 · V27 加列 + 双写 + 回填核验（可与功能开发完全并行）

- **V27__pii_add_cipher_columns.sql**（全部 NULLable，索引先建普通索引）：
  - users：`phone_cipher`、`phone_hmac`、`phone_last4` + `idx_users_phone_hmac`
  - sms_codes：`phone_hmac`（+`phone_last4` 供排障日志）
  - tenants / tenant_applications / wholesaler_applications：`contact_phone_cipher`
  - inquiry_requests：`rt_phone_cipher`、`rt_phone_hmac`
  - customer_prices：`rt_phone_cipher`、`rt_phone_hmac` + `idx_custprice_hmac`、普通复合索引 `(wholesaler_id, rt_phone_hmac, sku_id)`
  - blacklist：`target_value_hmac`、`target_value_cipher` + 普通索引 `(target_type, target_value_hmac)`
- **双写**：`write-mode=dual` 后，insert/update 同写 明文列+cipher+hmac。写入口收敛（对应 §1.2 触点）：createUser（AccountServiceImpl:589-597）、换绑（:445）、ensureUserByPhone（UserServiceImpl:50-51）、sms_codes 落库（AccountServiceImpl:130-140）、settle/手工设价 upsert（PricingServiceImpl:177/196 与 F1 段）、询价落库（InquiryServiceImpl submit）、blacklist add/复活（BlacklistServiceImpl:78-111）、tenant/wholesaler_applications 落库（TenantServiceImpl:92/835/895、WholesalerApplicationServiceImpl:353）。
- **回填**：独立 CommandLineRunner（`--pii.backfill=users,customer_prices,blacklist,…`），分页扫"明文非空且 hmac/cipher 为空"，幂等可断点重跑；**blacklist 是 v2 新增回填表**（PHONE 行）。
- **核验三件套 + V28**：①行数一致 `count(明文非空)=count(cipher非空)`；②抽样 1% 解密比对 + 全量 `HMAC(phone)==phone_hmac` 双算断言（防 R1 规范化漂移）；③hmac 无重复（customer_prices 重点：同号异格式历史脏数据，11 §1.6-R3）→ 通过后 **V28__pii_unique_index.sql** 把 `uk_phone_hmac`、`uk_custprice_wh_hmac_sku`、`uk_blacklist_type_hmac` 升为 UNIQUE，并同步删除对应普通索引。
- **回滚**：`write-mode` 拨回 legacy 即止血；新列数据留存无害；V27/V28 无需回滚（additive）。

### 阶段 1 · 灰度双读切换（登录是命门，三步走，每步可独立回退）

**Step 1（shadow 影子双查验证期，零行为变化）**：`read-mode=shadow`——所有等值读**仍走旧列出结果**，同时用 hmac 列再查一遍，仅比对+计数（`pii.shadow.mismatch` 计数器 + WARN 日志带 userId 不带手机号）。覆盖 A1–A6 登录链、B1/B2 黑名单、C1–C3 定价。**闸门：连续 ≥7 天 mismatch=0** 才允许进 Step 2。这是"双读验证期"的实现载体——用生产真实流量验证回填完整性与规范化一致性，代价只是每请求多一次索引查询。

**Step 2（非命门先切）**：`read-mode=hmac` 按模块灰度——先切 blacklist（B1/B2）、sms_codes 校验（AccountServiceImpl:528）、pricing（C1–C3）与 Redis 键（C4：`matchKey` 换 hmac，旧键自然失效不清洗；sms/login 限流键前缀派生换 HMAC）。这些路径失败的爆炸半径是"单次风控/定价/验证码不命中"，不是全员锁死。**闸门：pricing 全量用例 + 入驻黑名单用例 + E2E 45×2 全绿；观察 ≥3 天。**

**Step 3（登录切换，唯一需冻结窗口 0.5d）**：A1–A6 六处等值查询改走 `phone_hmac`，但实现为**双读回退**而非硬切：

```
user = selectOne(eq(phone_hmac, hmac))            // 主路
if (user == null) {
    user = selectOne(eq(phone_hash, sha256))      // 兜底路（旧列，双写期间持续新鲜）
    if (user != null) { metrics++; 异步补写该行 hmac; WARN 告警 }
}
```

- **误锁风险分析**（登录失败矩阵）：hmac 命中=正常；hmac 未中+旧列命中=回填遗漏/规范化漂移 → 兜底放行 + 自愈补写 + 告警（**不误锁任何人**）；两列都未中=真无此账号。唯一残余风险是 `PII_IDX_KEY` 配错——已被 §3 启动 KAT 拦截。
- 换绑/注册的**查重**路径（A1/A4）双读窗口内要**两列都查**（任一命中即视为占用），否则同号可能在两列各注册一个账号（R1 变体）。
- **回退**：`read-mode` 拨回 shadow/plain，秒级、无数据损失（双写从未停）。兜底路命中率指标归零且稳定 ≥2 周后，才允许在代码里摘除 sha256 兜底分支。
- **闸门**：冻结窗口内 AC-S1~S6（注册/密码登录/RT 免密/找回/换绑/登出踢出）在 `read-mode=hmac` 下全绿 + 后端 408 全绿 + E2E 45×2 全绿；上线后监控兜底命中率与登录失败率 ≥3 天。

### 阶段 2 · 明文收缩 + 脱敏展示（V29，唯一不可逆段）

1. **VO 层脱敏先行**（收缩前完成，收缩后无明文可漏）：默认回传 `138****1234`（统一走 `SmsUtil.maskPhone`，SmsUtil.java:59）；需要全号的场景走独立"查看完整号"接口（权限校验 + 审计日志），后端由 cipher 解密。前端逐页口径见 §5.2。
2. **检索口径切换**：LIKE 模糊查号下线（BlacklistServiceImpl:57、ta/Pricing.vue:225 前端过滤），改"完整 11 位精确查（应用侧转 hmac）+ last4 尾号查"——产品折衷同 11 §1.6-R4，需产品确认。
3. **V29__pii_shrink_plaintext.sql**：先 `RENAME COLUMN phone TO phone__bak`（跨一个发布周期）→ 下个窗口 DROP：users.phone/phone_hash + uk_phone_hash、sms_codes.phone、tenants/tenant_applications/wholesaler_applications.contact_phone、inquiry_requests.rt_phone、customer_prices.rt_phone + 旧 `uk_custprice_wh_phone_sku`/`idx_custprice_phone`；blacklist PHONE 行 `target_value` 改写为 `PHONE_****` + last4 摘要（保留列供 LICENSE_NO，见 §2-1）。同批删代码双写分支与开关。
4. **前置条件（闸门）**：阶段 1 兜底命中率为零 ≥2 周 + 跨一次完整回归 + 全库备份 + `phone__bak` 过渡期无回捞记录。**回滚**：DROP 前靠 rename 列秒级恢复；DROP 后仅能备份还原（故 rename 过渡强制）。

---

## 5. 波次拆分 · 闸门 · 回滚总表

### 5.1 波次

| 波 | 内容 | 并行性 | 闸门（过不了不进下一波） | 回滚 |
|---|---|---|---|---|
| PII-W1 | 基建：PiiCryptoService（AES-GCM/HMAC/规范化单入口/密钥装载+KAT）、PiiProperties、prod fail-fast 配置、**顺手收口 BlacklistServiceImpl:94/111/126 明文日志**、sha256Hex 8 处调用收敛到 `PiiCrypto.phoneIndex()`（行为不变，仍返回 sha256——先收口再换算法） | 完全并行 | 单测 KAT/加解密/规范化矩阵 + 408 基线零回归 | 纯新增代码，revert 即回 |
| PII-W2 | V27 加列 + 双写（write-mode=dual）+ sms_codes 清理任务 | 完全并行 | 408 全绿 + 新增双写断言用例 + Flyway 干跑 + dev 库实插验证 | write-mode 拨回 legacy |
| PII-W3 | 回填 runner + 核验三件套 + V28 唯一索引 | 完全并行（低峰跑批） | 三件套 SQL 报告归档 shared/test-plan；customer_prices 去重报告 | 任务幂等可重跑；V28 失败=不升 UNIQUE，不影响线上 |
| PII-W4 | 阶段 1 Step1：shadow 影子双查上线 | 完全并行 | **生产 mismatch=0 连续 ≥7 天** | read-mode 拨回 plain |
| PII-W5 | 阶段 1 Step2：非命门切读（blacklist/sms/pricing + Redis 键 HMAC 化） | 半并行（建议随迭代回归合车） | pricing 全量 + 黑名单用例 + E2E 45×2；观察 ≥3 天 | read-mode 分模块拨回 |
| PII-W6 | 阶段 1 Step3：登录双读切换 | **冻结窗口 0.5d** | AC-S1~S6@hmac + 408 + E2E 全绿；兜底命中率/登录失败率监控 ≥3 天 | read-mode 秒级回拨 |
| PII-W7 | 阶段 2 前置：VO 脱敏 + 前端逐页打码 + 查全号接口 + 检索口径切换 | 完全并行（前后端两 worktree） | 视觉回归（先例：18 图矩阵）+ §5.2 清单逐页验收 | 前端 revert |
| PII-W8 | 阶段 2：V29 rename→观察→drop + 删开关代码 | 独立小窗口 ×2 | 兜底零命中 ≥2 周 + 全库备份 + rename 过渡期干净 | rename 期可逆；drop 后不可逆 |

### 5.2 前端脱敏逐页清单（阶段 2 / PII-W7 验收基准，实测 9 处展示 + 6 处输入）

| 页面:行 | 字段 | 现状 | v2 口径 |
|---|---|---|---|
| ops/Blacklist.vue:337（列表键值列）/:236（移除确认弹窗） | targetValue | 全号 | **打码 + last4**；加黑输入框（:409-412）保持全号输入；搜索（:98）改精确/尾号（§4 阶段 2-2） |
| ops/TenantAudit.vue:337 | contactPhone | 全号 | 打码；OPS 审核确需联系 → "查看完整号"点击展开（权限+审计） |
| ta/WholesalerApplications.vue:542 | contactPhone | 全号 | 打码；同上展开机制 |
| ta/Pricing.vue:211（列）/:225（客户端模糊过滤）/:344（编辑回填）/:396（撤销确认文案） | rtPhone | 全号 | 列打码；过滤改后端精确+尾号；**编辑不回显全号**（身份键不可改，显示 last4；新建才输全号）；确认文案打码 |
| wa/Inquiry.vue:265 | rtPhone | 全号 | 打码；WA 联系买家场景走详情展开（是否放开由产品定） |
| wa/PriceSettleDialog.vue:147/:197 | rtPhone | 全号 | 打码（沉淀专属价只需身份一致性，不需要全号） |
| wa/Staff.vue:539 | 员工 phone | 全号 | **建议保留全号（例外项）**：WA 管理员管理自家员工属合理知情范围——留产品确认，若收紧则同打码+展开 |
| wa/Apply.vue:165 | 本人申请 contactPhone 回显 | 全号 | 保留（本人数据） |
| st/*（Bills/BillDetail/Disputes/Dashboard）、ta/BillsOverview、ops/Arbitrations、通知中心 | — | **实测无手机号展示** | 立规约防回潮（§1.2-G） |
| 输入框（不涉打码）：Login/Register/ForgotPassword（本人）、rt/Store.vue:243（RT 报价身份）、ta/Wholesalers.vue:461（waPhone 开号）、WarehouseSwitcher.vue:190-191（建仓联系人） | — | 明文输入 | 不变（输入必须全号） |

### 5.3 与 11 号文档排期建议的衔接

11 §6 的"第 3 批（PII）"由本文取代：W1–W5 全部可与 P5/功能开发并行；仅 W6 要冻结 0.5d、W8 要两个独立小窗口。H5（Boot 3.5 升级）**仍应先于 W6 登录切读**完成（避免"新框架+新登录路径"同回归叠加，11 §6 原则不变）；若 H5 迟迟不排，W1–W4 照常推进不受阻。

---

## 6. 风险刷新（相对 11 §1.6）

- R1（规范化漂移）→ **缓解升级**：单入口收敛（PII-W1 先做、行为不变）+ 阶段 0 双算核验 + 阶段 1 shadow 生产验证 + 登录双读兜底自愈，四层防线。
- R2（索引密钥丢失/泄露）→ 不变；新增 KAT 拦"配错密钥"这一更高频事故。
- R3（customer_prices 唯一键脏数据）→ 不变，V28 前去重报告为闸门。
- R6（**v2 新增**）：blacklist 明文存量与 LICENSE_NO 混列——PHONE/LICENSE 双分支处理易漏，用例须覆盖"PHONE 打码后 LICENSE 检索不受影响 / 复活路径双列一致"。
- R7（**v2 新增**）：shadow 双查使每次登录/定价多一次索引查询——量级为单行主键级查询，`resolvePrice ≤200ms` 预算无虞（11 §1.7-2 同判），仍纳入 W4 观察指标。

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v2 | 2026-08-11 | 独立成文：V26 基线全消费点实测盘点（§1，7 表/6 登录触点/4 黑名单触点/6 定价触点/3 类 Redis 键/导出通知阴性）；blacklist 明文存量纳入；密钥管理落点对齐 prod fail-fast 先例并新增启动 KAT（§3）；三段式重设计——shadow 影子双查验证期 + 登录双读兜底自愈（§4）；8 波次闸门/回滚总表 + 前端脱敏逐页清单（§5） |
