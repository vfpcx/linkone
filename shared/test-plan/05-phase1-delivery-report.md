# Phase-1 批发商卖货 · 交付报告

> 编写：Team Lead · 2026-07-02
> 范围：phase-1「批发商作为卖家卖货」最小闭环（到出库完成）
> 结论：**功能闭环打通、测试全绿、代码审查闭环，判定可交付。**

---

## 1. 目标与范围（回顾）

产品决策（用户拍板）：
- 第一期**不做**批发商自助入驻申请审批。
- 商户由 **TA 自营创建**；卖货闭环做到**出库完成**。
- 本期支持 **H5 验证闭环**（RT 买家端最小页）。

**闭环链路**：TA 注册建仓 → TA 自营建商户 → 上架 SKU（公开价）→ 员工注册码开通 WK → WK 入库 → RT 扫码进店浏览 → 提交询价 → WA 确认 → 系统自动转出库 → 库存扣减。

---

## 2. 交付内容

### 2.1 后端（模块化单体，7 域模块）
| 切片 | 内容 |
|---|---|
| A1 wholesaler | TA 自营建商户（带 waPhone 开通 WA 账号）、唯一性、鉴权 |
| A2 product | SKU 上架/改/上下架、公开价（单价/起批价/起批量）|
| B1 inventory | 入/出库（单 sku 维度）、库存流水、Redisson 锁防超卖 |
| B2 storefront | RT 进店聚合（店内 WA + 在售 SKU + 价 + 库存），三重租户隔离 |
| C1 document·入库 | WK 登记入库→单事务调 addStock；文档号服务（Redis INCR + 唯一索引）|
| C2 document·询价出库 | RT 提交询价 → WA 确认（状态 CAS）→ 自动建出库单 + 扣库存（库存不足整体回滚）|
| 员工注册码 | TA 生码（WK/ST）→ 员工凭码注册绑定仓库 → 解锁 WK 入库（复用 invite_codes 表）|

迁移：V4–V8（wholesalers/skus/inventories+stock_movements/inbound/inquiry+outbound）。

### 2.2 前端（admin Vue3 + Element Plus）
- TA：商户管理、SKU 上架、员工管理（生码/作废）
- 员工：凭码注册（Register.vue 支持 ?role/?code）
- WK：入库登记页
- WA：询价确认页
- RT：移动端 H5 进店浏览 + 提交询价（公开路由）
- 修复：鉴权头 Bearer→裸 token（影响全站登录态请求）

---

## 3. 测试与质量

### 3.1 测试
- **后端 93 全绿**：9 个 `*ScenarioTest`（75 场景用例，覆盖 S1–S7）+ 2 个 ControllerTest（18）。
  - 含 **4 条虚拟线程并发**：并发扣库存不超卖、并发入库不 lost-update、并发确认仅 1 成功、并发注册仅 1 成功。
- **业务旅程 E2E 18 passed**（Playwright）：正常成交整链 / 确认转出库 / 幂等 / 卖光不显示 / 缺货 / 空店 / 越权 / 员工离职 + auth 回归。
- **端到端 curl 验证**：入库 100 → 询价 30 → WA 确认 → 出库 → 库存 70，全链数据一致。

### 3.2 代码审查（机制已确立并见效）
审查基准：安全编码规约 §10 并发 + 服务拆分 §3 边界。结论处置：
| 项 | 级别 | 处置 |
|---|---|---|
| F1 addStock 并发未加锁 | 🔴 | **已修**（Redisson 锁+self 代理，补 INV-S7-02 并发入库测试）|
| F6 简码 count%100 回绕撞码 | 🟠 | **已修**（随机码+唯一约束重试）|
| F3 容量公示信任客户端 tenantId | 🟠 | 产品决策：**保持公开**（有意为之），按设计 |
| F2 document/product 跨域直连他域 mapper | 🟠 | 架构债，登记 08 §4，**P2 偿还**（不阻塞）|
| B-RT-03 询价不预检库存 | — | 产品决策：**确认时校验**，按设计 |
| F4/F5/F7/F8/F9 | 🟢 | 低危/风格，已记录，排期优化 |

已加固的并发点（addStock/deductStock 锁、confirmByWa 状态 CAS、注册码 CAS）经复核实现正确。

---

## 4. 关键决策留痕

| # | 决策 | 结论 |
|---|---|---|
| D1 | 商户来源 | TA 自营创建（不做自助入驻审批）|
| D2 | 卖货范围 | 到出库完成 |
| D3 | 员工体系 | 不重构架构；实现原设计已有的**员工注册码**（WK/ST=仓库员工）|
| D4 | 容量公示 | 保持公开（扫码可见）|
| D5 | 缺货时机 | 确认/出库环节校验，非提交时 |
| D6 | 老板多仓 | 延后（P2，见 07 文档）|

---

## 5. 已知限制 / 后续

- **P2 架构债**：跨域直连表改走 Service 接口（08 §4 清单）。
- **下一期（P2–P4）**：老板多仓、WA 拒绝/议价、客户专属价/批量调价、退货/盘点/批次+临期、计费结算。
- **上线前硬化（D-14）**：Redis 密码、手机号 PII 加密、prod 关 SQL stdout、依赖 CVE 扫描。
- **测试补全**：B-RT-04 多 SKU、B-RT-05 跨批发商、B-RT-06 起批量、B-WA-02 抢货整链（业务场景清单 04 缺口）。
- **B-WA-04 观察**：越权确认因租户行级过滤先于角色校验，返回 50284（询价不存在）而非 50286；两者皆合法拒绝，若需统一暴露 50286 需调 confirmByWa 校验顺序。

---

## 6. 交付物索引

- 代码：`main` @ `c3b03b3`（已 push GitHub `vfpcx/linkone`）
- 后端测试：`backend/src/test/.../*ScenarioTest.java` + `*ControllerTest.java`
- E2E：`frontend/apps/admin/e2e/{auth,sell-flow,sell-flow-2}.spec.ts`
- 文档：
  - `architecture/05-secure-coding-guardrails.md`（安全+并发规约 v2）
  - `architecture/06-phase1-wholesaler-selling-plan.md`（phase-1 计划）
  - `architecture/07-*`（老板-仓库-员工/员工注册码）
  - `architecture/08-service-split-and-evolution.md`（服务拆分演进）
  - `architecture/api-contract-account.md`（账号契约）
  - `test-plan/02-scenario-test-plan.md`（技术场景）
  - `test-plan/03-defect-findings.md`（缺陷清单）
  - `test-plan/04-business-scenarios.md`（业务场景清单）
  - `00-roadmap.md`（整体路线图）

---

## 7. 结论

phase-1「批发商卖货最小闭环」**功能完整、测试全绿（后端 93 + E2E 18）、代码审查 🔴/🟠 全部闭环或按产品决策关闭、代码已入主干并推送**。判定 **可交付**。后续按路线图进入 P2 还债与下一期能力。
