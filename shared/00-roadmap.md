# 仓储云 SaaS · 整体路线图（Master Roadmap）v2

> 编写：Team Lead · 2026-06-28（v2 2026-09-01 更新：P1–P4 状态按各期交付报告/归档计划校正，X 期细化 PII 三段式进度）
> 依据：`architecture/02-modules.md`（模块全集）+ `product/`（PRD/故事）+ 用户阶段决策
> 角色：OPS 平台运营 · TA 仓库主体(店铺=TA 1:1) · WK 仓管 · ST 财务 · WA 批发商(店内卖家) · WE 批发商员工 · RT 终端买家(H5/小程序)
> 说明：按**能力闭环**分期，非按模块。每期内部仍走 产品→架构→并行开发→审查→合并；每期贯穿安全规约自检 + 回归测试绿。

---

## 进度总览

| 期 | 主题 | 状态 |
|---|---|---|
| **P0** | 基础底座（账号/租户/安全/契约/测试基建）| ✅ 已完成 |
| **P1** | 批发商卖货最小闭环（到出库）+ RT H5 验证 | ✅ 已完成 |
| **P2** | 入驻生态 + 定价能力 | ✅ 已完成 |
| **P3** | 完整单据与履约异常 | ✅ 已完成 |
| **P4** | 计费结算 | ✅ 已完成 |
| **P5** | 运营增强与正式多端 | ⬜ 规划（未拍板）|
| **X** | 生产硬化（贯穿，上线前必过）| 🟡 进行中（PII-W8 明文收缩收口）|

---

## P0 · 基础底座 ✅
- 账号/鉴权：注册/登录(密码+验证码)/找回/改密/换绑，Sa-Token 会话，登录锁定限流，防账号枚举
- 租户：TA 自助注册仓库(PENDING)→OPS 审核→ACTIVE；店铺与 TA 1:1；店铺设置
- 安全加固：鉴权 path、跨租户隔离(TenantLine)、验证码 mock 隔离、短信防刷、状态机/唯一性/范围校验
- 工程：前后端契约对齐(roles/路由)、错误码、Redis 稳态、**场景测试(S1–S9)+Playwright E2E+CI**、安全编码规约
- 产出：后端 47 测试绿 / 前端 Playwright 8 绿 / 已上 GitHub

## P1 · 批发商卖货最小闭环 ✅（详见 `architecture/06-phase1-wholesaler-selling-plan.md`）
**目标**：TA 自营建商户 → 上架SKU(公开价) → WK入库 → RT(H5)扫码进店询价 → WA确认 → 自动转出库 → WK出库
- 模块切片：wholesaler(仅TA自营) · product(SKU+公开价) · inventory(入/出库,批次关闭) · document(入库单/询价/出库单) · store-front · **RT 最小 H5**
- 不含：自助入驻审批、专属价沉淀、账单、退货盘点临期、批次复杂度
- 执行：6 波单切片 Agent，按余额窗口推进
- 产出：2026-07-02 交付（`test-plan/05-phase1-delivery-report.md`）：闭环打通、测试全绿、审查闭环

## P2 · 入驻生态 + 定价能力 ✅
- **入驻**：WA 自助入驻申请 → TA 审批；OPS 代建(需授权/客诉单)；退驻(R13)/强制下架(R14)/全平台黑名单
- **定价**：客户专属价((rt_phone,sku))、议价沉淀(询价确认内沉淀)、批量调价(涨降%/改值, Redisson锁)、调价历史、价格匹配≤200ms(Redis缓存)
- WE（批发商员工）账号与权限
- 产出：入驻（`shared/archive/task_plan-p2-onboarding.md`，Wave1 主链 + Wave2 R13/R14，158/158 绿）；定价（`test-plan/06-p2-pricing-delivery-report.md`，127 绿 + 视觉验收）

## P3 · 完整单据与履约异常 ✅
- 入库：WK 代建 72h 默认接受 + WA 异议 → 反向冲销 + TA 仲裁；拍照入库 + 展示图同步
- 出库：代建出库不可异议、大额二次确认、WA 客诉 → OPS 仲裁
- 退货单、盘点单(盘盈/盘亏)
- 批次(Batch)管理 + 临期预警 + 强制清库(ExpiryClearance) + 批次开关联动副作用
- 单据状态机引擎抽象、单据打印(PDF)、单据号(Redis INCR)
- 产出：`shared/archive/task_plan-p3-documents.md`（T5/T1/T2 异常链+双仲裁）、`task_plan-p3b.md`（T1 正向申请链/T3 退货盘点/T4 批次临期，337 绿 / E2E 38）；交付报告 `test-plan/10-p3-delivery-report.md`、`11-p3b-delivery-report.md`

