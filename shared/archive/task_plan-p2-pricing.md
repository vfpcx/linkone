# Task Plan · P2 定价能力（Pricing）

> 来源：已审批实施计划（内置规划模式）转录为 planning-with-files 三件套。
> 关联：`findings.md`（调研）· `progress.md`（会话日志）· `architecture/09-pricing-design.md`（设计文档，Wave1 产出）
> 规则依据：CLAUDE.md 全局协作规则 §6（编码阶段自主执行）§7（强制 planning-with-files）

## 目标与范围（用户已拍板）

- 交付范围 = **全量 P2 定价**：客户专属价 CRUD + 批量调价（公开价/专属价）+ 调价历史 + 价格匹配（询价时 & RT 登录浏览时，带 Redis 缓存）+ 议价沉淀 + 前端价格管理页/沉淀弹窗/RT 匹配展示 + 测试与审查闭环。
- 议价深度 = **简化版**：WA 在确认弹窗直接逐项输成交价 + 勾选沉淀；不做 WA 回价→RT accept-bargain 完整回合。

## 决策记录（Decisions）

| # | 决策 | 结论 |
|---|---|---|
| PR-D1 | 交付范围 | 全量 P2 定价 |
| PR-D2 | 议价深度 | confirm 时输成交价 + 沉淀（简化，不做回价回合） |
| PR-D3 | RT 浏览价匹配身份 | api-spec 已定「匹配专属价需鉴权」→ 匿名浏览看公开价，RT 登录后按手机号匹配 |
| PR-D4 | 客户身份键 | rt_phone（与 inquiry_requests.rt_phone 一致），不引入新账号体系 |
| PR-D5 | 数据模型 | 新增 customer_prices + price_change_logs 两表（V9 迁移），不动 skus 三件套 |
| PR-D6 | 执行方式 | git worktree 隔离；后端链 Wave1→2→3 串行（同 worktree），前端 Wave4 另 worktree 并行 |

## 阶段（Phases / Waves）

- [进行中] **Wave 1 后端·核心**：V9 迁移两表；CustomerPrice/PriceChangeLog 实体+Mapper；PricingService（专属价 CRUD + resolvePrice + Redis 缓存）；PricingController @ /api/v1/tenant/customer-prices；错误码 50300 段；TenantLine 白名单追加；PricingScenarioTest；09-pricing-design.md。〔worktree: pricing / branch feat/p2-pricing〕
- [进行中·并行] **Wave 4 前端**：api-types/pricing + api/pricing.ts + views/ta/Pricing.vue（专属价/批量调价/调价历史）+ WA 沉淀弹窗 PriceSettleDialog.vue + RT Store.vue 匹配展示 + 路由。〔worktree: pricing-fe / branch feat/p2-pricing-fe〕
- [待办] **Wave 2 后端·批量调价+历史**：批量调公开价/专属价（Redisson 锁 + self 代理事务；单次≤500 专属/≤200 SKU；5 分钟防重；六式 adjust_mode；写 PriceChangeLog）；GET /price-change-logs；并发测试（虚拟线程）。〔等 Wave1〕
- [待办] **Wave 3 后端·沉淀+RT 匹配鉴权**：ConfirmInquiryDto（逐项 dealPrice + settleAsCustomerPrice）；confirmByWa 单事务内沉淀；StoreFront buildOnSaleSkus 透传 rtPhone → resolvePrice；RT 端点可选鉴权；失效链路（过期/手动/删SKU）。〔等 Wave2〕
- [待办] **Wave 5 测试/审查**：pricing-flow E2E；code-review + code-simplifier（§10 并发/§3 边界）；合并双分支到 main；回归绿；更新交付文档。

## 接口契约（据 api-spec）

- `GET/POST /api/v1/tenant/customer-prices`、`PATCH/DELETE /api/v1/tenant/customer-prices/{id}`、`POST /.../customer-prices/batch-update`
- `POST /api/v1/tenant/skus/batch-price-update`（批量调公开价）
- `GET /api/v1/tenant/price-change-logs`
- `POST /api/v1/tenant/inquiry/{id}/confirm` 扩 `ConfirmInquiryDto`
- `GET /api/v1/rt/skus`、`/rt/store` 可选鉴权（登录带 rt_phone 匹配专属价）
- 约定：tenantId 序列化 String；金额 DECIMAL(12,2)；返回 R<T>；userId=StpUtil.getLoginIdAsLong()

## 验证（Verification）

- 后端：`mvn test`（H2）——PricingScenarioTest 绿 + 原 93 用例回归绿；并发用例（批量调价 lost-update / 5 分钟防重）断言正确。
- 缓存：resolvePrice 优先级（专属>公开、失效/过期回退）+ 写后缓存 delete 生效。
- 端到端 curl：TA 建 SKU（公开价 20）→ 设手机号专属价 15 → RT 登录浏览得 15、匿名得 20 → 询价确认输成交价 12 沉淀 → 再查该客户专属价 = 12。
- 前端 E2E：pricing-flow.spec.ts 通过，sell-flow* 回归绿。

## 未做 / 后续

- 完整议价回合（WA 回价→RT accept-bargain）；专属价失效的「退驻 60 天/RT 注销即失效」；调价历史 1 年定时清理与导出；批量复查一键跟随调整。
