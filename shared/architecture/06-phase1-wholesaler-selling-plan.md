# Phase-1 · 批发商卖货 实施切片与任务拆解 v1

> 编写：Team Lead · 2026-06-28
> 决策来源：用户 2026-06-28 拍板 —— 不做自助入驻审批；商户 TA 自营创建；卖货闭环**到出库完成**
> 定位：在既有 `02-modules.md`（domain-wholesaler/product/inventory/document 已设计）之上，切出 phase-1 最小可售卖闭环，并给出可并行的任务拆解。**不重新定义模块，只切 phase-1 子集。**

---

## 1. Phase-1 闭环（唯一主线）

```
TA 自营创建商户(批发商) + 开账号
        │
        ▼
WA 上架 SKU（公开价：单价/起批价/起批量）
        │
        ▼
WK 入库登记 → 库存增加（批次关闭模式，按 sku 维度）
        │
        ▼
RT 扫 TA 店铺码进店 → 浏览店内批发商 & SKU（看公开价）
        │
        ▼
RT 提交询价(Inquiry) → WA 报价/确认(dealPrice)
        │
        ▼
询价确认 → 单事务自动转出库单(OutboundRequest)
        │
        ▼
WK 出库登记 → 库存扣减(FIFO 简化：单 sku 维度) → 闭环完成
```

---

## 2. 范围边界（In / Out）

### ✅ In（phase-1 必做）
| 模块 | phase-1 子集 |
|---|---|
| wholesaler | **仅** `createSelfOperated(tenantId,...)` + 商户账号开通 + `updateProfile`（介绍/营业资质占位）|
| product | `createSku/updateSku/toggleListing` + 公开价(单价/起批价/起批量) + `listByTenantForRt`（无专属价时返回公开价）|
| inventory | `addStock`(入库) + `deductStock`(出库, 单 sku 维度) + `queryInventory` + `listInStockSkusFor`；**批次关闭模式** |
| document | `InboundRequest.submitByWk+register`（WK 代建入库登记）；`Inquiry.submitByRt + confirm`；`OutboundRequest.autoGenerateFromInquiry + register`（WK 出库登记）；单据号生成 |
| store-front | `getStorePage(tenantId, viewerRole=RT)` + `/rt/wholesalers?storeId=` + RT 浏览 SKU 列表 |

### ❌ Out（phase-1 不做，后续批次）
- 批发商**自助入驻申请 + TA 审批**、OPS 代建、退驻/强制下架/黑名单
- **客户专属价**沉淀/议价(bargain)/批量调价（phase-1 只做公开价；询价 dealPrice 仅记快照，不沉淀）
- 批次(Batch)/临期预警/强制清库、拍照入库归档、退货单、盘点单
- 账单/结算(domain-billing)、DailySnapshot 计费
- 入库 72h 异议/仲裁、出库客诉/仲裁（phase-1 WK 登记即生效；异常人工处理）
- RT 端 H5/小程序真实 UI（见 §5 RT 端取舍）

---

## 3. 数据模型（phase-1 新增表，最小集）

> 以既有 V1/V2 迁移风格新增 V4+ flyway 脚本；不改既有表结构（加列用新迁移）。

| 表 | 关键列 | 说明 |
|---|---|---|
| `wholesalers` | id, tenant_id, name, owner_user_id, license(占位), intro, status(ACTIVE), source(SELF_OPERATED), created_at | TA 自营商户；phase-1 status 直接 ACTIVE，无审批态 |
| `skus` | id, wholesaler_id, tenant_id, spu_id(可空/简化), name, spec, unit_price, moq_price, moq_qty, listed(bool), main_image, created_at | 公开价三件套；phase-1 SPU 可简化为自由文本，不强制平台 SPU |
| `inventories` | id, wholesaler_id, sku_id, tenant_id, qty, pallet_qty, updated_at | 单 sku 维度（批次关闭）；唯一索引 (wholesaler_id, sku_id) |
| `stock_movements` | id, sku_id, wholesaler_id, type(INBOUND/OUTBOUND), qty, ref_doc_no, operator_user_id, created_at | 流水；phase-1 只 INBOUND/OUTBOUND |
| `inbound_requests` | id, doc_no, wholesaler_id, sku_id, qty, status(REGISTERED), wk_user_id, created_at | WK 代建登记即 REGISTERED |
| `inquiries` | id, doc_no, store_id/tenant_id, wholesaler_id, rt_phone, items(json: sku,qty), status(PENDING/CONFIRMED/REJECTED), deal_price, created_at | RT 询价 |
| `outbound_requests` | id, doc_no, wholesaler_id, inquiry_id, items(json), status(REGISTERED), wk_user_id, created_at | 询价确认自动生成 |
| `store_fronts` | (复用既有 stores/store_front) | RT 进店页，加店内 WA/SKU 聚合查询 |

