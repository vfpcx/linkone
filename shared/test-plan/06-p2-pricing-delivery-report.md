# P2 定价能力 · 交付报告

> 编写：Team Lead · 2026-07
> 范围：P2「定价能力」全量（客户专属价 + 批量调价 + 调价历史 + 价格匹配 + 议价沉淀 + RT 登录匹配）
> 结论：**功能闭环、127 后端测试全绿、视觉验收通过、代码审查发现并修复 1 🔴 + 3 🟠，判定可交付。**

---

## 1. 目标与范围（用户决策）

- 交付范围：**全量 P2 定价**（含 RT 登录后浏览按手机号匹配专属价）。
- 议价深度：**简化版**——WA 在确认弹窗直接逐项输成交价 + 勾选沉淀，不做 WA 回价→RT accept-bargain 完整回合（[[99-open-questions]] 待续）。
- 商品档案品类/结构化规格：**保持 phase-1 简化**（`name`+自由文本 `spec`），完整体系延后（决策 **D56**）。

## 2. 交付内容

### 2.1 后端（`pricing` 新域 + 3 域集成，`feat/p2-pricing` 4+1 提交合入 main）
| 切片 | 内容 |
|---|---|
| Wave1 | `V9__init_pricing.sql`(customer_prices/price_change_logs 两表)、CustomerPrice/PriceChangeLog 实体+Mapper、PricingService 专属价 CRUD + `resolvePrice`(Redis RBucket 缓存 60s)、PricingController、错误码 50300 段、TenantLine 白名单 |
| Wave2 | 批量调价(公开价 skus / 专属价, 六模式 PCT_UP/DOWN/SET_VALUE/DELTA/DISABLE/SET_EXPIRE)、Redisson 锁 `lock:price:{wholesalerId}`+self 代理事务、5 分防重、PriceChangeLog、调价历史查询 |
| Wave3a | 议价沉淀：ConfirmInquiryDto + confirmByWa 扩展，同事务内 dealPrice≠公开价快照且勾选→settleFromInquiry 写/覆盖专属价(source=from_inquiry) |
| Wave3b | RT 浏览价格匹配：StoreSkuVo.matchedPrice + RtStoreController 可选鉴权(StpUtil.isLogin 取手机号)、AccountService.getPhoneByUserId(不越域) |
| Wave3c | 专属价失效链路：过期/手动失效(Wave1 已内建)、disableBySku 级联(就绪未触发,无 SKU 删除入口)+缓存失效 |

### 2.2 前端（`feat/p2-pricing-fe` 2 提交合入 main）
- `api-types/pricing.ts` + `api/pricing.ts`(pricingApi 7 方法)
- `views/ta/Pricing.vue`：客户专属价(CRUD 弹窗)/批量调价(表单)/调价历史 三 tab + 路由 `/ta/pricing`
- `views/wa/PriceSettleDialog.vue`：议价沉淀弹窗(逐项成交价+沉淀+精确"已有专属价覆盖"提示)，接入 wa/Inquiry.vue
- `views/rt/Store.vue`：RT 命中专属价展示"专属¥x"+公开价划线

## 3. 测试与质量

### 3.1 测试
- **后端 127 全绿**（含 5 个定价 ScenarioTest：CRUD/匹配优先级/批量/沉淀/RT匹配/过期/级联，及并发虚拟线程用例）。相比 P1 的 93 → 127。
- **视觉验收**（§3.5 主会话截图肉眼审）：`/ta/pricing` 客户专属价 tab + 批量调价 tab 布局对齐俱佳；专属价前后端数据流端到端打通(造数→¥15/¥25/生效中正确渲染)。
- **前端 typecheck** 0 错。

### 3.2 代码审查（发现真缺陷，机制见效）
| 项 | 级别 | 处置 |
|---|---|---|
| F1 专属价唯一键不含 status，upsert 只匹配 ACTIVE → revoke 重设/沉淀遇 DISABLED 撞唯一键，回滚 confirmByWa 整事务(含库存) | 🔴 | **已修**(改按物理键匹配, 命中任意状态则 UPDATE 重置 ACTIVE) + 2 回归测试 |
| F2 5 分冷却门在锁外 → 并发双提交都通过(涨价复利) | 🟠 | **已修**(冷却检查+set 移入锁内) + 并发回归测试 |
| F3 缓存在提交前失效 → 并发读回填旧价 ≤60s | 🟠 | **已修**(afterCommit 失效) |
| F4 Redisson 锁 lease 15s < 200-SKU 批量时长 → 锁中途失效 | 🟠 | **已修**(去显式 lease 启用 watchdog 自动续租) |
| F5 disableBySku 无鉴权(无调用点) / F6 EXPIRED 无后台job / F7 getPhoneByUserId 任意登录角色 | 🟢 | 记录, 排期/加 SECURITY 标注 |

**审查价值**：F1 是测试全绿下的盲区(无用例覆盖 revoke→重设 / 沉淀遇 DISABLED)，若不审会进生产破坏询价确认核心链路。

## 4. 关键决策留痕

| # | 决策 | 结论 |
|---|---|---|
| D44/D45/D46 | 专属价沉淀维度/有效期/公开价联动 | (rt_phone,sku) 二元组 / 永久可手动设期 / 不自动联动(已实现) |
| D56 | 商品档案品类/结构化规格 | phase-1 保持简化, 完整化延后独立期(好改, skus.spu_id 已预留) |
| P2-议价 | 议价深度 | 简化版 confirm 输成交价+沉淀, 不做 RT 回价回合 |

## 5. 已知限制 / 后续

- **议价完整回合**(WA 回价→RT accept-bargain)未做，本期用 confirm 输成交价替代。
- **专属价失效**的「退驻 60 天保留 / RT 注销即失效」未做(本期仅过期/手动/删SKU级联)；且 SKU 删除入口尚不存在，disableBySku 级联就绪未触发。
- **EXPIRED 状态**无后台扫描 job(读时按 expireAt 判定, 行为正确, 仅数据卫生)。
- 调价历史 1 年清理 job、导出(OPS/TA/WA)未做。
- 商品档案品类/结构化规格(D56)延后。

## 6. 交付物索引

- 代码：`main`（后端 `pricing` 域 + document/storefront/account 集成；前端 `views/ta/Pricing.vue`+`wa/PriceSettleDialog.vue`+`rt/Store.vue`）
- 设计：`architecture/09-pricing-design.md`
- 测试：`backend/src/test/.../pricing/*ScenarioTest.java`(5) + `storefront/PricingRtMatchScenarioTest.java`
- 规范新增：`test-plan/00-overview.md §3.5 视觉验收 / §3.6 缺陷根治`

## 7. 结论

P2「定价能力」**功能完整、后端 127 测试全绿、视觉验收通过、代码审查 🔴/🟠 全部闭环、前后端已合主干**。判定 **可交付**。
