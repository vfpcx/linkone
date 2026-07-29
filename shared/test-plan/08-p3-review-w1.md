# 08 · P3 提前代码审查（BE-W1 入库异常链 / BE-W2 出库状态机 / FE-W1 入库链前端）

> 编写：测试&审查 Agent · 2026-07-25 · 审查对象：main@877bd07（BE-W1/BE-W2/FE-W1 均已合并）
> 基线：`architecture/12-p3-design.md` v2（含两波据实现备注）、`product/09-p3-arbitration-prd.md` v1.1、`architecture/05-secure-coding-guardrails.md`
> 审查重点：越权 / 并发 / 资损 / 前端契约一致性 / PII。逐项过码，不凑数——通过项在 §4 显式列出。

---

## 0. 结论速览

| 级别 | 数量 | 编号 |
|---|---|---|
| BLOCKER | 1 | B1 |
| MAJOR | 3 | M1–M3（其中 M1/M2/M3 与 FE-W1 交付时登记的 3 项契约偏差重合，本审查据码确认并定级） |
| MINOR | 5 | N1–N5 |

P3 新增代码整体质量高于 P2 同期：状态机矩阵/CAS/错误码/租户隔离/附件魔数/授权位切点等均按规约落地且测试覆盖充分。唯一 BLOCKER 是库存并发基建的系统性缺陷（P1 存量同染，P3 冲销/回补路径把它放大为资损级）。

---

## 1. BLOCKER

### B1 · 库存「读-算-写」并未被 Redisson 锁真正串行化：外层事务旧快照读 + 锁先于事务提交释放（资损，双向）

**归属**：BE-W1（inventory 基建；P1 `deductStock`/`addStock` 存量同染，BE-W2 回补路径同染）
**规约**：05 §10 P1「先锁后事务、**提交后释放**、锁覆盖整个 commit 窗口」；12 §2.4「锁内消灭计算→扣减间隙的 TOCTOU」

**现象**：`InventoryServiceImpl` 的类注释与实现自相矛盾，两个并发窗口均未关闭。

1. **窗口 A（旧快照读，MySQL REPEATABLE READ 下必现）**
   业务方法先带 `@Transactional` 开事务并做过 SELECT，再调 inventory 方法：
   - `InboundRequestServiceImpl.disputeByWa`（:194-227）：`loadInbound`(:203, selectById) 建立读快照 → CAS → `reverseInboundForDispute`；
   - `ArbitrationServiceImpl.decideByTa`（:139-214）：selectById 仲裁单后调 `restoreInboundAfterArbitration`；
   - `OutboundRequestServiceImpl.withdrawByWa/confirmWithdrawByWk`、`InquiryServiceImpl.voidByWa`：loadOutbound/selectById 后调 `reverseOutbound`。

   内层 `doXxxInTx` 的 `@Transactional` 传播 REQUIRED **并入外层事务**，锁内 `lockRowForUpdate`（`InventoryServiceImpl.java:409-415`）是**普通 SELECT**（无 FOR UPDATE），在 InnoDB RR 下读的是**外层事务开始时的旧快照**——即便此刻已持有 Redisson 锁，读到的 `inventories.qty` 也可能是陈旧值。注释（:410-411）以「串行化由外层锁保证，无需 FOR UPDATE」自辩，其前提（锁覆盖提交窗口）见下条即不成立。

2. **窗口 B（锁早于提交释放）**
   `withLock`（:452-472）在 body（内层方法）返回后 finally 即 `unlock()`，而外层事务还要继续建仲裁单、发通知、生成单据号（Redis 往返）才提交。下一个抢到锁的线程读不到未提交的库存变更。类注释 :44-45「事务提交后才释放锁」仅当**无外层事务**时成立（P1 直调场景）；P3 全部业务链都有外层事务。

**资损场景（举例）**：
- 登记 30 件、期间已售 20 件（已提交）。WA 异议：`disputeByWa` 事务快照在售出提交前建立 → 锁内 `onhand` 读到 30（实际 10）→ `reversedQty=30, shortfall=0` 固化进仲裁单（错值，仲裁单落单后不可变）→ `inv.setQty(30-30=0)` **绝对值覆写**（实际应为 10-10）——TA 依据错误的差额定责，商户少赔/多赔；
- 反向：快照偏高时 `setQty(旧值-冲销)` 可把库存**覆写抬高**，凭空造出幻库存（`qty > Σ流水`，§0 不变量破坏），后续可超卖；
- 同 sku 两笔入库单并发异议（各自单据 CAS 都成功）：窗口 B 下后者读不到前者未提交的扣减，双双全额冲销 → 库存透支。

