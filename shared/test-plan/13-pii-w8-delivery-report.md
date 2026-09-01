# 13 · PII-S2 交付验收报告（W8 明文收缩收官）

> 测试&审查 Agent · 2026-09-01 · 验收基线 main=8837001（W8 后端 8954049 + 前端 72c5597 + 文档 74c5410 + E2E 适配 8837001 全部合入）
> 真源：`architecture/16-pii-w8-shrink-plan.md`（实施拆解）+ `task_plan.md`「PII-S2 收口（PII-W8）」+ 产品决策 `shared/product/w8-pii-s2-decisions.md`（v1）
> 上期基线：12-p4-delivery-report.md（main=5319e44，408 测试 / E2E 45 例）

## 1. W8 范围（PII 阶段2 明文收缩，全项目唯一不可逆段）

| 迁移 | 内容 | 可逆性 |
|---|---|---|
| `V31__pii_add_cipher_columns.sql` | 补 cipher / last4 列（users/tenants/tenant_applications/inquiry_requests/customer_prices/sms_codes/wholesaler_applications/blacklist，全部 NULL；**含 `customer_prices.rt_phone_last4`，产品决策 v3 并入**） | 可逆（加列） |
| `V32__pii_unique_hmac_indexes.sql` | hmac 索引升 UNIQUE（`uk_phone_hmac` / `uk_blacklist_type_hmac` / `uk_custprice_wh_hmac_sku`）+ 去重闸门 | 可逆（删索引） |
| `V33__pii_shrink_rename.sql` | 明文列 RENAME `*__bak`（8 列，Flyway H2/MySQL 双变体）；`rt_phone_last4` 保留不 rename | 秒级可逆（DDL） |
| `V34__pii_shrink_drop.sql` | DROP `*__bak` + 旧唯一键（`uk_phone_hash`/`uk_custprice_wh_phone_sku` + `idx_custprice_phone`）+ blacklist PHONE 行改写 `PHONE_****{last4}` 摘要（hmac 尾 4 消歧，§1.5）；保留 `rt_phone_last4` | **不可逆**（备份还原） |

代码侧：

- **删双写/开关**：6 类整删（`PiiShadowReader`/`PiiReadRouter`/`PiiModule`/`PiiFallbackHealer`/`PiiBackfillService`/`PiiBackfillRunner`）+ `isDualWrite()`/`read-mode`/`read-modes`/`write-mode` 全清。
- **读路径直连**：Account/User/Blacklist/Inquiry 4 Service 直连 `PiiHmacQueries`；`PiiRevealService` 改 cipher 解密 + D1 接线 `selectInquiryIgnoreTenant`；`PiiCrypto` AES-GCM + 确定性 cipher KAT（`AAAAAAAAAAAAAAAA/0CZM...` 三源闭环）。
- **前端（D1/D4，72c5597）**：wa/Inquiry 查全号交互、ta/Pricing 移除客户端手机号过滤（仅保留 SKU 名过滤与展示列）。

## 2. 波次闸门数据（均 Team Lead 独立复验后合并）

| 段 | 合并 commit | 闸门 |
|---|---|---|
| W6 登录双读（前置） | 61df04d | **488 绿** + RED 双变异验证（杀 15/7） |
| W7 管理端打码+查全号（前置） | 44fb080 | **49 测试类绿**（列表打码 / 检索口径 / `GET /api/v1/pii/phone-reveal` 四 biz 权限矩阵） |
| W8 后端收口 | 8954049 | **451 绿**（455−4 占位，SKIP=0 无占位残留） |
| W8 前端 D1/D4 | 72c5597 | F1 联调 D1/D3/D4 全过（真实 MySQL） |
| 决策登记 | 691908d | G-8.6 显式例外（WA 员工全号） |

## 3. W8 后端全量回归（451 绿，V33+V34 H2 schema 直跑）

49 测试类 / 451 例全绿（0 失败 / 0 错误 / 0 跳过），在 **V33+V34 后的 H2 schema** 上直接跑（验证迁移与代码同构）。

PII 关卡重构（16 §4.2）：