雪花 ID、tenant_id 自动填充、TenantLine 隔离沿用既有基建（注意把新表纳入隔离白名单评估）。

---

## 4. 任务拆解与并行计划（依赖驱动）

> 标注可并行批次。每个任务交付：实体+mapper+service+controller+单元/集成测试（按 `05-secure-coding-guardrails.md` 自检 S1–S9；写接口必带 S2/S4/S5）。

### 阶段 A（并行，无相互依赖）— 基座
- **A1 wholesaler 切片**：wholesalers 表 + `createSelfOperated`（TA 鉴权，仅本租户）+ 商户账号开通（复用 account register/角色 WA 绑定 wholesaler_id）+ updateProfile。
- **A2 product 切片**：skus 表 + `createSku/updateSku/toggleListing` + 公开价校验。依赖 A1 的 wholesaler_id（接口契约先定，可桩并行）。

### 阶段 B（依赖 A）— 库存与上架联调
- **B1 inventory 切片**：inventories/stock_movements + `addStock/deductStock(单sku, Redisson 锁)/queryInventory/listInStockSkusFor`。
- **B2 store-front 切片**：`getStorePage(RT)` + `/rt/wholesalers` + `listByTenantForRt`（聚合 在售SKU+公开价+库存>0）。依赖 A2/B1。

### 阶段 C（依赖 A+B）— 交易闭环
- **C1 document·入库**：`InboundRequest.submitByWk+register` → 单事务调 `inventory.addStock`。
- **C2 document·询价+出库**：`Inquiry.submitByRt/confirm` → 单事务 `OutboundRequest.autoGenerateFromInquiry` → `WK.register` 调 `inventory.deductStock`。单据号 Redis INCR。

### 阶段 D（贯穿）— 前端 + E2E
- **D1 admin 前端**：商户管理(TA)/SKU上架(WA)/入库(WK)/询价确认(WA)/出库(WK) 页面。
- **D1b RT H5**：最小 RT H5 进店页（扫码进店 → 浏览店内 WA/SKU 公开价 → 提交询价）。对接 store-front + inquiry 接口。
- **D2 E2E**：扩展 Playwright，加「TA建商户→上架→入库→RT(H5)询价→WA确认→出库」整链路用例。

**并行度建议**：A1+A2 并行（2 后端 Agent）；B1+B2 并行；C1+C2 可并行（接口契约先定）；D1 前端随 B/C 接口就绪推进。每阶段交付即合并 + 重跑测试。

---

## 5. 关键取舍点（需 Team Lead/产品确认）

1. **RT 端 UI**：✅ **已定（2026-06-28）= 方案 b：本期做最小 RT H5 进店+询价页**，跑通完整验证闭环。
   - 范围：扫码进店 → 浏览店内 WA/SKU(公开价) → 提交询价；不做支付/账户/收藏等。
   - 技术：最小 H5（可独立轻量页或 admin 内 RT 路由），对接 store-front + inquiry 接口。
2. **SPU 取舍**：phase-1 SKU 是否挂平台 SPU？建议简化为商户自由录入（name+spec），SPU 合并/标准化延后。
3. **店铺与商户**：沿用「店铺=TA 1:1、WA 为店内商户」模型（见记忆 phase1-wholesaler-seller-scope）。

---

## 6. 与已有成果的衔接
- 批次三 register 已接收 `wholesalerName/targetTenantId`（未落库）——本期 A1 落库时复用。
- 安全编码规约 `05-secure-coding-guardrails.md` 全程适用（新接口必挂鉴权 + 租户隔离 + S2/S4/S5 用例）。
- 余额限制：当前子 Agent 余额不足，执行需待余额恢复后按 §4 并行派发；本设计可直接作为派发依据。

---

## 7. 变更记录
| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-06-28 | 首版：phase-1 批发商卖货切片 + 数据模型 + 4 阶段并行任务拆解 |