**为什么测试没拦住**：`InboundDisputeChainScenarioTest` P3-CAP-04（:357-368）确实用虚拟线程打了 `disputeByWa` 并发，但测试库是 H2（`src/test/resources/application.yml:5`，默认 READ COMMITTED），窗口 A 在 RC 下不存在、窗口 B 靠内存库的微秒级提交侥幸躲过；生产 MySQL 默认 RR，窗口 A 是确定性行为不是概率事件。

**修复建议**（一处改动同关两窗口，无需动事务编排）：
`lockRowForUpdate` 改为真正的锁行读——`wrapper.last("FOR UPDATE")`。InnoDB 锁定读永远读**最新已提交**版本且会阻塞在未提交行锁上：窗口 A 的旧快照读消失，窗口 B 中后进线程也会等到前事务提交。配套建议：
- 扣减/冲销保留绝对值写法亦可（FOR UPDATE 后读算写原子），但更稳妥是改相对增量 `setSql("qty = qty - ...")` + `WHERE qty >= ?`；
- 补一条 MySQL（或 H2 设 `LOCK_MODE`/RR）下的并发回归用例，断言「异议前售出已提交 → reversedQty 按 10 封顶」；
- 类注释 :44-45 与 :409-415 按实况改写，避免后人再引用错误前提。

---

## 2. MAJOR

### M1 · 「通知归属 WA」发给 `wholesalers.owner_user_id`，SELF_OPERATED 商户 WA 收不到全链通知

**归属**：BE-W1 + BE-W2（FE-W1 交付时已登记为契约偏差①，本审查据码确认扩散面）
**证据**：`InboundRequestServiceImpl.java:156-161`（登记待确认）、`:303-307`（72h 自动确认）、`ArbitrationServiceImpl.java:226-229`（TA 裁决）、`:367-370`（OPS 裁决）、`OutboundRequestServiceImpl.java:379-382/402-405/483-487`（R4 结果/代建出库）——全部以 `wholesaler.getOwnerUserId()` 作收件人。SELF_OPERATED 商户该列为 TA 操作人，真实绑定的 WA（user_roles 推导，`listForWa` 正是这么查的）一条通知都收不到；72h 倒计时类通知缺失直接导致单据被自动确认，与 PRD 09 §1.4「通知对方当事人」不符。
**建议**：抽 `resolveWaRecipients(wholesalerId)`：经 AuthService 查该商户 ACTIVE WA 绑定（可多人则群发或取主绑定），owner_user_id 仅作兜底。属通知面订正，不动业务链。

### M2 · `/files/**` 静态映射启动序缺陷：目录不存在时 `toUri()` 缺尾斜杠，全部附件 GET 失败直至重启

**归属**：BE-W1（FE-W1 已登记契约偏差②，据码确认）
**证据**：`SaTokenConfig.java:80-83`——`Path.of(uploadDir).toAbsolutePath().normalize().toUri()` 在目录尚不存在时返回不带尾 `/` 的 URI，Spring 资源定位失效；目录由**首次上传**才创建（`FileStorageServiceImpl.java:50`），故全新部署后「上传成功但预览 500/404」，重启自愈（联调时已实际踩中，见 progress.md 环境插曲）。
**建议**：`addResourceHandlers` 中先 `Files.createDirectories(...)` 再注册，或手工保证 location 以 `/` 结尾。一行修复，建议随下一波顺带。

### M3 · PRD 09 §6.2 刚性规则未达：异议弹窗无「实时在库 M / 差额 N−M」且差额>0 无强制二次确认

**归属**：BE 补端点（缺异议前在库查询）+ FE-W1（FE 已登记契约偏差③并降级为口径文案）
**证据**：`frontend/apps/admin/src/views/wa/InboundDisputeDialog.vue:116-130`——「将冲销/已售部分」均为静态文案；PRD 09 §6.2 要求**提交前必须展示**三个实时数字且**差额>0 时强制二次确认**（差额直接决定商户赔付敞口，属产品刚性规则而非展示优化）。WA 在不知道差额规模的情况下提交异议，事后仅靠结果回显。
**建议**：BE 在 WA 队列 VO 或独立端点回传该 (wholesaler, sku) 当前 onhand（只读，无锁语义要求，展示允许轻微过期）；FE 据此渲染三数字 + 差额>0 时二段确认。在 B1 修复前该数字亦有助人工发现冲销错值。

---

## 3. MINOR

### N1 · `confirmWithdrawByWk` 的 CAS 条件不含 `withdraw_requested=1`（先查后写残留）