- **改写**：`PiiDualWriteBackfillScenarioTest` → `PiiWriteScenarioTest`（写切点落 hmac+cipher 且 `decrypt(phoneCipher)==phone`）；`PiiRevealScenarioTest`（种子落 cipher 列，reveal 解密还原）。
- **整删**：`PiiShadowReadScenarioTest`（19）/ `PiiLoginHmacReadScenarioTest`（18）/ `PiiHmacReadScenarioTest`（22，9/1 裁定整类删除替代改写——V34 后明文已删，「只读 hmac 直连」是结构性唯一路径，无 legacy 分支可选）；DualWrite 回填/对账 6 例。
- **新增**：cipher KAT、`decrypt` 失败路径（损坏密文 → 语义错误码）、blacklist `PHONE_****` 摘要格式 + 冲突消歧单测、D1 修复回归（带 TenantContext 的 WA 查全号不 404）。
- **只读路径替代覆盖生效证据**（9/1 裁定附加条件）：读路径核心业务场景测试（登录/定价/黑名单/员工等）在 V33+V34 schema 下全绿。

代码残留断言（16 §8.2 R1–R7 grep 口径）全 0：`users.phone` / `sms_codes.phone` / `contact_phone` / `rt_phone` / `target_value` 明文引用、`isDualWrite()` 等开关类、`phone__bak`（仅 V33/V34 SQL 内）全部命中 0。

## 4. E2E 全套（101/101 全绿）

前置：8080 后端（spring-boot:run，dev,local，自 main）＋ 5173 admin vite dev ＋ MySQL 3306 ＋ Redis 6379。全量 7.5m，单 worker 串行。