## P4 · 计费结算 ✅
- DailySnapshot(每日0点) → 月度账单(Bill/BillItem)生成
- 账单调整(折扣/减免/冲销)、下发WA、已收款登记/冲销、账单申诉、导出(PDF/Excel)
- 分段计费(R20 规则变更)、BillingRule(在 tenant)
- 产出：2026-08-10 收官（`test-plan/12-p4-delivery-report.md`，八段合并，408 绿×4 遍 / E2E 45）

## P5 · 运营增强与正式多端 ⬜
- 容量公示精度按 viewer 脱敏、撮合页运营(主推SKU/置顶WA)、推荐仓库算法(RT进店)
- 通知/站内信、语音(ASR)录单、文件/OSS(营业资质/附件)
- **RT 正式小程序/H5**、各角色 Dashboard 真实接口联调（替换当前 mock）

## X · 生产硬化（贯穿，上线前必过）🟡 进行中
- **手机号明文加密(PII, H1)**——三段式，**W8 收口完成**（2026-09-01，main=72c5597，后端 H2 451 绿，架构师 §8.2 终验通过）：
  - S0 加列双写 ✅（V27/V30 五表 hmac 双写+回填+对账，419 绿）
  - S1 影子灰度+切读 ✅ 代码就位：Step1 影子双查（登录 8 切点）→ Step2 非命门切读（blacklist/sms/pricing + Redis 键 HMAC 化）→ Step3 登录双读切换（2026-08-31，488 绿）；**生产切读执行待环境**
  - S2 明文收缩（V31-V34 删明文列，唯一不可逆段）**[完成 2026-09-01]**：V31 cipher/last4 补列 + V32 hmac 唯一索引 + V33 RENAME + V34 DROP + blacklist last4 摘要；删双写/开关 6 类 + 4 Service 直连 hmac；AES-GCM + 确定性 KAT；F1 前端 D1/D4 合入。**V31-V34 真实 MySQL 执行；§8.1 回填闸门真实库核对通过（缺口数据已整链清除，9/1，备份 backup_w8_gap_delete_20260901.sql；uk_phone_hmac 唯一索引完好、hmac 零重复）+ V34 观察期闸门待发布窗口**
  - 产品决策 D1-D4 全部定稿（wa/Inquiry 查全号放开、wa/Staff 全号显式例外 G-8.6）
- 其余硬化项 ✅ 代码落地（`test-plan/09-hardening-w1-report.md`）：H2 Redis 密码+ACL（prod fail-fast，ACL username 留注释）/ H3 Sa-Token active-timeout（主配 1800s）/ H4 SQL stdout 关闭+日志 profile 化 / H5 Boot 3.2.5→3.5.16 CVE 根治
- 上线检查单余项（**待环境**，`task_plan.md` 验收条目）：CVE 复扫、prod 冒烟、graceful shutdown、Redis 实际启用密码
- 对应缺陷清单 D-14（见 `test-plan/03-defect-findings.md`）

---

## 依赖主线（粗粒度）
```
P0 账号/租户/安全 ──> P1 卖货闭环 ──> P2 入驻+定价 ──> P3 完整单据 ──> P4 计费 ──> P5 运营/多端
                          └─ store-front/inventory/document 在 P1 打基础，P3 扩异常分支
                          └─ pricing 在 P1 只做公开价，P2 补专属价/议价/批量
```

## 节流与质量约束（每期适用）
- **API 余额**为节流阀：单切片 Agent(≤~100k)、每波 1–2 个、完成即验证+提交，撞墙即停等恢复。
- 每个新接口按 `architecture/05-secure-coding-guardrails.md` 自检（鉴权/租户隔离/S2/S4/S5 用例）。
- 每波合并 main + 重跑场景测试，保持回归绿；E2E 随接口就绪扩链路。

## 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-28 | 首版：P0–P5 + 贯穿硬化 的整体路线图 |
| v2 | 2026-09-01 | 进度校正：P1–P4 全 ✅（按交付报告/归档计划）；P5 未拍板保持规划；X 细化 PII 三段式进度（S2/W8 收口完成，main=72c5597，451 绿，V31-V34 发布窗口待环境）+ 硬化 H2–H5 落地状态 |