**归属**：BE-W2。`OutboundRequestServiceImpl.java:368-376`：flag 检查是锁外预读，CAS 只卡 `status=PRINTED`。两个 WK 并发一拒一确认时，拒绝方先清 flag（:393-398），确认方 CAS 仍成功 → 单据被撤销+回补，与刚做出的「拒绝」决定相悖。同租户 WK 互踩、库存账仍配平，风险低；但违反 05 §10 红线，且修复只需在 CAS wrapper 加 `.eq(withdrawRequested, 1)`。

### N2 · 仲裁附件 URL 无格式白名单，任意外链落库并在 TA/OPS 弹窗直接加载

**归属**：BE-W1（dispute）/BE-W2（complain）。`InboundDisputeDto` 仅限单条 ≤200 字（`OutboundComplainDto` 连单条长度都未限，仅靠 `encodeAttachments` 的 1024 总长兜底，`ArbitrationServiceImpl.java:427-441`）；服务端不校验 URL 必须是本站 `/files/...`。`ta/Approvals.vue:492-499` 以 `el-image` 直渲 → 仲裁人浏览器向发起方指定的任意外站发请求（IP/UA 泄露、跟踪像素），且外链「证据」可在裁决后被替换，破坏留痕语义（结论备注是线下赔偿唯一依据）。`javascript:` 类向量被 img/src 语境天然抑制，故不升 MAJOR。
**建议**：后端按 `^/files/\d{6}/[0-9a-f-]{36}\.(jpg|png|webp)$` 白名单校验（50340/参数错误），两 DTO 对齐单条长度约束。

### N3 · 登记出库/回退静默清撤回 flag，WA 撤回申请被隐式否决且无通知

**归属**：BE-W2。`OutboundRequestServiceImpl.java:350-352`（register）、`:333-334`（revert）在撤回申请在途（`withdraw_requested=1`）时直接清 flag 完成出库，WA 既无「拒绝」通知也无任何回执（对比 `rejectWithdrawByWk` 有通知 :402-405）。建议 register/revert 命中 flag=1 时补发一条「撤回申请未获受理，单据已出库/回到待受理」站内信。

### N4 · R8 与 R4/登记的加锁次序互逆，存在 InnoDB 死锁窗口

**归属**：BE-W2。`InquiryServiceImpl.voidByWa` 先锁询价行再逐张锁出库行（CAS 次序：inquiry→outbound）；`withdrawByWa`/`registerByWk` 是 outbound→inquiry（CAS 出库后 `recomputeInquiryState` 更新询价，`OutboundRequestServiceImpl.java:187-193/347-356`）。同一询价名下并发 void×withdraw/register 可成 AB-BA 死锁——InnoDB 会检测并牺牲一方，但透出的是 90001 而非 50331 语义码。概率低、有自愈，登记备查即可；如收口，让 voidByWa 在 CAS 询价前不持有出库行锁（先撤出库单再 CAS 询价）或统一次序。

### N5 · `doRestoreInboundInTx` 配对流水缺失时仍恢复库存（防御不对称）

**归属**：BE-W1。`InventoryServiceImpl.java:328-350`：按 refDocNo 找不到 DISPUTE_REVERSAL 时不拒绝，照常 `qty += reversedQty` 且 `reversal_of_id=null`——与 `doReverseOutboundInTx` 找不到原流水即拒（:223-226）不对称，破坏「DISPUTE_RESTORE 必回指配对冲销」的据实现备注 1 承诺（P4 配对抵消依赖）。正常流程不可达（reversedQty>0 必有冲销流水），属防御缺口：建议 `reversal==null && ctx.qty>0` 时抛防御性异常。

---

## 4. 审查通过项（重点核过，无问题，防止后续重复排查）