| spec | 用例 | 结果 |
|---|---|---|
| auth | E1-E8（注册/登录/找回/工作台/退出/负向×2/幂等） | 8/8 ✅ |
| onboarding-flow | ONB-E2E-01~05（入驻主链/黑名单拦截/退驻/WE 员工链/**PII-W7 查全号入口×2**） | 5/5 ✅ |
| sell-flow / sell-flow-2 / outbound-chain | SELL/B 系列 + OUT-01~04 | 12/12 ✅ |
| inbound-dispute | INB-01~03（WA 确认/异议/TA 仲裁） | 3/3 ✅ |
| p3b-inbound-forward / p3b-t3 / p3b-t4 | 入库转发/退货盘点/批次到期 | 9/9 ✅ |
| p4-billing / p4-w5b-export-overview | 计费全链/导出+总览 | 7/7 ✅ |
| onboarding-visual + w5-visual + p3b-w5 + p4-w5 | 视觉矩阵（1280/768/375/390×844 多视口） | 57/57 ✅ |

**契约适配记录（8837001）**：首跑 100/101，`ONB-E2E-02` 失败——黑名单列表断言仍用 W7 时代 `masked()` 格式（`130****3306`），而 V34 后 `target_value` 已是 `PHONE_****{last4}` 摘要（16 §1.5 设计内，后端 `page()` 原样返回、无前端 maskPhone）。更新断言为 `blacklistSummary()`（`PHONE_****{last4}`），与 ONB-E2E-05 的 `maskPhone`（`138****1234`，tenant/wa 列表，后端解密打码）区分。**非产品缺陷**，单用例复跑通过后全量闭环。

## 5. 真实 MySQL 迁移执行 + 缺口处置（9/1）

- **V31–V34 已在真实库执行**（开发库），Flyway H2/MySQL 双变体终态一致。
- **cipher 回填闸门（§8.1）**：users 1919/1919、tenants 551/551、tenant_applications 0/0、inquiry_requests 104/104、blacklist(PHONE) 33/33、sms_codes 0/0（空表）、customer_prices.rt_phone_last4 12/12 —— **全 100%**。
- **恢复残留缺口链整链清除**（Team Lead 决策，删除前全库备份）：users 577、tenants 194、inquiry 47、customer_prices 2、sms_codes 15、wholesaler_applications 89 等。7/8 表 100% 覆盖；wholesaler_applications 448/460=97.4%——剩余 12 行属 4 个正常用户 APPROVED 申请单（`contact_phone` 明文本为 NULL，非缺口），**保留不删**。
- **V32 去重闸门**：users / blacklist(PHONE) / customer_prices 三组 hmac 唯一分组 `COUNT(*)>1` == 0 行；缺口清除后 `uk_phone_hmac` 完好。

## 6. F1 联调证据（真实库新造数据链，§8.4）

| 验收项 | 结果 |
|---|---|
| D1 INQUIRY 查全号 | WA 带 TenantContext 调用 `GET /api/v1/pii/phone-reveal` 返回完整号 **13700002001**（不 404） |
| D3 员工全号（G-8.6 例外） | 员工列表 `WholesalerEmployeeVo.phone == decrypt(phone_cipher)`，完整号 **13600002005**，与收缩前展示一致 |
| D4 打码展示 | 列表 `137****2001`，全号不落列表；ta/Pricing 移除手机号过滤后仅 SKU 名过滤 |

联调测试账号：TA 13800002001 / OPS 15800002001 / WA 15900002003 / WE 13600002005。解密路径留档于联调脚本 `db_w8_apt*.py`。

## 7. 决策与红线（D1–D4 定稿落地）

- **D1**：`revealInquiry` 改 `selectInquiryIgnoreTenant(id)`——修复 W7 遗留缺陷（WA/WE 带 TenantContext 查全号被 TenantLine 注入过滤成 404）。
- **D2/D3**：见 `shared/product/w8-pii-s2-decisions.md` v1；D3 员工保留全号已在 guardrails 登记显式例外（G-8.6，691908d）。
- **D4**：纯前端（ta/Pricing.vue），无后端增量。
- **红线**：V34 不可逆，唯一恢复手段 = §5.1 全库备份还原；中途失败**禁止 `flyway repair` + 重跑**（MySQL `DROP COLUMN` 隐式提交，重跑必报「列不存在」）；V33 与 W8 代码**同批发布**，V34 跨一个发布周期**单独发布**；8.1/8.2 `NOT LIKE 'PHONE_****%'` 守卫仅为意外重跑兜底，不作恢复路径。

## 8. 遗留清单（全部属部署侧，待发布环境）

| # | 项 | 说明 | 级别 |
|---|---|---|---|
| W8-L1 | §8.5 还原演练 | 备份已完成（`backup_w8_gap_delete_20260901.sql`，全库 INSERT 39 表 4.9MB）；演练属发布窗口执行（本地可先模拟：还原到临时库 → 抽样对比行数 + 关键列校验和） | 高（发布前） |
| W8-L2 | V34 观察期 | 无生产环境 → 观察对象不存在，**挂起**（W6 先例：用户拍板无生产环境不设观察期）；§8.5 其余闸门项均已在本地闭环 | 挂起 |
| W8-L3 | prod 冒烟 | prod profile 实机冒烟（PII_DEK_V1 fail-fast + 三链路 + reveal 解密）未做 | 高（上线前） |
| W8-L4 | CVE 复扫 | OWASP dep-check / Trivy 门禁未执行，以 06 报告 + Boot 3.5.16 归档依赖树（`dependency-tree-after-boot3516.txt`）为基线 | 高（上线前） |
| W8-L5 | graceful shutdown | Windows 停服行为未实测 | 中（上线前） |
| W8-L6 | Redis ACL | 6379 仍 0.0.0.0；部署配置需 bind 回环 + protected-mode + requirepass（对齐 P4-L5） | 高（上线前） |

## 9. 备份与安全索引

- 删除前全库备份：`backup_w8_gap_delete_20260901.sql`（39 表 INSERT，4.9MB，V34 drop 前明文完整）
- 用户表备份（迁移/清理前置）：`backup_users_before_backfill.csv`
- 删除 SQL 留档：`backup_w8_gap_delete_20260901.sql`（§8.1 缺口链删除前生成）

## 10. 下一期建议

1. **上线前**：按 W8-L1~L6 清上线检查单（还原演练 → prod 冒烟 → CVE 复扫 → graceful shutdown → Redis ACL）。
2. 若部署：V34 发布窗口内保持 §8.5 观察期闸门口径（零兜底日志 / 抽样解密 100% / 全量绿）。
3. 还原演练脚本（备份 → 临时库还原 → 行数+校验和对比）建议固化进 `shared/ops/`，与 V33 反向 rename 脚本一同入库。

## 11. 服务保持

- 后端：http://localhost:8080（spring-boot:run，profiles=dev,local，自 main）
- 前端：http://localhost:5173（主仓 frontend vite dev，@cangchu/admin）
- MySQL 3306 / Redis 6379 本地运行中