**越权/隔离**
- `INBOUND_CONFIRM` 授权位切点：`requireWaOrAuthorizedWe`（`InboundRequestServiceImpl.java:365-376`）——WA 直通、WE 必查授权位（缺位 42004）、其余 42xxx；confirm/dispute 双端点均过切点；WE 队列只读可见符合设计。BE-W2 出库仅 WA（据实现备注 1）与 PRD 09 §5 一致。
- 仲裁 decide 租户隔离：TenantLine 兜底 + `requireTaRole(arb.getTenantId())` 以**仲裁单自身租户**为准双保险（`ArbitrationServiceImpl.java:142-150`）；跨租户按不存在 50334 不泄露存在性；TA/OPS 端点互设 bizType 镜像门（:148/:309）；`TenantInterceptor` 对 `X-Tenant-Id` 做归属校验（G-2.1）。
- `/api/v1/files` 在 checkLogin include（`SaTokenConfig.java:48`）；魔数校验实效：真读字节、扩展名由魔数推导、UUID 落盘名，Content-Type/文件名不参与判定（`FileStorageServiceImpl.java:38-76`）；multipart 上限 10MB>5MB 业务限，无静默截断。GET /files/** 免登录为拍板接受项（12 §4.4）。
- 站内信 `markRead` 非本人按不存在 50341（`NotificationServiceImpl.java:78-83`）；列表恒定 `recipient=登录人`。

**并发/幂等**
- 重复 decide：仲裁单 CAS PENDING→DECIDED 唯一赢家（TA/OPS 两端同式）；副作用失败整体回滚（同事务）。
- dispute×72h Job×手动确认三方竞态：单据 CAS 唯一赢家；CAS 失败语义化 50331/50332 与备注 3 完全一致（`casConflict`）。
- 一单一诉：入库靠状态单向性；出库「查历史仲裁单再插」虽是先查后写，但外层 COMPLETED→COMPLAINED CAS 已保证同刻至多一个 complain 进入插入段，串行场景由计数拦截——组合闸门成立（`ArbitrationServiceImpl.java:244-251`）。
- 回补配对：每单至多一次 WITHDRAWN/CANCELLED（CAS 保证）→ 恰一条 OUTBOUND_REVERSAL，`reversal_of_id` 锁内解析恒非空，找不到原流水即拒。

**资损口径**
- `biz_time`：INBOUND/OUTBOUND=now(=created_at)；OUTBOUND_REVERSAL=原流水锚点；DISPUTE_REVERSAL=异议时刻（D39）；DISPUTE_RESTORE=原入库单 created_at（G10）——四类全对表 12 §1.5；V15 存量回填幂等。
- shortfall 固化：reversed/shortfall 仅 create 时写入，decide 的 UPDATE set 列表不含二者，无任何改写端点。
- 封顶公式 `min(Q, max(onhand,0))`、售罄 0 冲销不写流水仍立单、托盘按比例双重封顶+remark 快照还原：与 12 §2.4/备注 1 一致（计算本身正确，B1 是喂给它的 onhand 可能陈旧）。
- 大额 50% 整数口径 `qty*2 > onhand`、30 天窗口 `completed_at` 锚点、矩阵逐格（DocStateMachine 双矩阵与 12 §1.2/§2.1 全等）、R13 未结枚举含出库三态+入库两态+仲裁 PENDING（`InquiryServiceImpl.java:403-419` + `WholesalerLifecycleServiceImpl.java:412-414`）。

**前端**
- 附件双重校验一致：5MB/jpg·png·webp/≤5 张三处同值（`file.ts` 常量、`AttachmentUpload.vue`、后端 50340），前端预检不越权代替后端魔数。
- 错误码映射完整：50330–50342 在 `packages/error-codes` codes.ts:152-179 + messages-zh.ts 全量登记且文案与 12 §6.2 一致；`wa/Inbound.vue` 对 50331/50332 定向刷新处理；`ta/Approvals.vue` liability 三态（仅 REJECTED∧差额>0 显示且必填、其余不传）与后端 50342 双向校验镜像。
- reason 合成「[预设] 补充」+512 预算扣减、「其他」必填补充，符合 PRD 09 §1.1 收口口径。

**PII**
- P3 新增代码（document/inventory/notify/common.file 后端 + FE-W1 页面）日志与通知正文均无手机号；`receiverPhone` 未实现（备注 5）故无泄露面。唯一手机号日志 `InquiryServiceImpl.java:165`（rtPhone）为 P1 存量提交链，未随本波扩散——已在 11-hardening-design.md 泄露面清单内，不重复立项。

---

## 5. 波次归属汇总

| 编号 | 级别 | 归属 | 建议时机 |
|---|---|---|---|
| B1 | BLOCKER | BE-W1（inventory 基建，P1 存量同染） | FE-W2 合并前必须修（出库链上线将放大触发面） |
| M1 | MAJOR | BE-W1+W2 通知面 | 随 B1 修复批次 |
| M2 | MAJOR | BE-W1 | 一行修复，随下一波 |
| M3 | MAJOR | BE 补端点 + FE-W1 | FE-W2 波一并（同为弹窗改造） |
| N1/N3/N4 | MINOR | BE-W2 | T3 波顺带 |
| N2 | MINOR | BE-W1/W2 | T3 波顺带 |
| N5 | MINOR | BE-W1 | T3 波顺带 |

---

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-07-25 | 首版：P3 三波合并后提前审查——BLOCKER 1（库存锁两窗口）/MAJOR 3（通知收件人、/files 启动序、PRD §6.2 刚性规则）/MINOR 5；通过项清单防重复排查 |
